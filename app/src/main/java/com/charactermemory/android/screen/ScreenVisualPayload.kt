package com.charactermemory.android.screen

import com.charactermemory.android.data.jsonObject
import com.google.gson.JsonObject
import java.util.Base64

/** Core-owned JSON contract: visual frames are transient, not chat attachments. */
object ScreenVisualPayload {
    private fun frame(bytes: ByteArray): JsonObject = jsonObject(
        "filename" to "android-display.jpg",
        "source" to "DISPLAY",
        "captured_at_ms" to System.currentTimeMillis(),
        "data_url" to "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
    )

    fun observation(character: String, conversation: String, jpeg: ByteArray): JsonObject =
        jsonObject("character_id" to character, "conversation_id" to conversation, "visual_frame" to frame(jpeg))

    fun directQuestion(character: String, conversation: String, jpeg: ByteArray): JsonObject =
        jsonObject("character_id" to character, "conversation_id" to conversation,
            "message" to "请看看我当前共享的手机画面，说说你注意到了什么。",
            "visual_frames" to listOf(frame(jpeg)))
}
