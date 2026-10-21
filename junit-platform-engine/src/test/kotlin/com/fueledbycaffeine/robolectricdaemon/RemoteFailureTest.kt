package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.internal.protobuf.ByteString
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.RemoteThrowable
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.StackFrame
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.TestFailure
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

class RemoteFailureTest {
  @Test
  fun `serialized failures keep real types causes and suppressed exceptions`() {
    val original = ProjectFailure("outer", IllegalArgumentException("root"))
    original.addSuppressed(IllegalStateException("hidden"))
    val remote = DaemonTestEngine.remoteFailure(serializedFailure(original))
    assertInstanceOf(ProjectFailure::class.java, remote)
    assertEquals("outer", remote.message)
    assertInstanceOf(IllegalArgumentException::class.java, remote.cause)
    assertEquals("hidden", remote.suppressed.single().message)
    assertEquals(original.stackTrace.toList(), remote.stackTrace.toList())
  }

  @Test
  fun `serialized failures with other types fall back to the reconstructed chain`() {
    val remote =
      DaemonTestEngine.remoteFailure(serializedFailure(Carrier(HashMap(mapOf("key" to "value")))))
    assertInstanceOf(RuntimeException::class.java, remote)
    assertEquals("example.Carrier: fallback", remote.message)
  }

  private fun serializedFailure(exception: Throwable): TestFailure {
    val bytes = ByteArrayOutputStream()
    ObjectOutputStream(bytes).use { it.writeObject(exception) }
    return TestFailure.newBuilder()
      .addCauses(RemoteThrowable.newBuilder().setType("example.Carrier").setMessage("fallback"))
      .setSerialized(ByteString.copyFrom(bytes.toByteArray()))
      .build()
  }

  class ProjectFailure(message: String, cause: Throwable) : IllegalStateException(message, cause)

  class Carrier(val payload: HashMap<String, String>) : RuntimeException("carrier")

  @Test
  fun `preserves assertion values causes and stack frames`() {
    val failure =
      TestFailure.newBuilder()
        .setAssertion(true)
        .setExpected("\u0000")
        .setActual("")
        .addCauses(
          RemoteThrowable.newBuilder()
            .setType("org.junit.ComparisonFailure")
            .setMessage("comparison")
            .addFrames(
              StackFrame.newBuilder()
                .setClassName("example.Test")
                .setMethod("test")
                .setFile("Test.java")
                .setLine(42)
            )
        )
        .addCauses(
          RemoteThrowable.newBuilder()
            .setType("java.lang.IllegalStateException")
            .addFrames(
              StackFrame.newBuilder().setClassName("example.Native").setMethod("call").setLine(-2)
            )
        )
        .build()
    val remote =
      assertInstanceOf(AssertionFailedError::class.java, DaemonTestEngine.remoteFailure(failure))
    assertEquals("\u0000", remote.expected.value)
    assertEquals("", remote.actual.value)
    assertEquals("org.junit.ComparisonFailure: comparison", remote.message)
    assertEquals(
      StackTraceElement("example.Test", "test", "Test.java", 42),
      remote.stackTrace.single(),
    )
    val cause = checkNotNull(remote.cause)
    assertEquals("java.lang.IllegalStateException: ", cause.message)
    assertEquals(StackTraceElement("example.Native", "call", null, -2), cause.stackTrace.single())
    assertNull(cause.cause)
  }

  @Test
  fun `absent comparison values remain null`() {
    val failure =
      TestFailure.newBuilder()
        .setAssertion(true)
        .addCauses(RemoteThrowable.newBuilder().setType("java.lang.AssertionError").setMessage(""))
        .build()
    val remote =
      assertInstanceOf(AssertionFailedError::class.java, DaemonTestEngine.remoteFailure(failure))
    assertNull(remote.expected.value)
    assertNull(remote.actual.value)
    assertEquals(0, remote.stackTrace.size)
  }
}
