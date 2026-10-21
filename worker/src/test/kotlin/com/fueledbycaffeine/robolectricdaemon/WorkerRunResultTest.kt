package com.fueledbycaffeine.robolectricdaemon

import java.io.IOException
import java.util.ServiceConfigurationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkerRunResultTest {
  @Test
  fun `successful stages accumulate immutable results`() {
    val initial = WorkerRunResult.Success()
    val reload = WorkerReload(ReloadState.FULL, ReloadCause(ReloadReason.NEW_WORKER))
    val result = initial.then { it.copy(reload = reload) }.then { it.copy(tests = 3, failures = 1) }
    assertEquals(WorkerRunResult.Success(), initial)
    assertEquals(WorkerRunResult.Success(tests = 3, failures = 1, reload = reload), result)
    assertInstanceOf(WorkerRunResult.Success::class.java, result)
  }

  @Test
  fun `recording failure retains counts and reload and skips later stages`() {
    val executed =
      WorkerRunResult.Success(tests = 3, failures = 1, reload = WorkerReload(ReloadState.PROJECT))
    val failure = IOException("could not read project classes")
    val failed = executed.then { throw failure }
    val result = failed.then { error("must not run after failure") }
    assertSame(failed, result)
    assertEquals(WorkerRunResult.Failure(executed, failure), result)
    assertEquals(executed.tests, result.tests)
    assertEquals(executed.failures, result.failures)
    assertEquals(executed.reload, result.reload)
  }

  @Test
  fun `preparation failure retains error reload state and zero counts`() {
    val failure = IllegalStateException("no runtime provider")
    val result = WorkerRunResult.Success().then { throw failure }
    assertEquals(WorkerRunResult.Failure(WorkerRunResult.Success(), failure), result)
  }

  @Test
  fun `execution failure retains the successful reload decision`() {
    val prepared = WorkerRunResult.Success(reload = WorkerReload(ReloadState.PROJECT))
    val failure = IOException("client disconnected")
    val result = prepared.then { throw failure }
    assertEquals(WorkerRunResult.Failure(prepared, failure), result)
    assertEquals(prepared.reload, result.reload)
  }

  @Test
  fun `records supported failures but propagates other errors`() {
    for (failure in
      listOf(IOException("io"), LinkageError("linkage"), ServiceConfigurationError("provider"))) {
      val result = WorkerRunResult.Success().then { throw failure }
      assertSame(failure, assertInstanceOf(WorkerRunResult.Failure::class.java, result).error)
    }
    val fatal = OutOfMemoryError("fatal")
    assertSame(
      fatal,
      assertThrows(OutOfMemoryError::class.java) { WorkerRunResult.Success().then { throw fatal } },
    )
  }
}
