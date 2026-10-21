package com.fueledbycaffeine.robolectricdaemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkerReloadTest {
  @Test
  fun `preserves reload summary labels`() {
    assertEquals("error", WorkerReload(ReloadState.ERROR).toString())
    assertEquals("project", WorkerReload(ReloadState.PROJECT).toString())
    val expected =
      mapOf(
        ReloadReason.NEW_WORKER to "new-worker",
        ReloadReason.MIRROR_RECREATED to "mirror-recreated",
        ReloadReason.CLASSPATH_CHANGED to "classpath-changed",
        ReloadReason.RESOURCES_CHANGED to "resources-changed",
        ReloadReason.INSTRUMENTED_PROJECT_CLASS_CHANGED to "instrumented-project-class-changed",
        ReloadReason.PROJECT_CLASS_BOUND_BY_NAME to "project-class-bound-by-name",
        ReloadReason.BOUND_PROJECT_CLASS_CHANGED to "bound-project-class-changed",
      )
    for ((reason, label) in expected) {
      assertEquals("full($label)", WorkerReload(ReloadState.FULL, ReloadCause(reason)).toString())
    }
  }

  @Test
  fun `preserves class details in reload summaries`() {
    assertEquals(
      "full(project-class-bound-by-name:example.Counter+2)",
      WorkerReload(
          ReloadState.FULL,
          ReloadCause(ReloadReason.PROJECT_CLASS_BOUND_BY_NAME, "example.Counter+2"),
        )
        .toString(),
    )
  }
}
