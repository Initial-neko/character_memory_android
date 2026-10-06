package com.charactermemory.android.camera

import org.junit.Assert.*
import org.junit.Test

class CallCameraFramesTest {
    @Test fun expiresAndNeverCrossesCallOrCameraOwnership() {
        val frames = CallCameraFrames()
        frames.update(1, 2, 3, 100, byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), frames.latest(1, 2, 3, 10_100))
        assertNull(frames.latest(1, 2, 3, 10_101))
        assertNull(frames.latest(1, 2, 3, 99))
        assertNull(frames.latest(2, 2, 3, 100))
        assertNull(frames.latest(1, 3, 3, 100))
        assertNull(frames.latest(1, 2, 4, 100))
        frames.clear()
        assertNull(frames.latest(1, 2, 3, 100))
    }
    @Test fun retainsOnlyLatestFrameAndDoesNotExposeMutableOwnedBytes() {
        val frames = CallCameraFrames()
        val bytes = byteArrayOf(1)
        frames.update(1, 2, 3, 0, bytes)
        bytes[0] = 8
        assertEquals(1, requireNotNull(frames.latest(1, 2, 3, 0))[0].toInt())
        requireNotNull(frames.latest(1, 2, 3, 0))[0] = 9
        assertEquals(1, requireNotNull(frames.latest(1, 2, 3, 0))[0].toInt())
        frames.update(1, 2, 3, 1, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), frames.latest(1, 2, 3, 1))
    }
    @Test fun rejectsUnboundedOrEmptyFrames() {
        for (bytes in listOf(byteArrayOf(), ByteArray(2 * 1024 * 1024 + 1))) {
            try { CallCameraFrames().update(1, 2, 3, 0, bytes); fail("Invalid frame accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
