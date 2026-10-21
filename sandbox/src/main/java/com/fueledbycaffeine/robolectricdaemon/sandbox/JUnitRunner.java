package com.fueledbycaffeine.robolectricdaemon.sandbox;

import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.RemoteThrowable;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.StackFrame;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.TestEvent;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.TestFailure;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent;
import com.fueledbycaffeine.robolectricdaemon.internal.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.vintage.engine.VintageTestEngine;
import org.junit.vintage.engine.Constants;

/** Executes Gradle-selected Vintage IDs and forwards Platform events without adapting JUnit 4. */
public final class JUnitRunner {
  private static final int MAX_SERIALIZED_FAILURE = 1 << 20;

  private JUnitRunner() {}

  /** @return {tests run, failures} */
  public static int[] run(
      ClassLoader loader,
      Supplier<List<String>> next,
      List<String> includes,
      Consumer<WorkerEvent> events) throws ClassNotFoundException {
    var engine = new VintageTestEngine();
    var launcher = LauncherFactory.create(
        LauncherConfig.builder()
            .enableTestEngineAutoRegistration(false)
            .enableTestExecutionListenerAutoRegistration(false)
            .enableLauncherDiscoveryListenerAutoRegistration(false)
            .enableLauncherSessionListenerAutoRegistration(false)
            .enablePostDiscoveryFilterAutoRegistration(false)
            .addTestEngines(engine)
            .build());
    Events listener = new Events(events);
    List<String> candidates;
    while (!(candidates = next.get()).isEmpty()) {
      for (String candidate : candidates) {
        UniqueId runnerId = UniqueId.forEngine(engine.getId()).append("runner", candidate);
        var selected = includes.stream()
            .map(UniqueId::parse)
            .filter(id -> id.hasPrefix(runnerId))
            .collect(Collectors.toList());
        if (selected.isEmpty()) {
          throw new IllegalStateException("No selected Vintage tests for " + candidate);
        }
        // Loading by name through the base would pin this epoch's test class to that loader.
        TestPlan plan = launcher.discover(LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(Class.forName(candidate, false, loader)))
            .filters((PostDiscoveryFilter) descriptor -> FilterResult.includedIf(
                selected.stream().anyMatch(id ->
                    id.hasPrefix(descriptor.getUniqueId()) || descriptor.getUniqueId().hasPrefix(id))))
            .enableImplicitConfigurationParameters(false)
            .configurationParameter(Constants.PARALLEL_EXECUTION_ENABLED, "false")
            .build());
        if (!plan.containsTests()) {
          throw new IllegalStateException("Selected tests no longer match Vintage: " + candidate);
        }
        var discovered = plan.getDescendants(plan.getRoots().iterator().next());
        Set<UniqueId> discoveredIds = discovered.stream()
            .map(test -> UniqueId.parse(test.getUniqueId())).collect(Collectors.toSet());
        if (!discoveredIds.containsAll(selected) || discovered.stream()
            .filter(TestIdentifier::isTest)
            .anyMatch(test -> selected.stream().noneMatch(id ->
                UniqueId.parse(test.getUniqueId()).hasPrefix(id)))) {
          throw new IllegalStateException("Vintage could not preserve the selected test IDs: " + candidate);
        }
        launcher.execute(plan, listener);
        listener.checkFailure();
      }
    }
    return new int[] {listener.tests, listener.failures};
  }

  private static final class Events implements TestExecutionListener {
    private final Consumer<WorkerEvent> events;
    private RuntimeException deliveryFailure;
    private Throwable engineFailure;
    private int tests;
    private int failures;

    Events(Consumer<WorkerEvent> events) {
      this.events = events;
    }

    private void send(TestEvent.Builder event) {
      if (deliveryFailure != null) return;
      try {
        events.accept(WorkerEvent.newBuilder().setTest(event).build());
      } catch (RuntimeException e) {
        // Platform logs and swallows listener exceptions; rethrow after execution instead.
        deliveryFailure = e;
      }
    }

    void checkFailure() {
      if (deliveryFailure != null) throw deliveryFailure;
      if (engineFailure != null) throw new IllegalStateException("Vintage execution failed", engineFailure);
    }

    @Override
    public void dynamicTestRegistered(TestIdentifier test) {
      TestEvent.Builder event = event(test, TestEvent.Kind.REGISTERED)
          .setParentId(test.getParentId().orElseThrow())
          .setDisplayName(test.getDisplayName())
          .setLegacyName(test.getLegacyReportingName())
          .setDescriptorType(test.getType().name());
      test.getSource().ifPresent(source -> {
        if (source instanceof MethodSource) {
          MethodSource method = (MethodSource) source;
          event.setClassName(method.getClassName()).setMethod(method.getMethodName());
        } else if (source instanceof ClassSource) {
          event.setClassName(((ClassSource) source).getClassName());
        }
      });
      send(event);
    }

    @Override
    public void executionStarted(TestIdentifier test) {
      if (test.getParentId().isPresent()) send(event(test, TestEvent.Kind.STARTED));
    }

    @Override
    public void executionSkipped(TestIdentifier test, String reason) {
      if (test.getParentId().isPresent()) {
        send(event(test, TestEvent.Kind.SKIPPED).setReason(reason));
      }
    }

    @Override
    public void executionFinished(TestIdentifier test, TestExecutionResult result) {
      if (test.getParentId().isEmpty()) {
        result.getThrowable().ifPresent(error -> engineFailure = error);
        return;
      }
      if (test.isTest()) tests++;
      if (result.getStatus() == TestExecutionResult.Status.FAILED) failures++;
      TestEvent.Builder event = event(test, TestEvent.Kind.FINISHED)
          .setStatus(TestEvent.Status.valueOf(result.getStatus().name()));
      result.getThrowable().ifPresent(error -> event.setFailure(failure(error)));
      send(event);
    }

    private static TestEvent.Builder event(TestIdentifier test, TestEvent.Kind kind) {
      return TestEvent.newBuilder().setUniqueId(test.getUniqueId()).setKind(kind);
    }
  }

  private static TestFailure failure(Throwable exception) {
    TestFailure.Builder event = TestFailure.newBuilder()
        .setAssertion(exception instanceof AssertionError);
    String expected = comparison(exception, true);
    String actual = comparison(exception, false);
    if (expected != null) event.setExpected(expected);
    if (actual != null) event.setActual(actual);
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = exception; current != null && seen.add(current); current = current.getCause()) {
      RemoteThrowable.Builder remote = RemoteThrowable.newBuilder().setType(current.getClass().getName());
      String message = safeMessage(current);
      if (message != null) remote.setMessage(message);
      for (StackTraceElement frame : current.getStackTrace()) {
        StackFrame.Builder stack = StackFrame.newBuilder()
            .setClassName(frame.getClassName())
            .setMethod(frame.getMethodName())
            .setLine(frame.getLineNumber());
        if (frame.getFileName() != null) stack.setFile(frame.getFileName());
        remote.addFrames(stack);
      }
      event.addCauses(remote);
    }
    byte[] serialized = serialize(exception);
    if (serialized != null) event.setSerialized(ByteString.copyFrom(serialized));
    return event.build();
  }

  private static byte[] serialize(Throwable exception) {
    var bytes = new ByteArrayOutputStream();
    try (var out = new ObjectOutputStream(bytes)) {
      out.writeObject(exception);
    } catch (Exception | StackOverflowError e) {
      return null;
    }
    return bytes.size() <= MAX_SERIALIZED_FAILURE ? bytes.toByteArray() : null;
  }

  private static String comparison(Throwable exception, boolean expected) {
    if (exception instanceof org.junit.ComparisonFailure) {
      org.junit.ComparisonFailure comparison = (org.junit.ComparisonFailure) exception;
      return expected ? comparison.getExpected() : comparison.getActual();
    }
    if (exception instanceof junit.framework.ComparisonFailure) {
      junit.framework.ComparisonFailure comparison = (junit.framework.ComparisonFailure) exception;
      return expected ? comparison.getExpected() : comparison.getActual();
    }
    return null;
  }

  private static String safeMessage(Throwable throwable) {
    try {
      return throwable.getMessage();
    } catch (RuntimeException e) {
      return "<getMessage() threw " + e + ">";
    }
  }
}
