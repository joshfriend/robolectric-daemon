package com.fueledbycaffeine.robolectricdaemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GradleSystemPropertiesTest {
  @Test
  fun `uses the last value for repeated JVM properties`() {
    assertEquals(
      mapOf("roborazzi.test.verify" to "true"),
      gradleSystemProperties(
        listOf("-Droborazzi.test.verify=false", "-Droborazzi.test.verify=true")
      ),
    )
  }

  @Test
  fun `keeps project properties and drops Gradle and daemon internals`() {
    val properties =
      gradleSystemProperties(
        listOf(
          "-Xmx512m",
          "-Dorg.gradle.internal.worker.tmpdir=/tmp/worker",
          "-Dorg.gradle.native=false",
          "-Drobolectric.daemon.request=/tmp/request.properties",
          "-Dfile.encoding=UTF-8",
          "-Droborazzi.test.verify=true",
          "-Dequation=a=b",
          "-Dempty=",
          "-Dflag",
        )
      )
    assertEquals(
      mapOf(
        "file.encoding" to "UTF-8",
        "roborazzi.test.verify" to "true",
        "equation" to "a=b",
        "empty" to "",
        "flag" to "",
      ),
      properties,
    )
  }
}
