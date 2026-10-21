package com.fueledbycaffeine.robolectricdaemon

import java.util.ServiceConfigurationError

/** Preserves completed execution stages when a later stage fails. */
internal sealed class WorkerRunResult {
  abstract val tests: Int
  abstract val failures: Int
  abstract val reload: WorkerReload

  data class Success(
    override val tests: Int = 0,
    override val failures: Int = 0,
    override val reload: WorkerReload = WorkerReload(ReloadState.ERROR),
  ) : WorkerRunResult()

  data class Failure(val previous: Success, val error: Throwable) : WorkerRunResult() {
    override val tests: Int
      get() = previous.tests

    override val failures: Int
      get() = previous.failures

    override val reload: WorkerReload
      get() = previous.reload
  }

  fun then(step: (Success) -> Success): WorkerRunResult =
    when (this) {
      is Failure -> this
      is Success ->
        try {
          step(this)
        } catch (e: Exception) {
          Failure(this, e)
        } catch (e: LinkageError) {
          Failure(this, e)
        } catch (e: ServiceConfigurationError) {
          Failure(this, e)
        }
    }
}
