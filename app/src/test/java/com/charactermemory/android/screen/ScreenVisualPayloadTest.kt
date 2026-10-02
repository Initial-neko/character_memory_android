package com.charactermemory.android.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenVisualPayloadTest {
    @Test fun manualMessageTargetsOneCharacterAndIncludesOneDisplayFrame() {
        val body = ScreenVisualPayload.directQuestion("rin", "session-1", byteArrayOf(1, 2, 3))
        assertEquals("rin", body.get("character_id").asString)
        assertEquals("session-1", body.get("conversation_id").asString)
        assertTrue(body.get("message").asString.isNotBlank())
        val frames = body.getAsJsonArray("visual_frames")
        assertEquals(1, frames.size())
        assertEquals("DISPLAY", frames[0].asJsonObject.get("source").asString)
        assertTrue(frames[0].asJsonObject.get("data_url").asString.startsWith("data:image/jpeg;base64,"))
    }

    @Test fun periodicObservationNeverPretendsToBeUserMessage() {
        val body = ScreenVisualPayload.observation("rin", "session-1", byteArrayOf(9))
        assertFalse(body.has("message"))
        assertFalse(body.has("visual_frames"))
        assertEquals("DISPLAY", body.getAsJsonObject("visual_frame").get("source").asString)
    }
}
