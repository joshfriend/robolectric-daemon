package com.fueledbycaffeine.robolectricdaemon.functionaltest

import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.DaemonProject
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.configurationCacheReused
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.file
import com.fueledbycaffeine.robolectricdaemon.functionaltest.fixtures.gradleVersion
import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CiDetectionFunctionalTest {
  @ParameterizedTest
  @CsvSource(
    "NULL, NULL, NULL, true",
    "true, NULL, NULL, false",
    "false, NULL, NULL, false",
    "'', NULL, NULL, false",
    "NULL, true, NULL, false",
    "NULL, false, NULL, false",
    "NULL, '', NULL, false",
    "true, true, NULL, false",
    "true, true, true, true",
    "NULL, NULL, false, false",
    nullValues = ["NULL"],
  )
  fun `detects CI with explicit daemon overrides`(
    environment: String?,
    systemProperty: String?,
    override: String?,
    enabled: Boolean,
  ) {
    val project = DaemonProject().build()
    project
      .file("lib/build.gradle")
      .appendText(
        """

        tasks.register('ciStatus') {
          def test = tasks.named('testDebugUnitTest').get()
          inputs.property('daemonEnabled', test.options instanceof org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions)
          doLast {
            println "daemon enabled: " + inputs.properties.daemonEnabled
          }
        }
        """
          .trimIndent()
      )
    val variables = System.getenv().toMutableMap().apply { remove("CI") }
    if (environment != null) variables["CI"] = environment
    val arguments = mutableListOf(":lib:ciStatus", "--stacktrace")
    if (systemProperty != null) arguments.add("-Dci=$systemProperty")
    if (override != null) arguments.add("-Probolectric.daemon=$override")
    val runner =
      GradleRunner.create()
        .withProjectDir(project.rootDir)
        .withGradleVersion(gradleVersion.version)
        .withEnvironment(variables)
        .withArguments(arguments)
    assertThat(runner.build().output).contains("daemon enabled: $enabled")
    val cached = runner.build()
    assertThat(cached.output).contains("daemon enabled: $enabled")
    assertThat(cached.configurationCacheReused).isTrue()
    if (override == null) {
      variables.remove("CI")
      arguments.removeAll { it.startsWith("-Dci=") }
      if (enabled) arguments.add("-Dci=true")
      val changed = runner.withEnvironment(variables).withArguments(arguments).build()
      assertThat(changed.output).contains("daemon enabled: ${!enabled}")
      assertThat(changed.configurationCacheReused).isFalse()
    }
  }
}
