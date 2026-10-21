package com.fueledbycaffeine.robolectricdaemon

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.Properties

/**
 * Hashes the test task's other inputs (AGP's resource APK, merged assets, ...) that sandboxes read
 * once at startup, so a change to any of them gets a fresh sandbox. Hashes are cached by size and
 * timestamp in [cacheFile].
 */
internal object RuntimeInputs {
  fun digest(
    inputs: Collection<File>,
    classpath: List<File>,
    stateDirectory: Path,
    cacheFile: Path,
  ): String {
    val previous = cacheFile.readPropertiesOrNull() ?: Properties()
    val stamps = Properties()
    val hash = sha256()
    val classDirectories = classpath.filter { it.isDirectory }.map { it.toPath() }
    val excluded = classpath.toSet()
    // Gradle reports class path directories file by file; the mirror covers those. Test outputs
    // and screenshot goldens are re-read from disk by each test, so they never stale the JVM.
    val files =
      inputs
        .asSequence()
        .filter { it !in excluded && !isReadFreshByTests(it) }
        .map { it.toPath().toAbsolutePath() }
        .filter { path -> !path.startsWith(stateDirectory) }
        .filter { path -> classDirectories.none { path.startsWith(it) } }
        .flatMap { path ->
          when {
            Files.isDirectory(path) ->
              Files.walk(path)
                .use { it.filter(Files::isRegularFile).sorted().toList() }
                .asSequence()
            Files.isRegularFile(path) -> sequenceOf(path)
            else -> emptySequence()
          }
        }
        .distinct()
        .sorted()
    for (file in files) {
      val key = file.toString()
      val stamp = "${Files.size(file)}:${Files.getLastModifiedTime(file).toMillis()}"
      val cached = previous.getProperty(key)
      val value =
        if (cached != null && cached.startsWith("$stamp:")) cached
        else "$stamp:${sha256().digest(Files.readAllBytes(file)).toHex()}"
      stamps.setProperty(key, value)
      hash.update(key)
      hash.update(value.substringAfterLast(':'))
    }
    if (stamps != previous) cacheFile.writePropertiesAtomically(stamps)
    return hash.digest().toHex()
  }

  private fun isReadFreshByTests(file: File): Boolean {
    val path = file.absolutePath.replace(File.separatorChar, '/')
    val name = file.name.lowercase(Locale.ROOT)
    return "/build/outputs/" in path ||
      "/build/reports/" in path ||
      "/build/test-results/" in path ||
      name.endsWith(".png") ||
      name.endsWith(".webp")
  }
}
