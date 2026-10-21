package com.fueledbycaffeine.robolectricdaemon

import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.ServiceLoader
import java.util.function.Predicate
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.streams.asSequence

/** Long-lived dependency runtime, including the Robolectric sandboxes cached inside it. */
internal class WorkerBase(
  private val classpath: List<URL>,
  private val resourceKey: String,
  private val mirrorId: String,
  supportClasspath: List<URL>,
) : AutoCloseable {
  val loader = BaseLoader((supportClasspath + classpath).toTypedArray())
  private val temporaryFiles = mutableSetOf<Path>()
  val runtime =
    ServiceLoader.load(WorkerRuntime::class.java, loader).toList().let { providers ->
      check(providers.size == 1) {
        "Expected exactly one Robolectric worker runtime provider, found ${providers.size}"
      }
      providers.single()
    }
  private val pinned = runtime.pinnedClasses()
  private var statefulBound: List<String> = emptyList()
  private var boundDigests: Map<String, String> = emptyMap()
  private val pinnedDigests = mutableMapOf<String, String>()

  var epochs = 0
    private set

  /** Returns why this runtime must be rebuilt, or null when only project classes need replacing. */
  fun reloadReason(
    classpath: List<URL>,
    resourceKey: String,
    mirrorId: String,
    projectEntries: List<URL>,
  ): ReloadCause? {
    if (this.mirrorId != mirrorId) {
      return ReloadCause(ReloadReason.MIRROR_RECREATED)
    }
    if (this.classpath != classpath) {
      return ReloadCause(ReloadReason.CLASSPATH_CHANGED)
    }
    if (this.resourceKey != resourceKey) {
      return ReloadCause(ReloadReason.RESOURCES_CHANGED)
    }
    // Robolectric instruments these into the sandbox itself, so they can't be swapped per run.
    for ((name, digest) in pinnedDigests) {
      if (digest != classDigest(projectEntries, name)) {
        return ReloadCause(ReloadReason.INSTRUMENTED_PROJECT_CLASS_CHANGED, name)
      }
    }
    if (statefulBound.isNotEmpty()) {
      return ReloadCause(
        ReloadReason.PROJECT_CLASS_BOUND_BY_NAME,
        statefulBound[0] + if (statefulBound.size > 1) "+${statefulBound.size - 1}" else "",
      )
    }
    for ((name, digest) in boundDigests) {
      if (digest != classDigest(projectEntries, name)) {
        return ReloadCause(ReloadReason.BOUND_PROJECT_CLASS_CHANGED, name)
      }
    }
    return null
  }

  fun newEpoch(projectEntries: List<URL>): ClassLoader {
    epochs++
    val urls = projectEntries.toTypedArray()
    val owned = ownedClasses(projectEntries)
    val project = ProjectLoader(urls, owned, loader)
    loader.project = project
    runtime.setProject(urls, Predicate(owned::contains))
    return project
  }

  /** The mirror can't change during a request, so these are the files the sandbox just read. */
  fun recordProjectClasses(projectEntries: List<URL>) {
    for (name in pinned.toList()) {
      pinnedDigests.getOrPut(name) { classDigest(projectEntries, name) }
    }
    recordBound(projectEntries)
  }

  /**
   * A bound class also counts as stateful if any project class it reaches is stateful: the old copy
   * resolves references through its own, old project loader.
   */
  private fun recordBound(projectEntries: List<URL>) {
    val roots = loader.boundByName() + runtime.boundByName()
    val owned = loader.project?.owned
    if (!runtime.canAnalyzeClasses() || owned == null) {
      statefulBound = roots
      boundDigests = emptyMap()
      return
    }

    val classes = mutableMapOf<String, BoundClass>()
    val closures = roots.associateWith {
      constantClassDigests(it, owned) { name ->
        classes.getOrPut(name) { readBound(projectEntries, name) }
      }
    }
    statefulBound = closures.filterValues { it == null }.keys.toList()
    boundDigests = closures.values.filterNotNull().flatMap { it.toList() }.toMap()
  }

  private fun constantClassDigests(
    root: String,
    owned: Set<String>,
    lookup: (String) -> BoundClass,
  ): Map<String, String>? {
    val closure = mutableMapOf<String, String>()
    val pending = ArrayDeque<String>().apply { addLast(root) }
    while (pending.isNotEmpty()) {
      val name = pending.removeLast()
      if (name in closure) {
        continue
      }
      val bound = lookup(name)
      if (!bound.constantOnly) {
        return null
      }
      closure[name] = bound.digest
      bound.references.filterTo(pending) { it in owned }
    }
    return closure
  }

  private fun readBound(projectEntries: List<URL>, name: String): BoundClass {
    val bytes = classBytes(projectEntries, name) ?: return BoundClass(MISSING, false, emptyList())
    return try {
      BoundClass(digest(bytes), runtime.isConstantOnly(bytes), runtime.references(bytes))
    } catch (e: IllegalArgumentException) {
      // ASM rejects newer class-file versions; keep the conservative reload behavior.
      logger.log(
        LogRecord(Level.WARNING, "Could not analyze bound project class {0}").apply {
          parameters = arrayOf(name)
          thrown = e
        }
      )
      BoundClass(digest(bytes), false, emptyList())
    }
  }

  fun retainTemporaryFiles(paths: Collection<Path>) {
    temporaryFiles.addAll(paths)
  }

  /**
   * Robolectric's native-runtime shutdown hook can't load classes after the loader is closed, so
   * the worker owns cleanup of its extraction directories.
   */
  override fun close() {
    try {
      loader.close()
    } catch (e: IOException) {
      logger.log(Level.WARNING, "Could not close retired loader", e)
    }
    WorkerExecution.deleteTemporaryFiles(temporaryFiles)
  }

  private class BoundClass(
    val digest: String,
    val constantOnly: Boolean,
    val references: List<String>,
  )

  companion object {
    private val logger = Logger.getLogger(WorkerBase::class.java.name)
    private const val MISSING = "missing"

    private fun classDigest(projectEntries: List<URL>, className: String): String =
      classBytes(projectEntries, className)?.let(::digest) ?: MISSING

    /** The class file the project loader would read, or null if no project entry has it. */
    private fun classBytes(projectEntries: List<URL>, className: String): ByteArray? {
      val relative = className.replace('.', '/') + ".class"
      for (entry in projectEntries) {
        val file = Path.of(entry.toURI()).resolve(relative)
        if (Files.isRegularFile(file)) {
          return Files.readAllBytes(file)
        }
      }
      return null
    }

    private fun digest(bytes: ByteArray): String =
      Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun ownedClasses(projectEntries: List<URL>): Set<String> = buildSet {
      for (entry in projectEntries) {
        val root = Path.of(entry.toURI())
        if (!Files.isDirectory(root)) {
          continue
        }
        Files.walk(root).use { paths ->
          paths
            .asSequence()
            .map { root.relativize(it).toString().replace('\\', '/') }
            .filter {
              it.endsWith(".class") &&
                !it.startsWith("META-INF/") &&
                !it.endsWith("module-info.class")
            }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .toCollection(this)
        }
      }
    }
  }
}
