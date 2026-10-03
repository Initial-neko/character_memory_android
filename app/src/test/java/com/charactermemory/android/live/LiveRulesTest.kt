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
    @Test fun acceptedMentionCommentOnlyClearsTheSameMentionSelection() {
        val receipt = CommentReceipt("c2", "draft A", 7L, listOf("rin", "mei"))
        assertTrue(LiveRules.shouldClearCommentDraft("draft A", 7L, listOf("rin", "mei"), receipt))
        assertFalse(LiveRules.shouldClearCommentDraft("draft A", 7L, listOf("rin"), receipt))
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
        val confirmed = jsonObject("id" to 9, "created_at" to "2026-10-02T10:00:00.200+08:00", "content" to "old")
        val fresh = jsonObject("id" to 1, "comments" to listOf(
            jsonObject("id" to 12, "created_at" to "2026-10-02T02:00:00.100Z"),
            jsonObject("id" to 9, "created_at" to "2026-10-02T02:00:00.200Z", "content" to "enriched"),
            jsonObject("id" to 10, "created_at" to "2026-10-02T10:00:00.200+08:00")))
        val rows = LiveRules.preserveConfirmedComments(fresh, listOf(confirmed)).items("comments")
        assertEquals(listOf("12", "9", "10"), rows.map { it.text("id") })
        assertEquals("enriched", rows[1].text("content"))
    }
    @Test fun spaceMentionsRequireAtMostFourUniqueNonArchivedRoleIdsIncludingDeferred() {
        val spaceEligibleRoleIds = setOf("rin", "mei", "lex", "yuki", "nova")
        assertTrue(LiveRules.validSpaceMentions(emptyList(), spaceEligibleRoleIds))
        assertTrue(LiveRules.validSpaceMentions(listOf("rin", "mei", "lex", "yuki"), spaceEligibleRoleIds))
        assertTrue(LiveRules.validSpaceMentions(listOf("nova"), spaceEligibleRoleIds))
        assertFalse(LiveRules.validSpaceMentions(listOf("rin", "rin"), spaceEligibleRoleIds))
        assertFalse(LiveRules.validSpaceMentions(listOf("rin", "mei", "lex", "yuki", "nova"), spaceEligibleRoleIds))
        assertFalse(LiveRules.validSpaceMentions(listOf("rin", "archived"), spaceEligibleRoleIds))
        assertFalse(LiveRules.validSpaceMentions(listOf("rin", "missing"), spaceEligibleRoleIds))
    }
    @Test fun spaceCommentRetryReusesIdempotencyKeyOnlyForIdenticalPayload() {
        val pending = PendingCommentRequest("一起去吗？", 17L, null, listOf("rin", "mei"), "request-1")
        assertEquals("request-1", LiveRules.commentRequestId(pending, "一起去吗？", 17L, null, listOf("rin", "mei"), "request-2"))
        assertEquals("request-2", LiveRules.commentRequestId(pending, "一起去吗", 17L, null, listOf("rin", "mei"), "request-2"))
        assertEquals("request-2", LiveRules.commentRequestId(pending, "一起去吗？", 17L, null, listOf("mei", "rin"), "request-2"))
    }
    @Test fun notificationCommentMergesOnceIntoFullPostAndPreservesOtherFields() {
        val post = jsonObject("id" to 42, "content" to "今天去看海。", "media_items" to listOf(
            jsonObject("media_id" to "media-42", "url" to "/media/42.png", "available" to true)),
            "like_count" to 2, "likes" to listOf(jsonObject("character_id" to "rin"), jsonObject("character_id" to "mei")),
            "comments" to listOf(jsonObject("id" to 51, "content" to "旧评论"),
                jsonObject("id" to 52, "content" to "另一条已有评论"),
                jsonObject("id" to 59, "content" to "stale notification comment")))
        val sourceComment = jsonObject("id" to 59, "post_id" to 42, "content" to "我看到你喊我啦。", "mentions_user" to true)
        val attached = LiveRules.attachSpaceComment(post, sourceComment, "42")
        val comments = attached.items("comments")
        assertEquals("42", attached.text("id"))
        assertEquals(listOf("51", "52", "59"), comments.map { it.text("id") })
        assertEquals("我看到你喊我啦。", comments.last().text("content"))
        assertEquals(post.get("media_items"), attached.get("media_items"))
        assertEquals(post.get("like_count"), attached.get("like_count"))
        assertEquals(post.get("likes"), attached.get("likes"))
        assertEquals(1, LiveRules.attachSpaceComment(attached, sourceComment, "42").items("comments").count { it.text("id") == "59" })
        assertTrue(comments.last().get("mentions_user").asBoolean)
    }
    @Test fun lateSpaceRefreshKeepsConfirmedNotificationCommentAndAcceptsFreshPostFields() {
        val notificationComment = jsonObject("id" to 59, "post_id" to 42, "actor_type" to "CHARACTER",
            "content" to "持久化的通知评论", "mentions_user" to true)
        val refreshedPost = jsonObject("id" to 42, "content" to "服务端更新后的动态", "created_at" to "2026-10-03T10:00:00+08:00",
            "media_items" to listOf(jsonObject("media_id" to "media-42-new", "url" to "/media/42-new.png", "available" to true)),
            "like_count" to 3, "likes" to listOf(jsonObject("character_id" to "rin"), jsonObject("character_id" to "mei"),
                jsonObject("character_id" to "lex")),
            "comments" to listOf(jsonObject("id" to 51, "content" to "保留评论一"),
                jsonObject("id" to 52, "content" to "保留评论二")))

        val merged = LiveRules.preserveConfirmedComments(refreshedPost, listOf(notificationComment))

        assertEquals("服务端更新后的动态", merged.text("content"))
        assertEquals("media-42-new", merged.items("media_items").single().text("media_id"))
        assertEquals(3, merged.get("like_count").asInt)
        assertEquals(3, merged.items("likes").size)
        assertEquals(listOf("51", "52", "59"), merged.items("comments").map { it.text("id") })
        assertEquals("持久化的通知评论", merged.items("comments").single { it.text("id") == "59" }.text("content"))
        assertEquals(1, LiveRules.preserveConfirmedComments(merged, listOf(notificationComment)).items("comments")
            .count { it.text("id") == "59" })
    }
    @Test fun spaceRoleMentionsAndRepliesToUserHaveDistinctAttentionMarkers() {
        val userComment = jsonObject("id" to 10, "actor_type" to "USER")
        val characterComment = jsonObject("id" to 11, "actor_type" to "CHARACTER")
        val comments = listOf(userComment, characterComment)
        val mention = jsonObject("id" to 12, "actor_type" to "CHARACTER", "mentions_user" to true)
        val replyToUser = jsonObject("id" to 13, "actor_type" to "CHARACTER", "reply_to_comment_id" to 10)
        val replyToCharacter = jsonObject("id" to 14, "actor_type" to "CHARACTER", "reply_to_comment_id" to 11)
        val userAuthored = jsonObject("id" to 15, "actor_type" to "USER", "mentions_user" to true)

        assertEquals("角色 @ 了你", LiveRules.spaceRoleAttentionMarker(mention, comments))
        assertEquals("角色回复了你", LiveRules.spaceRoleAttentionMarker(replyToUser, comments))
        assertNull(LiveRules.spaceRoleAttentionMarker(replyToCharacter, comments))
        assertNull(LiveRules.spaceRoleAttentionMarker(userAuthored, comments))
    }

    @Test fun stickerPacksPreservePackNamesAndStickerOrderFromCoreCatalog() {
        val packs = LiveRules.stickerPacks(listOf(
            jsonObject("id" to "wave", "label" to "挥手", "pack_id" to "default", "pack_name" to "内置"),
            jsonObject("id" to "happy", "label" to "开心", "pack_id" to "default", "pack_name" to "内置"),
            jsonObject("id" to "sparkle", "label" to "星光", "pack_id" to "custom", "pack_name" to "自定义")
        ))

        assertEquals(listOf("default", "custom"), packs.map { it.id })
        assertEquals(listOf("内置", "自定义"), packs.map { it.name })
        assertEquals(listOf("wave", "happy"), packs.first().stickers.map { it.text("id") })
        assertEquals(listOf("sparkle"), packs.last().stickers.map { it.text("id") })
    }
}
