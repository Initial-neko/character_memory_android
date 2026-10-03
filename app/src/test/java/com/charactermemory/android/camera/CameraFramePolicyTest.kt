package com.charactermemory.android.camera

import org.junit.Assert.*
import org.junit.Test

class CameraFramePolicyTest {
    @Test fun uprightPixelsUseSensorAndDisplayQuarterTurnsForBothLenses() {
        assertEquals(90, CameraFramePolicy.rotation(90, 0, false))
        assertEquals(180, CameraFramePolicy.rotation(90, 90, false))
        assertEquals(90, CameraFramePolicy.rotation(270, 0, true))
        assertEquals(180, CameraFramePolicy.rotation(270, 90, true))
    }
    @Test fun selectionRejectsOversizedStreamsAndFitsWithin1080() {
        assertEquals(720 to 540, CameraFramePolicy.fit(720, 540))
        assertEquals(1080 to 810, CameraFramePolicy.fit(4000, 3000))
        assertEquals(1920 to 1080, CameraFramePolicy.choose(listOf(8000 to 6000, 640 to 480, 1920 to 1080)))
    }
}
