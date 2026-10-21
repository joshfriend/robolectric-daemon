package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.DaemonContract.Worker
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.ClassGroup
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.ClientMessage
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.Empty
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.RunRequest as RunMessage
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Starts, finds, talks to and stops a worker. Independent of Gradle APIs. */
object WorkerClient {
  const val STATE_FILE = Worker.FILE

  private val START_TIMEOUT: Duration = Duration.ofSeconds(60)
  private const val START_POLL_MILLIS = 20L
  private const val CONNECT_TIMEOUT_MILLIS = 2_000
  private const val CONTROL_TIMEOUT_MILLIS = 5_000
  private const val EXIT_TIMEOUT_SECONDS = 10L

  /** Short, so a cancelled build (thread interrupt) can abandon a long test run. */
  private const val RUN_POLL_MILLIS = 250

  /** A request for one test task execution. */
  data class RunRequest(
    val resourceKey: String,
    val mirrorId: String,
    val classpath: List<String>,
    val projectEntries: List<String>,
    val systemProperties: Map<String, String>,
    val includes: List<String>,
  )

  /** The worker's final completion event. */
  class Outcome(val tests: Int, val failures: Int, val error: String, val summary: String)

  /** Starts a worker and waits until it answers. */
  fun start(
    directory: Path,
    identity: String,
    command: List<String>,
    workingDirectory: Path,
    environment: Map<String, String>,
  ): Properties {
    val state = directory.resolve(STATE_FILE)
    val token = UUID.randomUUID().toString()
    val builder =
      ProcessBuilder(command + listOf(state.toAbsolutePath().toString(), identity))
        .directory(workingDirectory.toFile())
        .redirectErrorStream(true)
        .redirectOutput(
          ProcessBuilder.Redirect.appendTo(directory.resolve(Worker.LOG_FILE).toFile())
        )
    builder.environment().clear()
    builder.environment().putAll(environment)
    val process = builder.start()
    var started = false
    try {
      DataOutputStream(process.outputStream).use { it.writeUTF(token) }
      val deadline = System.nanoTime() + START_TIMEOUT.toNanos()
      while (System.nanoTime() < deadline && process.isAlive) {
        val worker = state.readPropertiesOrNull()
        if (worker != null && token == worker.getProperty(Worker.TOKEN) && alive(worker)) {
          started = true
          return worker
        }
        Thread.sleep(START_POLL_MILLIS)
      }
      throw IOException(
        "Robolectric worker did not start; see ${directory.resolve(Worker.LOG_FILE)}"
      )
    } finally {
      if (!started) process.destroyForcibly()
    }
  }

  /** Sends a run request and streams events until the worker reports completion. */
  fun run(
    worker: Properties,
    request: RunRequest,
    next: () -> List<String>,
    events: (WorkerEvent) -> Unit,
  ): Outcome {
    connect(worker).use { socket ->
      socket.soTimeout = RUN_POLL_MILLIS
      val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
      val input = DataInputStream(BufferedInputStream(InterruptibleInput(socket.getInputStream())))
      val run =
        RunMessage.newBuilder()
          .setResourceKey(request.resourceKey)
          .setMirrorId(request.mirrorId)
          .addAllClasspath(request.classpath)
          .addAllProjectEntries(request.projectEntries)
          .putAllSystemProperties(request.systemProperties)
          .addAllIncludes(request.includes)
      val message =
        ClientMessage.newBuilder().setToken(worker.getProperty(Worker.TOKEN)).setRun(run)
      Protocol.writeRequest(output, message.build())
      output.flush()
      while (true) {
        val event = Protocol.readEvent(input)
        if (event.hasCompleted()) {
          val done = event.completed
          return Outcome(done.tests, done.failures, done.error, done.summary)
        }
        if (event.hasNext()) {
          if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Test run cancelled")
          }
          Protocol.writeRequest(
            output,
            ClientMessage.newBuilder()
              .setClassGroup(ClassGroup.newBuilder().addAllClasses(next()))
              .build(),
          )
          output.flush()
          continue
        }
        events(event)
      }
    }
  }

  fun alive(worker: Properties): Boolean {
    if (process(worker)?.isAlive != true) return false
    return try {
      connect(worker).use { socket ->
        socket.soTimeout = CONTROL_TIMEOUT_MILLIS
        val request = DataOutputStream(socket.getOutputStream())
        Protocol.writeRequest(
          request,
          ClientMessage.newBuilder()
            .setToken(worker.getProperty(Worker.TOKEN))
            .setPing(Empty.getDefaultInstance())
            .build(),
        )
        request.flush()
        requireAcknowledged(DataInputStream(socket.getInputStream()))
        true
      }
    } catch (_: ConnectException) {
      false
    } catch (_: SocketTimeoutException) {
      false
    }
  }

  /** Asks the worker to exit, killing it if it doesn't answer (e.g. stuck in a test). */
  fun stop(worker: Properties) {
    val process = process(worker)?.takeIf { it.isAlive } ?: return
    try {
      connect(worker).use { socket ->
        socket.soTimeout = CONTROL_TIMEOUT_MILLIS
        val request = DataOutputStream(socket.getOutputStream())
        Protocol.writeRequest(
          request,
          ClientMessage.newBuilder()
            .setToken(worker.getProperty(Worker.TOKEN))
            .setStop(Empty.getDefaultInstance())
            .build(),
        )
        request.flush()
        requireAcknowledged(DataInputStream(socket.getInputStream()))
      }
    } catch (_: IOException) {
      process.destroyForcibly()
    }
    try {
      process.onExit().get(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
      process.destroyForcibly()
    } catch (e: ExecutionException) {
      throw IOException("Could not stop Robolectric worker", e)
    }
  }

  private fun process(worker: Properties): ProcessHandle? =
    ProcessHandle.of(worker.getProperty(Worker.PID).toLong()).orElse(null)

  private fun requireAcknowledged(input: DataInputStream) {
    val event = Protocol.readEvent(input)
    if (!event.hasAcknowledged()) {
      throw IOException("Expected worker acknowledgement, got ${event.payloadCase}")
    }
  }

  private fun connect(worker: Properties): Socket =
    Socket().apply {
      connect(
        InetSocketAddress(
          InetAddress.getLoopbackAddress(),
          worker.getProperty(Worker.PORT).toInt(),
        ),
        CONNECT_TIMEOUT_MILLIS,
      )
    }

  /** Retries socket read timeouts (which lose no data) until the calling thread is interrupted. */
  private class InterruptibleInput(input: InputStream) : FilterInputStream(input) {
    override fun read(): Int {
      while (true) {
        try {
          return super.read()
        } catch (_: SocketTimeoutException) {
          checkInterrupted()
        }
      }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      while (true) {
        try {
          return super.read(bytes, offset, length)
        } catch (_: SocketTimeoutException) {
          checkInterrupted()
        }
      }
    }

    private fun checkInterrupted() {
      if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Test run cancelled")
    }
  }
}
