package com.charactermemory.android.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ConversationProjectionTest {
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject

    @Test fun helpersTolerateNullWrongTypesAndOptionalFields() {
        val value = json("{\"text\":{},\"object\":null,\"items\":[null,12,{},\"a\"],\"flag\":{},\"number\":\"not-number\"}")
        assertEquals("fallback", value.text("text", "fallback"))
        assertTrue(value.obj("object").entrySet().isEmpty())
        assertNull(value.objOrNull("object"))
        assertNull(value.objOrNull("items"))
        assertNotNull(value.objOrNull("text"))
        assertEquals(1, value.items("items").size)
        assertFalse(value.flag("flag"))
        assertEquals(3L, value.number("number", 3L))
        assertTrue(jsonObject("value" to null).get("value").isJsonNull)
    }

    @Test fun directSseRetainsDurableIdAndMetadataAttachments() {
        val result = ConversationProjection.message("character_event", json("""{"id":44,"character_id":"rin","event_type":"CHARACTER_MESSAGE","event_time":"2026-10-02T10:00:00+08:00","content":"reply","metadata":{"source_event_id":12,"action":"IMAGE","media_id":"img-1","action_index":0}}"""))!!
        assertEquals("44", result.text("id"))
        assertEquals("12", result.text("source_event_id"))
        assertEquals("assistant", result.text("role"))
        assertEquals("/v1/media/img-1", result.obj("image").text("url"))
    }

    @Test fun groupSsePreservesOpaqueIdentityAndSpeaker() {
        val result = ConversationProjection.message("group_character_event", json("""{"id":"group-event:009","conversation_id":"group-1","turn_id":"turn-1","actor_type":"CHARACTER","actor_id":"rin","event_time":"2026-10-02T10:00:00Z","content":"group reply","metadata":{"source_conversation_event_id":"user:2","sticker_id":"wave"}}"""))!!
        assertEquals("group-event:009", result.text("id"))
        assertEquals("rin", result.text("actor_id"))
        assertEquals("group-1", result.text("conversation_id"))
        assertEquals("/v1/stickers/wave/asset", result.obj("sticker").text("url"))
    }

    @Test fun reconciliationDeduplicatesExactDurableIdsAndEnrichesWithoutMergingResponses() {
        val events = listOf(json("{\"id\":44,\"source_event_id\":12,\"content\":\"one\",\"event_time\":\"2026-10-02T10:00:00Z\"}"), json("{\"id\":45,\"source_event_id\":12,\"content\":\"two\",\"event_time\":\"2026-10-02T10:00:01Z\"}"))
        val history = listOf(json("{\"id\":44,\"content\":\"one\",\"image\":{\"url\":\"/v1/media/a\"},\"event_time\":\"2026-10-02T10:00:00Z\"}"), json("{\"id\":\"044\",\"content\":\"opaque\",\"event_time\":\"2026-10-02T10:00:02Z\"}"), json("{\"content\":\"not durable\"}"))
        val result = ConversationProjection.merge(events, history)
        assertEquals(listOf("44", "45", "044"), result.map { it.text("id") })
        assertEquals("/v1/media/a", result[0].obj("image").text("url"))
        assertEquals(result, ConversationProjection.merge(result, history))
        assertFalse(events[0].has("image"))
    }

    @Test fun silenceErrorsAndMemberCompletionNeverFabricateOrDiscardMessages() {
        assertNull(ConversationProjection.message("reaction_error", json("{\"message\":\"failure\"}")))
        assertEquals("typing", ConversationProjection.reaction("queued", "reaction_status", json("{\"state\":\"typing\"}")))
        assertEquals("typing", ConversationProjection.reaction("typing", "group_member_complete", json("{\"silent\":true}")))
        assertEquals("silent", ConversationProjection.reaction("typing", "reaction_complete", json("{\"silent\":true}")))
        assertEquals("error", ConversationProjection.reaction("typing", "reaction_error", json("{}")))
        assertEquals("error", ConversationProjection.reaction("error", "reaction_status", json("{\"state\":null}")))
    }

    @Test fun authoritativeHistoryNullClearsAttachmentsWhileSparseSsePreservesThem() {
        val stored = json("""{"id":44,"content":"reply","image":{"url":"/v1/media/old"},"sticker":{"url":"/v1/stickers/old/asset"}}""")
        val sparse = ConversationProjection.message("character_event", json("""{"id":44,"content":"reply","event_type":"CHARACTER_MESSAGE","metadata":{}}"""))!!
        val afterSparse = ConversationProjection.merge(listOf(stored), listOf(sparse))
        assertEquals("/v1/media/old", afterSparse.single().obj("image").text("url"))
        assertEquals("/v1/stickers/old/asset", afterSparse.single().obj("sticker").text("url"))
        val history = json("""{"id":44,"content":"reply","image":null,"sticker":null}""")
        val reconciled = ConversationProjection.merge(afterSparse, listOf(history)).single()
        assertTrue(reconciled.get("image").isJsonNull)
        assertTrue(reconciled.get("sticker").isJsonNull)
        assertNotNull(stored.objOrNull("image"))
        assertNotNull(stored.objOrNull("sticker"))
    }
}
