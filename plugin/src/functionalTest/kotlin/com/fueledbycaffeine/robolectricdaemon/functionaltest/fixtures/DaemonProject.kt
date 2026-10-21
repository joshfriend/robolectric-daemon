package com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures

import com.autonomousapps.kit.AbstractGradleProject
import com.autonomousapps.kit.GradleBuilder
import com.autonomousapps.kit.GradleProject
import com.autonomousapps.kit.Source
import com.autonomousapps.kit.gradle.Dependency.Companion.testImplementation
import com.autonomousapps.kit.gradle.GradleProperties
import com.autonomousapps.kit.gradle.Plugin
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText
import org.gradle.testkit.runner.BuildResult
import org.gradle.util.GradleVersion

internal val gradleVersion: GradleVersion
  get() =
    GradleVersion.version(
      System.getProperty("gradleVersion").orEmpty().ifBlank { GradleVersion.current().version }
    )

internal val agpVersion: String
  get() = System.getProperty("agpVersion")

internal val robolectricVersion: String
  get() = System.getProperty("robolectricVersion")

/** An Android library with a plain JUnit test and a Robolectric test. */
class DaemonProject : AbstractGradleProject() {
  fun build(): GradleProject =
    newGradleProjectBuilder(GradleProject.DslKind.GROOVY)
      .withRootProject {
        gradleProperties =
          GradleProperties.of(
            "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8",
            "org.gradle.caching=true",
            "org.gradle.parallel=true",
            "org.gradle.configuration-cache=true",
            "android.useAndroidX=true",
            "org.gradle.java.home=${System.getProperty("fixtureJavaHome")}",
          )
        withSettingsScript { plugins(Plugin("com.android.library", agpVersion, apply = false)) }
        withFile("local.properties", "sdk.dir=${androidHome()}")
      }
      .withSubproject("lib") {
        withBuildScript {
          plugins(Plugin("com.android.library"), Plugin(PLUGIN_ID, PLUGIN_UNDER_TEST_VERSION))
          dependencies(
            testImplementation("junit:junit:4.13.2"),
            testImplementation("org.robolectric:robolectric:$robolectricVersion"),
          )
          additions =
            """
            android {
              namespace = "com.example.lib"
              compileSdk = 36
              defaultConfig { minSdk = 28 }
            }

            tasks.withType(Test).configureEach {
              maxParallelForks = providers.gradleProperty("forks").getOrElse("1").toInteger()
            }
            """
              .trimIndent()
        }
        withFile("src/main/AndroidManifest.xml", "<manifest />")
        sources =
          mutableListOf(
            production(1),
            exampleTest(fail = false),
            Source.kotlin(
                """
                package com.example.lib

                import org.junit.Assert.assertNotNull
                import org.junit.Assert.assertSame
                import org.junit.Test
                import org.junit.runner.RunWith
                import org.robolectric.RobolectricTestRunner
                import org.robolectric.RuntimeEnvironment
                import org.robolectric.annotation.Config

                @RunWith(RobolectricTestRunner::class)
                @Config(sdk = [34])
                class SandboxTest {
                  @Test
                  fun application() {
                    assertNotNull(RuntimeEnvironment.getApplication().packageName)
                    val context = Thread.currentThread().contextClassLoader
                    assertSame(Revision::class.java, context.loadClass("com.example.lib.Revision"))
                    assertSame(Revision::class.java.classLoader, context)
                  }
                }
                """
                  .trimIndent()
              )
              .withPath("com/example/lib", "SandboxTest")
              .withSourceSet("test")
              .build(),
          )
      }
      .write()

  companion object {
    const val PLUGIN_ID = "com.fueledbycaffeine.robolectric-daemon"
    const val PRODUCTION = "lib/src/main/kotlin/com/example/lib/Revision.kt"
    const val EXAMPLE_TEST = "lib/src/test/kotlin/com/example/lib/ExampleTest.kt"
    const val STATE = "lib/build/robolectric-daemon/testDebugUnitTest"

    private fun androidHome(): String =
      System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        ?: "${System.getProperty("user.home")}/Library/Android/sdk"

    /**
     * A library JAR, outside the fixture so it isn't a project output, that loads classes by name
     * through its own loader, as AndroidX does with R classes. The JVM then records that persistent
     * loader as their initiating loader.
     */
    fun binderJar(): File {
      val directory = Files.createTempDirectory("binder")
      val source = directory.resolve("binder/Binder.java")
      Files.createDirectories(source.parent)
      source.writeText(
        """
        package binder;

        public final class Binder {
          public static Object field(String className, String field) throws Exception {
            return load(className).getField(field).get(null);
          }

          public static Class<?> load(String className) throws Exception {
            return Class.forName(className, true, Binder.class.getClassLoader());
          }
        }
        """
          .trimIndent()
      )
      val classes = directory.resolve("classes")
      val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
      check(compiler.run(null, null, null, "--release", "11", "-d", "$classes", "$source") == 0)
      val jar = directory.resolve("binder.jar").toFile()
      JarOutputStream(jar.outputStream()).use { output ->
        output.putNextEntry(JarEntry("binder/Binder.class"))
        output.write(Files.readAllBytes(classes.resolve("binder/Binder.class")))
        output.closeEntry()
      }
      return jar
    }

    fun production(revision: Int, member: String = ""): Source =
      Source.kotlin(
          """
          package com.example.lib

          object Revision {
            $member
            fun value(): Int = $revision
          }
          """
            .trimIndent()
        )
        .withPath("com/example/lib", "Revision")
        .build()

    /** Fails if statics leak between runs: each run must see a fresh `System` property. */
    fun exampleTest(fail: Boolean): Source =
      Source.kotlin(
          """
          package com.example.lib

          import org.junit.Assert.assertEquals
          import org.junit.Assert.assertNull
          import org.junit.Assert.fail
          import org.junit.Test

          class ExampleTest {
            @Test
            fun example() {
              assertNull(Leak.value)
              Leak.value = "set"
              assertEquals(1, Revision.value())
              ${if (fail) "fail(\"edited failure\")" else ""}
            }
          }

          object Leak {
            var value: String? = null
          }
          """
            .trimIndent()
        )
        .withPath("com/example/lib", "ExampleTest")
        .withSourceSet("test")
        .build()
  }
}

fun GradleProject.build(vararg args: String): BuildResult =
  GradleBuilder.build(gradleVersion, rootDir, *args)

fun GradleProject.buildAndFail(vararg args: String): BuildResult =
  GradleBuilder.buildAndFail(gradleVersion, rootDir, *args)

fun GradleProject.file(path: String): File = rootDir.resolve(path)

fun GradleProject.write(path: String, source: Source) {
  file(path).writeText(source.source)
}

val BuildResult.configurationCacheReused: Boolean
  get() = "Configuration cache entry reused" in output

/** The worker's PID from its run summary. */
val BuildResult.workerPids: Set<Long>
  get() = Regex("""pid=(\d+) """).findAll(output).map { it.groupValues[1].toLong() }.toSet()
