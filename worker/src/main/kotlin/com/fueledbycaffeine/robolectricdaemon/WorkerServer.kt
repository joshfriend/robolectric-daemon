package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.DaemonContract.Worker
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.ClientMessage
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.Empty
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.RunRequest
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.thread

/** Owns worker discovery, authentication, and the sequential socket request loop. */
internal class WorkerServer(
  private val state: Path,
  private val identity: String,
  private val token: String,
) {
  fun serve(onRun: (WorkerRunSession) -> Unit) {
    ServerSocket(0, 16, InetAddress.getLoopbackAddress()).use { server ->
      server.soTimeout = IDLE_TIMEOUT.toMillis().toInt()
      publishState(server.localPort)
      val watchdog = watchStateFile()
      try {
        var running = true
        while (running) {
          try {
            server.accept().use { socket ->
              socket.soTimeout = REQUEST_TIMEOUT_MILLIS
              val input = DataInputStream(socket.getInputStream())
              val output = DataOutputStream(socket.getOutputStream())
              val message = Protocol.readRequest(input)
              if (token != message.token) {
                return@use
              }
              when (message.payloadCase) {
                ClientMessage.PayloadCase.PING -> acknowledge(output)
                ClientMessage.PayloadCase.STOP -> {
                  acknowledge(output)
                  running = false
                }
                ClientMessage.PayloadCase.RUN -> onRun(SocketRunSession(message.run, input, output))
                else -> throw IOException("Unexpected worker request: ${message.payloadCase}")
              }
              output.flush()
            }
          } catch (e: SocketTimeoutException) {
            logger.log(Level.INFO, "Robolectric worker idle timeout", e)
            running = false
          } catch (e: IOException) {
            // A client that disappears mid-request doesn't take the worker down.
            logger.log(Level.WARNING, "Robolectric worker request failed", e)
          } catch (e: UncheckedIOException) {
            logger.log(Level.WARNING, "Robolectric worker request failed", e)
          }
        }
      } finally {
        watchdog.interrupt()
        watchdog.join()
      }
    }
  }

  private fun acknowledge(output: DataOutputStream) {
    Protocol.writeEvent(
      output,
      WorkerEvent.newBuilder().setAcknowledged(Empty.getDefaultInstance()).build(),
    )
  }

  private class SocketRunSession(
    override val request: RunRequest,
    private val input: DataInputStream,
    output: DataOutputStream,
  ) : WorkerRunSession {
    private val events = EventWriter(output)

    override fun emit(event: WorkerEvent) = events.accept(event)

    override fun nextClassGroup(): List<String> {
      emit(WorkerEvent.newBuilder().setNext(Empty.getDefaultInstance()).build())
      try {
        val reply = Protocol.readRequest(input)
        if (!reply.hasClassGroup()) {
          throw IOException("Expected class group, got ${reply.payloadCase}")
        }
        return reply.classGroup.classesList
      } catch (e: IOException) {
        throw UncheckedIOException(e)
      }
    }
  }

  private fun publishState(port: Int) {
    val status =
      Properties().apply {
        setProperty(Worker.PID, ProcessHandle.current().pid().toString())
        setProperty(Worker.PORT, port.toString())
        setProperty(Worker.IDENTITY, identity)
        setProperty(Worker.TOKEN, token)
      }
    val attributes =
      if (Files.getFileStore(state.parent).supportsFileAttributeView("posix")) {
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      } else {
        emptyArray()
      }
    val staging = Files.createTempFile(state.parent, "${Worker.FILE}-", ".tmp", *attributes)
    try {
      Files.newOutputStream(staging).use { status.store(it, "Robolectric test worker") }
      Files.move(
        staging,
        state,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } finally {
      Files.deleteIfExists(staging)
    }
  }

  /**
   * Exits once the state file is gone (e.g. `clean`) or names another worker, so nothing has to
   * stop workers explicitly before their build directory disappears.
   */
  private fun watchStateFile(): Thread =
    thread(name = "Robolectric worker state watchdog", isDaemon = true) {
      while (true) {
        try {
          Thread.sleep(STATE_POLL_MILLIS)
          if (token != readToken()) {
            logger.info("Robolectric worker state file gone or replaced; exiting")
            WorkerExecution.halt(0)
          }
        } catch (_: InterruptedException) {
          return@thread
        }
      }
    }

  private fun readToken(): String? {
    val properties = Properties()
    try {
      Files.newInputStream(state).use { properties.load(it) }
    } catch (e: IOException) {
      return null
    }
    return properties.getProperty(Worker.TOKEN)
  }

  companion object {
    private val logger = Logger.getLogger(WorkerServer::class.java.name)
    private val IDLE_TIMEOUT = Duration.ofHours(1)
    private const val REQUEST_TIMEOUT_MILLIS = 10000
    private const val STATE_POLL_MILLIS = 2000L
  }
}
