package com.charactermemory.android.live

import org.junit.Assert.*
import org.junit.Test

class CallStageTest {
    @Test fun sharingKeepsCharacterSelectionAndRestoresItAfterStop() {
        val state = CallStageState(CallStageMode.LIVE2D)
        assertEquals(CallStageMode.SCREEN_SHARE, state.mode(sharing = true, camera = false))
        assertTrue(state.hasCharacterOverlay(sharing = true, camera = false))
        assertEquals(CallStageMode.LIVE2D, state.mode(sharing = false, camera = false))
        assertEquals(CallStageMode.VIDEO, state.mode(sharing = false, camera = true))
    }
}
