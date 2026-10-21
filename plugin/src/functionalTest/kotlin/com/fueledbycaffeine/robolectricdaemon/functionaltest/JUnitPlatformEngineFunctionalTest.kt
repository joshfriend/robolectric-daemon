package com.fueledbycaffeine.robolectricdaemon.functionaltest

import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.STATE
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.build
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.buildAndFail
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.file
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.workerPids
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

class JUnitPlatformEngineFunctionalTest {
  private val test = ":lib:testDebugUnitTest"

  @Test
  fun `no matching tests do not start workers`() {
    val project = DaemonProject().build()
    try {
      val missing = project.buildAndFail(test, "--tests", "com.example.lib.ExampleTest.missing")
      assertThat(missing.output).contains("No tests found for given includes")
      assertThat(missing.workerPids).isEmpty()
      assertThat(project.file("$STATE/jars").exists()).isFalse()
      assertThat(project.file("$STATE/worker-1").exists()).isFalse()
    } finally {
      project.build(":lib:stopRobolectricDaemon")
    }
  }

  @Test
  fun `build script and command line filters select remote tests`() {
    val project = DaemonProject().build()
    project
      .file("lib/src/test/kotlin/com/example/lib/SelectedTest.kt")
      .writeText(
        """
        package com.example.lib

        import org.junit.Test
        import org.junit.Assert.fail

        class SelectedTest {
          @Test fun included() {}
          @Test fun alsoIncluded() {}
          @Test fun excluded() { fail("excluded test ran") }
        }
        """
          .trimIndent()
      )
    project
      .file("lib/build.gradle")
      .appendText(
        """

        tasks.withType(Test).configureEach {
          filter {
            includeTestsMatching '*SelectedTest'
            excludeTestsMatching '*SelectedTest.excluded'
          }
        }
        """
          .trimIndent()
      )
    try {
      val filtered = project.build(test)
      assertWithMessage(filtered.output).that(filtered.output).contains("2 tests, 0 failed")
      val selected = project.build(test, "--tests", "*SelectedTest.included")
      assertWithMessage(selected.output).that(selected.output).contains("1 tests, 0 failed")
    } finally {
      project.build(":lib:stopRobolectricDaemon")
    }
  }

