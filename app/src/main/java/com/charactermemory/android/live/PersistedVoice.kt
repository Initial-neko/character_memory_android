package com.charactermemory.android.live

import com.charactermemory.android.data.text
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.util.Locale

internal enum class PersistedVoiceStatus { PENDING, READY, FAILED, UNKNOWN }

internal data class PersistedVoicePresentation(
    val status: PersistedVoiceStatus,
    val mediaId: String?,
    val durationMs: Long?,
    val error: String?
) {
    val canPlay: Boolean get() = status == PersistedVoiceStatus.READY && !mediaId.isNullOrBlank()
}
internal fun persistedVoicePresentation(message: JsonObject): PersistedVoicePresentation {
    val status = when (message.text("voice_status").lowercase(Locale.ROOT)) {
        "pending" -> PersistedVoiceStatus.PENDING
        "ready" -> PersistedVoiceStatus.READY
        "failed" -> PersistedVoiceStatus.FAILED
        else -> PersistedVoiceStatus.UNKNOWN
    }
    val mediaId = message.text("voice_media_id").takeIf { it.isNotBlank() }
    val durationMs = runCatching {
        message.get("voice_duration_ms")?.takeUnless { it.isJsonNull }?.asLong
    }.getOrNull()?.takeIf { it >= 0L }
    val error = message.text("voice_error").takeIf { it.isNotBlank() }
    return PersistedVoicePresentation(status, mediaId, durationMs, error)
}

internal fun voiceMediaPath(mediaId: String?): String? {
    val id = mediaId?.takeIf { it.isNotBlank() } ?: return null
    return "/v1/media/${URLEncoder.encode(id, "UTF-8").replace("+", "%20")}"
}
