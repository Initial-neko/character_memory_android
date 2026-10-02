package com.charactermemory.android.media

import org.junit.Assert.*
import org.junit.Test

class WavPcm16Test {
    @Test fun pcm16WavHeaderMatchesMono16kAudioAndDoesNotChangeSamples() {
        val samples = byteArrayOf(1, 0, -1, 127, 0, -128)
        val wav = WavPcm16.encode(samples)
        fun ascii(from: Int, until: Int) = String(wav.copyOfRange(from, until), Charsets.US_ASCII)
        fun le(offset: Int, width: Int): Int =
            (0 until width).fold(0) { number, index -> number or ((wav[offset + index].toInt() and 255) shl (index * 8)) }
        assertEquals("RIFF", ascii(0, 4))
        assertEquals("WAVE", ascii(8, 12))
        assertEquals("fmt ", ascii(12, 16))
        assertEquals("data", ascii(36, 40))
        assertEquals(36 + samples.size, le(4, 4))
        assertEquals(1, le(20, 2))
        assertEquals(1, le(22, 2))
        assertEquals(16_000, le(24, 4))
        assertEquals(32_000, le(28, 4))
        assertEquals(2, le(32, 2))
        assertEquals(16, le(34, 2))
        assertEquals(samples.size, le(40, 4))
        assertArrayEquals(samples, wav.copyOfRange(44, wav.size))
    }

    @Test fun rejectsEmptyOddSizedAndOversizedPcm() {
        assertThrows(IllegalArgumentException::class.java) { WavPcm16.encode(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { WavPcm16.encode(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { WavPcm16.encode(ByteArray(4 * 1024 * 1024)) }
    }

    @Test fun recordsNoSecretsAndPreservesSpecifiedSampleRate() {
        val wav = WavPcm16.encode(byteArrayOf(0, 1), 24_000)
        assertEquals(24_000, (wav[24].toInt() and 255) or ((wav[25].toInt() and 255) shl 8))
    }
}
