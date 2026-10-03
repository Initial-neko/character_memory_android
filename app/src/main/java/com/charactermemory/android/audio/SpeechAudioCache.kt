package com.charactermemory.android.audio

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One owner per exclusive directory. Only successful audio bytes enter this ephemeral cache. */
class SpeechAudioCache(
    private val directory: File,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxEntries: Int = 16,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val ttlMillis: Long = 5 * 60_000L
) {
    data class Key(val coreUrl: String, val mediaUrl: String, val ownerId: String, val text: String)
    private data class Entry(val file: File, val bytes: Long, val createdAt: Long)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalBytes = 0L
    private val ownedFilename = Regex("cm-speech-[a-f0-9]{64}-[a-f0-9-]{36}\\.(wav|mp3)")

    init {
        require(maxEntries > 0 && maxBytes > 0 && ttlMillis > 0)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建朗读缓存目录" }
        // There is no durable metadata: a new owner removes only this cache's orphan files.
        directory.listFiles()?.filter { it.isFile && ownedFilename.matches(it.name) }?.forEach { it.delete() }
    }

    @Synchronized
    fun get(key: Key): File? {
        prune()
        return entries[digest(key)]?.file
    }

    /** Returns null for empty/oversized audio; invalid extensions are rejected before disk access. */
    @Synchronized
    fun put(key: Key, bytes: ByteArray, extension: String): File? {
        require(extension in setOf("wav", "mp3")) { "朗读音频格式必须为 wav 或 mp3" }
        prune()
        if (bytes.isEmpty() || bytes.size.toLong() > maxBytes) return null
        val id = digest(key)
        val file = File(directory, "cm-speech-$id-${UUID.randomUUID()}.$extension")
        try {
            file.writeBytes(bytes)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        remove(id)
        entries[id] = Entry(file, bytes.size.toLong(), clock())
        totalBytes += bytes.size
        while (entries.size > maxEntries || totalBytes > maxBytes) remove(entries.keys.first())
        return file
    }

    @Synchronized
    fun clear() {
        entries.keys.toList().forEach { remove(it) }
    }

    private fun prune() {
        val now = clock()
        entries.entries.filter { (_, entry) ->
            !entry.file.isFile || now - entry.createdAt >= ttlMillis || now < entry.createdAt
        }.map { it.key }.forEach { remove(it) }
    }

    private fun remove(id: String) {
        val entry = entries.remove(id) ?: return
        totalBytes -= entry.bytes
        entry.file.delete()
    }

    private fun digest(key: Key): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(key.coreUrl, key.mediaUrl, key.ownerId, key.text).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size.toString() + ":").toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
