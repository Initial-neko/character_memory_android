package com.charactermemory.android.audio

import org.junit.Assert.*
import org.junit.Test

class AudioRequestFenceTest {
    @Test fun newSpeechCancelsPreviousAndLateCompletionCannotClaimPlayback() {
        val gate = AudioRequestFence()
        var cancelled = 0
        val old = gate.begin("first") { cancelled++ }
        val fresh = gate.begin("second") { }
        assertEquals(1, cancelled)
        assertFalse(gate.consume(old))
        assertTrue(gate.consume(fresh))
        assertFalse(gate.consume(fresh))
    }
    @Test fun recordOrNavigationInvalidationCancelsSynthesisAndRejectsItsLateResult() {
        val gate = AudioRequestFence()
        var cancelled = false
        val request = gate.begin("speech") { cancelled = true }
        gate.cancelAll()
        assertTrue(cancelled)
        assertFalse(gate.consume(request))
    }
    @Test fun disposingAnUnrelatedBubbleDoesNotCancelCurrentSpeech() {
        val gate = AudioRequestFence()
        val request = gate.begin("current") { fail("unrelated owner must not cancel") }
        gate.cancelOwner("other")
        assertTrue(gate.consume(request))
    }
    @Test fun everyLossIncludingPermanentMustStopPlayback() {
        assertTrue(shouldStopForAudioFocus(-1))
        assertTrue(shouldStopForAudioFocus(-2))
        assertTrue(shouldStopForAudioFocus(-3))
        assertFalse(shouldStopForAudioFocus(1))
    }
}
