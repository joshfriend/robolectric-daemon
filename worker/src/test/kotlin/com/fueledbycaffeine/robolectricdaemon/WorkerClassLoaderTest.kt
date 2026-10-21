package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import java.net.URLClassLoader
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkerClassLoaderTest {
  @Test
  fun `shares protocol classes and private protobuf with their original identities`() {
    val loader = ProtocolLoader
    for (type in
      listOf(
        WorkerRuntime::class.java,
        Protocol::class.java,
        DaemonContract::class.java,
        WorkerEvent::class.java,
        WorkerEvent.Builder::class.java,
        WorkerEvent.PayloadCase::class.java,
        WorkerEvent::class.java.superclass,
      )) {
      assertSame(type, loader.loadClass(type.name))
    }
    assertSame(String::class.java, loader.loadClass(String::class.java.name))
  }

  @Test
  fun `does not expose worker implementation or application dependencies`() {
    for (type in listOf(WorkerMain::class.java, Unit::class.java, Test::class.java)) {
      assertThrows(ClassNotFoundException::class.java) { ProtocolLoader.loadClass(type.name) }
    }
  }

  @Test
  fun `test runtime can load its own Kotlin while sharing the protocol`() {
    val kotlin = Unit::class.java.protectionDomain.codeSource.location
    URLClassLoader(arrayOf(kotlin), ProtocolLoader).use { loader ->
      assertSame(loader, loader.loadClass(Unit::class.java.name).classLoader)
      assertSame(WorkerRuntime::class.java, loader.loadClass(WorkerRuntime::class.java.name))
    }
  }
}
