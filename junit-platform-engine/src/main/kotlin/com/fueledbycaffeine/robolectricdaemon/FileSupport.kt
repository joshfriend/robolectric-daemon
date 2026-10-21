package com.fueledbycaffeine.robolectricdaemon

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

fun MessageDigest.update(value: String) {
  update(value.toByteArray(StandardCharsets.UTF_8))
  update(0.toByte())
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it) }

fun digest(vararg parts: ByteArray): String {
  val hash = sha256()
  for (part in parts) {
    hash.update(part)
    hash.update(0.toByte())
  }
  return hash.digest().toHex()
}

fun Path.readPropertiesOrNull(): Properties? {
  if (!Files.isRegularFile(this)) return null
  return Properties().also { properties -> Files.newInputStream(this).use(properties::load) }
}

/** Replaces [this] with [properties] in one step, so readers never see a partial file. */
fun Path.writePropertiesAtomically(properties: Properties, comment: String? = null) {
  Files.createDirectories(parent)
  val staging = Files.createTempFile(parent, "$fileName-", ".tmp")
  Files.newOutputStream(staging).use { properties.store(it, comment) }
  Files.move(staging, this, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

fun Path.deleteTree() {
  if (!Files.exists(this)) return
  Files.walk(this).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}

/**
 * Runs [action] holding the task directory's lock, which serializes the classpath mirror and the
 * workers between concurrent builds.
 */
fun <T> withClientLock(taskDirectory: Path, action: () -> T): T {
  Files.createDirectories(taskDirectory)
  FileChannel.open(
      taskDirectory.resolve("client.lock"),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    )
    .use { channel ->
      channel.lock().use {
        return action()
      }
    }
}
