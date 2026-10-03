package com.charactermemory.android.live

import org.junit.Assert.*
import org.junit.Test

class SpaceReplyWindowTest {
    @Test fun expiresNinetySecondsAfterConfirmationEvenWhenReopened() {
        val window = SpaceReplyWindow(1_000L)
        assertTrue(window.isOpen(1_000L))
        assertTrue(window.isOpen(90_999L))
        assertFalse(window.isOpen(91_000L))
        // Reusing the confirmed receipt after navigation cannot renew its window.
        assertFalse(window.isOpen(100_000L))
        assertFalse(window.isOpen(200_000L))
    }

    @Test fun timeBeforeConfirmationDoesNotOpenAWindow() {
        assertFalse(SpaceReplyWindow(1_000L).isOpen(999L))
    }
}
