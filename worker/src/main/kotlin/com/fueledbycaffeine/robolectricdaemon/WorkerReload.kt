package com.fueledbycaffeine.robolectricdaemon

internal enum class ReloadState {
  ERROR,
  PROJECT,
  FULL,
}

internal enum class ReloadReason(val description: String) {
  NEW_WORKER("new-worker"),
  MIRROR_RECREATED("mirror-recreated"),
  CLASSPATH_CHANGED("classpath-changed"),
  RESOURCES_CHANGED("resources-changed"),
  INSTRUMENTED_PROJECT_CLASS_CHANGED("instrumented-project-class-changed"),
  PROJECT_CLASS_BOUND_BY_NAME("project-class-bound-by-name"),
  BOUND_PROJECT_CLASS_CHANGED("bound-project-class-changed"),
}

internal data class ReloadCause(val reason: ReloadReason, val detail: String? = null) {
  override fun toString(): String = reason.description + (detail?.let { ":$it" } ?: "")
}

internal data class WorkerReload(
  val state: ReloadState,
  val cause: ReloadCause? = null,
) {
  override fun toString(): String =
    when (state) {
      ReloadState.ERROR -> "error"
      ReloadState.PROJECT -> "project"
      ReloadState.FULL -> "full($cause)"
    }
}
