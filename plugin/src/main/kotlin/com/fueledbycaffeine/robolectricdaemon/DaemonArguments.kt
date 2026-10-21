package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.DaemonContract.Launch
import com.fueledbycaffeine.robolectricdaemon.DaemonContract.Request
import java.nio.charset.StandardCharsets
import java.util.Properties
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
import org.gradle.process.CommandLineArgumentProvider

internal class DaemonArguments(
  @get:Internal val directory: Provider<Directory>,
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val sync: Provider<RegularFile>,
  @get:Internal val runtimeInputs: FileCollection,
  @get:Classpath val worker: FileCollection,
  @get:Classpath val sandbox: FileCollection,
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> {
    val directory = directory.get().asFile
    val properties = Properties()
    properties.setProperty(Request.DIRECTORY, directory.absolutePath)
    properties.setProperty(Request.SYNC, sync.get().asFile.absolutePath)
    properties.setProperty(Request.WORKER, worker.asPath)
    properties.setProperty(Request.SANDBOX, sandbox.asPath)
    properties.setProperty(Request.INPUTS, runtimeInputs.asPath)
    val file = directory.toPath().resolve(Request.FILE)
    properties.let { if (it != file.readPropertiesOrNull()) file.writePropertiesAtomically(it) }
    return listOf("-D${DaemonContract.REQUEST_PROPERTY}=$file")
  }

  fun writeLaunch(test: Test) {
    val directory = directory.get().asFile
    val properties = Properties()
    properties.setProperty(Launch.JAVA, test.javaLauncher.get().executablePath.asFile.absolutePath)
    properties.setProperty(Launch.WORKING_DIRECTORY, test.workingDir.canonicalPath)
    val arguments = test.allJvmArgs.filterNot { it.startsWith("-D") }
    properties.setProperty(Launch.ARGUMENT_COUNT, arguments.size.toString())
    arguments.forEachIndexed { index, value ->
      properties.setProperty("${Launch.ARGUMENT_PREFIX}$index", value)
    }
    test.allJvmArgs
      .filter { it.startsWith("-D") }
      .forEach {
        properties.setProperty(
          "${Launch.PROPERTY_PREFIX}${it.substring(2).substringBefore('=')}",
          it.substringAfter('=', ""),
        )
      }
    test.environment.forEach { (key, value) ->
      properties.setProperty("${Launch.ENVIRONMENT_PREFIX}$key", value.toString())
    }
    val configuredEnvironment =
      test.environment
        .filter { (key, value) -> value.toString() != System.getenv(key) }
        .toSortedMap()
    properties.setProperty(
      Launch.ENVIRONMENT_IDENTITY,
      digest(
        configuredEnvironment.entries
          .joinToString("\n") { "${it.key}=${it.value}" }
          .toByteArray(StandardCharsets.UTF_8)
      ),
    )
    directory.toPath().resolve(Launch.FILE).writePropertiesAtomically(properties)
  }
}

internal class ConfigureBridgeExecution(private val arguments: DaemonArguments) : Action<Task> {
  override fun execute(task: Task) {
    val test = task as Test
    val options = test.options
    check(
      options is JUnitPlatformOptions &&
        options.includeEngines == setOf(DaemonContract.ENGINE_ID) &&
        DaemonContract.ENGINE_ID !in options.excludeEngines
    ) {
      "Robolectric daemon requires its JUnit Platform engine; do not override the test framework or engines. " +
        "Use -Probolectric.daemon=false for another test framework."
    }
    arguments.writeLaunch(test)
    val local =
      test.debug ||
        test.forkEvery > 0 ||
        test.allJvmArgs.any { it.startsWith("-javaagent") || it.startsWith("-agent") }
    test.systemProperty(DaemonContract.FORKS_PROPERTY, test.maxParallelForks)
    test.systemProperty(DaemonContract.LOCAL_PROPERTY, local)
    // Gradle owns a single disposable engine JVM; the daemon retains the shared work queue.
    if (!local) test.maxParallelForks = 1
  }
}
