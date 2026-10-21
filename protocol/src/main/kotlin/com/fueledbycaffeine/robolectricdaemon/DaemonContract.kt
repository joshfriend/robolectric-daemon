package com.fueledbycaffeine.robolectricdaemon

/** Shared file and property names exchanged by the Gradle plugin, engine and worker. */
object DaemonContract {
  const val ENGINE_ID = "robolectric-daemon"
  const val REQUEST_PROPERTY = "robolectric.daemon.request"
  const val FORKS_PROPERTY = "robolectric.daemon.forks"
  const val LOCAL_PROPERTY = "robolectric.daemon.local"
  const val WORKER_MAIN = "com.fueledbycaffeine.robolectricdaemon.WorkerMain"
  const val WORKER_DIRECTORY_PREFIX = "worker-"
  const val DURATIONS_FILE = "durations.properties"
  const val STAGED_JARS_DIRECTORY = "jars"

  object Request {
    const val FILE = "request.properties"
    const val DIRECTORY = "directory"
    const val SYNC = "sync"
    const val WORKER = "worker"
    const val SANDBOX = "sandbox"
    const val INPUTS = "inputs"
  }

  object Launch {
    const val FILE = "launch.properties"
    const val JAVA = "java"
    const val WORKING_DIRECTORY = "workingDirectory"
    const val ARGUMENT_COUNT = "argumentCount"
    const val ARGUMENT_PREFIX = "argument."
    const val PROPERTY_PREFIX = "property."
    const val ENVIRONMENT_PREFIX = "environment."
    const val ENVIRONMENT_IDENTITY = "environmentIdentity"
  }

  object Classpath {
    const val MIRROR_ID = "mirrorId"
    const val SOURCES = "sources"
    const val CLASSPATH = "classpath"
    const val PROJECT_ENTRIES = "projectEntries"
    const val RESOURCE_KEY = "resourceKey"
  }

  object Worker {
    const val FILE = "worker.properties"
    const val LOG_FILE = "worker.log"
    const val TEMP_DIRECTORY = "tmp"
    const val PID = "pid"
    const val PORT = "port"
    const val IDENTITY = "identity"
    const val TOKEN = "token"
  }
}
