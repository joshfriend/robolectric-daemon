package com.fueledbycaffeine.robolectricdaemon.functionaltest

import com.autonomousapps.kit.GradleProject
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.build
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.buildAndFail
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.configurationCacheReused
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.file
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

class ClasspathSyncFunctionalTest {
  private val task = ":lib:mirrorClasspath"
  private val state = "lib/build/mirror-test"

  @Test
  fun `directories use incremental changes and recover missing mirrors`() {
    val project = fixture()
    val classes = project.file("lib/first")
    classes.resolve("Example.class").writeText("first class")
    classes.resolve("unchanged.class").writeText("unchanged class")
    val resource = classes.resolve("example.txt")
    resource.writeText("resource one")
    project.build(task)
    val cold = manifest(project)
    val target = project.file("$state/live/0-first")
    val unchanged = target.resolve("unchanged.class").lastModified()
    assertThat(project.build(task).task(task)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

    classes.resolve("Example.class").writeText("edited class")
    classes.resolve("Added.class").writeText("added class")
    val edited = project.build(task, "--info")
    val warm = manifest(project)
    assertThat(edited.configurationCacheReused).isTrue()
    assertThat(warm.getProperty("mirrorId")).isEqualTo(cold.getProperty("mirrorId"))
    assertThat(warm.getProperty("resourceKey")).isEqualTo(cold.getProperty("resourceKey"))
    assertThat(target.resolve("Example.class").readText()).isEqualTo("edited class")
    assertThat(target.resolve("Added.class").readText()).isEqualTo("added class")
    assertThat(target.resolve("unchanged.class").lastModified()).isEqualTo(unchanged)

    check(classes.resolve("Added.class").delete())
    project.build(task)
    assertThat(target.resolve("Added.class").exists()).isFalse()
    assertThat(manifest(project).getProperty("resourceKey"))
      .isEqualTo(cold.getProperty("resourceKey"))

    resource.writeText("resource two")
    project.build(task)
    val changedResource = manifest(project)
    assertThat(changedResource.getProperty("mirrorId")).isEqualTo(cold.getProperty("mirrorId"))
    assertThat(changedResource.getProperty("resourceKey"))
      .isNotEqualTo(cold.getProperty("resourceKey"))
    assertThat(target.resolve("example.txt").readText()).isEqualTo("resource two")
    check(resource.delete())
    project.build(task)
    assertThat(target.resolve("example.txt").exists()).isFalse()
    assertThat(manifest(project).getProperty("resourceKey"))
      .isNotEqualTo(changedResource.getProperty("resourceKey"))

    check(target.resolve("Example.class").delete())
    project.build(task)
    val recovered = manifest(project)
    assertThat(target.resolve("Example.class").readText()).isEqualTo("edited class")
    assertThat(recovered.getProperty("mirrorId")).isNotEqualTo(cold.getProperty("mirrorId"))
    assertThat(project.build(task).task(task)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)

    check(project.file("$state/classpath.properties").delete())
    project.build(task)
    assertThat(manifest(project).getProperty("mirrorId"))
      .isNotEqualTo(recovered.getProperty("mirrorId"))
    assertThat(target.resolve("Example.class").readText()).isEqualTo("edited class")

    classes.resolve("metadata").mkdirs()
    classes.resolve("metadata/module-info.class").writeText("module one")
    project.build(task)
    val module = manifest(project)
    classes.resolve("metadata/module-info.class").writeText("module two")
    project.build(task)
    assertThat(manifest(project).getProperty("resourceKey"))
      .isNotEqualTo(module.getProperty("resourceKey"))

    for (file in classes.walkBottomUp()) check(file.delete())
    project.build(task)
    assertThat(target.walkTopDown().filter(File::isFile).toList()).isEmpty()
  }

  @Test
  fun `jar sync removes stale files and classpath reordering rebuilds stable URLs`() {
    val project = fixture()
    val jar = project.file("lib/classes.jar")
    writeJar(jar, "Old.class" to "old", "example.txt" to "resource")
    project.build(task)
    val cold = manifest(project)
    val target = project.file("$state/live/1-classes.jar")
    assertThat(target.resolve("Old.class").readText()).isEqualTo("old")

    writeJar(jar, "New.class" to "new", "example.txt" to "resource")
    project.build(task)
    assertThat(target.resolve("Old.class").exists()).isFalse()
    assertThat(target.resolve("New.class").readText()).isEqualTo("new")
    assertThat(manifest(project).getProperty("mirrorId")).isEqualTo(cold.getProperty("mirrorId"))
    assertThat(manifest(project).getProperty("resourceKey"))
      .isEqualTo(cold.getProperty("resourceKey"))

    writeJar(jar, "New.class" to "edited", "META-INF/version.class" to "metadata")
    project.build(task)
    assertThat(target.resolve("New.class").readText()).isEqualTo("edited")
    assertThat(target.resolve("example.txt").exists()).isFalse()
    assertThat(manifest(project).getProperty("resourceKey"))
      .isNotEqualTo(cold.getProperty("resourceKey"))

    val reordered = project.build(task, "-PmirrorOrder=classes.jar,first")
    assertThat(reordered.task(task)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val result = manifest(project)
    assertThat(result.getProperty("mirrorId")).isNotEqualTo(cold.getProperty("mirrorId"))
    assertThat(result.getProperty("classpath").split(File.pathSeparator))
      .containsExactly(
        project.file("$state/live/0-classes.jar").absolutePath,
        project.file("$state/live/1-first").absolutePath,
      )
      .inOrder()
    assertThat(target.exists()).isFalse()
    assertThat(project.file("$state/live/0-classes.jar/New.class").readText()).isEqualTo("edited")
  }

  @Test
  fun `full rebuild waits for the worker lock and failed sync recovers from scratch`() {
    val project = fixture()
    val jar = project.file("lib/classes.jar")
    writeJar(jar, "Example.class" to "original")
    project.build(task)
    val started = project.file("$state/started")
    check(started.delete())
    val target = project.file("$state/live/1-classes.jar/Example.class")
    FileChannel.open(
        project.file("$state/client.lock").toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
      )
      .use { channel ->
        val lock = channel.lock()
        val build = CompletableFuture.supplyAsync {
          project.build(task, "-PmirrorOrder=classes.jar,first")
        }
        try {
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
          while (!started.exists() && System.nanoTime() < deadline && !build.isDone) {
            Thread.sleep(10)
          }
          assertThat(started.exists()).isTrue()
          assertThat(build.isDone).isFalse()
          assertThat(target.readText()).isEqualTo("original")
        } finally {
          lock.release()
          build.get(1, TimeUnit.MINUTES)
        }
      }
    assertThat(target.exists()).isFalse()
    assertThat(project.file("$state/live/0-classes.jar/Example.class").readText())
      .isEqualTo("original")

    val beforeFailure = manifest(project)
    jar.writeText("not a jar")
    val failed = project.buildAndFail(task, "-PmirrorOrder=classes.jar,first")
    assertThat(failed.task(task)?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(project.file("$state/classpath.properties").exists()).isFalse()
    writeJar(jar, "Recovered.class" to "recovered")
    project.build(task, "-PmirrorOrder=classes.jar,first")
    assertThat(project.file("$state/live/0-classes.jar/Recovered.class").readText())
      .isEqualTo("recovered")
    assertThat(manifest(project).getProperty("mirrorId"))
      .isNotEqualTo(beforeFailure.getProperty("mirrorId"))
    assertThat(project.build(task, "-PmirrorOrder=classes.jar,first").task(task)?.outcome)
      .isEqualTo(TaskOutcome.UP_TO_DATE)
  }

  private fun fixture(): GradleProject {
    val project = DaemonProject().build()
    check(project.file("lib/first").mkdirs())
    writeJar(project.file("lib/classes.jar"))
    project
      .file("lib/build.gradle")
      .appendText(
        """

        tasks.register('mirrorClasspath', com.fueledbycaffeine.robolectricdaemon.SyncRobolectricClasspath) {
          classpath.from(providers.gradleProperty('mirrorOrder').orElse('first,classes.jar').map { it.split(',') })
          buildRoot.set(rootDir.absolutePath)
          gradleUserHome.set(gradle.gradleUserHomeDir.absolutePath)
          mirror.set(layout.buildDirectory.dir('mirror-test/live'))
          result.set(layout.buildDirectory.file('mirror-test/classpath.properties'))
          daemonEnabled.set(true)
          def started = layout.buildDirectory.file('mirror-test/started').get().asFile
          doFirst {
            started.parentFile.mkdirs()
            started.text = 'started'
          }
        }
        """
          .trimIndent()
      )
    return project
  }

  private fun manifest(project: GradleProject): Properties =
    Properties().apply {
      project.file("$state/classpath.properties").inputStream().use { load(it) }
    }

  private fun writeJar(jar: File, vararg entries: Pair<String, String>) {
    JarOutputStream(jar.outputStream()).use { output ->
      for ((name, contents) in entries) {
        output.putNextEntry(JarEntry(name).apply { time = 0 })
        output.write(contents.toByteArray())
        output.closeEntry()
      }
    }
  }
}
