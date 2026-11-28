package com.fueledbycaffeine.robolectricdaemon

import com.fueledbycaffeine.robolectricdaemon.DaemonContract.Worker
import com.fueledbycaffeine.robolectricdaemon.messages.WorkerMessages.WorkerEvent
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkerClientTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `starts authenticates and stops without exposing token in process arguments`() {
    val worker = WorkerClient.start(directory, "identity", command(), directory, System.getenv())
    val process = ProcessHandle.of(worker.getProperty(Worker.PID).toLong()).orElseThrow()
    try {
      val arguments = Files.readAllLines(directory.resolve("launch-arguments"))
      assertFalse(arguments.any { it.contains(worker.getProperty(Worker.TOKEN)) })
      assertEquals(
        listOf("", directory.resolve(Worker.FILE).toString(), "identity"),
        arguments,
      )
      assertTrue(WorkerClient.alive(worker))
    } finally {
      WorkerClient.stop(worker)
      if (process.isAlive) process.destroyForcibly()
    }
    assertFalse(process.isAlive)
  }

  @Test
  fun `missing truncated and empty pipe tokens fail before publishing state`() {
    for (mode in listOf("missing", "truncated", "empty")) {
      val state = directory.resolve("$mode.properties")
      val log = directory.resolve("$mode.log")
      val process =
        ProcessBuilder(command() + listOf(state.toString(), "identity"))
          .redirectErrorStream(true)
          .redirectOutput(log.toFile())
          .start()
      try {
        DataOutputStream(process.outputStream).use {
          when (mode) {
            "truncated" -> it.writeByte(0)
            "empty" -> it.writeUTF("")
          }
        }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Worker did not reject $mode token")
        assertEquals(1, process.exitValue())
        assertFalse(Files.exists(state))
        assertTrue(Files.readString(log).contains("Robolectric worker failed"))
      } finally {
        if (process.isAlive) {
          process.destroyForcibly()
          process.waitFor(10, TimeUnit.SECONDS)
        }
      }
    }
  }

  private fun command(): List<String> {
    val temporary = Files.createDirectories(directory.resolve("worker-tmp"))
    val classpath =
      listOf(
          WorkerLaunchProbe::class.java,
          WorkerMain::class.java,
          Protocol::class.java,
          WorkerEvent::class.java,
          Unit::class.java,
        )
        .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        .distinct()
        .joinToString(File.pathSeparator)
    return listOf(
      Path.of(System.getProperty("java.home"), "bin", "java").toString(),
      "-Djava.io.tmpdir=$temporary",
      "-cp",
      classpath,
      WorkerLaunchProbe::class.java.name,
      "",
    )
  }
}

object WorkerLaunchProbe {
  @JvmStatic
  fun main(args: Array<String>) {
    Files.write(Path.of(args[1]).resolveSibling("launch-arguments"), args.toList())
    WorkerMain.main(args)
  }
}
