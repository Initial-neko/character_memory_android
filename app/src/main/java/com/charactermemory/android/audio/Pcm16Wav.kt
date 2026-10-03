package com.charactermemory.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encodes bounded mono, 16 kHz, signed PCM16 samples as a RIFF/WAVE file. */
object Pcm16Wav {
    const val SAMPLE_RATE_HZ = 16_000
    const val MAX_DURATION_MS = 30_000
    const val MAX_SAMPLE_COUNT = SAMPLE_RATE_HZ * MAX_DURATION_MS / 1_000
    const val MAX_PCM_BYTES = MAX_SAMPLE_COUNT * 2
    const val HEADER_BYTES = 44
    const val MAX_WAV_BYTES = HEADER_BYTES + MAX_PCM_BYTES

    /** [pcm] contains interleaved mono samples as already encoded little-endian bytes. */
    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.isNotEmpty()) { "PCM audio must not be empty" }
        require(pcm.size % 2 == 0) { "PCM16 audio must contain whole samples" }
        require(pcm.size / 2 <= MAX_SAMPLE_COUNT) { "PCM audio exceeds 30 seconds" }

        val wav = ByteBuffer.allocate(HEADER_BYTES + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray(Charsets.US_ASCII))
        wav.putInt(36 + pcm.size)
        wav.put("WAVE".toByteArray(Charsets.US_ASCII))
        wav.put("fmt ".toByteArray(Charsets.US_ASCII))
        wav.putInt(16)
        wav.putShort(1) // PCM format
        wav.putShort(1) // mono
        wav.putInt(SAMPLE_RATE_HZ)
        wav.putInt(SAMPLE_RATE_HZ * 2) // byte rate: 16,000 samples/s * 2 bytes/sample
        wav.putShort(2) // block align: one 16-bit channel
        wav.putShort(16)
        wav.put("data".toByteArray(Charsets.US_ASCII))
        wav.putInt(pcm.size)
        wav.put(pcm)
        return wav.array()
    }
}
