package com.charactermemory.android.live

import com.charactermemory.android.data.ConversationProjection
import com.charactermemory.android.data.text
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedVoiceMessageTest {
    @Test fun latePendingHistoryCannotEraseReadySseButAFreshFailureCanClearAudio() {
        val pending = json("""{"id":44,"voice_status":"pending","voice_media_id":null,"voice_error":null}""")
        val ready = json("""{"id":44,"voice_status":"ready","voice_media_id":"asset-1","voice_duration_ms":1500,"voice_error":null}""")
        val reconciled = ConversationProjection.reconcileHistory(listOf(ready), listOf(pending), listOf(pending)).single()
        assertEquals("ready", reconciled.text("voice_status"))
        assertEquals("asset-1", reconciled.text("voice_media_id"))
        val failed = json("""{"id":44,"voice_status":"failed","voice_media_id":null,"voice_duration_ms":null,"voice_error":"asset failed"}""")
        val fresh = ConversationProjection.reconcileHistory(listOf(reconciled), listOf(failed), listOf(reconciled)).single()
        assertEquals("failed", fresh.text("voice_status"))
        assertTrue(fresh.get("voice_media_id").isJsonNull)
    }
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject

    @Test fun directAndGroupSsePromoteAllFourCanonicalVoiceFields() {
        val direct = ConversationProjection.message("character_event", json(
            """{"id":44,"event_type":"CHARACTER_MESSAGE","event_time":"2026-10-02T10:00:00Z","content":"hello","metadata":{"action":"VOICE_MESSAGE","voice_status":"pending","voice_media_id":null,"voice_duration_ms":null,"voice_error":null}}"""
        ))!!
        val group = ConversationProjection.message("group_character_event", json(
            """{"id":"group:voice:1","actor_type":"CHARACTER","actor_id":"rin","event_time":"2026-10-02T10:00:00Z","content":"hello","metadata":{"action":"VOICE_MESSAGE","voice_status":"ready","voice_media_id":"asset-1","voice_duration_ms":1200,"voice_error":null}}"""
        ))!!

        assertEquals("VOICE_MESSAGE", direct.text("action"))
        assertEquals("pending", direct.text("voice_status"))
        assertTrue(direct.get("voice_media_id").isJsonNull)
        assertTrue(direct.get("voice_duration_ms").isJsonNull)
        assertTrue(direct.get("voice_error").isJsonNull)
        assertEquals("group:voice:1", group.text("id"))
        assertEquals("ready", group.text("voice_status"))
        assertEquals("asset-1", group.text("voice_media_id"))
        assertEquals(1200L, group.get("voice_duration_ms").asLong)
        assertTrue(group.get("voice_error").isJsonNull)

        val history = ConversationProjection.merge(emptyList(), listOf(json(
            """{"id":44,"action":"VOICE_MESSAGE","voice_status":"ready","voice_media_id":"asset-history","voice_duration_ms":900,"voice_error":null}"""
        ))).single()
        assertEquals("ready", history.text("voice_status"))
        assertEquals("asset-history", history.text("voice_media_id"))
        assertEquals(900L, history.get("voice_duration_ms").asLong)
        assertTrue(history.get("voice_error").isJsonNull)
    }

    @Test fun voiceTransitionsMergeByDurableIdAndRespectSparseAndExplicitNullFields() {
        val pending = ConversationProjection.message("character_event", json(
            """{"id":44,"event_type":"CHARACTER_MESSAGE","event_time":"2026-10-02T10:00:00Z","content":"hello","metadata":{"action":"VOICE_MESSAGE","voice_status":"pending","voice_media_id":null,"voice_duration_ms":null,"voice_error":null}}"""
        ))!!
        val ready = ConversationProjection.message("character_event", json(
            """{"id":44,"event_type":"CHARACTER_MESSAGE","event_time":"2026-10-02T10:00:00Z","content":"hello","metadata":{"action":"VOICE_MESSAGE","voice_status":"ready","voice_media_id":"asset-1","voice_duration_ms":1200,"voice_error":null}}"""
        ))!!
        val afterReady = ConversationProjection.merge(listOf(pending), listOf(ready))
        assertEquals(1, afterReady.size)
        assertEquals("hello", afterReady.single().text("content"))
        assertEquals("ready", afterReady.single().text("voice_status"))
        assertEquals("asset-1", afterReady.single().text("voice_media_id"))

        val sparse = ConversationProjection.message("character_event", json(
            """{"id":44,"event_type":"CHARACTER_MESSAGE","event_time":"2026-10-02T10:00:00Z","content":"hello","metadata":{"voice_status":"ready"}}"""
        ))!!
        val afterSparse = ConversationProjection.merge(afterReady, listOf(sparse)).single()
        assertEquals("asset-1", afterSparse.text("voice_media_id"))
        assertEquals(1200L, afterSparse.get("voice_duration_ms").asLong)

        val failedSse = ConversationProjection.message("character_event", json(
            """{"id":44,"event_type":"CHARACTER_MESSAGE","event_time":"2026-10-02T10:00:00Z","content":"hello","metadata":{"voice_status":"failed","voice_media_id":null,"voice_duration_ms":null,"voice_error":"synthesis unavailable"}}"""
        ))!!
        val pendingToFailed = ConversationProjection.merge(listOf(pending), listOf(failedSse)).single()
        assertEquals("failed", pendingToFailed.text("voice_status"))
        assertEquals("hello", pendingToFailed.text("content"))
        assertEquals("synthesis unavailable", pendingToFailed.text("voice_error"))

        val failedHistory = json(
            """{"id":44,"event_time":"2026-10-02T10:00:00Z","content":"hello","voice_status":"failed","voice_media_id":null,"voice_duration_ms":null,"voice_error":"synthesis unavailable"}"""
        )
        val failed = ConversationProjection.merge(listOf(afterSparse), listOf(failedHistory)).single()
        assertEquals("failed", failed.text("voice_status"))
        assertTrue(failed.get("voice_media_id").isJsonNull)
        assertTrue(failed.get("voice_duration_ms").isJsonNull)
        assertEquals("synthesis unavailable", failed.text("voice_error"))
    }

    @Test fun voicePresentationKeepsTextAndOnlyEnablesAReadyMessageWithAnAssetId() {
        val pending = voice("pending")
        val ready = voice("ready", "voice/id 1", 1500)
        val missingAsset = voice("ready")
        val failed = voice("failed", error = "synthesis unavailable")
        val unknown = voice("processing")
        val absent = voice(null)

        assertEquals(PersistedVoiceStatus.PENDING, persistedVoicePresentation(pending).status)
        assertFalse(persistedVoicePresentation(pending).canPlay)
        assertEquals(PersistedVoiceStatus.READY, persistedVoicePresentation(ready).status)
        assertTrue(persistedVoicePresentation(ready).canPlay)
        assertEquals("/v1/media/voice%2Fid%201", voiceMediaPath(persistedVoicePresentation(ready).mediaId))
        assertEquals(PersistedVoiceStatus.READY, persistedVoicePresentation(missingAsset).status)
        assertFalse(persistedVoicePresentation(missingAsset).canPlay)
        assertEquals(PersistedVoiceStatus.FAILED, persistedVoicePresentation(failed).status)
        assertEquals("synthesis unavailable", persistedVoicePresentation(failed).error)
        assertEquals(PersistedVoiceStatus.UNKNOWN, persistedVoicePresentation(unknown).status)
        assertEquals(PersistedVoiceStatus.UNKNOWN, persistedVoicePresentation(absent).status)
        assertEquals("hello", ready.text("content"))
        assertNull(voiceMediaPath(null))
        assertNull(voiceMediaPath(" "))
    }

    private fun voice(status: String?, mediaId: String? = null, durationMs: Long? = null, error: String? = null) =
        JsonObject().apply {
            addProperty("action", "VOICE_MESSAGE")
            addProperty("content", "hello")
            status?.let { addProperty("voice_status", it) }
            mediaId?.let { addProperty("voice_media_id", it) }
            durationMs?.let { addProperty("voice_duration_ms", it) }
            error?.let { addProperty("voice_error", it) }
        }
}
