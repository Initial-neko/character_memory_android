package com.charactermemory.android.live

import com.charactermemory.android.data.*
import com.google.gson.JsonObject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun LiveViewModel.generateCharacter() {
    val description = state.value.characterPrompt.trim()
    if (description.length < 3) { update { it.copy(error = "人物描述至少 3 个字符") }; return }
    operation("character-draft", write = true) { client ->
        val draft = client.post("/v1/characters/draft", jsonObject("description" to description)).objOrNull("draft")
            ?: error("服务器没有返回人物草稿")
        currentCoroutineContext().ensureActive()
        update { it.copy(draft = draft, capacityConfirmation = null, notice = "草稿已生成；确认后才创建人物。") }
    }
}

fun LiveViewModel.confirmCharacter(overSoftLimit: Boolean = false) {
    val snapshot = state.value
    val draft = snapshot.draft ?: return
    operation("character-confirm", write = true) { client ->
        val response = client.post("/v1/characters", jsonObject("draft" to draft, "confirm_over_soft_limit" to overSoftLimit,
            "creation" to jsonObject("source" to "PERSONA_BUILDER", "prompt" to snapshot.characterPrompt)))
        val profile = response.objOrNull("character") ?: error("服务器没有返回新人物")
        currentCoroutineContext().ensureActive()
        update { it.copy(characters = LiveRules.mergeRecords(it.characters, listOf(profile)), draft = null,
            capacityConfirmation = null, notice = "人物已创建：${profile.text("name", profile.text("id"))}", page = LivePage.HOME) }
        refresh()
    }
}

fun LiveViewModel.resumeEnsemble() = operation("ensemble-resume") { client ->
    val build = client.get("/v1/ensembles").objOrNull("build")
    currentCoroutineContext().ensureActive()
    applyBuild(build)
}

internal fun LiveViewModel.applyBuild(build: JsonObject?) {
    update { current ->
        val ready = build?.items("drafts")?.filter { it.text("status", "READY") == "READY" && it.objOrNull("draft") != null }
            ?.map { it.number("index", -1L).toInt() }?.filter { it >= 0 }?.toSet() ?: emptySet()
        val same = current.build?.text("group_id") == build?.text("group_id")
        current.copy(build = build, selectedMembers = if (same) current.selectedMembers.intersect(ready) else ready,
            capacityConfirmation = null)
    }
}

fun LiveViewModel.prepareEnsemble() {
    val prompt = state.value.ensemblePrompt.trim()
    if (prompt.length < 3) { update { it.copy(error = "群聊描述至少 3 个字符") }; return }
    operation("ensemble-prepare", write = true) { client ->
        val build = client.post("/v1/ensembles/prepare", jsonObject("prompt" to prompt)).objOrNull("build")
            ?: error("服务器没有返回构建记录，请恢复查看")
        currentCoroutineContext().ensureActive(); applyBuild(build)
    }
}

fun LiveViewModel.refreshEnsemble() {
    val id = state.value.build?.text("group_id")?.takeIf { it.isNotBlank() } ?: return
    operation("ensemble-refresh") { client ->
        val build = client.get("/v1/ensembles/${pathId(id)}").objOrNull("build")
        currentCoroutineContext().ensureActive(); applyBuild(build)
    }
}

fun LiveViewModel.researchEnsemble(index: Int? = null) {
    val id = state.value.build?.text("group_id")?.takeIf { it.isNotBlank() } ?: return
    operation("ensemble-research", write = true) { client ->
        val suffix = if (index == null) "research" else "members/$index/retry"
        val build = client.post("/v1/ensembles/${pathId(id)}/$suffix", jsonObject()).objOrNull("build")
        currentCoroutineContext().ensureActive(); applyBuild(build)
    }
}

fun LiveViewModel.selectMember(index: Int, selected: Boolean) = update {
    it.copy(selectedMembers = if (selected) it.selectedMembers + index else it.selectedMembers - index)
}

fun LiveViewModel.confirmEnsemble(overSoftLimit: Boolean = false) {
    val snapshot = state.value
    val id = snapshot.build?.text("group_id")?.takeIf { it.isNotBlank() } ?: return
    if (snapshot.selectedMembers.size !in 2..12) { update { it.copy(error = "请选择 2–12 位可用成员") }; return }
    operation("ensemble-confirm", write = true) { client ->
        val build = client.post("/v1/ensembles/${pathId(id)}/confirm", jsonObject("selected_indices" to snapshot.selectedMembers.sorted(),
            "confirm_over_soft_limit" to overSoftLimit, "use_voice_design" to false)).objOrNull("build")
            ?: error("服务器没有返回建群结果，请刷新构建记录")
        currentCoroutineContext().ensureActive(); applyBuild(build)
        val group = build.objOrNull("group")
        if (build.text("status") == "ACTIVE" && group != null) {
            update { it.copy(groups = LiveRules.mergeRecords(it.groups, listOf(group)), notice = "群聊已创建", page = LivePage.HOME) }
            refresh()
        }
    }
}

