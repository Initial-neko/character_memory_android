package com.charactermemory.android.data

import com.google.gson.JsonObject
import java.time.OffsetDateTime

/** Core 508c6f0: history id is durable; SSE id is a separate replay cursor. */
object ConversationProjection {
    fun merge(existing: List<JsonObject>, incoming: List<JsonObject>): List<JsonObject> {
        val messages = linkedMapOf<String, JsonObject>()
        (existing + incoming).forEach { message ->
            val id = message.text("id")
            if (id.isNotBlank()) {
                val merged = messages[id]?.deepCopy() ?: JsonObject()
                message.entrySet().forEach { (key, value) ->
                    // Absent SSE keys preserve enrichment; explicit history null clears it.
                    merged.add(key, value.deepCopy())
                }
                messages[id] = merged
            }
        }
        // Stable time order works for integer and opaque group IDs without coercing IDs.
        return messages.values.sortedWith(compareBy {
            runCatching { OffsetDateTime.parse(it.text("event_time")).toInstant() }.getOrNull()
        })
    }

    fun message(type: String, payload: JsonObject): JsonObject? {
        if (type != "character_event" && type != "group_character_event") return null
        if (payload.text("id").isBlank()) return null
        val result = payload.deepCopy()
        val metadata = payload.obj("metadata")
        val user = if (type == "group_character_event") payload.text("actor_type") == "USER"
            else payload.text("event_type") == "USER_MESSAGE"
        result.addProperty("role", if (user) "user" else "assistant")
        if (user && metadata.has("display_text") && !metadata.get("display_text").isJsonNull) result.add("content", metadata.get("display_text").deepCopy())
        listOf("action", "action_index", "sticker_id", "image_id", "media_id", "source_event_id", "source_event_type", "source_conversation_event_id", "mentions", "voice", "voice_url", "voice_status", "voice_media_id", "voice_duration_ms", "voice_error").forEach { key ->
            if (metadata.has(key)) result.add(key, metadata.get(key).deepCopy())
        }
        val mediaId = result.text("media_id")
        val imageId = result.text("image_id")
        val characterId = result.text("character_id", result.text("actor_id"))
        if (!result.get("image").let { it != null && it.isJsonObject }) {
            when {
                mediaId.isNotBlank() -> result.add("image", jsonObject("id" to mediaId, "url" to "/v1/media/${segment(mediaId)}"))
                imageId.isNotBlank() && characterId.isNotBlank() -> result.add("image", jsonObject("id" to imageId, "label" to metadata.text("image_label"), "url" to "/v1/images/${segment(characterId)}/${segment(imageId)}/asset"))
            }
        }
        val stickerId = result.text("sticker_id")
        if (stickerId.isNotBlank() && !result.get("sticker").let { it != null && it.isJsonObject }) {
            result.add("sticker", jsonObject("id" to stickerId, "label" to metadata.text("sticker_label"), "url" to "/v1/stickers/${segment(stickerId)}/asset"))
        }
        return result
    }

    fun reaction(current: String, type: String, payload: JsonObject): String = when (type) {
        "reaction_status" -> payload.text("state").takeIf { it in setOf("queued", "typing", "idle", "superseded") } ?: current
        "reaction_complete" -> if (payload.flag("silent")) "silent" else "idle"
        "reaction_error" -> "error"
        else -> current // One group member finishing does not finish the entire turn.
    }

    private fun segment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
