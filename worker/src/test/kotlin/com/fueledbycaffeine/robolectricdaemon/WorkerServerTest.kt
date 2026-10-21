package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.DaemonContract.Worker
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.ClassGroup
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.ClientMessage
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.Completed
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.Empty
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.RunRequest
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkerServerTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `publishes discovery state and acknowledges ping and stop`() {
    withServer { worker ->
      assertEquals(ProcessHandle.current().pid().toString(), worker.getProperty(Worker.PID))
      assertEquals("identity", worker.getProperty(Worker.IDENTITY))
      assertEquals(TOKEN, worker.getProperty(Worker.TOKEN))
      val state = directory.resolve(Worker.FILE)
      assertFalse(Files.exists(directory.resolve("${Worker.FILE}.tmp")))
      if (Files.getFileStore(state).supportsFileAttributeView("posix")) {
        assertEquals(
          PosixFilePermissions.fromString("rw-------"),
          Files.getPosixFilePermissions(state),
        )
      }
      ping(worker)
    }
  }

  @Test
  fun `rejects unauthenticated requests without dispatching or stopping`() {
    val runs = AtomicInteger()
    withServer(run = { runs.incrementAndGet() }) { worker ->
      val requests =
        listOf(
          ClientMessage.newBuilder().setPing(Empty.getDefaultInstance()),
          ClientMessage.newBuilder().setStop(Empty.getDefaultInstance()),
          ClientMessage.newBuilder().setRun(RunRequest.getDefaultInstance()),
        )
      for (request in requests) {
        connect(worker).use { socket ->
          send(socket, request.setToken("wrong-token").build())
          assertEquals(-1, socket.getInputStream().read())
        }
      }
      ping(worker)
      assertEquals(0, runs.get())
    }
  }

  @Test
  fun `delivers run session with events and class groups and accepts subsequent requests`() {
    val request = RunRequest.newBuilder().setResourceKey("resources").build()
    withServer(
      run = { session ->
        assertEquals(request, session.request)
        session.emit(WorkerEvent.newBuilder().setStdout("running\n").build())
        assertEquals(listOf("ExampleTest"), session.nextClassGroup())
        assertEquals(emptyList<String>(), session.nextClassGroup())
        session.emit(
          WorkerEvent.newBuilder().setCompleted(Completed.newBuilder().setTests(1)).build()
        )
      }
    ) { worker ->
      connect(worker).use { socket ->
        send(socket, ClientMessage.newBuilder().setToken(TOKEN).setRun(request).build())
        val input = DataInputStream(socket.getInputStream())
        assertEquals("running\n", Protocol.readEvent(input).stdout)
        assertTrue(Protocol.readEvent(input).hasNext())
        send(
          socket,
          ClientMessage.newBuilder()
            .setClassGroup(ClassGroup.newBuilder().addClasses("ExampleTest"))
            .build(),
        )
        assertTrue(Protocol.readEvent(input).hasNext())
        send(
          socket,
          ClientMessage.newBuilder().setClassGroup(ClassGroup.getDefaultInstance()).build(),
        )
        assertEquals(1, Protocol.readEvent(input).completed.tests)
        assertEquals(-1, input.read())
      }
      ping(worker)
    }
  }

  @Test
  fun `rejects unexpected class group replies and accepts subsequent requests`() {
    withServer(
      run = { session ->
        val failure = assertThrows(UncheckedIOException::class.java) { session.nextClassGroup() }
        assertEquals("Expected class group, got PING", failure.cause?.message)
        throw failure
      }
    ) { worker ->
      connect(worker).use { socket ->
        send(
          socket,
          ClientMessage.newBuilder()
            .setToken(TOKEN)
            .setRun(RunRequest.getDefaultInstance())
            .build(),
        )
        assertTrue(Protocol.readEvent(DataInputStream(socket.getInputStream())).hasNext())
        send(socket, ClientMessage.newBuilder().setPing(Empty.getDefaultInstance()).build())
        assertEquals(-1, socket.getInputStream().read())
      }
      ping(worker)
    }
  }

  @Test
  fun `preserves IO failure when client disconnects while supplying class groups`() {
    withServer(
      run = { session ->
        val failure = assertThrows(UncheckedIOException::class.java) { session.nextClassGroup() }
        assertTrue(failure.cause is IOException)
        throw failure
      }
    ) { worker ->
      connect(worker).use { socket ->
        send(
          socket,
          ClientMessage.newBuilder()
            .setToken(TOKEN)
            .setRun(RunRequest.getDefaultInstance())
            .build(),
        )
        assertTrue(Protocol.readEvent(DataInputStream(socket.getInputStream())).hasNext())
        socket.shutdownOutput()
        assertEquals(-1, socket.getInputStream().read())
      }
      ping(worker)
    }
  }

  @Test
  fun `continues serving after malformed requests and run IO failures`() {
    withServer(
      run = { session ->
        val failure = IOException("client disconnected")
        if (session.request.resourceKey == "unchecked") throw UncheckedIOException(failure)
        throw failure
      }
    ) { worker ->
      connect(worker).use { socket ->
        DataOutputStream(socket.getOutputStream()).apply {
          writeInt(-1)
          flush()
        }
        assertEquals(-1, socket.getInputStream().read())
      }
      connect(worker).use { socket ->
        send(
          socket,
          ClientMessage.newBuilder()
            .setToken(TOKEN)
            .setClassGroup(ClassGroup.getDefaultInstance())
            .build(),
        )
        assertEquals(-1, socket.getInputStream().read())
      }
      connect(worker).use { it.shutdownOutput() }
      for (key in listOf("checked", "unchecked")) {
        connect(worker).use { socket ->
          send(
            socket,
            ClientMessage.newBuilder()
              .setToken(TOKEN)
              .setRun(RunRequest.newBuilder().setResourceKey(key))
              .build(),
          )
          assertEquals(-1, socket.getInputStream().read())
        }
      }
      ping(worker)
    }
  }

  private fun withServer(
    run: (WorkerRunSession) -> Unit = { throw AssertionError("Unexpected run request") },
    body: (Properties) -> Unit,
  ) {
    val state = directory.resolve(Worker.FILE)
    val task = FutureTask { WorkerServer(state, "identity", TOKEN).serve(run) }
    thread(name = "WorkerServerTest", isDaemon = true) { task.run() }
    try {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (!Files.exists(state)) {
        if (task.isDone) task.get()
        check(System.nanoTime() < deadline) { "Worker server did not publish its state" }
        Thread.sleep(10)
      }
      body(readState(state))
    } finally {
      if (Files.exists(state) && !task.isDone) {
        connect(readState(state)).use { socket ->
          send(
            socket,
            ClientMessage.newBuilder().setToken(TOKEN).setStop(Empty.getDefaultInstance()).build(),
          )
          assertTrue(Protocol.readEvent(DataInputStream(socket.getInputStream())).hasAcknowledged())
        }
      }
      task.get(5, TimeUnit.SECONDS)
    }
  }

  private fun readState(state: Path): Properties =
    Properties().apply { Files.newInputStream(state).use { load(it) } }

  private fun connect(worker: Properties): Socket =
    Socket(InetAddress.getLoopbackAddress(), worker.getProperty(Worker.PORT).toInt()).apply {
      soTimeout = 5000
    }

  private fun send(socket: Socket, request: ClientMessage) {
    val output = DataOutputStream(socket.getOutputStream())
    Protocol.writeRequest(output, request)
    output.flush()
  }

  private fun ping(worker: Properties) {
    connect(worker).use { socket ->
      send(
        socket,
        ClientMessage.newBuilder().setToken(TOKEN).setPing(Empty.getDefaultInstance()).build(),
      )
      assertTrue(Protocol.readEvent(DataInputStream(socket.getInputStream())).hasAcknowledged())
    }
  }

  companion object {
    private const val TOKEN = "test-token"
  }
}
