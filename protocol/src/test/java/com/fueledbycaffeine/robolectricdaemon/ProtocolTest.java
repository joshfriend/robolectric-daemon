package com.fueledbycaffeine.robolectricdaemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.ClassGroup;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.ClientMessage;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.Completed;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.Empty;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.RemoteThrowable;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.RunRequest;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.StackFrame;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.TestFailure;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.TestEvent;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolTest {
  private static final Empty EMPTY = Empty.getDefaultInstance();
  private static final TestEvent TEST =
      TestEvent.newBuilder()
          .setUniqueId("[engine:junit-vintage]/[runner:example.Test]/[test:test%5B0%5D(example.Test)]")
          .build();

  @Test
  void roundTripsRequestsInSequence() throws IOException {
    RunRequest run =
        RunRequest.newBuilder()
            .setResourceKey("resources")
            .setMirrorId("mirror")
            .addClasspath("/dependency.jar")
            .addProjectEntries("/classes")
            .putSystemProperties("unicode", "\u2603\u0000")
            .addIncludes(TEST.getUniqueId())
            .build();
    List<ClientMessage> messages =
        List.of(
            ClientMessage.newBuilder().setToken("token").setPing(EMPTY).build(),
            ClientMessage.newBuilder().setToken("token").setStop(EMPTY).build(),
            ClientMessage.newBuilder().setToken("token").setRun(run).build(),
            ClientMessage.newBuilder()
                .setClassGroup(ClassGroup.newBuilder().addClasses("example.Test"))
                .build(),
            ClientMessage.newBuilder().setClassGroup(ClassGroup.getDefaultInstance()).build());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(bytes);
    for (ClientMessage message : messages) Protocol.writeRequest(output, message);
    DataInputStream input = input(bytes.toByteArray());
    for (ClientMessage message : messages) assertEquals(message, Protocol.readRequest(input));
    assertEquals(-1, input.read());
  }

  @Test
  void roundTripsEveryEventAndPreservesOptionalValues() throws IOException {
    TestFailure failure =
        TestFailure.newBuilder()
            .setAssertion(true)
            .setExpected("\u0000")
            .setActual("")
            .addCauses(
                RemoteThrowable.newBuilder()
                    .setType("java.lang.AssertionError")
                    .setMessage("")
                    .addFrames(
                        StackFrame.newBuilder()
                            .setClassName("example.Test")
                            .setMethod("test")
                            .setFile("")
                            .setLine(-2)))
            .addCauses(
                RemoteThrowable.newBuilder()
                    .setType("java.lang.IllegalStateException")
                    .addFrames(
                        StackFrame.newBuilder()
                            .setClassName("example.Helper")
                            .setMethod("call")
                            .setLine(42)))
            .build();
    List<WorkerEvent> events =
        List.of(
            WorkerEvent.newBuilder().setAcknowledged(EMPTY).build(),
            WorkerEvent.newBuilder().setNext(EMPTY).build(),
            WorkerEvent.newBuilder().setTest(TEST.toBuilder()
                .setKind(TestEvent.Kind.REGISTERED)
                .setParentId("[engine:junit-vintage]/[runner:example.Test]")
                .setDisplayName("test[0]").setLegacyName("test[0]")
                .setDescriptorType("TEST").setClassName("example.Test").setMethod("test")).build(),
            WorkerEvent.newBuilder().setTest(TEST.toBuilder().setKind(TestEvent.Kind.STARTED)).build(),
            WorkerEvent.newBuilder().setTest(TEST.toBuilder().setKind(TestEvent.Kind.FINISHED)).build(),
            WorkerEvent.newBuilder().setTest(TEST.toBuilder().setKind(TestEvent.Kind.SKIPPED)
                .setReason("ignored")).build(),
            WorkerEvent.newBuilder().setTest(TEST.toBuilder().setKind(TestEvent.Kind.FINISHED)
                .setStatus(TestEvent.Status.FAILED).setFailure(failure)).build(),
            WorkerEvent.newBuilder().setTest(TEST.toBuilder().setKind(TestEvent.Kind.FINISHED)
                .setStatus(TestEvent.Status.ABORTED).setFailure(failure)).build(),
            WorkerEvent.newBuilder().setStdout("\u2603\u0000\n").build(),
            WorkerEvent.newBuilder().setStderr("").build(),
            WorkerEvent.newBuilder()
                .setCompleted(
                    Completed.newBuilder()
                        .setTests(3)
                        .setFailures(1)
                        .setError("error")
                        .setSummary("summary"))
                .build());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(bytes);
    for (WorkerEvent event : events) Protocol.writeEvent(output, event);
    DataInputStream input = input(bytes.toByteArray());
    for (WorkerEvent event : events) assertEquals(event, Protocol.readEvent(input));
    assertEquals(-1, input.read());
    assertTrue(failure.hasExpected());
    assertTrue(failure.hasActual());
    assertTrue(failure.getCauses(0).hasMessage());
    assertFalse(failure.getCauses(1).hasMessage());
    assertTrue(failure.getCauses(0).getFrames(0).hasFile());
    assertFalse(failure.getCauses(1).getFrames(0).hasFile());
  }

  @Test
  void rejectsInvalidLengthsAndTruncatedFrames() throws IOException {
    for (int length : new int[] {-1, Protocol.MAX_MESSAGE + 1, Integer.MAX_VALUE}) {
      assertThrows(IOException.class, () -> Protocol.readEvent(input(frame(length))));
      assertThrows(IOException.class, () -> Protocol.readRequest(input(frame(length))));
    }
    assertThrows(EOFException.class, () -> Protocol.readEvent(input(new byte[] {0, 0})));
    assertThrows(EOFException.class, () -> Protocol.readRequest(input(frame(2, (byte) 1))));
    // The exact limit is accepted by framing, then fails because the payload is absent.
    assertThrows(
        EOFException.class, () -> Protocol.readEvent(input(frame(Protocol.MAX_MESSAGE))));
  }

  @Test
  void writesTheExactMessageLimit() throws IOException {
    // One field tag and a four-byte protobuf length precede this string.
    WorkerEvent event =
        WorkerEvent.newBuilder().setStdout("x".repeat(Protocol.MAX_MESSAGE - 5)).build();
    assertEquals(Protocol.MAX_MESSAGE, event.getSerializedSize());
    DataOutputStream output = new DataOutputStream(OutputStream.nullOutputStream());
    Protocol.writeEvent(output, event);
    assertEquals(Protocol.MAX_MESSAGE + Integer.BYTES, output.size());
  }

  @Test
  void rejectsOversizedWritesBeforeWritingTheHeader() {
    WorkerEvent event =
        WorkerEvent.newBuilder().setStdout("x".repeat(Protocol.MAX_MESSAGE)).build();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    assertThrows(
        IOException.class, () -> Protocol.writeEvent(new DataOutputStream(bytes), event));
    assertEquals(0, bytes.size());
  }

  @Test
  void rejectsMissingUnknownAndMalformedPayloads() throws IOException {
    // Field 100 is unknown to both directional envelopes.
    for (byte[] payload : new byte[][] {{}, {(byte) 0xa2, 6, 0}, {0}}) {
      byte[] frame = frame(payload.length, payload);
      assertThrows(IOException.class, () -> Protocol.readRequest(input(frame)));
      assertThrows(IOException.class, () -> Protocol.readEvent(input(frame)));
    }
    DataOutputStream output = new DataOutputStream(new ByteArrayOutputStream());
    assertThrows(
        IOException.class, () -> Protocol.writeRequest(output, ClientMessage.getDefaultInstance()));
    assertThrows(
        IOException.class, () -> Protocol.writeEvent(output, WorkerEvent.getDefaultInstance()));
  }

  @Test
  void acceptsUnknownFieldsAlongsideAKnownPayload() throws IOException {
    ClientMessage message = ClientMessage.newBuilder().setPing(EMPTY).build();
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.write(message.toByteArray());
    payload.write(new byte[] {(byte) 0xa2, 6, 0});
    ClientMessage read = Protocol.readRequest(input(frame(payload.size(), payload.toByteArray())));
    assertTrue(read.hasPing());
  }

  private static DataInputStream input(byte[] bytes) {
    return new DataInputStream(new ByteArrayInputStream(bytes));
  }

  private static byte[] frame(int length, byte... payload) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(bytes);
    output.writeInt(length);
    output.write(payload);
    return bytes.toByteArray();
  }
}
