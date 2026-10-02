package com.charactermemory.android.live

import com.charactermemory.android.data.jsonObject
import com.charactermemory.android.data.text
import com.charactermemory.android.data.items
import org.junit.Assert.*
import org.junit.Test

class LiveRulesTest {
    @Test fun directKeysAreServerAndCharacterScoped() {
        assertEquals(LiveRules.directKey("https://host", "rin"), LiveRules.directKey("https://host", "rin"))
        assertNotEquals(LiveRules.directKey("https://host", "rin"), LiveRules.directKey("https://other", "rin"))
        assertNotEquals(LiveRules.directKey("https://host", "rin"), LiveRules.directKey("https://host", "other"))
    }
    @Test fun paginationDeduplicatesAndRefreshUsesIncomingRecord() {
        val merged = LiveRules.mergeRecords(listOf(jsonObject("id" to 3, "content" to "old")),
            listOf(jsonObject("id" to 3, "content" to "edited"), jsonObject("id" to 2)))
        assertEquals(listOf("3", "2"), merged.map { it.text("id") })
        assertEquals("edited", merged.first().text("content"))
    }
    @Test fun staleGenerationCannotMutateNewTargetOrServer() {
        val fence = GenerationFence()
        val old = fence.current
        assertTrue(fence.accepts(old))
        fence.advance()
        assertFalse(fence.accepts(old))
        assertTrue(fence.accepts(fence.current))
    }
    @Test fun cursorOnlyAdvancesWhenServerSuppliesUsableNextPage() {
        assertNull(LiveRules.nextCursor(jsonObject("has_more" to true, "next_before_id" to null)))
        assertNull(LiveRules.nextCursor(jsonObject("has_more" to false, "next_before_id" to 9)))
        assertEquals("9", LiveRules.nextCursor(jsonObject("has_more" to true, "next_before_id" to 9)))
    }
    @Test fun refreshKeepsOlderPagesAndPrefersFreshPostWhilePrependingNew() {
        val refreshed = LiveRules.refreshRecords(listOf(jsonObject("id" to 3, "content" to "old"), jsonObject("id" to 2)),
            listOf(jsonObject("id" to 4), jsonObject("id" to 3, "content" to "new")))
        assertEquals(listOf("4", "3", "2"), refreshed.map { it.text("id") })
        assertEquals("new", refreshed[1].text("content"))
    }
    @Test fun acceptedCommentOnlyClearsTheUnchangedSubmittedDraft() {
        val receipt = CommentReceipt("c1", "draft A", 7L)
        assertTrue(LiveRules.shouldClearCommentDraft("draft A", 7L, receipt))
        assertFalse(LiveRules.shouldClearCommentDraft("draft B", 7L, receipt))
        assertFalse(LiveRules.shouldClearCommentDraft("draft A", 8L, receipt))
        assertFalse(LiveRules.shouldClearCommentDraft("draft A", null, receipt))
    }
    @Test fun stalePostReadCannotEraseConfirmedCommentAndFreshReadEnrichesIt() {
        val confirmed = jsonObject("id" to 9, "content" to "confirmed")
        val stale = jsonObject("id" to 1, "content" to "post", "comments" to emptyList<Any>())
        assertEquals(listOf("9"), LiveRules.preserveConfirmedComments(stale, listOf(confirmed)).items("comments").map { it.text("id") })
        val fresh = jsonObject("id" to 1, "comments" to listOf(jsonObject("id" to 9, "content" to "confirmed", "actor_type" to "USER")))
        val rows = LiveRules.preserveConfirmedComments(fresh, listOf(confirmed)).items("comments")
        assertEquals(1, rows.size)
        assertEquals("USER", rows.first().text("actor_type"))
    }
    @Test fun memberProgressExcludesOldTurnAndUpdatesOneRowPerMember() {
        val old = jsonObject("turn_id" to "old", "character_id" to "rin", "silent" to true)
        val current = jsonObject("turn_id" to "new", "character_id" to "rin", "silent" to false)
        assertTrue(LiveRules.memberProgress(listOf(old), "new", old).isEmpty())
        assertTrue(LiveRules.memberProgress(listOf(old), null, current).isEmpty())
        val rows = LiveRules.memberProgress(listOf(old, current), "new", current)
        assertEquals(1, rows.size)
        assertEquals("new", rows.first().text("turn_id"))
    }
    @Test fun confirmedCommentsRetainCanonicalTimeThenIdOrderAndServerEnrichment() {
        val confirmed = jsonObject("id" to 9, "created_at_epoch" to 2.2, "content" to "old")
        val fresh = jsonObject("id" to 1, "comments" to listOf(
            jsonObject("id" to 1, "created_at_epoch" to 2.1),
            jsonObject("id" to 9, "created_at_epoch" to 2.2, "content" to "enriched"),
            jsonObject("id" to 10, "created_at_epoch" to 2.2)))
        val rows = LiveRules.preserveConfirmedComments(fresh, listOf(confirmed)).items("comments")
        assertEquals(listOf("1", "9", "10"), rows.map { it.text("id") })
        assertEquals("enriched", rows[1].text("content"))
    }
}
