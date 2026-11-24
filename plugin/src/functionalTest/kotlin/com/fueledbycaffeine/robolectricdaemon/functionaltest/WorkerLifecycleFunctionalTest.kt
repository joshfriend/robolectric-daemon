package com.fueledbycaffeine.robolectricdaemon.functionaltest

import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.EXAMPLE_TEST
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.PRODUCTION
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.STATE
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.exampleTest
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.production
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.build
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.buildAndFail
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.configurationCacheReused
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.file
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.workerPids
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.write
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.jar.JarFile
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

class WorkerLifecycleFunctionalTest {
  private val test = ":lib:testDebugUnitTest"
  private val sync = ":lib:syncDebugUnitTestRobolectricClasspath"

  @Test
  fun `reuses warm workers across edits and reports like a forked test JVM`() {
    val project = DaemonProject().build()
    val pids = mutableSetOf<Long>()
    try {
      val cold = project.build(test)
      pids += cold.workerPids
      assertWithMessage(cold.output).that(cold.output).contains("2 tests, 0 failed")
      assertWithMessage(cold.output).that(cold.output).contains("reload=full(new-worker)")
      assertThat(cold.task(sync)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
      val jars = checkNotNull(project.file("$STATE/jars").listFiles()).toList()
      assertThat(jars.map { it.name.substringBefore('-') })
        .containsAtLeast("worker", "protocol", "sandbox", "junit", "opentest4j")
      for (jar in jars) {
        JarFile(jar).use {
          val protocol = it.getEntry("com/fueledbycaffeine/robolectricdaemon/Protocol.class")
          val worker = it.getEntry("com/fueledbycaffeine/robolectricdaemon/WorkerMain.class")
          if (jar.name.startsWith("protocol-")) {
            assertThat(protocol).isNotNull()
            assertThat(worker).isNull()
            assertThat(
                it.getEntry("com/fueledbycaffeine/robolectricdaemon/messages/WorkerMessages.class")
              )
              .isNotNull()
            assertThat(
                it.getEntry(
                  "com/fueledbycaffeine/robolectricdaemon/internal/protobuf/MessageLite.class"
                )
              )
              .isNotNull()
            assertThat(it.getEntry("com/google/protobuf/MessageLite.class")).isNull()
          } else if (jar.name.startsWith("worker-")) {
            assertThat(protocol).isNull()
            assertThat(worker).isNotNull()
          }
        }
      }
      assertThat(project.file("lib/build/test-results/testDebugUnitTest").list()?.toList())
        .containsAtLeast(
          "TEST-com.example.lib.ExampleTest.xml",
          "TEST-com.example.lib.SandboxTest.xml",
        )

      // Unchanged: Gradle skips the test, and with it the worker.
      val upToDate = project.build(test)
      assertThat(upToDate.task(sync)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
      assertThat(upToDate.task(test)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
      assertThat(upToDate.configurationCacheReused).isTrue()

      // A production edit (including a new static) reloads only project classes.
      project.write(PRODUCTION, production(1, member = "val offset = \"0\".toInt()"))
      val edited = project.build(test)
      assertWithMessage(edited.output).that(edited.workerPids).isEqualTo(pids)
      assertWithMessage(edited.output).that(edited.output).contains("reload=project")
      assertWithMessage(edited.output).that(edited.output).contains("2 tests, 0 failed")
      assertThat(edited.configurationCacheReused).isTrue()

      project.write(PRODUCTION, production(2))
      val failed = project.buildAndFail(test)
      assertWithMessage(failed.output).that(failed.workerPids).isEqualTo(pids)
      assertWithMessage(failed.output).that(failed.output).contains("2 tests, 1 failed")
      assertThat(
          project
            .file("lib/build/test-results/testDebugUnitTest/TEST-com.example.lib.ExampleTest.xml")
            .readText()
        )
        .contains("expected:&lt;1&gt; but was:&lt;2&gt;")
      assertThat(project.file("lib/build/reports/tests/testDebugUnitTest/index.html").isFile)
        .isTrue()

      project.write(PRODUCTION, production(1))
      val missing = project.buildAndFail(test, "--tests", "com.example.lib.ExampleTest.missing")
      assertWithMessage(missing.output)
        .that(missing.output)
        .contains("No tests found for given includes")
      assertThat(missing.workerPids).isEmpty()

      // Two forks: one worker per class, both warm on the next run.
      project.write(PRODUCTION, production(1, member = "val unused = 1"))
      val forked = project.build(test, "-Pforks=2")
      pids += forked.workerPids
      assertWithMessage(forked.output)
        .that(forked.output)
        .contains("Started Robolectric test worker 2")
      assertThat(forked.workerPids).hasSize(2)
      project.write(PRODUCTION, production(1, member = "val unused = 2"))
      val forkedAgain = project.build(test, "-Pforks=2")
      assertWithMessage(forkedAgain.output)
        .that(forkedAgain.workerPids)
        .isEqualTo(forked.workerPids)
      assertWithMessage(forkedAgain.output).that(forkedAgain.output).doesNotContain("Started")

      // Disabled: Gradle's own forked executer runs the tests.
      project.write(EXAMPLE_TEST, exampleTest(fail = true))
      val disabled = project.buildAndFail(test, "-Probolectric.daemon=false")
      assertWithMessage(disabled.output).that(disabled.output).doesNotContain("Robolectric worker:")
      assertWithMessage(disabled.output)
        .that(disabled.output)
        .contains("ExampleTest > example FAILED")
      assertThat(disabled.task(sync)?.outcome).isEqualTo(TaskOutcome.SKIPPED)
    } finally {
      val stopped = project.build(":lib:stopRobolectricDaemon")
      if (pids.isNotEmpty()) {
        assertWithMessage(stopped.output)
          .that(stopped.output)
          .contains("Stopped Robolectric worker PID")
      }
    }
    for (pid in pids) {
      val alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
      assertWithMessage("worker $pid still running").that(alive).isFalse()
    }
    assertThat(project.file("$STATE/worker-1/tmp").exists()).isFalse()
  }

  @Test
  fun `system properties set by later doFirst actions reach warm workers`() {
    val project = DaemonProject().build()
    // Registered before the daemon's own action, so it runs after it, as Roborazzi's does.
    project
      .file("lib/build.gradle")
      .appendText(
        """

        def lateMode = providers.gradleProperty('mode').orElse('record')
        tasks.withType(Test).configureEach {
          doFirst {
            it.systemProperty('late.prop', 'yes')
            it.systemProperty('roborazzi.test.record', lateMode.get() == 'record')
            it.systemProperty('roborazzi.test.verify', lateMode.get() == 'verify')
            it.systemProperty('roborazzi.output.dir', 'build/late-output')
          }
        }
        """
          .trimIndent()
      )
    project
      .file("lib/src/test/kotlin/com/example/lib/LatePropertyTest.kt")
      .writeText(
        """
        package com.example.lib

        import java.io.File
        import org.junit.Assert.assertEquals
        import org.junit.Assert.assertTrue
        import org.junit.Test
        import org.junit.runner.RunWith
        import org.robolectric.RobolectricTestRunner
        import org.robolectric.annotation.Config

        @RunWith(RobolectricTestRunner::class)
        @Config(sdk = [34])
        class LatePropertyTest {
          @Test
          fun late() {
            assertEquals("yes", System.getProperty("late.prop"))
            assertEquals("build/late-output", System.getProperty("roborazzi.output.dir"))
            val record = System.getProperty("roborazzi.test.record").toBoolean()
            val verify = System.getProperty("roborazzi.test.verify").toBoolean()
            assertTrue(record xor verify)
            val output = File(System.getProperty("roborazzi.output.dir"), "revision.txt")
            if (record) {
              output.parentFile.mkdirs()
              output.writeText(Revision.value().toString())
            }
            if (verify) {
              assertEquals("recorded revision", output.readText(), Revision.value().toString())
            }
          }
        }
        """
          .trimIndent()
      )
    val filter = "com.example.lib.LatePropertyTest"
    try {
      val record = project.build(test, "--tests", filter, "--rerun")
      assertWithMessage(record.output).that(record.output).contains("1 tests, 0 failed")
      val output = project.file("lib/build/late-output/revision.txt")
      assertThat(output.readText()).isEqualTo("1")
      val identity = project.file("$STATE/worker-1/identity.txt").readText()
      assertWithMessage(identity).that(identity).contains("property late.prop=yes")
      assertWithMessage(identity).that(identity).doesNotContain("roborazzi.")
      assertWithMessage(identity).that(identity).doesNotContain("org.gradle.")
      assertWithMessage(identity).that(identity).doesNotContain("robolectric.daemon.")

      // Neither a rerun nor a different roborazzi.* mode restarts the worker.
      val verify = project.build(test, "--tests", filter, "--rerun", "-Pmode=verify")
      assertWithMessage(verify.output).that(verify.output).contains("1 tests, 0 failed")
      assertWithMessage(verify.output).that(verify.output).doesNotContain("Restarting")
      assertThat(verify.workerPids).isEqualTo(record.workerPids)
      assertThat(output.readText()).isEqualTo("1")

      // A mismatch must fail verification, not silently pass or overwrite the recording.
      output.writeText("different")
      val failed = project.buildAndFail(test, "--tests", filter, "--rerun", "-Pmode=verify")
      assertWithMessage(failed.output).that(failed.output).contains("1 tests, 1 failed")
      assertWithMessage(failed.output).that(failed.output).doesNotContain("Restarting")
      assertThat(failed.workerPids).isEqualTo(record.workerPids)
      assertThat(output.readText()).isEqualTo("different")
      assertThat(
          project
            .file(
              "lib/build/test-results/testDebugUnitTest/TEST-com.example.lib.LatePropertyTest.xml"
            )
            .readText()
        )
        .contains("recorded revision")
    } finally {
      project.build(":lib:stopRobolectricDaemon")
    }
  }

  @Test
  fun `clean stops orphaned workers`() {
    val project = DaemonProject().build()
    val pids = project.build(test).workerPids
    assertThat(pids).isNotEmpty()
    project.build("clean")
    val deadline = System.nanoTime() + 10_000_000_000L
    while (
      pids.any { ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) } &&
        System.nanoTime() < deadline
    ) {
      Thread.sleep(100)
    }
    for (pid in pids) {
      assertWithMessage("worker $pid still running")
        .that(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
        .isFalse()
    }
  }
}
