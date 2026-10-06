package com.charactermemory.android.camera

import org.junit.Assert.*
import org.junit.Test

class CameraFramePolicyTest {
    @Test fun uprightPixelsUseSensorAndDisplayQuarterTurnsForBothLenses() {
        assertEquals(90, CameraFramePolicy.rotation(90, 0, false))
        assertEquals(180, CameraFramePolicy.rotation(90, 90, false))
        assertEquals(270, CameraFramePolicy.rotation(270, 0, true))
        assertEquals(180, CameraFramePolicy.rotation(270, 90, true))
    }
    @Test fun texturePreviewDoesNotRotateTheSensorTwice() {
        assertEquals(0, CameraFramePolicy.previewRotation(0))
        assertEquals(270, CameraFramePolicy.previewRotation(90))
        assertEquals(180, CameraFramePolicy.previewRotation(180))
        assertEquals(90, CameraFramePolicy.previewRotation(270))
        assertEquals(960 to 1280, CameraFramePolicy.previewBufferSize(1280, 960, 90))
        assertEquals(960 to 1280, CameraFramePolicy.previewBufferSize(1280, 960, 270))
        assertEquals(1280 to 960, CameraFramePolicy.previewBufferSize(1280, 960, 0))
        assertEquals(1280 to 960, CameraFramePolicy.previewBufferSize(1280, 960, 180))
    }
    @Test fun selectionRejectsOversizedStreamsAndFitsWithin1080() {
        assertEquals(720 to 540, CameraFramePolicy.fit(720, 540))
        assertEquals(1080 to 810, CameraFramePolicy.fit(4000, 3000))
        assertEquals(1920 to 1080, CameraFramePolicy.choose(listOf(8000 to 6000, 640 to 480, 1920 to 1080)))
    }
}
