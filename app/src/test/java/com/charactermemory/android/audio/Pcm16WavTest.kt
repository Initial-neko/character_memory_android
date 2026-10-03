package com.charactermemory.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Pcm16WavTest {
    @Test fun encodesMono16KhzPcm16HeaderAndPreservesLittleEndianSamples() {
        val pcm = byteArrayOf(0x34, 0x12, 0x00, 0x80.toByte())

        val wav = Pcm16Wav.encode(pcm)

        assertEquals(48, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals(40L, uint32(wav, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals(16L, uint32(wav, 16))
        assertEquals(1, uint16(wav, 20))
        assertEquals(1, uint16(wav, 22))
        assertEquals(16_000L, uint32(wav, 24))
        assertEquals(32_000L, uint32(wav, 28))
        assertEquals(2, uint16(wav, 32))
        assertEquals(16, uint16(wav, 34))
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(4L, uint32(wav, 40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test fun acceptsExactlyThirtySecondsWithinTheWavByteLimit() {
        val wav = Pcm16Wav.encode(ByteArray(Pcm16Wav.MAX_PCM_BYTES))

        assertEquals(480_000, Pcm16Wav.MAX_SAMPLE_COUNT)
        assertEquals(960_044, wav.size)
        assertEquals(960_000L, uint32(wav, 40))
    }

    @Test fun rejectsEmptyPcm() {
        assertThrows(IllegalArgumentException::class.java) { Pcm16Wav.encode(byteArrayOf()) }
    }

    @Test fun rejectsOddPcmByteCount() {
        assertThrows(IllegalArgumentException::class.java) { Pcm16Wav.encode(byteArrayOf(1)) }
    }

    @Test fun rejectsMoreThan480000Samples() {
        assertThrows(IllegalArgumentException::class.java) {
            Pcm16Wav.encode(ByteArray(Pcm16Wav.MAX_PCM_BYTES + 2))
        }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
}