fun LiveViewModel.cancelEnsemble() {
    val id = state.value.build?.text("group_id")?.takeIf { it.isNotBlank() } ?: return
    operation("ensemble-cancel", write = true) { client ->
        val build = client.post("/v1/ensembles/${pathId(id)}/cancel", jsonObject()).objOrNull("build")
        currentCoroutineContext().ensureActive(); applyBuild(build)
        update { it.copy(notice = "构建已取消") }
    }
}

fun LiveViewModel.loadSpace(older: Boolean = false) {
    val cursor = if (older) state.value.spaceCursor ?: return else null
    operation("space") { client ->
        val query = mutableMapOf("limit" to "10")
        cursor?.let { query["before_id"] = it }
        val page = client.get("/v1/space/posts", query)
        currentCoroutineContext().ensureActive()
        update {
            val posts = page.items("posts").map { post -> LiveRules.preserveConfirmedComments(post, it.confirmedComments[post.text("id")].orEmpty()) }
            it.copy(posts = if (older) LiveRules.mergeRecords(it.posts, posts) else LiveRules.refreshRecords(it.posts, posts),
            spaceCursor = if (older || !it.spacePaged) LiveRules.nextCursor(page) else it.spaceCursor,
            spacePaged = older || it.spacePaged) }
    }
}

fun LiveViewModel.refreshPost(id: String) = operation("space-post-$id") { client ->
    val post = client.get("/v1/space/posts/${pathId(id)}").objOrNull("post") ?: error("动态不存在")
    currentCoroutineContext().ensureActive(); update {
        it.copy(posts = LiveRules.mergeRecords(it.posts, listOf(LiveRules.preserveConfirmedComments(post, it.confirmedComments[id].orEmpty()))))
    }
}

