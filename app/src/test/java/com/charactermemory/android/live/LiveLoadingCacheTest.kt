package com.charactermemory.android.live

import com.charactermemory.android.data.ConversationProjection
import com.charactermemory.android.data.jsonObject
import com.charactermemory.android.data.text
import org.junit.Assert.*
import org.junit.Test

class LiveLoadingCacheTest {
    private fun key(server: String = "https://core.example", id: String = "a", group: Boolean = false, conversation: String = "one") =
        ConversationCacheKey(server, "https://media.example", group, id, conversation)
    private fun message(id: String, content: String = id) = jsonObject("id" to id, "content" to content)

    @Test fun isolatesServerTargetScopeAndConversation() {
        val cache = ConversationSnapshotCache()
        cache.put(key(), listOf(message("1")), "cursor", true)
        assertNotNull(cache.get(key()))
        assertNull(cache.get(key(server = "https://other.example")))
        assertNull(cache.get(key(id = "b")))
        assertNull(cache.get(key(group = true)))
        assertNull(cache.get(key(conversation = "two")))
        assertNull(cache.get(key().copy(mediaUrl = "https://other-media.example")))
    }

    @Test fun evictsLeastRecentlyViewedConversation() {
        val cache = ConversationSnapshotCache(maxConversations = 2)
        cache.put(key(id = "a"), listOf(message("1")), null, false)
        cache.put(key(id = "b"), emptyList(), null, false)
        cache.get(key(id = "a"))
        cache.put(key(id = "c"), emptyList(), null, false)
        assertNotNull(cache.get(key(id = "a")))
        assertNull(cache.get(key(id = "b")))
        assertNotNull(cache.get(key(id = "c")))
    }

    @Test fun boundedMessagesKeepSafePaginationBoundaryAndDetachedObjects() {
        val cache = ConversationSnapshotCache(maxMessages = 2)
        val source = listOf(message("1"), message("2"), message("3"))
        cache.put(key(), source, "older-than-1", true)
        source[1].addProperty("content", "mutated")
        val snapshot = cache.get(key())!!
        assertEquals(listOf("2", "3"), snapshot.messages.map { it.text("id") })
        assertEquals("2", snapshot.historyCursor)
        assertTrue(snapshot.historyPaged)
        assertEquals("2", snapshot.messages.first().text("content"))
        snapshot.messages.first().addProperty("content", "display-mutated")
        assertEquals("2", cache.get(key())!!.messages.first().text("content"))
    }

    @Test fun cachedDisplayCanBeUpdatedByHistoryWithoutLosingOlderPage() {
        val cache = ConversationSnapshotCache()
        cache.put(key(), listOf(message("1"), message("2", "cached")), "older", true)
        val display = cache.get(key())!!
        assertEquals("cached", display.messages.last().text("content"))
        val merged = ConversationProjection.reconcileHistory(display.messages, listOf(message("2", "server"), message("3")), display.messages)
        cache.put(key(), merged, display.historyCursor, display.historyPaged)
        val refreshed = cache.get(key())!!
        assertEquals(listOf("1", "2", "3"), refreshed.messages.map { it.text("id") })
        assertEquals("server", refreshed.messages[1].text("content"))
        assertEquals("older", refreshed.historyCursor)
        assertTrue(refreshed.historyPaged)
        cache.clear()
        assertNull(cache.get(key()))
    }

    @Test fun cachedVoiceReadyCanBeAuthoritativelyClearedByNextHistory() {
        val cache = ConversationSnapshotCache()
        cache.put(key(), listOf(message("1").apply { addProperty("voice_status", "ready"); addProperty("voice_media_id", "audio") }), null, false)
        val display = cache.get(key())!!.messages
        val incoming = message("1").apply { addProperty("voice_status", "failed"); add("voice_media_id", com.google.gson.JsonNull.INSTANCE) }
        val result = ConversationProjection.reconcileHistory(display, listOf(incoming), display).single()
        assertEquals("failed", result.text("voice_status"))
        assertTrue(result.get("voice_media_id").isJsonNull)
    }

