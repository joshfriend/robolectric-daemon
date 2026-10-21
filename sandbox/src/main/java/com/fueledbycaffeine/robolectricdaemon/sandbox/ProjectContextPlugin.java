package com.fueledbycaffeine.robolectricdaemon.sandbox;

import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin;

/**
 * Runs inside each test after Robolectric makes the persistent sandbox loader the context loader,
 * and swaps in the current project-class loader. Libraries that load classes by name through the
 * context loader (ClassGraph preview scanning, ServiceLoader...) then bind them to a loader that's
 * discarded with this epoch, instead of pinning stale copies to the sandbox. Robolectric restores
 * the previous context loader only after the whole class, so later tests in the class start with
 * the project loader this plugin installed.
 */
public final class ProjectContextPlugin implements TestEnvironmentLifecyclePlugin {
  @Override
  public void onSetupApplicationState() {
    Thread thread = Thread.currentThread();
    ClassLoader context = thread.getContextClassLoader();
    ClassLoader sandbox =
        context instanceof ProjectContext || context == null ? context : context.getParent();
    if (!(sandbox instanceof ProjectContext)) {
      throw new IllegalStateException(
          "Expected a project-aware sandbox context class loader, but got " + context);
    }
    thread.setContextClassLoader(((ProjectContext) sandbox).projectContext());
  }
}
