package com.fueledbycaffeine.robolectricdaemon.functionaltest

import com.fueledbycaffeine.robolectricdaemon.DaemonContract
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
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

class RobolectricDaemonFunctionalTest {
  private val test = ":lib:testDebugUnitTest"
  private val sync = ":lib:syncDebugUnitTestRobolectricClasspath"

  @Test
  fun `runtime dependency changes rerun tests and restart workers without overwriting staged jars`() {
    val project = DaemonProject().build()
    project.file("gradle.properties").appendText("\norg.gradle.caching=false\n")
    val runtime = project.file("lib/runtime")
    check(runtime.mkdirs())
    val root = File(System.getProperty("runtimeBuildRoot"))
    for (module in listOf("worker", "protocol")) {
      val jar =
        checkNotNull(root.resolve("$module/build/libs").listFiles()).single {
          it.name.startsWith("$module-") && it.extension == "jar"
        }
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

  @Test
  fun `available workers drain the queue within one project epoch`() {
    val project = DaemonProject().build()
    project
      .file("lib/src/test/kotlin/com/example/lib/QueueTest.kt")
      .writeText(
        """
        package com.example.lib

        import java.io.File
        import org.junit.Assert.assertEquals
        import org.junit.Assert.assertNull
        import org.junit.Assert.assertTrue
        import org.junit.Test

        object QueueState {
          var pid: Long? = null

          fun await(name: String) {
            val file = File("build/" + name)
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!file.exists() && System.nanoTime() < deadline) {
              Thread.sleep(20)
            }
            assertTrue("Timed out waiting for " + name, file.exists())
          }
        }

        class AQueueTest {
          @Test(timeout = 30_000)
          fun waitsForQueuedClass() {
            assertNull(QueueState.pid)
            File("build/queue-started").writeText("started")
            QueueState.await("queue-completed")
          }
        }

        class BQueueTest {
          @Test
          fun setsProjectState() {
            assertNull(QueueState.pid)
            QueueState.pid = ProcessHandle.current().pid()
          }
        }

        class CQueueTest {
          @Test(timeout = 30_000)
          fun runsOnAvailableWorker() {
            QueueState.await("queue-started")
            assertEquals(ProcessHandle.current().pid(), QueueState.pid)
            File("build/queue-completed").writeText("completed")
          }

          class Nested {
            @Test
            fun staysWithTopLevelClass() {
              assertEquals(ProcessHandle.current().pid(), QueueState.pid)
            }
          }
        }
        """
          .trimIndent()
      )
    try {
      // Equal unknown durations would pre-partition A+C together, stranding C behind A.
      val cold = project.build(test, "-Pforks=2", "--tests", "*QueueTest*")
      assertWithMessage(cold.output).that(cold.output).contains("4 tests, 0 failed")
      assertThat(cold.workerPids).hasSize(2)
      assertThat(cold.output).contains("request=1 base=1 epoch=1 reload=full(new-worker)")
      assertThat(Regex("request=1 base=1 epoch=1").findAll(cold.output).count()).isEqualTo(2)

      project.file("lib/build/queue-started").delete()
      project.file("lib/build/queue-completed").delete()
      // Stale, equal timings must not strand C on the blocked worker on a warm run either.
      project
        .file("$STATE/${DaemonContract.DURATIONS_FILE}")
        .writeText(
          """
          com.example.lib.AQueueTest=100
          com.example.lib.BQueueTest=100
          com.example.lib.CQueueTest=100
          """
            .trimIndent()
        )
      project.write(PRODUCTION, production(1, member = "val queueRevision = 2"))
      val warm = project.build(test, "-Pforks=2", "--tests", "*QueueTest*")
      assertWithMessage(warm.output).that(warm.output).contains("4 tests, 0 failed")
      assertThat(warm.workerPids).isEqualTo(cold.workerPids)
      assertThat(warm.output).contains("request=2 base=1 epoch=2 reload=project")
      assertThat(Regex("request=2 base=1 epoch=2").findAll(warm.output).count()).isEqualTo(2)
      for (name in listOf("AQueueTest", "BQueueTest", "CQueueTest", "CQueueTest${'$'}Nested")) {
        val report =
          project.file("lib/build/test-results/testDebugUnitTest/TEST-com.example.lib.$name.xml")
        assertThat(report.readText()).contains("tests=\"1\"")
      }
    } finally {
      project.build(":lib:stopRobolectricDaemon")
    }
  }

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
  fun `every Robolectric test in a class gets the project context loader`() {
    val project = DaemonProject().build()
    project
      .file("lib/src/test/kotlin/com/example/lib/ContextTest.kt")
      .writeText(
        """
        package com.example.lib

        import org.junit.Assert.assertSame
        import org.junit.Test
        import org.junit.runner.RunWith
        import org.robolectric.RobolectricTestRunner
        import org.robolectric.annotation.Config

        @RunWith(RobolectricTestRunner::class)
        @Config(sdk = [34])
        class ContextTest {
          @Test fun first() = check()
          @Test fun second() = check()
          @Test fun third() = check()

          private fun check() {
            assertSame(Revision::class.java.classLoader, Thread.currentThread().contextClassLoader)
          }
        }
        """
          .trimIndent()
      )
    try {
      val result = project.build(test, "--tests", "com.example.lib.ContextTest")
      assertWithMessage(result.output).that(result.output).contains("3 tests, 0 failed")
    } finally {
      project.build(":lib:stopRobolectricDaemon")
    }
  }

  @Test
  fun `classes bound by name reload the sandbox only when they change or hold state`() {
    val project = DaemonProject().build()
    val binder = DaemonProject.binderJar()
    project
      .file("lib/build.gradle")
      .appendText("\ndependencies { testImplementation(files('${binder.absolutePath}')) }\n")
    val strings = project.file("lib/src/main/res/values/strings.xml")
    strings.parentFile.mkdirs()
    strings.writeText("<resources><string name=\"greeting\">hi</string></resources>")
    val constants = project.file("lib/src/main/kotlin/com/example/lib/Constants.kt")
    constants.writeText("package com.example.lib\n\nconst val GREETING = \"hello\"\n")
    val boundTest =
      """
      package com.example.lib

      import binder.Binder
      import org.junit.Assert.assertEquals
      import org.junit.Test
      import org.junit.runner.RunWith
      import org.robolectric.RobolectricTestRunner
      import org.robolectric.annotation.Config

      // Library code loads R and constants by name, binding them to persistent loaders.
      class BoundTest {
        @Test
        fun bound() {
          assertEquals(R.string.greeting, Binder.field("com.example.lib.R\${'$'}string", "greeting"))
          assertEquals(GREETING, Binder.field("com.example.lib.ConstantsKt", "GREETING"))
        }
      }

      @RunWith(RobolectricTestRunner::class)
      @Config(sdk = [34])
      class SandboxBoundTest {
        @Test
        fun bound() {
          assertEquals(R.string.greeting, Binder.field("com.example.lib.R\${'$'}string", "greeting"))
          assertEquals(GREETING, Binder.field("com.example.lib.ConstantsKt", "GREETING"))
        }
      }
      """
        .trimIndent()
    project.file("lib/src/test/kotlin/com/example/lib/BoundTest.kt").writeText(boundTest)
    try {
      val cold = project.build(test)
      assertWithMessage(cold.output).that(cold.output).contains("4 tests, 0 failed")
      assertWithMessage(cold.output).that(cold.output).contains("reload=full(new-worker)")

      // Constant-only classes bound by name stay valid while their class files are unchanged.
      project.write(PRODUCTION, production(1, member = "val unused = 1"))
      val edited = project.build(test)
      assertWithMessage(edited.output).that(edited.output).contains("4 tests, 0 failed")
      assertWithMessage(edited.output).that(edited.output).contains("reload=project")
      assertWithMessage(edited.output).that(edited.output).doesNotContain("reload=full")
      assertThat(edited.workerPids).isEqualTo(cold.workerPids)

      // A changed one must not be served stale (the tests compare against the inlined constant).
      constants.writeText("package com.example.lib\n\nconst val GREETING = \"hello again\"\n")
      val changed = project.build(test)
      assertWithMessage(changed.output).that(changed.output).contains("4 tests, 0 failed")
      assertWithMessage(changed.output)
        .that(changed.output)
        .contains("reload=full(bound-project-class-changed:com.example.lib.ConstantsKt)")

      // A class with state bound by name needs a fresh sandbox, as a fresh JVM would re-create it.
      project
        .file("lib/src/main/kotlin/com/example/lib/Counter.kt")
        .writeText("package com.example.lib\n\nobject Counter {\n  var count = 0\n}\n")
      project
        .file("lib/src/test/kotlin/com/example/lib/CounterTest.kt")
        .writeText(
          """
          package com.example.lib

          import binder.Binder
          import org.junit.Test

          class CounterTest {
            @Test
            fun bind() {
              Binder.load("com.example.lib.Counter")
            }
          }
          """
            .trimIndent()
        )
      val stateful = project.build(test)
      assertWithMessage(stateful.output).that(stateful.output).contains("5 tests, 0 failed")
      project.write(PRODUCTION, production(1, member = "val unused = 2"))
      val reloaded = project.build(test)
      assertWithMessage(reloaded.output).that(reloaded.output).contains("5 tests, 0 failed")
      assertWithMessage(reloaded.output)
        .that(reloaded.output)
        .contains("reload=full(project-class-bound-by-name:com.example.lib.Counter")
    } finally {
      project.build(":lib:stopRobolectricDaemon")
      binder.parentFile.deleteRecursively()
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
