package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.Security
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.net.ssl.HttpsURLConnection
import kotlin.streams.asSequence

/** Request-scoped JVM state and worker-owned temporary files. */
internal object WorkerExecution {
  private val logger = Logger.getLogger(WorkerExecution::class.java.name)
  private val temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"))

  /** Runs with the request's system properties and output capture, restoring JVM-global state. */
  fun execute(
    base: WorkerBase,
    requestProperties: Map<String, String>,
    events: Consumer<WorkerEvent>,
    body: () -> IntArray,
  ): IntArray {
    val original = System.getProperties().clone() as Properties
    val properties = (original.clone() as Properties).apply { putAll(requestProperties) }
    val stdout = System.out
    val stderr = System.err
    val context = Thread.currentThread().contextClassLoader
    val providers = Security.getProviders()
    val locale = Locale.getDefault()
    val displayLocale = Locale.getDefault(Locale.Category.DISPLAY)
    val formatLocale = Locale.getDefault(Locale.Category.FORMAT)
    val zone = TimeZone.getDefault()
    val hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    val temporaryBefore = listTemporaryFiles()
    val out = PrintStream(LineEvents(false, events), true, StandardCharsets.UTF_8)
    val err = PrintStream(LineEvents(true, events), true, StandardCharsets.UTF_8)
    System.setOut(out)
    System.setErr(err)
    System.setProperties(properties)
    // Robolectric builds its sandbox loaders from the context loader's URLs and parent.
    Thread.currentThread().contextClassLoader = base.loader
    try {
      return body()
    } finally {
      out.flush()
      err.flush()
      for (provider in Security.getProviders()) {
        Security.removeProvider(provider.name)
      }
      for (provider in providers) {
        Security.addProvider(provider)
      }
      Locale.setDefault(locale)
      Locale.setDefault(Locale.Category.DISPLAY, displayLocale)
      Locale.setDefault(Locale.Category.FORMAT, formatLocale)
      TimeZone.setDefault(zone)
      HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier)
      System.setProperties(original)
      System.setOut(stdout)
      System.setErr(stderr)
      Thread.currentThread().contextClassLoader = context
      val created = listTemporaryFiles() - temporaryBefore
      // A fork deletes Robolectric's per-test data dirs from shutdown hooks at exit; here they'd
      // pile up (about 0.4 GB per app run). The sandbox's own dirs live as long as the base.
      val (perTest, persistent) =
        created.partition { path ->
          val name = path.fileName.toString()
          name.startsWith("robolectric-") &&
            !name.startsWith("robolectric-nativeruntime") &&
            !name.startsWith("robolectric-no-test-yet")
        }
      base.retainTemporaryFiles(persistent)
      deleteTemporaryFiles(perTest)
    }
  }

  fun halt(status: Int) {
    System.out.flush()
    System.err.flush()
    deleteTemporaryFiles(listTemporaryFiles())
    // Skip shutdown hooks: Robolectric's would fail on closed loaders.
    Runtime.getRuntime().halt(status)
  }

  fun deleteTemporaryFiles(roots: Collection<Path>) {
    for (root in roots) {
      try {
        Files.walk(root).use { paths ->
          paths.asSequence().sortedDescending().forEach { Files.deleteIfExists(it) }
        }
      } catch (e: IOException) {
        logger.log(
          LogRecord(Level.WARNING, "Could not delete worker temp file {0}").apply {
            parameters = arrayOf(root)
            thrown = e
          }
        )
      }
    }
  }

  private fun listTemporaryFiles(): Set<Path> =
    try {
      Files.list(temporaryDirectory).use { it.asSequence().toSet() }
    } catch (e: IOException) {
      logger.log(Level.WARNING, "Could not list worker temp files", e)
      emptySet()
    }
}
