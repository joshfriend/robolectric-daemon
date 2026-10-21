package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.DaemonContract.Classpath
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.work.ChangeType
import org.gradle.work.FileChange

/**
 * Applies Gradle's classpath changes to stable worker URLs. The manifest tracks file presence and
 * resource content, but leaves class-file change detection to Gradle.
 */
internal class LiveClasspath(
  private val files: FileSystemOperations,
  private val archives: ArchiveOperations,
) {
  fun sync(
    mirror: Path,
    previous: Properties?,
    entries: List<File>,
    changes: List<FileChange>?,
    projectOutput: (File) -> Boolean,
  ): Properties {
    val incremental = previous != null && changes != null
    val result = if (incremental) Properties().apply { putAll(previous) } else Properties()
    if (!incremental) files.delete { it.delete(mirror) }
    Files.createDirectories(mirror)
    val classpath = mutableListOf<String>()
    val projectEntries = mutableListOf<String>()
    entries.forEachIndexed { index, entry ->
      if (!projectOutput(entry)) {
        classpath += entry.absolutePath
        return@forEachIndexed
      }
      val target = mirror.resolve("$index-${entry.name}")
      classpath += target.toString()
      projectEntries += target.toString()
      syncEntry(mirror, target, entry, result, changes.takeIf { incremental })
    }
    val resources = sha256()
    result
      .stringPropertyNames()
      .filter { it.startsWith(FILE) && result.getProperty(it).isNotEmpty() }
      .sorted()
      .forEach {
        resources.update(it)
        resources.update(result.getProperty(it))
      }
    result.setProperty(
      Classpath.MIRROR_ID,
      if (incremental) previous.getProperty(Classpath.MIRROR_ID) else UUID.randomUUID().toString(),
    )
    result.setProperty(
      Classpath.SOURCES,
      entries.joinToString(File.pathSeparator) { it.absolutePath },
    )
    result.setProperty(Classpath.CLASSPATH, classpath.joinToString(File.pathSeparator))
    result.setProperty(Classpath.PROJECT_ENTRIES, projectEntries.joinToString(File.pathSeparator))
    result.setProperty(Classpath.RESOURCE_KEY, resources.digest().toHex())
    return result
  }

  private fun syncEntry(
    mirror: Path,
    target: Path,
    entry: File,
    result: Properties,
    changes: List<FileChange>?,
  ) {
    Files.createDirectories(target)
    if (changes == null || changes.any { it.file == entry }) {
      files.sync {
        it.into(target)
        if (entry.isDirectory) it.from(entry)
        else if (entry.isFile) it.from(archives.zipTree(entry))
      }
      result
        .stringPropertyNames()
        .filter { it.startsWith("$FILE${target.fileName}/") }
        .forEach(result::remove)
      Files.walk(target).use { paths ->
        paths.filter(Files::isRegularFile).sorted().forEach { record(result, mirror, it) }
      }
    } else {
      changes
        .filter { it.file.toPath().startsWith(entry.toPath()) }
        .forEach { change ->
          val destination = target.resolve(entry.toPath().relativize(change.file.toPath()))
          if (change.changeType == ChangeType.REMOVED) {
            files.delete { it.delete(destination) }
            val key = "$FILE${mirror.relativize(destination).toString().replace('\\', '/')}"
            result
              .stringPropertyNames()
              .filter { it == key || it.startsWith("$key/") }
              .forEach(result::remove)
          } else if (change.file.isFile) {
            files.copy {
              it.from(change.file)
              it.into(destination.parent)
            }
            record(result, mirror, destination)
          }
        }
    }
  }

  private fun record(result: Properties, mirror: Path, file: Path) {
    val relative = mirror.relativize(file).toString().replace('\\', '/')
    val path = relative.substringAfter('/')
    val ordinaryClass =
      path.endsWith(".class") &&
        !path.endsWith("module-info.class") &&
        !path.startsWith("META-INF/")
    result.setProperty(
      "$FILE$relative",
      if (ordinaryClass) "" else sha256().digest(Files.readAllBytes(file)).toHex(),
    )
  }

  companion object {
    private const val FILE = "file:"

    fun isPresent(mirror: Path, result: Properties): Boolean =
      Files.isDirectory(mirror) &&
        result
          .getProperty(Classpath.PROJECT_ENTRIES)
          .split(File.pathSeparator)
          .filter(String::isNotEmpty)
          .all { Files.isDirectory(Path.of(it)) } &&
        result
          .stringPropertyNames()
          .filter { it.startsWith(FILE) }
          .all { Files.isRegularFile(mirror.resolve(it.removePrefix(FILE))) }
  }
}
