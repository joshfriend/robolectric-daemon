package com.fueledbycaffeine.robolectricdaemon.functionaltest

import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.EXAMPLE_TEST
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.PRODUCTION
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.build
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.buildAndFail
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.file
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.workerPids
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

class WorkerRuntimeIsolationFunctionalTest {
  private val test = ":lib:testDebugUnitTest"

  @Test
  fun `worker Kotlin runtime stays isolated from Kotlin and Java-only projects`() {
    val project = DaemonProject().build()
    val isolation = project.file("lib/src/test/kotlin/com/example/lib/KotlinIsolationTest.kt")
    isolation.writeText(
      """
      package com.example.lib

      import org.junit.Assert.assertNotSame
      import org.junit.Test

      class KotlinIsolationTest {
        @Test
        fun usesProjectRuntime() {
          val workerKotlin = ClassLoader.getSystemClassLoader().loadClass("kotlin.Unit")
          assertNotSame(workerKotlin, Unit::class.java)
          assertNotSame(workerKotlin.classLoader, Unit::class.java.classLoader)
        }
      }
      """
        .trimIndent()
    )
    try {
      val kotlin = project.build(test, "--tests", "*KotlinIsolationTest")
      assertWithMessage(kotlin.output).that(kotlin.output).contains("1 tests, 0 failed")
      assertThat(kotlin.workerPids).isNotEmpty()

      for (path in
        listOf(
          PRODUCTION,
          EXAMPLE_TEST,
          "lib/src/test/kotlin/com/example/lib/SandboxTest.kt",
        )) {
        check(project.file(path).delete())
      }
      check(isolation.delete())
      project
        .file("lib/build.gradle")
        .appendText(
          """

          configurations.matching { it.name == 'debugUnitTestRuntimeClasspath' }.configureEach {
            exclude group: 'org.jetbrains.kotlin', module: 'kotlin-stdlib'
          }
          """
            .trimIndent()
        )
      val java = project.file("lib/src/test/java/com/example/lib/JavaIsolationTest.java")
      check(java.parentFile.mkdirs())
      java.writeText(
        """
        package com.example.lib;

        import static org.junit.Assert.assertNotNull;
        import static org.junit.Assert.assertThrows;
        import org.junit.Test;

        public class JavaIsolationTest {
          @Test
          public void doesNotInheritWorkerRuntime() throws Exception {
            assertNotNull(ClassLoader.getSystemClassLoader().loadClass("kotlin.Unit"));
            assertThrows(ClassNotFoundException.class,
                () -> getClass().getClassLoader().loadClass("kotlin.Unit"));
          }
        }
        """
          .trimIndent()
      )
      val javaOnly = project.build(test, "--tests", "*JavaIsolationTest")
      assertWithMessage(javaOnly.output).that(javaOnly.output).contains("1 tests, 0 failed")
      assertThat(javaOnly.workerPids).isEqualTo(kotlin.workerPids)
    } finally {
      project.build(":lib:stopRobolectricDaemon")
    }
  }

  @Test
  fun `typed runtime runs plain JUnit without Robolectric or ASM and reports comparisons`() {
    val project = DaemonProject().build()
    project.file("lib/src/test/kotlin/com/example/lib/SandboxTest.kt").delete()
    val script = project.file("lib/build.gradle")
    script.writeText(
      script
        .readText()
        .lineSequence()
        .filterNot { "org.robolectric:robolectric:" in it }
        .joinToString("\n")
    )
    script.appendText(
      "\ndependencies { testImplementation('com.google.protobuf:protobuf-java:3.25.5') }\n"
    )
    project
      .file("lib/src/test/kotlin/com/example/lib/ComparisonTest.kt")
      .writeText(
        """
        package com.example.lib

        import org.junit.Test

        class ComparisonTest {
          @Test fun junit4() {
            println("protobuf-worker-stdout")
            System.err.println("protobuf-worker-stderr")
            throw org.junit.ComparisonFailure("junit4-comparison", "expected-four", "actual-four")
          }
          @Test fun junit3() { throw junit.framework.ComparisonFailure("junit3-comparison", "expected-three", "actual-three") }
          @Test fun custom() {
            val value = com.google.protobuf.StringValue.of("project-protobuf")
            org.junit.Assert.assertEquals(value, com.google.protobuf.StringValue.parseFrom(value.toByteArray()))
            org.junit.Assert.assertTrue(
              com.google.protobuf.MessageLite::class.java.protectionDomain.codeSource.location.toString()
                .contains("protobuf-java-3.25.5")
            )
            throw CustomFailure()
          }
          @Test fun nested() {
            val cause = IllegalArgumentException("remote-root-cause")
            cause.stackTrace = arrayOf(StackTraceElement("example.Helper", "call", "Helper.java", 42))
            val failure = IllegalStateException("remote-outer-cause", cause)
            failure.addSuppressed(UnsupportedOperationException("remote-suppressed"))
            throw failure
          }
        }

        class CustomFailure : AssertionError("custom-failure") {
          fun getExpected(): String = error("unexpected-getter-call")
          fun getActual(): String = error("unexpected-getter-call")
        }
        """
          .trimIndent()
      )
    try {
      val failed = project.buildAndFail(test, "--tests", "*ComparisonTest")
      assertWithMessage(failed.output).that(failed.output).contains("4 tests, 4 failed")
      val report =
        project
          .file("lib/build/test-results/testDebugUnitTest/TEST-com.example.lib.ComparisonTest.xml")
          .readText()
      for (message in
        listOf(
          "junit4-comparison",
          "junit3-comparison",
          "custom-failure",
          "[expected]-four",
          "[actual]-four",
          "[expected]-three",
          "[actual]-three",
          "remote-root-cause",
          "remote-outer-cause",
          "example.Helper.call(Helper.java:42)",
          "protobuf-worker-stdout",
          "protobuf-worker-stderr",
          // Real exception types, as in a forked test JVM.
          "type=\"org.junit.ComparisonFailure\"",
          "type=\"junit.framework.ComparisonFailure\"",
          "type=\"com.example.lib.CustomFailure\"",
          "type=\"java.lang.IllegalStateException\"",
          "Caused by: java.lang.IllegalArgumentException: remote-root-cause",
          "Suppressed: java.lang.UnsupportedOperationException: remote-suppressed",
        )) {
        assertThat(report).contains(message)
      }
      assertThat(report).doesNotContain("unexpected-getter-call")
      val plain = project.build(test, "--tests", "*ExampleTest")
      assertWithMessage(plain.output).that(plain.output).contains("1 tests, 0 failed")
      assertThat(plain.workerPids).isEqualTo(failed.workerPids)
    } finally {
      project.build(":lib:stopRobolectricDaemon")
    }
  }
}
