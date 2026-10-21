package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.DaemonContract.Classpath
import com.fueledbycaffeine.robolectricdaemon.DaemonContract.Launch
import com.fueledbycaffeine.robolectricdaemon.DaemonContract.Request
import com.fueledbycaffeine.robolectricdaemon.DaemonContract.Worker
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** A client in Gradle's disposable test JVM; it has no dependency on Gradle APIs. */
internal class DaemonSession(config: Properties) {
  val directory: Path = Path.of(config.getProperty(Request.DIRECTORY))
  val forks: Int = System.getProperty(DaemonContract.FORKS_PROPERTY, "1").toInt()
  private val live =
    checkNotNull(Path.of(config.getProperty(Request.SYNC)).readPropertiesOrNull()) {
      "Missing synced Robolectric classpath"
    }
  private val workerJars = paths(config.getProperty(Request.WORKER)).map(::stage)
  private val sandbox = paths(config.getProperty(Request.SANDBOX)).map(::stage)
  private val launch =
    checkNotNull(directory.resolve(Launch.FILE).readPropertiesOrNull()) {
      "Missing Robolectric daemon launch options"
    }
  private val arguments =
    List(launch.getProperty(Launch.ARGUMENT_COUNT).toInt()) {
      launch.getProperty("${Launch.ARGUMENT_PREFIX}$it")
    }
  private val properties = gradleSystemProperties()
  private val startup = properties.filterKeys { !it.startsWith("roborazzi.") }.toSortedMap()
  private val environment = prefixed(Launch.ENVIRONMENT_PREFIX)
  private val java = launch.getProperty(Launch.JAVA)
  private val cwd = Path.of(launch.getProperty(Launch.WORKING_DIRECTORY))
  private val identityLines = buildList {
    add("java=$java ${File(java).length()}:${File(java).lastModified()}")
    add("workingDirectory=$cwd")
    workerJars.forEach { add("worker=${it.fileName}") }
    sandbox.forEach { add("sandbox=${it.fileName}") }
    arguments.forEach { add("jvmArg=$it") }
    startup.forEach { (key, value) -> add("property $key=$value") }
    add("environment=${launch.getProperty(Launch.ENVIRONMENT_IDENTITY)}")
  }
  private val identity =
    digest(identityLines.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
  private val runtimeKey =
    RuntimeInputs.digest(
      paths(config.getProperty(Request.INPUTS)).map(Path::toFile),
      paths(live.getProperty(Classpath.SOURCES)).map(Path::toFile),
      directory,
      directory.resolve("inputs.properties"),
    )
  val template =
    WorkerClient.RunRequest(
      resourceKey =
        digest(live.getProperty(Classpath.RESOURCE_KEY).toByteArray(), runtimeKey.toByteArray()),
      mirrorId = live.getProperty(Classpath.MIRROR_ID),
      classpath = paths(live.getProperty(Classpath.CLASSPATH)).map(Path::toString),
      projectEntries = paths(live.getProperty(Classpath.PROJECT_ENTRIES)).map(Path::toString),
      systemProperties = properties,
      includes = emptyList(),
    )

  fun worker(index: Int): Properties {
    val workerDirectory = directory.resolve("${DaemonContract.WORKER_DIRECTORY_PREFIX}${index + 1}")
    Files.createDirectories(workerDirectory)
    val state = workerDirectory.resolve(WorkerClient.STATE_FILE)
    val existing = state.readPropertiesOrNull()
    if (existing != null) {
      if (existing.getProperty(Worker.IDENTITY) == identity && WorkerClient.alive(existing))
        return existing
      println(
        "Restarting Robolectric test worker ${index + 1}: launch configuration changed or worker unavailable"
      )
      WorkerClient.stop(existing)
      Files.deleteIfExists(state)
    }
    Files.write(workerDirectory.resolve("identity.txt"), identityLines, StandardCharsets.UTF_8)
    val temporary = workerDirectory.resolve(Worker.TEMP_DIRECTORY)
    temporary.deleteTree()
    Files.createDirectories(temporary)
    val command = buildList {
      add(java)
      addAll(arguments)
      add("-XX:+StartAttachListener")
      startup.forEach { (key, value) -> add("-D$key=$value") }
      add("-Djava.io.tmpdir=${temporary.toAbsolutePath()}")
      add("-cp")
      add(workerJars.joinToString(File.pathSeparator))
      add(DaemonContract.WORKER_MAIN)
      add(sandbox.joinToString(File.pathSeparator))
    }
    val worker = WorkerClient.start(workerDirectory, identity, command, cwd, environment)
    println("Started Robolectric test worker ${index + 1} (pid ${worker.getProperty(Worker.PID)})")
    return worker
  }

  fun stopSurplus() {
    for (worker in workerDirectories(directory)) {
      if (
        worker.fileName.toString().removePrefix(DaemonContract.WORKER_DIRECTORY_PREFIX).toInt() >
          forks
      ) {
        val state = worker.resolve(WorkerClient.STATE_FILE)
        state.readPropertiesOrNull()?.let(WorkerClient::stop)
        Files.deleteIfExists(state)
      }
    }
  }

  private fun stage(source: Path): Path {
    val bytes = Files.readAllBytes(source)
    val target =
      directory
        .resolve(DaemonContract.STAGED_JARS_DIRECTORY)
        .resolve("${source.fileName.toString().removeSuffix(".jar")}-${digest(bytes).take(16)}.jar")
    if (!Files.isRegularFile(target)) {
      Files.createDirectories(target.parent)
      val staging = Files.createTempFile(target.parent, "stage-", ".tmp")
      try {
        Files.write(staging, bytes)
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
      } finally {
        Files.deleteIfExists(staging)
      }
    }
    return target
  }

  private fun paths(value: String): List<Path> =
    if (value.isEmpty()) emptyList() else value.split(File.pathSeparator).map(Path::of)

  private fun prefixed(prefix: String): Map<String, String> =
    launch
      .stringPropertyNames()
      .filter { it.startsWith(prefix) }
      .associate { it.removePrefix(prefix) to launch.getProperty(it) }
}

/**
 * The `-D` properties Gradle launched this JVM with. Reading them here, rather than from the Test
 * task, includes properties other plugins add in their own `doFirst` actions (e.g. Roborazzi's
 * verify mode), whatever order those actions run in.
 */
internal fun gradleSystemProperties(
  arguments: List<String> = ManagementFactory.getRuntimeMXBean().inputArguments
): Map<String, String> =
  arguments
    .filter { it.startsWith("-D") }
    .associate { it.substring(2).substringBefore('=') to it.substringAfter('=', "") }
    .filterKeys { !it.startsWith("org.gradle.") && !it.startsWith("robolectric.daemon.") }

fun workerDirectories(taskDirectory: Path): List<Path> {
  if (!Files.isDirectory(taskDirectory)) return emptyList()
  return Files.list(taskDirectory).use { children ->
    children
      .filter {
        Files.isDirectory(it) &&
          it.fileName
            .toString()
            .matches(Regex("${Regex.escape(DaemonContract.WORKER_DIRECTORY_PREFIX)}\\d+"))
      }
      .sorted()
      .toList()
  }
}
