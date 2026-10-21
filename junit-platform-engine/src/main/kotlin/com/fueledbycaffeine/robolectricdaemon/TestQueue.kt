package com.fueledbycaffeine.robolectricdaemon

import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Queues candidate classes longest first using the previous run's per-class times. Nested classes
 * stay with their top-level class. Uses only as many workers as there are selected class groups.
 */
internal class TestQueue private constructor(val groups: List<List<String>>, val workers: Int) {
  private val pending = ConcurrentLinkedQueue(groups)

  fun next(): List<String> = pending.poll() ?: emptyList()

  companion object {
    fun of(
      candidates: List<String>,
      durations: Properties,
      maxParallelForks: Int,
    ): TestQueue {
      val groups = candidates.groupBy { it.substringBefore('$') }.toSortedMap()
      val known =
        groups.keys.mapNotNull {
          durations.getProperty(it)?.toLong()?.takeIf { millis -> millis > 0 }
        }
      val typical = if (known.isEmpty()) 1 else maxOf(1, known.sum() / known.size)
      val weights = groups.keys.associateWith { durations.getProperty(it)?.toLong() ?: typical }
      val workers = minOf(maxParallelForks, groups.size)
      return TestQueue(
        groups.keys.sortedByDescending { weights.getValue(it) }.map { groups.getValue(it) },
        workers,
      )
    }
  }
}
