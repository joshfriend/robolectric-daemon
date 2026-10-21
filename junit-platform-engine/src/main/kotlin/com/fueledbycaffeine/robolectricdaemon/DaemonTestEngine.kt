package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.internal.protobuf.ByteString
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.TestEvent
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.TestFailure
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import java.io.ObjectInputFilter
import java.io.ObjectInputStream
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.Collections
import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.vintage.engine.VintageTestEngine
import org.opentest4j.AssertionFailedError

/** Public JUnit Platform SPI, running in an ordinary Gradle test worker. */
class DaemonTestEngine : TestEngine {
  private val local = VintageTestEngine()
  private var fallback = false

  override fun getId(): String = DaemonContract.ENGINE_ID

  override fun discover(request: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor {
    fallback =
      System.getProperty(DaemonContract.LOCAL_PROPERTY) == "true" ||
        ManagementFactory.getRuntimeMXBean().inputArguments.any {
          it.startsWith("-javaagent") || it.startsWith("-agent")
        }
    val discovered = local.discover(request, uniqueId)
    return if (fallback) discovered else Root(uniqueId, discovered)
  }

  override fun execute(request: ExecutionRequest) {
    if (fallback) {
      local.execute(request)
      return
    }
    val root = request.rootTestDescriptor as Root
    val listener = request.engineExecutionListener
    listener.executionStarted(root)
    try {
      if (root.children.isEmpty()) {
        listener.executionFinished(root, TestExecutionResult.successful())
        return
      }
      val config =
        checkNotNull(
          Path.of(System.getProperty(DaemonContract.REQUEST_PROPERTY)).readPropertiesOrNull()
        ) {
          "Missing Robolectric daemon request"
        }
      withClientLock(Path.of(config.getProperty(DaemonContract.Request.DIRECTORY))) {
        execute(root, listener, DaemonSession(config))
      }
      listener.executionFinished(root, TestExecutionResult.successful())
    } catch (e: Exception) {
      if (e is InterruptedException) Thread.currentThread().interrupt()
      listener.executionFinished(root, TestExecutionResult.failed(e))
    }
  }

  private fun execute(root: Root, listener: EngineExecutionListener, session: DaemonSession) {
    val selected =
      root.children
        .flatMap { runner ->
          val descriptors = listOf(runner) + runner.descendants
          if (descriptors.map { it.uniqueId }.toSet() == root.original.getValue(runner.uniqueId))
            listOf(runner)
          else descriptors.filter { it.isTest }
        }
        .map { it.uniqueId.withRoot(UniqueId.forEngine(local.id)).toString() }
    val durationsFile = session.directory.resolve(DaemonContract.DURATIONS_FILE)
    val durations = durationsFile.readPropertiesOrNull() ?: Properties()
    val queue =
      TestQueue.of(
        root.children.map { root.owners.getValue(it.uniqueId) },
        durations,
        session.forks,
      )
    val errors = ConcurrentLinkedQueue<Throwable>()
    val outcomes = Collections.synchronizedList(mutableListOf<WorkerClient.Outcome>())
    val lock = Any()
    val events = RemoteEvents(root, listener)
    session.stopSurplus()
    val threads =
      List(queue.workers) { index ->
        Thread(
          {
            try {
              outcomes +=
                WorkerClient.run(
                  session.worker(index),
                  session.template.copy(includes = selected),
                  queue::next,
                ) { event ->
                  synchronized(lock) { events.accept(event) }
                }
            } catch (e: Throwable) {
              // A client thread must report linkage/errors too, never silently skip its tests.
              errors += e
            }
          },
          "Robolectric daemon client ${index + 1}",
        )
      }
    threads.forEach(Thread::start)
    try {
      threads.forEach(Thread::join)
    } catch (e: InterruptedException) {
      threads.forEach(Thread::interrupt)
      throw e
    }
    events.finish()
    errors.firstOrNull()?.let { throw IllegalStateException("Robolectric worker failed", it) }
    check(outcomes.none { it.error.isNotEmpty() }) {
      "Robolectric worker failed: ${outcomes.joinToString { it.error }}"
    }
    events.classMillis.forEach { (name, millis) ->
      durations.setProperty(name.substringBefore('$'), maxOf(1, millis).toString())
    }
    durationsFile.writePropertiesAtomically(durations)
    println(
      "Robolectric worker: ${outcomes.sumOf { it.tests }} tests, ${outcomes.sumOf { it.failures }} failed " +
        "(${outcomes.joinToString("; ") { it.summary }})"
    )
  }

  private class Root(id: UniqueId, discovered: TestDescriptor) :
    EngineDescriptor(id, "Robolectric daemon") {
    val owners: Map<UniqueId, String>
    val original: Map<UniqueId, Set<UniqueId>>

    init {
      val runners = discovered.children.toList()
      owners = runners.associate { runner ->
        runner.uniqueId to (runner.source.orElseThrow() as ClassSource).className
      }
      original = runners.associate { runner ->
        runner.uniqueId to (listOf(runner) + runner.descendants).map { it.uniqueId }.toSet()
      }
      runners.forEach { runner ->
        discovered.removeChild(runner)
        addChild(runner)
      }
    }
  }

  private class RemoteEvents(
    private val root: Root,
    private val listener: EngineExecutionListener,
  ) {
    private val descriptors = root.descendants.associateBy { it.uniqueId }.toMutableMap()
    private val running = mutableSetOf<TestDescriptor>()
    private val completed = mutableSetOf<TestDescriptor>()
    private val started = mutableMapOf<TestDescriptor, Long>()
    val classMillis = mutableMapOf<String, Long>()

    fun accept(event: WorkerEvent) {
      if (event.hasStdout() || event.hasStderr()) {
        // Gradle captures actual stdout/stderr from this normal test JVM.
        if (event.hasStdout()) print(event.stdout) else System.err.print(event.stderr)
        return
      }
      check(event.hasTest()) { "Unexpected execution event ${event.payloadCase}" }
      val remote = event.test
      val id = UniqueId.parse(remote.uniqueId).withRoot(root.uniqueId)
      if (remote.kind == TestEvent.Kind.REGISTERED) {
        register(id, remote)
        return
      }
      val test = descriptors[id] ?: error("Worker reported an undiscovered test: $id")
      when (remote.kind) {
        TestEvent.Kind.STARTED -> {
          listener.executionStarted(test)
          running += test
          if (id in root.owners) started[test] = System.nanoTime()
        }
        TestEvent.Kind.SKIPPED -> {
          listener.executionSkipped(test, remote.reason)
          completed += test
          completed += test.descendants
        }
        TestEvent.Kind.FINISHED -> {
          val failure = remote.failure.takeIf { remote.hasFailure() }?.let(::remoteFailure)
          val result =
            when (remote.status) {
              TestEvent.Status.SUCCESSFUL -> TestExecutionResult.successful()
              TestEvent.Status.FAILED ->
                TestExecutionResult.failed(checkNotNull(failure) { "Missing remote failure: $id" })
              TestEvent.Status.ABORTED -> TestExecutionResult.aborted(failure)
              else -> error("Unexpected execution status ${remote.status}")
            }
          listener.executionFinished(test, result)
          running -= test
          completed += test
          started.remove(test)?.let {
            val name = root.owners.getValue(id).substringBefore('$')
            classMillis.merge(name, (System.nanoTime() - it) / 1_000_000, Long::plus)
          }
        }
        else -> error("Unexpected test event ${remote.kind}")
      }
    }

    private fun register(id: UniqueId, remote: TestEvent) {
      check(id !in descriptors) { "Worker registered a duplicate test: $id" }
      val parentId = UniqueId.parse(remote.parentId).withRoot(root.uniqueId)
      val parent = descriptors[parentId] ?: error("Missing dynamic test parent: $parentId")
      val source =
        when {
          remote.method.isNotEmpty() -> MethodSource.from(remote.className, remote.method)
          remote.className.isNotEmpty() -> ClassSource.from(remote.className)
          else -> null
        }
      val descriptor =
        object : AbstractTestDescriptor(id, remote.displayName, source) {
          override fun getType(): TestDescriptor.Type =
            TestDescriptor.Type.valueOf(remote.descriptorType)

          override fun getLegacyReportingName(): String = remote.legacyName
        }
      parent.addChild(descriptor)
      descriptors[id] = descriptor
      listener.dynamicTestRegistered(descriptor)
    }

    fun finish() {
      running.toList().asReversed().forEach { test ->
        listener.executionFinished(
          test,
          TestExecutionResult.failed(IllegalStateException("Worker did not finish the test")),
        )
        completed += test
      }
      descriptors.values
        .filter { it.isTest && it !in completed }
        .forEach { listener.executionSkipped(it, "Not executed by Vintage") }
    }
  }

  internal companion object {
    private fun UniqueId.withRoot(root: UniqueId): UniqueId =
      segments.drop(1).fold(root) { id, segment -> id.append(segment) }

    fun remoteFailure(event: TestFailure): Throwable =
      event.serialized.takeIf { event.hasSerialized() }?.let(::deserialize)
        ?: reconstructedFailure(event)

    /**
     * The worker's real exception, or null when this JVM can't read it: for example a class
     * Robolectric instrumented, a type absent here, or a field outside [failureFilter].
     */
    private fun deserialize(bytes: ByteString): Throwable? =
      try {
        ObjectInputStream(bytes.newInput()).use {
          it.objectInputFilter = failureFilter
          it.readObject() as? Throwable
        }
      } catch (_: Exception) {
        null
      } catch (_: LinkageError) {
        null
      }

    // Exception graphs only, so worker data can't reach general deserialization gadgets.
    private val failureFilter = ObjectInputFilter { info ->
      val type = info.serialClass()
      when {
        info.depth() > 1_000 || info.references() > 100_000 || info.streamBytes() > 2 shl 20 ->
          ObjectInputFilter.Status.REJECTED
        type == null || allowedInFailure(type) -> ObjectInputFilter.Status.ALLOWED
        else -> ObjectInputFilter.Status.REJECTED
      }
    }

    private fun allowedInFailure(type: Class<*>): Boolean =
      when {
        // Elements are checked individually, so ArrayList's backing Object[] is safe.
        type.isArray ->
          type.componentType == Any::class.java || allowedInFailure(type.componentType)
        type.isPrimitive || Throwable::class.java.isAssignableFrom(type) -> true
        type.name.startsWith("java.lang.") ->
          type == String::class.java ||
            type == StackTraceElement::class.java ||
            type == java.lang.Boolean::class.java ||
            type == Character::class.java ||
            Number::class.java.isAssignableFrom(type)
        else -> type in failureCollections || type.name == "org.opentest4j.ValueWrapper"
      }

    private val failureCollections =
      setOf(
        ArrayList::class.java,
        Collections.emptyList<Any>().javaClass,
        Collections.unmodifiableList(ArrayList<Any>()).javaClass,
      )

    private fun reconstructedFailure(event: TestFailure): Throwable {
      var cause: Throwable? = null
      event.causesList.asReversed().forEachIndexed { at, remote ->
        val message = "${remote.type}: ${if (remote.hasMessage()) remote.message else ""}"
        val throwable =
          if (at == event.causesCount - 1 && event.assertion)
            AssertionFailedError(
              message,
              event.expected.takeIf { event.hasExpected() },
              event.actual.takeIf { event.hasActual() },
              cause,
            )
          else RuntimeException(message, cause)
        throwable.stackTrace =
          remote.framesList
            .map {
              StackTraceElement(
                it.className,
                it.method,
                it.file.takeIf { _ -> it.hasFile() },
                it.line,
              )
            }
            .toTypedArray()
        cause = throwable
      }
      return cause ?: IllegalStateException("Unknown remote failure")
    }
  }
}
