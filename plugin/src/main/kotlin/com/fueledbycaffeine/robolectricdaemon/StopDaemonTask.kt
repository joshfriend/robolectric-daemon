package com.fueledbycaffeine.robolectricdaemon

import java.nio.file.Files
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Stops the module's workers and deletes their mirrors. */
@DisableCachingByDefault(because = "Stops external interactive workers")
abstract class StopDaemonTask : DefaultTask() {
  @get:Internal abstract val stateDirectory: DirectoryProperty

  @TaskAction
  fun stop() {
    val root = stateDirectory.get().asFile.toPath()
    if (!Files.isDirectory(root)) {
      logger.lifecycle("No Robolectric workers for this module.")
      return
    }
    val tasks =
      Files.list(root).use { children -> children.filter(Files::isDirectory).sorted().toList() }
    for (task in tasks) {
      withClientLock(task) {
        for (worker in workerDirectories(task)) {
          val state = worker.resolve(WorkerClient.STATE_FILE)
          state.readPropertiesOrNull()?.let { properties ->
            WorkerClient.stop(properties)
            logger.lifecycle(
              "Stopped Robolectric worker PID {}",
              properties.getProperty(DaemonContract.Worker.PID),
            )
          }
          Files.deleteIfExists(state)
          worker.resolve(DaemonContract.Worker.TEMP_DIRECTORY).deleteTree()
        }
        task.resolve(DaemonContract.STAGED_JARS_DIRECTORY).deleteTree()
      }
    }
  }
}
