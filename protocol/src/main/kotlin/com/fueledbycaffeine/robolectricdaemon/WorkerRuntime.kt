package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import java.net.URL
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.function.Supplier

/** Parent-loaded contract between the worker and its test-runtime provider. */
@JvmSuppressWildcards
interface WorkerRuntime {
  @Throws(ClassNotFoundException::class)
  fun execute(
    loader: ClassLoader,
    next: Supplier<List<String>>,
    includes: List<String>,
    events: Consumer<WorkerEvent>,
  ): IntArray

  fun setProject(urls: Array<URL>, owns: Predicate<String>)

  fun pinnedClasses(): Set<String>

  fun boundByName(): List<String>

  fun canAnalyzeClasses(): Boolean

  fun isConstantOnly(bytes: ByteArray): Boolean

  fun references(bytes: ByteArray): List<String>
}
