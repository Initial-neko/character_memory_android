package com.charactermemory.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPipPolicyTest {
    @Test
    fun entryRequiresSupportedActiveTimedCallOnCallScreen() {
        assertTrue(CallPipPolicy.mayEnter(true, true, 100L, true))
        assertFalse(CallPipPolicy.mayEnter(false, true, 100L, true))
        assertFalse(CallPipPolicy.mayEnter(true, false, 100L, true))
        assertFalse(CallPipPolicy.mayEnter(true, true, 0L, true))
        assertFalse(CallPipPolicy.mayEnter(true, true, 100L, false))
        assertFalse(CallPipPolicy.mayAutoEnter(true, false, 0L, false, 0))
        assertFalse(CallPipPolicy.mayAutoEnter(true, true, 100L, true, 1))
        assertTrue(CallPipPolicy.mayAutoEnter(true, true, 100L, true, 0))
    }

    @Test
    fun remoteActionOnlyTargetsTheCallThatCreatedIt() {
        assertTrue(CallPipPolicy.matchesCallAction(true, 100L, 100L))
        assertFalse(CallPipPolicy.matchesCallAction(false, 100L, 100L))
        assertFalse(CallPipPolicy.matchesCallAction(true, 100L, 200L))
        assertFalse(CallPipPolicy.matchesCallAction(true, 0L, 100L))
    }

    @Test
    fun dismissingPipEndsCallButReturningOrRecreatingDoesNot() {
        assertTrue(CallPipPolicy.shouldEndOnDismissal(true, true, false, true))
        assertFalse(CallPipPolicy.shouldEndOnDismissal(false, true, false, true))
        assertFalse(CallPipPolicy.shouldEndOnDismissal(true, false, false, true))
        assertFalse(CallPipPolicy.shouldEndOnDismissal(true, true, true, true))
        assertFalse(CallPipPolicy.shouldEndOnDismissal(true, true, false, false))
    }
}
