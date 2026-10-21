package com.fueledbycaffeine.robolectricdaemon

import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider

/** Separate classpaths for the worker's application loader and its Robolectric loader. */
internal class WorkerClasspath(
  val runtime: FileCollection,
  val sandbox: FileCollection,
  val engine: FileCollection,
) {
  companion object {
    fun create(project: Project, enabled: Provider<Boolean>): WorkerClasspath {
      // A configuration-time decision keeps disabled configurations out of the cached task graph.
      val active = enabled.get()
      return WorkerClasspath(
        configuration(project, "Worker", "worker", active),
        configuration(project, "Sandbox", "sandbox", active),
        configuration(project, "Engine", "junit-platform-engine", active),
      )
    }

    private fun configuration(
      project: Project,
      role: String,
      module: String,
      enabled: Boolean,
    ): FileCollection {
      val dependencies =
        project.configurations.dependencyScope("robolectricDaemon${role}Dependencies")
      project.dependencies.add(
        dependencies.name,
        "${BuildConfig.GROUP}:$module:${BuildConfig.VERSION}",
      )
      val classpath =
        project.configurations.resolvable("robolectricDaemon${role}Classpath") {
          it.extendsFrom(dependencies)
          it.attributes { attributes ->
            attributes.attribute(
              Usage.USAGE_ATTRIBUTE,
              project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME),
            )
            attributes.attribute(
              Category.CATEGORY_ATTRIBUTE,
              project.objects.named(Category::class.java, Category.LIBRARY),
            )
            attributes.attribute(
              LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
              project.objects.named(LibraryElements::class.java, LibraryElements.JAR),
            )
          }
        }
      return project.objects.fileCollection().also { if (enabled) it.from(classpath) }
    }
  }
}