    @Test fun successIncludingEmptyResponseUsesTtlAndForceBypassesIt() {
        var now = 0L
        val gate = RefreshWindow(ttlMillis = 60, clock = { now })
        val first = gate.begin("empty-stickers")!!
        assertNull(gate.begin("empty-stickers", force = true))
        gate.finish(first, success = true)
        now = 59
        assertNull(gate.begin("empty-stickers"))
        val forced = gate.begin("empty-stickers", force = true)!!
        gate.finish(forced, success = true)
        now = 119
        assertNotNull(gate.begin("empty-stickers"))
        assertNotNull(gate.begin("other"))
    }

    @Test fun failureOrCancelledLateCompletionDoesNotCacheSuccess() {
        var now = 0L
        val gate = RefreshWindow(ttlMillis = 60, clock = { now })
        val failed = gate.begin("roster")!!
        gate.finish(failed, success = false)
        val cancelled = gate.begin("roster")!!
        gate.cancelInFlight()
        val current = gate.begin("roster")!!
        gate.finish(cancelled, success = true)
        assertNull(gate.begin("roster"))
        gate.finish(current, success = false)
        assertNotNull(gate.begin("roster"))
        gate.clear()
        now = 1
        assertNotNull(gate.begin("roster"))
    }

    @Test fun freshnessSurvivesNavigationCancellationButNotClear() {
        val gate = RefreshWindow(ttlMillis = 60, clock = { 0L })
        gate.finish(gate.begin("avatar-with-empty-url")!!, success = true)
        gate.cancelInFlight()
        assertNull(gate.begin("avatar-with-empty-url"))
        gate.clear()
        assertNotNull(gate.begin("avatar-with-empty-url"))
    }

    @Test fun disconnectedNewestPageResetsCursorRatherThanSkippingUnseenMiddle() {
        val old = (51..250).map { message(it.toString()) }
        val page = (301..350).map { message(it.toString()) }
        val result = ConversationHistoryRefresh.reconcile(ConversationSnapshot(old, "51", true), page, old, "301", older = false)
        assertEquals((301..350).map { it.toString() }, result.messages.map { it.text("id") })
        assertEquals("301", result.historyCursor)
        assertFalse(result.historyPaged)
    }

    @Test fun overlappingNewestPageRetainsPreviouslyLoadedPagesAndCursor() {
        val old = (51..250).map { message(it.toString()) }
        val page = (201..300).map { message(it.toString()) }
        val result = ConversationHistoryRefresh.reconcile(ConversationSnapshot(old, "51", true), page, old, "201", older = false)
        assertEquals((51..300).map { it.toString() }, result.messages.map { it.text("id") })
        assertEquals("51", result.historyCursor)
        assertTrue(result.historyPaged)
    }

    @Test fun disconnectedRefreshRetainsSseDuringRequestAndProtectsItsVoiceUpdate() {
        val old = listOf(message("250"))
        val page = listOf(message("350").apply { addProperty("voice_status", "pending") })
        val live = old + listOf(message("350").apply { addProperty("voice_status", "ready") }, message("351"))
        val result = ConversationHistoryRefresh.reconcile(ConversationSnapshot(live, "250", true), page, old, "350", older = false)
        assertEquals(listOf("350", "351"), result.messages.map { it.text("id") })
        assertEquals("ready", result.messages.first().text("voice_status"))
        assertEquals("350", result.historyCursor)
        assertFalse(result.historyPaged)
    }

    @Test fun olderPageWithoutOverlapStillAppendsAndEmptyRefreshKeepsCursor() {
        val old = listOf(message("51"), message("52"))
        val older = ConversationHistoryRefresh.reconcile(ConversationSnapshot(old, "51", false), listOf(message("50")), old, "50", older = true)
        assertEquals(setOf("50", "51", "52"), older.messages.map { it.text("id") }.toSet())
        assertEquals("50", older.historyCursor)
        assertTrue(older.historyPaged)
        val empty = ConversationHistoryRefresh.reconcile(older, emptyList(), older.messages, null, older = false)
        assertEquals(older, empty)
    }
}
