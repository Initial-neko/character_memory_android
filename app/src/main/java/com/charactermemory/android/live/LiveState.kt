package com.charactermemory.android.live

import com.charactermemory.android.data.ServerConfig
import com.charactermemory.android.data.flag
import com.charactermemory.android.data.text
import com.google.gson.JsonObject
import java.security.MessageDigest

enum class LivePage { HOME, CHAT, SETTINGS, CHARACTER, ENSEMBLE, SPACE, IMAGE, DETAILS }
data class ChatTarget(val id: String, val name: String, val group: Boolean, val conversationId: String,
    val memberIds: List<String> = emptyList())

data class LiveState(
    val config: ServerConfig,
    val page: LivePage = LivePage.HOME,
    val characters: List<JsonObject> = emptyList(),
    val summaries: Map<String, JsonObject> = emptyMap(),
    val groups: List<JsonObject> = emptyList(),
    val avatars: Map<String, String> = emptyMap(),
    val coreHealth: String = "未检查", val mediaHealth: String = "未检查",
    val target: ChatTarget? = null,
    val messages: List<JsonObject> = emptyList(),
    val historyCursor: String? = null,
    val streamStatus: String = "未连接", val reaction: String = "idle",
    val reactionError: String? = null,
    val memberProgress: List<JsonObject> = emptyList(),
    val busy: Set<String> = emptySet(),
    val error: String? = null, val notice: String? = null,
    val draft: JsonObject? = null, val capacityConfirmation: String? = null,
    val build: JsonObject? = null, val selectedMembers: Set<Int> = emptySet(),
    val persona: JsonObject? = null,
    val posts: List<JsonObject> = emptyList(), val spaceCursor: String? = null, val spacePaged: Boolean = false,
    val imagePrompt: String = "", val imageDraft: JsonObject? = null,
    val stickers: List<JsonObject> = emptyList(),
    val composeText: String = "", val characterPrompt: String = "", val ensemblePrompt: String = "",
    val imageInstruction: String = "", val imageCharacterId: String = "", val imagePurpose: String = "SCENE",
    val imageUseAvatar: Boolean = false
)

/** Ephemeral results are only valid in the session in which they were requested. */
class GenerationFence {
    var current: Long = 0L
        private set
    fun advance(): Long { current += 1; return current }
    fun accepts(generation: Long): Boolean = generation == current
}

object LiveRules {
    fun directKey(normalizedCore: String, characterId: String): String = "direct-" +
        MessageDigest.getInstance("SHA-256").digest((normalizedCore + "\u0000" + characterId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun mergeRecords(existing: List<JsonObject>, incoming: List<JsonObject>): List<JsonObject> {
        val records = linkedMapOf<String, JsonObject>()
        (existing + incoming).forEach { if (it.text("id").isNotBlank()) records[it.text("id")] = it }
        return records.values.toList()
    }
    fun nextCursor(page: JsonObject): String? = page.text("next_before_id").takeIf { page.flag("has_more") && it.isNotBlank() }
    fun refreshRecords(existing: List<JsonObject>, newest: List<JsonObject>): List<JsonObject> {
        val newIds = newest.map { it.text("id") }.toSet()
        return mergeRecords(emptyList(), newest + existing.filterNot { it.text("id") in newIds })
    }
}

