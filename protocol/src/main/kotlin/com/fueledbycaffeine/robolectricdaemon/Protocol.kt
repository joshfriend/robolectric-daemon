package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.ClientMessage
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Bounded, length-prefixed protobuf messages shared by the client and worker. */
object Protocol {
  const val MAX_MESSAGE = 64 * 1024 * 1024

  @JvmStatic
  @Throws(IOException::class)
  fun writeRequest(output: DataOutputStream, message: ClientMessage) {
    if (message.payloadCase == ClientMessage.PayloadCase.PAYLOAD_NOT_SET) {
      throw IOException("Missing client message payload")
    }
    write(output, message)
  }

  @JvmStatic
  @Throws(IOException::class)
  fun readRequest(input: DataInputStream): ClientMessage {
    val message = read(input, ClientMessage.parser())
    if (message.payloadCase == ClientMessage.PayloadCase.PAYLOAD_NOT_SET) {
      throw IOException("Missing or unknown client message payload")
    }
    return message
  }

  @JvmStatic
  @Throws(IOException::class)
  fun writeEvent(output: DataOutputStream, message: WorkerEvent) {
    if (message.payloadCase == WorkerEvent.PayloadCase.PAYLOAD_NOT_SET) {
      throw IOException("Missing worker event payload")
    }
    write(output, message)
  }

  @JvmStatic
  @Throws(IOException::class)
  fun readEvent(input: DataInputStream): WorkerEvent {
    val message = read(input, WorkerEvent.parser())
    if (message.payloadCase == WorkerEvent.PayloadCase.PAYLOAD_NOT_SET) {
      throw IOException("Missing or unknown worker event payload")
    }
    return message
  }

  private fun write(output: DataOutputStream, message: MessageLite) {
    val size = bounded(message.serializedSize)
    output.writeInt(size)
    message.writeTo(output)
  }

  private fun <T> read(input: DataInputStream, parser: Parser<T>): T {
    val bytes = ByteArray(bounded(input.readInt()))
    input.readFully(bytes)
    return parser.parseFrom(bytes)
  }

  private fun bounded(size: Int): Int {
    if (size !in 0..MAX_MESSAGE) {
      throw IOException("Corrupt worker message: length $size")
    }
    return size
  }
}
