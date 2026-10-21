package com.fueledbycaffeine.robolectricdaemon

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasHostTests
import com.android.build.api.variant.HostTest
import com.android.build.api.variant.HostTestBuilder
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.junit.JUnitOptions

/**
 * Runs an Android module's JVM unit test tasks (`test<Variant>UnitTest`) in persistent workers that
 * keep Robolectric's sandboxes warm across runs. Disable with `-Probolectric.daemon=false`; it is
 * off by default when the `CI` environment variable or `ci` system property is set.
 */
class RobolectricDaemonPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val providers = project.providers
    val enabled =
      providers
        .gradleProperty("robolectric.daemon")
        .map { it != "false" }
        .orElse(
          providers
            .of(CiValueSource::class.java) {
              it.parameters.environmentVariable.set(providers.environmentVariable("CI"))
              it.parameters.systemProperty.set(providers.systemProperty("ci"))
            }
            .map { !it }
        )
    val stateDirectory = project.layout.buildDirectory.dir("robolectric-daemon")
    val workerClasspath = WorkerClasspath.create(project, enabled)
    project.tasks.register("stopRobolectricDaemon", StopDaemonTask::class.java) { task ->
      task.group = "verification"
      task.description = "Stops this module's persistent Robolectric test workers."
      task.stateDirectory.set(stateDirectory)
    }
    project.pluginManager.withPlugin("com.android.base") {
      val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
      components.onVariants(components.selector().all()) { variant ->
        val hostTests = (variant as? HasHostTests)?.hostTests.orEmpty()
        val unitTest = hostTests[HostTestBuilder.UNIT_TEST_TYPE]
        if (unitTest != null) {
          project.register(variant.name, unitTest, stateDirectory, enabled, workerClasspath)
        }
      }
    }
  }

  private fun Project.register(
    variantName: String,
    hostTest: HostTest,
    stateDirectory: Provider<Directory>,
    enabled: Provider<Boolean>,
    workerClasspath: WorkerClasspath,
  ) {
    val project = this
    val suffix = variantName.replaceFirstChar(Char::uppercaseChar) + "UnitTest"
    // AGP's name for the variant's unit test task, which it registers after this callback.
    val testName = "test$suffix"
    val directory = stateDirectory.map { it.dir(testName) }
    val testClasspath = project.objects.fileCollection()
    val sync =
      project.tasks.register(
        "sync${suffix}RobolectricClasspath",
        SyncRobolectricClasspath::class.java,
      ) { task ->
        task.description = "Mirrors $testName's project classes for its Robolectric workers."
        task.classpath.from(testClasspath)
        task.buildRoot.set(project.rootDir.absolutePath)
        task.gradleUserHome.set(project.gradle.gradleUserHomeDir.absolutePath)
        task.mirror.set(directory.map { it.dir("live") })
        task.result.set(directory.map { it.file("classpath.properties") })
        task.daemonEnabled.set(enabled)
      }
    hostTest.configureTestTask { test ->
      val originalClasspath = test.classpath
      testClasspath.from(originalClasspath)
      install(test, sync, directory, enabled, workerClasspath)
    }
  }

  private fun install(
    test: Test,
    sync: TaskProvider<SyncRobolectricClasspath>,
    directory: Provider<Directory>,
    enabled: Provider<Boolean>,
    workerClasspath: WorkerClasspath,
  ) {
    // Keep engine-only dependencies out of the Robolectric base loader.
    val originalClasspath = test.classpath
    val result = sync.flatMap { it.result }
    test.inputs
      .files(result)
      .withPropertyName("robolectricDaemonClasspath")
      .withPathSensitivity(PathSensitivity.NONE)
    test.inputs
      .files(workerClasspath.runtime, workerClasspath.sandbox, workerClasspath.engine)
      .withPropertyName("robolectricDaemonRuntime")
      .withPathSensitivity(PathSensitivity.NONE)
    val active = enabled.get()
    if (!active) return
    val options = test.options
    if (
      options !is JUnitOptions ||
        options.includeCategories.isNotEmpty() ||
        options.excludeCategories.isNotEmpty()
    ) {
      test.logger.info(
        "Not using the Robolectric worker for {}: not uncategorized JUnit 4",
        test.path,
      )
      return
    }
    test.classpath = workerClasspath.engine.plus(originalClasspath)
    test.useJUnitPlatform { it.includeEngines(DaemonContract.ENGINE_ID) }
    test.testLogging.showStandardStreams = true
    val arguments =
      DaemonArguments(
        directory,
        result,
        test.inputs.files,
        workerClasspath.runtime,
        workerClasspath.sandbox,
      )
    test.jvmArgumentProviders.add(arguments)
    test.doFirst(ConfigureBridgeExecution(arguments))
  }
}
