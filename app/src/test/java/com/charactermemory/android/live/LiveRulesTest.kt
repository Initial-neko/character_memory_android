package com.charactermemory.android.live

import com.charactermemory.android.data.jsonObject
import com.charactermemory.android.data.text
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
}