fun LiveViewModel.loadSpaceNotifications() {
    if (state.value.config.coreUrl.isBlank()) return
    operation("space-notifications") { client ->
        val response = try {
            client.get("/v1/space/notifications", mapOf("unread_only" to "true", "limit" to "50"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            update { it.copy(spaceNotificationError = error.message ?: "提醒暂不可用") }
            return@operation
        }
        currentCoroutineContext().ensureActive()
        update { it.copy(spaceNotifications = response.items("notifications"),
            spaceUnreadCount = response.number("unread_count").coerceAtLeast(0L).toInt(), spaceNotificationError = null) }
    }
}

fun LiveViewModel.loadSpaceMentionCharacters() {
    if (state.value.config.coreUrl.isBlank()) return
    operation("space-mention-characters") { client ->
        val characters = client.get("/v1/characters", mapOf("archived" to "false", "include_deferred" to "true"))
            .items("characters")
        currentCoroutineContext().ensureActive()
        update { it.copy(spaceMentionCharacters = characters) }
    }
}

fun LiveViewModel.openSpaceNotification(notificationId: String) {
    val snapshot = state.value
    val notification = snapshot.spaceNotifications.firstOrNull { it.text("id") == notificationId } ?: return
    val postId = notification.text("post_id").takeIf { it.isNotBlank() } ?: return
    val commentId = notification.text("comment_id").takeIf { it.isNotBlank() } ?: return
    val comment = notification.objOrNull("comment") ?: return
    operation("space-notification-$notificationId", write = true) { client ->
        val fullPost = snapshot.posts.firstOrNull { it.text("id") == postId }
            ?: client.get("/v1/space/posts/${pathId(postId)}").objOrNull("post")
            ?: error("动态不存在")
        val response = client.post("/v1/space/notifications/${pathId(notificationId)}/read", jsonObject())
        currentCoroutineContext().ensureActive()
        val nextCount = if (response.has("unread_count")) response.number("unread_count").coerceAtLeast(0L).toInt()
            else (state.value.spaceUnreadCount - 1).coerceAtLeast(0)
        update { current ->
            val latestPost = current.posts.firstOrNull { it.text("id") == postId } ?: fullPost
            val confirmedComments = LiveRules.mergeRecords(current.confirmedComments[postId].orEmpty(), listOf(comment))
            val focusedPost = LiveRules.preserveConfirmedComments(
                LiveRules.attachSpaceComment(latestPost, comment, postId),
                confirmedComments
            )
            current.copy(page = LivePage.SPACE,
                posts = LiveRules.mergeRecords(current.posts, listOf(focusedPost)),
                confirmedComments = current.confirmedComments + (postId to confirmedComments),
                spaceNotifications = current.spaceNotifications.filterNot { it.text("id") == notificationId },
                spaceUnreadCount = nextCount, spaceNotificationError = null,
                focusedSpacePostId = postId, focusedSpaceCommentId = commentId,
                error = null)
        }
    }
}

fun LiveViewModel.comment(
    postId: String, content: String, replyTo: Long? = null, stickerId: String? = null, mentions: List<String> = emptyList()
) {
    if (content.trim().isBlank() && stickerId == null) return
    val snapshot = state.value
    val mentionCharacters = snapshot.spaceMentionCharacters.ifEmpty { snapshot.characters }
    val activeCharacterIds = mentionCharacters.map { it.text("id") }.filter { it.isNotBlank() }.toSet()
    if (!LiveRules.validSpaceMentions(mentions, activeCharacterIds)) {
        update { it.copy(error = "最多提及 4 位当前人物；请移除重复或不可用人物。") }; return
    }
    if ("comment-$postId" in snapshot.busy) return
    val payloadContent = content.trim().take(1000)
    val requestId = LiveRules.commentRequestId(snapshot.pendingCommentRequests[postId], payloadContent, replyTo, stickerId,
        mentions, UUID.randomUUID().toString())
    val pending = PendingCommentRequest(payloadContent, replyTo, stickerId, mentions.toList(), requestId)
    update { it.copy(pendingCommentRequests = it.pendingCommentRequests + (postId to pending)) }
    operation("comment-$postId", write = true) { client ->
        val response = client.post("/v1/space/posts/${pathId(postId)}/comments", jsonObject("content" to payloadContent,
            "reply_to_comment_id" to replyTo, "sticker_id" to stickerId, "mentions" to mentions, "client_request_id" to requestId))
        val comment = response.objOrNull("comment") ?: error("服务器没有返回评论凭据，请刷新动态确认")
        val receiptId = comment.text("id").takeIf { it.isNotBlank() } ?: error("服务器未返回评论 ID，请刷新确认")
        currentCoroutineContext().ensureActive()
        update { current -> current.copy(posts = current.posts.map { post ->
            if (post.text("id") != postId) post else post.deepCopy().apply {
                val comments = LiveRules.mergeRecords(post.items("comments"), listOf(comment))
                add("comments", com.google.gson.JsonArray().apply { comments.forEach { add(it) } })
            }
        }, commentReceipts = current.commentReceipts + (postId to CommentReceipt(receiptId, content, replyTo, SpaceReplyWindow(android.os.SystemClock.elapsedRealtime()), mentions.toList())),
            confirmedComments = current.confirmedComments + (postId to LiveRules.mergeRecords(current.confirmedComments[postId].orEmpty(), listOf(comment))),
            pendingCommentRequests = if (current.pendingCommentRequests[postId]?.clientRequestId == requestId)
                current.pendingCommentRequests - postId else current.pendingCommentRequests,
            notice = "评论已保存；被提及的人物会正常决定是否回复，也可能保持沉默。") }
    }
}

fun LiveViewModel.generateImageDraft() {
    val snapshot = state.value
    if (snapshot.target == null || snapshot.imageCharacterId.isBlank()) { update { it.copy(error = "请选择参考人物") }; return }
    if (snapshot.imageInstruction.trim().isBlank()) { update { it.copy(error = "请填写图片描述") }; return }
    operation("image-generate", write = true) { client ->
        val response = client.post("/v1/characters/${pathId(snapshot.imageCharacterId)}/images/generate",
            jsonObject("instruction" to snapshot.imageInstruction.trim(), "purpose" to snapshot.imagePurpose,
                "use_avatar_reference" to snapshot.imageUseAvatar, "persist_result" to false))
        currentCoroutineContext().ensureActive()
        val image = response.objOrNull("image")?.takeIf { it.text("data_url").startsWith("data:image/") }
            ?: error("生成接口未返回可发送图片草稿")
        update { it.copy(imagePrompt = response.text("prompt"), imageDraft = image,
            notice = "Core 已润色提示词并生成图片草稿；确认发送后才进入聊天历史。") }
}
}

fun LiveViewModel.discardImage() = update { it.copy(imageDraft = null) }
fun LiveViewModel.sendImageDraft() { state.value.imageDraft?.let { send(image = it) } }

