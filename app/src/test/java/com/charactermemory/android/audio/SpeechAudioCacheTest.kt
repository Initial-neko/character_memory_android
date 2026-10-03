package com.charactermemory.android.audio

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SpeechAudioCacheTest {
    private fun key(text: String = "private speech") = SpeechAudioCache.Key("https://core.example", "https://media.example", "character-a", text)
    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("speech-cache-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun hitsAreIsolatedAndFilenameContainsNoServerOwnerOrText() = withDirectory { directory ->
        val cache = SpeechAudioCache(directory)
        val file = cache.put(key(), byteArrayOf(1, 2), "wav")!!
        assertArrayEquals(byteArrayOf(1, 2), cache.get(key())!!.readBytes())
        assertFalse(file.name.contains("private"))
        assertFalse(file.name.contains("character-a"))
        assertFalse(file.name.contains("core"))
        assertNull(cache.get(key().copy(coreUrl = "https://other.example")))
        assertNull(cache.get(key().copy(mediaUrl = "https://other.example")))
        assertNull(cache.get(key().copy(ownerId = "character-b")))
        assertNull(cache.get(key("other")))
    }

    @Test fun ttlDeletesFilesAtBoundaryAndMissingFileIsMiss() = withDirectory { directory ->
        var now = 0L
        val cache = SpeechAudioCache(directory, clock = { now }, ttlMillis = 60)
        val file = cache.put(key(), byteArrayOf(1), "mp3")!!
        now = 59
        assertNotNull(cache.get(key()))
        now = 60
        assertNull(cache.get(key()))
        assertFalse(file.exists())
        val missing = cache.put(key(), byteArrayOf(2), "wav")!!
        missing.delete()
        assertNull(cache.get(key()))
    }

    @Test fun evictsLeastRecentlyUsedAtEntryAndByteBounds() = withDirectory { directory ->
        val cache = SpeechAudioCache(directory, maxEntries = 2, maxBytes = 4)
        val a = cache.put(key("a"), byteArrayOf(1, 2), "wav")!!
        val b = cache.put(key("b"), byteArrayOf(3, 4), "wav")!!
        cache.get(key("a"))
        cache.put(key("c"), byteArrayOf(5), "mp3")!!
        assertTrue(a.exists())
        assertFalse(b.exists())
        cache.put(key("d"), byteArrayOf(6, 7, 8, 9), "wav")!!
        assertFalse(a.exists())
        assertNull(cache.get(key("c")))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun rejectsInvalidAudioWithoutLosingExistingEntryAndReplacementRemovesOldFile() = withDirectory { directory ->
        val cache = SpeechAudioCache(directory, maxBytes = 2)
        val previous = cache.put(key(), byteArrayOf(1), "wav")!!
        assertNull(cache.put(key(), byteArrayOf(), "wav"))
        assertNull(cache.put(key(), byteArrayOf(1, 2, 3), "wav"))
        assertArrayEquals(byteArrayOf(1), cache.get(key())!!.readBytes())
        try { cache.put(key(), byteArrayOf(1), "../wav"); fail("invalid extension accepted") } catch (_: IllegalArgumentException) { }
        val replacement = cache.put(key(), byteArrayOf(2), "mp3")!!
        assertFalse(previous.exists())
        assertArrayEquals(byteArrayOf(2), replacement.readBytes())
        cache.clear()
        assertFalse(replacement.exists())
        assertNull(cache.get(key()))
    }

    @Test fun cleanupNeverDeletesUnownedFiles() = withDirectory { root ->
        val other = File(root, "other.txt").apply { writeText("keep") }
        val owned = File(root, "speech-audio")
        val unrelated = File(owned, "other.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        val cache = SpeechAudioCache(owned)
        cache.put(key(), byteArrayOf(1), "wav")!!
        cache.clear()
        assertTrue(other.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun restartRemovesOnlyOrphanedCacheFiles() = withDirectory { directory ->
        val first = SpeechAudioCache(directory)
        val file = first.put(key(), byteArrayOf(1), "wav")!!
        val other = File(directory, "untouched").apply { writeText("keep") }
        val restarted = SpeechAudioCache(directory)
        assertFalse(file.exists())
        assertTrue(other.exists())
        assertNull(restarted.get(key()))
    }

    @Test fun concurrentReplacementNeverLeaksFilesOrExceedsCapacity() = withDirectory { directory ->
        val cache = SpeechAudioCache(directory, maxEntries = 2, maxBytes = 4)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val writes = (0 until 24).map { index ->
                pool.submit<File?> { cache.put(key("text-${index % 3}"), byteArrayOf(index.toByte(), 1), "wav") }
            }
            writes.forEach { assertNotNull(it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
            val files = directory.listFiles()!!
            assertEquals(2, files.size)
            assertEquals(4L, files.sumOf { it.length() })
            cache.clear()
            assertEquals(0, directory.listFiles()!!.size)
        } finally { pool.shutdownNow() }
    }
}
