package com.charactermemory.android.audio

import org.junit.Assert.*
import org.junit.Test

class PcmSpeechSegmenterTest {
    @Test fun silenceProducesNoTranscriptAndSpeechEndsAfterNineHundredMs() {
        val gate = PcmSpeechSegmenter()
        repeat(100) { assertNull(gate.accept(ShortArray(160), 160)) }
        assertTrue(gate.finish().isEmpty())
        repeat(30) { assertNull(gate.accept(ShortArray(160) { 2000 }, 160)) }
        repeat(89) { assertNull(gate.accept(ShortArray(160), 160)) }
        val segment = gate.accept(ShortArray(160), 160)
        assertNotNull(segment)
        assertTrue(segment!!.any { it != 0.toByte() })
        assertTrue(segment.size <= 12 * 16000 * 2)
    }
    @Test fun shortNoiseIsDiscardedAndContinuousSpeechIsBounded() {
        val gate = PcmSpeechSegmenter()
        repeat(10) { gate.accept(ShortArray(160) { 2000 }, 160) }
        repeat(90) { gate.accept(ShortArray(160), 160) }
        assertTrue(gate.finish().isEmpty())
        val continuous = PcmSpeechSegmenter()
        var result: ByteArray? = null
        repeat(1200) { if (result == null) result = continuous.accept(ShortArray(160) { 2000 }, 160) }
        assertEquals(12 * 16000 * 2, result!!.size)
    }
}
