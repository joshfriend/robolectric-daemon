package com.fueledbycaffeine.robolectricdaemon

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.work.Incremental
import org.gradle.work.InputChanges

/**
 * Mirrors a test task's project classes into the stable directories its Robolectric worker loads
 * them from (see [LiveClasspath]). The test task consumes [result] as an input, so this runs before
 * it, and is up to date when no class or resource changed.
 */
@DisableCachingByDefault(because = "Maintains a mirror that running workers read in place")
abstract class SyncRobolectricClasspath
@Inject
constructor(
  private val files: FileSystemOperations,
  private val archives: ArchiveOperations,
) : DefaultTask() {
  /** The test task's class path. */
  // Classpath normalization reports reordered leaves as remove/add pairs, not per-file edits.
  @get:Incremental
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.ABSOLUTE)
  abstract val classpath: ConfigurableFileCollection

  /** Stable mirror names depend on the ordered source paths, not only classpath contents. */
  @get:Input
  val classpathPaths: List<String>
    get() = classpath.files.map { it.absolutePath }

  /** Entries under this directory (and not in [gradleUserHome]) are built by this build. */
  @get:Input abstract val buildRoot: Property<String>

  @get:Input abstract val gradleUserHome: Property<String>

  // Gradle must not delete live files outside the client lock on a non-incremental execution.
  @get:LocalState abstract val mirror: DirectoryProperty

  /** Worker class path, mirrored entries and resource hash; see [LiveClasspath]. */
  @get:OutputFile abstract val result: RegularFileProperty

  @get:Internal abstract val daemonEnabled: Property<Boolean>

  init {
    onlyIf("the Robolectric daemon is enabled") {
      (it as SyncRobolectricClasspath).daemonEnabled.get()
    }
    outputs.upToDateWhen {
      val task = it as SyncRobolectricClasspath
      val previous = task.result.get().asFile.toPath().readPropertiesOrNull()
      previous != null && LiveClasspath.isPresent(task.mirror.get().asFile.toPath(), previous)
    }
  }

  @TaskAction
  fun sync(inputChanges: InputChanges) {
    val resultFile = result.get().asFile.toPath()
    val root = Path.of(buildRoot.get())
    val userHome = Path.of(gradleUserHome.get())
    withClientLock(resultFile.parent) {
      val previous = resultFile.readPropertiesOrNull()
      // Until the new result is written, a crash leaves no result and the next sync starts over.
      Files.deleteIfExists(resultFile)
      val changes =
        if (inputChanges.isIncremental) inputChanges.getFileChanges(classpath).toList() else null
      logger.info("Robolectric classpath changes: {}", changes?.map { it.file } ?: "full rebuild")
      val properties =
        LiveClasspath(files, archives).sync(
          mirror.get().asFile.toPath(),
          previous,
          classpath.files.toList(),
          changes,
        ) {
          projectOutput(it, root, userHome)
        }
      resultFile.writePropertiesAtomically(properties, "Robolectric worker class path")
    }
  }

  private fun projectOutput(entry: File, root: Path, userHome: Path): Boolean {
    val path = entry.toPath().toAbsolutePath().normalize()
    return path.startsWith(root) && !path.startsWith(userHome)
  }
}
