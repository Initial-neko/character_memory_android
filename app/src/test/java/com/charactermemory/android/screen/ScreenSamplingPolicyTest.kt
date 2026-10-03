package com.charactermemory.android.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenSamplingPolicyTest {
    @Test fun noContentIsNeverUploaded() {
        assertFalse(ScreenSamplingPolicy.significant(null, intArrayOf()))
    }
    @Test fun firstFrameMayBeSent() {
        assertTrue(ScreenSamplingPolicy.significant(null, IntArray(1024) { 100 }))
    }
    @Test fun unchangedAndTinyUiChangesDoNotTrigger() {
        val old = IntArray(1024) { 100 }
        assertFalse(ScreenSamplingPolicy.significant(old, old.copyOf()))
        assertFalse(ScreenSamplingPolicy.significant(old, old.copyOf().also { for (n in 0..60) it[n] = 210 }))
    }
    @Test fun significantSceneChangeTriggers() {
        assertTrue(ScreenSamplingPolicy.significant(IntArray(1024) { 60 }, IntArray(1024) { 180 }))
    }
    @Test fun sizeChangeRequiresNewBaseline() {
        assertTrue(ScreenSamplingPolicy.significant(IntArray(20), IntArray(40)))
    }
    @Test fun smallLumaVariationDoesNotTrigger() {
        assertFalse(ScreenSamplingPolicy.significant(IntArray(1024) { 120 }, IntArray(1024) { 132 }))
    }
}