  @Test
  fun `engine preserves parameterized tests skips lifecycle failures and local fallback`() {
    val project = DaemonProject().build()
    project
      .file("lib/src/test/kotlin/com/example/lib/EngineTest.kt")
      .writeText(
        """
        package com.example.lib

        import org.junit.AfterClass
        import org.junit.BeforeClass
        import org.junit.Assert.assertTrue
        import org.junit.Assert.fail
        import org.junit.Assume.assumeTrue
        import org.junit.Ignore
        import org.junit.Test
        import org.junit.runner.RunWith
        import org.junit.runner.Description
        import org.junit.runner.Runner
        import org.junit.runner.notification.RunNotifier
        import org.junit.runners.Parameterized
        import org.junit.runners.Suite

        class LegacyEngineTest : junit.framework.TestCase() {
          fun testLegacy() { assertTrue(true) }
        }

        @RunWith(Parameterized::class)
        class ParameterizedBridgeTest(private val value: Int) {
          @Test fun value() {
            println("remote-value-" + value)
            assertTrue(value > 0)
          }
          @Test fun assumption() { assumeTrue(false) }
          @Ignore @Test fun ignored() { fail("must not run") }
          companion object {
            @JvmStatic @Parameterized.Parameters(name = "{index}: value*{0}")
            fun parameters() = listOf(arrayOf(1), arrayOf(2))
          }
        }

        @Ignore
        class IgnoredBridgeTest {
          @Test fun ignoredClass() { fail("must not run") }
        }

        class BeforeClassBridgeTest {
          @Test fun body() { fail("must not run") }
          companion object {
            @JvmStatic @BeforeClass fun before() { fail("remote-before-class") }
          }
        }

        @RunWith(DynamicBridgeRunner::class)
        class DynamicBridgeTest

        class DynamicBridgeRunner(private val type: Class<*>) : Runner() {
          private val declared = Description.createTestDescription(type, "declared")
          private val description = Description.createSuiteDescription(type).apply { addChild(declared) }
          override fun getDescription() = description
          override fun run(notifier: RunNotifier) {
            for (test in listOf(declared, Description.createTestDescription(type, "runtime"))) {
              notifier.fireTestStarted(test)
              notifier.fireTestFinished(test)
            }
          }
        }

        @RunWith(DuplicateBridgeRunner::class)
        class DuplicateBridgeTest

        class DuplicateBridgeRunner(type: Class<*>) : Runner() {
          private val tests = listOf(
            Description.createTestDescription(type.name, "same", "first"),
            Description.createTestDescription(type.name, "same", "second")
          )
          private val description = Description.createSuiteDescription(type).apply { tests.forEach(::addChild) }
          override fun getDescription() = description
          override fun run(notifier: RunNotifier) {
            tests.forEach {
              notifier.fireTestStarted(it)
              notifier.fireTestFinished(it)
            }
          }
        }

        class LifecycleBridgeTest {
          @Test fun body() {}
          companion object {
            @JvmStatic @AfterClass fun after() { fail("remote-after-class") }
          }

          object SuiteState { var before = false }

          @RunWith(Suite::class)
          @Suite.SuiteClasses(SuiteChildBridgeTest::class)
          class SuiteBridgeTest {
            companion object {
              @JvmStatic @BeforeClass fun before() { SuiteState.before = true }
              @JvmStatic @AfterClass fun after() { SuiteState.before = false }
            }
          }

          class SuiteChildBridgeTest {
            @Test fun runsInsideSuite() { assertTrue(SuiteState.before) }
          }

          @RunWith(UnfilterableBridgeRunner::class)
          class CustomRunnerBridgeTest

          class UnfilterableBridgeRunner(type: Class<*>) : Runner() {
            private val description = Description.createTestDescription(type, "custom")
            override fun getDescription() = description
            override fun run(notifier: RunNotifier) {
              notifier.fireTestStarted(description)
              println("custom-runner")
              notifier.fireTestFinished(description)
            }
          }
        }
        """
          .trimIndent()
      )
    try {
      val legacy = project.build(test, "--tests", "*LegacyEngineTest")
      assertWithMessage(legacy.output).that(legacy.output).contains("1 tests, 0 failed")
      val parameterized = project.build(test, "--tests", "*ParameterizedBridgeTest")
      assertWithMessage(parameterized.output).that(parameterized.output).contains("remote-value-1")
      val report =
        project.file(
          "lib/build/test-results/testDebugUnitTest/TEST-com.example.lib.ParameterizedBridgeTest.xml"
        )
      assertThat(report.readText()).contains("tests=\"6\"")
      assertThat(report.readText()).contains("skipped=\"4\"")
      assertThat(report.readText()).contains("value[0: value*1]")
      val filtered = project.build(test, "--tests", "*ParameterizedBridgeTest.value")
      assertWithMessage(filtered.output).that(filtered.output).contains("2 tests, 0 failed")
      assertThat(report.readText()).contains("tests=\"2\"")
      val suite = project.build(test, "--tests", "*SuiteBridgeTest")
      assertWithMessage(suite.output).that(suite.output).contains("1 tests, 0 failed")
      val custom = project.build(test, "--tests", "*CustomRunnerBridgeTest")
      assertWithMessage(custom.output).that(custom.output).contains("1 tests, 0 failed")
      assertWithMessage(custom.output).that(custom.output).contains("custom-runner")

      val dynamic = project.build(test, "--tests", "*DynamicBridgeTest")
      assertWithMessage(dynamic.output).that(dynamic.output).contains("2 tests, 0 failed")
      val dynamicReport =
        project
          .file(
            "lib/build/test-results/testDebugUnitTest/TEST-com.example.lib.DynamicBridgeTest.xml"
          )
          .readText()
      assertThat(dynamicReport).contains("tests=\"2\"")
      assertThat(dynamicReport).contains("runtime")
      val duplicates = project.build(test, "--tests", "*DuplicateBridgeTest")
      assertWithMessage(duplicates.output).that(duplicates.output).contains("2 tests, 0 failed")
      assertThat(
          project
            .file(
              "lib/build/test-results/testDebugUnitTest/TEST-com.example.lib.DuplicateBridgeTest.xml"
            )
            .readText()
        )
        .contains("tests=\"2\"")

      val ignored = project.build(test, "--tests", "*IgnoredBridgeTest")
      assertWithMessage(ignored.output).that(ignored.output).doesNotContain("must not run")
      assertThat(
          project
            .file(
              "lib/build/test-results/testDebugUnitTest/TEST-com.example.lib.IgnoredBridgeTest.xml"
            )
            .readText()
        )
        .contains("skipped=\"1\"")

      val lifecycle = project.buildAndFail(test, "--tests", "*LifecycleBridgeTest")
      assertWithMessage(lifecycle.output).that(lifecycle.output).contains("FAILED")
      assertThat(
          project
            .file(
              "lib/build/test-results/testDebugUnitTest/TEST-com.example.lib.LifecycleBridgeTest.xml"
            )
            .readText()
        )
        .contains("remote-after-class")

      val before = project.buildAndFail(test, "--tests", "*BeforeClassBridgeTest")
      assertThat(before.output).doesNotContain("must not run")
      assertThat(
          project
            .file(
              "lib/build/test-results/testDebugUnitTest/TEST-com.example.lib.BeforeClassBridgeTest.xml"
            )
            .readText()
        )
        .contains("remote-before-class")

      project
        .file("lib/build.gradle")
        .appendText("\ntasks.withType(Test).configureEach { ignoreFailures = true }\n")
      project.build(test, "--tests", "*LifecycleBridgeTest")
      project
        .file("lib/build.gradle")
        .appendText("\ntasks.withType(Test).configureEach { forkEvery = 1 }\n")
      val local = project.build(test, "--tests", "*ExampleTest")
      assertWithMessage(local.output).that(local.output).doesNotContain("Robolectric worker:")
    } finally {
      project.build(":lib:stopRobolectricDaemon")
    }
  }
}
