package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.RunRequest
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent

/** A synchronous run request, valid for the duration of the server's request callback. */
internal interface WorkerRunSession {
  val request: RunRequest

  fun emit(event: WorkerEvent)

  /** Requests the next class group; an empty group marks the end of the run. */
  fun nextClassGroup(): List<String>
}
