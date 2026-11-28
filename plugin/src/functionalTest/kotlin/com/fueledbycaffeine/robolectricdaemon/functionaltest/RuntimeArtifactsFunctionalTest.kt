package com.fueledbycaffeine.robolectricdaemon.functionaltest

import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.PRODUCTION
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.STATE
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.production
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.build
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.configurationCacheReused
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.file
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.workerPids
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.write
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

class RuntimeArtifactsFunctionalTest {
  private val test = ":lib:testDebugUnitTest"

  @Test
  fun `runtime dependency changes rerun tests and restart workers without overwriting staged jars`() {
    val project = DaemonProject().build()
    project.file("gradle.properties").appendText("\norg.gradle.caching=false\n")
    val runtime = project.file("lib/runtime")
    check(runtime.mkdirs())
    val root = File(System.getProperty("runtimeBuildRoot"))
    for (module in listOf("worker", "protocol")) {
      val jar =
        root.resolve("$module/build/libs/$module-${System.getProperty("runtimeVersion")}.jar")
      check(jar.isFile)
      jar.copyTo(runtime.resolve("$module.jar"))
    }
    project
      .file("lib/build.gradle")
      .appendText(
        """

        configurations.named('robolectricDaemonWorkerDependencies') {
          dependencies.clear()
          dependencies.add(project.dependencies.create(project.files('runtime/worker.jar', 'runtime/protocol.jar')))
          dependencies.add(project.dependencies.create('org.jetbrains.kotlin:kotlin-stdlib:${KotlinVersion.CURRENT}'))
        }
        """
          .trimIndent()
      )
    try {
      val cold = project.build(test)
      assertThat(cold.workerPids).isNotEmpty()
      val staged =
        checkNotNull(project.file("$STATE/jars").listFiles()).single {
          it.name.startsWith("protocol-")
        }
      val original = staged.readBytes()
      val protocol = runtime.resolve("protocol.jar")
      val entries =
        JarFile(protocol).use { jar ->
          jar.entries().asSequence().associate { entry ->
            entry.name to jar.getInputStream(entry).use { it.readBytes() }
          }
        }
      JarOutputStream(protocol.outputStream()).use { output ->
        for ((name, bytes) in entries) {
          output.putNextEntry(JarEntry(name))
          output.write(bytes)
          output.closeEntry()
        }
        output.putNextEntry(JarEntry("runtime-revision"))
        output.write(1)
        output.closeEntry()
      }
      val edited = project.build(test)
      assertThat(edited.task(test)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
      assertThat(edited.workerPids).isNotEmpty()
      assertThat(edited.workerPids.intersect(cold.workerPids)).isEmpty()
      assertThat(edited.configurationCacheReused).isTrue()
      assertThat(staged.readBytes()).isEqualTo(original)
      assertThat(
          checkNotNull(project.file("$STATE/jars").list()).count { it.startsWith("protocol-") }
        )
        .isEqualTo(2)
    } finally {
      project.build(":lib:stopRobolectricDaemon")
    }
  }

  @Test
  fun `does not resolve runtime artifacts for help or disabled workers`() {
    val project = DaemonProject().build()
    project
      .file("lib/build.gradle")
      .appendText(
        """

        configurations.matching { it.name in [
          'robolectricDaemonWorkerClasspath', 'robolectricDaemonSandboxClasspath',
          'robolectricDaemonEngineClasspath'
        ] }.configureEach {
          incoming.beforeResolve { throw new GradleException('Unexpected runtime resolution') }
        }
        """
          .trimIndent()
      )
    project.build("help")
    val disabled = project.build(test, "-Probolectric.daemon=false")
    assertThat(disabled.task(test)?.outcome)
      .isIn(listOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE))
    assertThat(disabled.output).doesNotContain("Robolectric worker:")
    assertThat(project.file("$STATE/jars").exists()).isFalse()
    assertThat(project.build(test, "-Probolectric.daemon=false").configurationCacheReused).isTrue()
  }

  @Test
  fun `resolves runtime artifacts from an included build`() {
    val project = DaemonProject().build()
    val root = System.getProperty("runtimeBuildRoot")
    val settings = project.file("settings.gradle")
    val original = settings.readText()
    assertThat(original).contains("pluginManagement {")
    settings.writeText(
      original.replaceFirst("pluginManagement {", "pluginManagement {\n  includeBuild('$root')") +
        "\nincludeBuild('$root')\n"
    )
    try {
      val cold = project.build(test)
      assertThat(cold.output).contains("2 tests, 0 failed")
      project.write(PRODUCTION, production(1, member = "val included = true"))
      val edited = project.build(test)
      assertThat(edited.workerPids).isEqualTo(cold.workerPids)
      assertThat(edited.configurationCacheReused).isTrue()
    } finally {
      project.build(":lib:stopRobolectricDaemon")
    }
  }
}
