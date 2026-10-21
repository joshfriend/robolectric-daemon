package com.fueledbycaffeine.robolectricdaemon

import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * The dependency loader. It also lists project directories so Robolectric (which copies its URLs)
 * can read project resources, but defers project classes to the current epoch.
 */
internal class BaseLoader(urls: Array<URL>) : URLClassLoader(urls, ProtocolLoader) {
  @Volatile var project: ProjectLoader? = null
  private val routed = ConcurrentHashMap.newKeySet<String>()

  override fun loadClass(name: String, resolve: Boolean): Class<*> {
    val current = project
    if (current != null && name in current.owned) {
      routed.add(name)
      return current.loadClass(name)
    }
    return super.loadClass(name, resolve)
  }

  /**
   * Project classes the JVM has bound to this loader by name (e.g. `Class.forName(name, init,
   * thisLoader)`): it records the initiating loader and never asks again, so later epochs would get
   * the old class. Direct `loadClass` calls aren't recorded.
   */
  fun boundByName(): List<String> = buildList {
    for (name in routed.toList()) {
      val loaded = findLoadedClass(name)
      if (loaded != null && loaded.classLoader !== this@BaseLoader) {
        add(name)
      } else {
        routed.remove(name)
      }
    }
  }
}

/** Shares the protocol artifact without exposing the application's other dependencies. */
internal object ProtocolLoader :
  URLClassLoader(
    arrayOf(
      checkNotNull(WorkerRuntime::class.java.protectionDomain.codeSource) {
          "Worker protocol has no code source"
        }
        .location
    ),
    ClassLoader.getPlatformClassLoader(),
  ) {
  override fun findClass(name: String): Class<*> {
    if (findResource(name.replace('.', '/') + ".class") == null) {
      throw ClassNotFoundException(name)
    }
    // Keep the same class identities on both sides of the worker/runtime boundary.
    return WorkerRuntime::class.java.classLoader.loadClass(name)
  }
}

/** One request's copy of the project classes, child-first. */
internal class ProjectLoader(
  urls: Array<URL>,
  val owned: Set<String>,
  parent: ClassLoader,
) : URLClassLoader(urls, parent) {
  override fun loadClass(name: String, resolve: Boolean): Class<*> {
    if (name !in owned) {
      return parent.loadClass(name)
    }
    return synchronized(getClassLoadingLock(name)) {
      val loaded = findLoadedClass(name) ?: findClass(name)
      if (resolve) {
        resolveClass(loaded)
      }
      loaded
    }
  }
}
