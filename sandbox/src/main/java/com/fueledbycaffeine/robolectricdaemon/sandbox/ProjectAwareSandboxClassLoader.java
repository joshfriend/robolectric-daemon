package com.fueledbycaffeine.robolectricdaemon.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.inject.Named;
import org.robolectric.internal.AndroidSandbox;
import org.robolectric.internal.bytecode.ClassDetails;
import org.robolectric.internal.bytecode.ClassInstrumentor;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.pluginapi.Sdk;

/**
 * Robolectric's sandbox loader, except that project and test classes are defined by a disposable
 * child loader instead of the sandbox itself. Android, Robolectric and library classes stay loaded,
 * instrumented and JIT-warm across edits; an edit only replaces the child.
 *
 * <p>Registered through Robolectric's plugin lookup ({@code META-INF/services}) and only present on
 * the persistent worker's class path.
 */
public final class ProjectAwareSandboxClassLoader extends AndroidSandbox.SdkSandboxClassLoader
    implements ProjectContext {
  private static volatile Project project;
  private static final Set<String> PINNED = ConcurrentHashMap.newKeySet();
  private static final Set<ProjectAwareSandboxClassLoader> INSTANCES =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

  private final InstrumentationConfiguration config;
  private ProjectLoader projectLoader;
  private final Set<String> routed = ConcurrentHashMap.newKeySet();

  public ProjectAwareSandboxClassLoader(
      InstrumentationConfiguration config,
      @Named("runtimeSdk") Sdk runtimeSdk,
      ClassInstrumentor classInstrumentor) {
    super(config, runtimeSdk, classInstrumentor);
    this.config = config;
    INSTANCES.add(this);
  }

  /** Called by the worker for each project-class epoch; sandboxes switch lazily. */
  public static void setProject(URL[] urls, Predicate<String> owns) {
    project = new Project(urls, owns);
  }

  /**
   * Project classes Robolectric instruments (e.g. via {@code instrumentedPackages}) must stay in
   * the sandbox; changing one needs a fresh sandbox.
   */
  public static Set<String> pinnedClasses() {
    return PINNED;
  }

  @Override
  public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    // The sandbox plugin must share the parent's interface, not define a sandbox copy.
    if (name.equals(ProjectContext.class.getName())) {
      return ProjectContext.class;
    }
    ProjectLoader loader = projectLoader();
    if (loader != null
        && !PINNED.contains(name)
        && loader.project.owns.test(name)
        && config.shouldAcquire(name)) {
      routed.add(name);
      return loader.loadClass(name);
    }
    return super.loadClass(name, resolve);
  }

  /**
   * The loader {@link ProjectContextPlugin} makes each test's context loader: lookups by name
   * through it see the current project classes and are forgotten with it.
   */
  @Override
  public ClassLoader projectContext() {
    ProjectLoader loader = projectLoader();
    return loader != null ? loader : this;
  }

  /**
   * Project classes the JVM bound to a sandbox loader by name ({@code Class.forName(name, init,
   * sandbox)} records the initiating loader and never asks it again), so later epochs would see
   * stale copies; the worker then replaces the sandbox.
   */
  public static List<String> boundByName() {
    List<String> bound = new ArrayList<>();
    List<ProjectAwareSandboxClassLoader> loaders;
    synchronized (INSTANCES) {
      loaders = new ArrayList<>(INSTANCES);
    }
    for (ProjectAwareSandboxClassLoader loader : loaders) {
      for (String name : loader.routed.toArray(new String[0])) {
        Class<?> loaded = loader.findLoadedClass(name);
        if (loaded != null && loaded.getClassLoader() != loader) {
          bound.add(name);
        } else {
          loader.routed.remove(name);
        }
      }
    }
    return bound;
  }

  private synchronized ProjectLoader projectLoader() {
    Project current = project;
    if (current == null) {
      return null;
    }
    if (projectLoader == null || projectLoader.project != current) {
      projectLoader = new ProjectLoader(current);
    }
    return projectLoader;
  }

  private Class<?> loadInSandbox(String name) throws ClassNotFoundException {
    return super.loadClass(name, false);
  }

  private static final class Project {
    final URL[] urls;
    final Predicate<String> owns;

    Project(URL[] urls, Predicate<String> owns) {
      this.urls = urls;
      this.owns = owns;
    }
  }

  private final class ProjectLoader extends URLClassLoader {
    final Project project;

    ProjectLoader(Project project) {
      super(project.urls, ProjectAwareSandboxClassLoader.this);
      this.project = project;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!project.owns.test(name) || PINNED.contains(name)) {
        return getParent().loadClass(name);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        return loaded != null ? loaded : findClass(name);
      }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      if (isInstrumented(name)) {
        PINNED.add(name);
        return loadInSandbox(name);
      }
      return super.findClass(name);
    }

    private boolean isInstrumented(String name) throws ClassNotFoundException {
      URL resource = findResource(name.replace('.', '/') + ".class");
      if (resource == null) {
        throw new ClassNotFoundException(name);
      }
      try (InputStream input = resource.openStream()) {
        return config.shouldInstrument(new ClassDetails(input.readAllBytes()));
      } catch (IOException e) {
        throw new ClassNotFoundException(name, e);
      }
    }
  }
}
