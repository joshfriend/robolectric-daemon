package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.Completed
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import java.io.DataInputStream
import java.io.File
import java.net.URL
import java.nio.file.Path
import java.time.Duration
import java.util.function.Consumer
import java.util.function.Supplier
import java.util.logging.Level
import java.util.logging.Logger

/** Persistent worker entry point and execution orchestration. */
class WorkerMain private constructor(private val supportClasspath: List<URL>) {
  private var base: WorkerBase? = null
  private var bases = 0
  private var requests = 0

  private fun run(session: WorkerRunSession) {
    val request = session.request
    val classpath = urls(request.classpathList)
    val projectEntries = urls(request.projectEntriesList)
    val started = System.nanoTime()
    requests++
    val events = Consumer<WorkerEvent>(session::emit)
    val next = Supplier(session::nextClassGroup)
    val result =
      WorkerRunResult.Success()
        .then { previous ->
          previous.copy(
            reload = prepareBase(classpath, projectEntries, request.resourceKey, request.mirrorId)
          )
        }
        .then { previous ->
          val active = checkNotNull(base)
          val projectLoader = active.newEpoch(projectEntries)
          val counts =
            WorkerExecution.execute(active, request.systemPropertiesMap, events) {
              active.runtime.execute(projectLoader, next, request.includesList, events)
            }
          previous.copy(tests = counts[0], failures = counts[1])
        }
        .then { previous ->
          checkNotNull(base).recordProjectClasses(projectEntries)
          previous
        }
    val error =
      when (result) {
        is WorkerRunResult.Success -> ""
        is WorkerRunResult.Failure -> {
          events.accept(
            WorkerEvent.newBuilder().setStderr(result.error.stackTraceToString()).build()
          )
          result.error.toString()
        }
      }
    events.accept(
      WorkerEvent.newBuilder()
        .setCompleted(
          Completed.newBuilder()
            .setTests(result.tests)
            .setFailures(result.failures)
            .setError(error)
            .setSummary(
              "pid=${ProcessHandle.current().pid()} request=$requests base=$bases " +
                "epoch=${base?.epochs ?: 0} reload=${result.reload} " +
                "workerMs=${Duration.ofNanos(System.nanoTime() - started).toMillis()}"
            )
        )
        .build()
    )
  }

  private fun prepareBase(
    classpath: List<URL>,
    projectEntries: List<URL>,
    resourceKey: String,
    mirrorId: String,
  ): WorkerReload {
    val current = base
    val reason =
      if (current == null) ReloadCause(ReloadReason.NEW_WORKER)
      else current.reloadReason(classpath, resourceKey, mirrorId, projectEntries)
    if (reason == null) {
      return WorkerReload(ReloadState.PROJECT)
    }
    current?.close()
    base = null
    base = WorkerBase(classpath, resourceKey, mirrorId, supportClasspath)
    bases++
    return WorkerReload(ReloadState.FULL, reason)
  }

  companion object {
    private val logger = Logger.getLogger(WorkerMain::class.java.name)

    @JvmStatic
    fun main(args: Array<String>) {
      try {
        val token = DataInputStream(System.`in`).readUTF()
        require(token.isNotBlank()) { "Missing worker authentication token" }
        val worker = WorkerMain(urls(args[0].split(File.pathSeparator)))
        WorkerServer(Path.of(args[1]).toAbsolutePath(), args[2], token).serve(worker::run)
      } catch (e: Throwable) {
        logger.log(Level.SEVERE, "Robolectric worker failed", e)
        WorkerExecution.halt(1)
      }
      WorkerExecution.halt(0)
    }

    private fun urls(paths: List<String>): List<URL> = paths.map { Path.of(it).toUri().toURL() }
  }
}
