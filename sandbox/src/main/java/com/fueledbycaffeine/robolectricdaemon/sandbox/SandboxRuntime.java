package com.fueledbycaffeine.robolectricdaemon.sandbox;

import com.fueledbycaffeine.robolectricdaemon.WorkerRuntime;
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Loaded inside the test runtime so the worker need not depend on JUnit, Robolectric or ASM. */
public final class SandboxRuntime implements WorkerRuntime {
  private final boolean robolectric =
      available("org/robolectric/internal/AndroidSandbox$SdkSandboxClassLoader.class");
  private final boolean asm = available("org/objectweb/asm/ClassReader.class");

  private static boolean available(String resource) {
    return SandboxRuntime.class.getClassLoader().getResource(resource) != null;
  }

  @Override
  public int[] execute(
      ClassLoader loader,
      Supplier<List<String>> next,
      List<String> includes,
      Consumer<WorkerEvent> events)
      throws ClassNotFoundException {
    return JUnitRunner.run(loader, next, includes, events);
  }

  @Override
  public void setProject(URL[] urls, Predicate<String> owns) {
    if (robolectric) {
      ProjectAwareSandboxClassLoader.setProject(urls, owns);
    }
  }

  @Override
  public Set<String> pinnedClasses() {
    return robolectric ? ProjectAwareSandboxClassLoader.pinnedClasses() : Set.of();
  }

  @Override
  public List<String> boundByName() {
    return robolectric ? ProjectAwareSandboxClassLoader.boundByName() : List.of();
  }

  @Override
  public boolean canAnalyzeClasses() {
    return asm;
  }

  @Override
  public boolean isConstantOnly(byte[] bytes) {
    return BoundClasses.isConstantOnly(bytes);
  }

  @Override
  public List<String> references(byte[] bytes) {
    return BoundClasses.references(bytes);
  }
}
