package com.fueledbycaffeine.robolectricdaemon.functionaltest

import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.PRODUCTION
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject.Companion.production
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.build
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.file
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.workerPids
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.write
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

class SandboxFunctionalTest {
  private val test = ":lib:testDebugUnitTest"

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
}
