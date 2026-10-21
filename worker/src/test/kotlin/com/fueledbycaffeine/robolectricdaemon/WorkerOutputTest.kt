package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.UncheckedIOException
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkerOutputTest {
  @Test
  fun `forwards complete UTF-8 lines and flushes trailing output once`() {
    val events = mutableListOf<WorkerEvent>()
    val output = LineEvents(false, Consumer { events.add(it) })
    val bytes = "first\ncaf\u00e9".toByteArray(Charsets.UTF_8)
    output.write(bytes, 0, bytes.size)
    assertEquals(listOf("first\n"), events.map { it.stdout })
    output.flush()
    output.flush()
    assertEquals(listOf("first\n", "caf\u00e9"), events.map { it.stdout })
    assertTrue(events.all { it.hasStdout() })
  }

  @Test
  fun `serializes stderr events with protocol framing`() {
    val bytes = ByteArrayOutputStream()
    val output = LineEvents(true, EventWriter(DataOutputStream(bytes)))
    output.write("failure\n".toByteArray())
    val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    val event = Protocol.readEvent(input)
    assertTrue(event.hasStderr())
    assertEquals("failure\n", event.stderr)
    assertEquals(-1, input.read())
  }

  @Test
  fun `preserves socket failures across output adapters`() {
    val failure = IOException("client disconnected")
    val socket =
      object : OutputStream() {
        override fun write(b: Int) {
          throw failure
        }
      }
    val writer = EventWriter(DataOutputStream(socket))
    val event = WorkerEvent.newBuilder().setStdout("test").build()
    assertSame(
      failure,
      assertThrows(UncheckedIOException::class.java) { writer.accept(event) }.cause,
    )
    val output = LineEvents(false, writer)
    output.write('x'.code)
    assertSame(failure, assertThrows(IOException::class.java) { output.flush() })
  }
}
