package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.util.function.Consumer

/** Serializes events onto the client socket; a failure means the client went away. */
internal class EventWriter(private val output: DataOutputStream) : Consumer<WorkerEvent> {
  @Synchronized
  override fun accept(event: WorkerEvent) {
    try {
      Protocol.writeEvent(output, event)
      output.flush()
    } catch (e: IOException) {
      throw UncheckedIOException(e)
    }
  }
}

/** Forwards stdout/stderr to the client a line at a time. */
internal class LineEvents(
  private val stderr: Boolean,
  private val events: Consumer<WorkerEvent>,
) : OutputStream() {
  private val line = ByteArrayOutputStream()

  @Synchronized
  @Throws(IOException::class)
  override fun write(b: Int) {
    line.write(b)
    if (b == '\n'.code) {
      flush()
    }
  }

  @Synchronized
  @Throws(IOException::class)
  override fun write(bytes: ByteArray, offset: Int, length: Int) {
    for (i in offset until offset + length) {
      write(bytes[i].toInt())
    }
  }

  @Synchronized
  @Throws(IOException::class)
  override fun flush() {
    if (line.size() == 0) {
      return
    }
    val text = line.toString(StandardCharsets.UTF_8)
    line.reset()
    try {
      val event = WorkerEvent.newBuilder()
      if (stderr) event.setStderr(text) else event.setStdout(text)
      events.accept(event.build())
    } catch (e: UncheckedIOException) {
      throw checkNotNull(e.cause)
    }
  }
}
