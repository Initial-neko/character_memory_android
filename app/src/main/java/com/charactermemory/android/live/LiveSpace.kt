package com.charactermemory.android.live

import android.widget.MediaController
import android.widget.VideoView
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.charactermemory.android.data.*
import com.google.gson.JsonObject
import kotlinx.coroutines.delay

@Composable
internal fun LiveSpace(state: LiveState, model: LiveViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.focusedSpacePostId, state.posts, state.spaceNotifications) {
        val postId = state.focusedSpacePostId ?: return@LaunchedEffect
        val postIndex = state.posts.indexOfFirst { it.text("id") == postId }
        if (postIndex >= 0) {
            val notificationCard = if (state.spaceNotifications.isNotEmpty() || state.spaceUnreadCount > 0 ||
                state.spaceNotificationError != null || "space-notifications" in state.busy) 1 else 0
            listState.animateScrollToItem(1 + notificationCard + postIndex)
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("live-space"), state = listState, contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { LiveSection("最新动态", if ("space" in state.busy) "加载中…" else "人物分享的日常") }
                LiveIconAction(LiveSymbol.REFRESH, "刷新动态", "live-space-refresh", "space" !in state.busy) { model.loadSpace() }
            }
            if (state.posts.isEmpty() && "space" !in state.busy) Text("暂无动态", color = LiveMuted)
        }
        if (state.spaceNotifications.isNotEmpty() || state.spaceUnreadCount > 0 || state.spaceNotificationError != null ||
            "space-notifications" in state.busy) item {
            LiveSpaceNotifications(state, model)
        }
        items(state.posts, key = { it.text("id") }) { post -> LivePost(post, state, model) }
        if (state.spaceCursor != null) item {
            LiveAction("加载更多", "live-space-older", "space" !in state.busy) { model.loadSpace(true) }
        }
    }
}

@Composable
private fun LiveSpaceNotifications(state: LiveState, model: LiveViewModel) {
    LivePanelCard(Modifier.fillMaxWidth().testTag("live-space-notifications")) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("角色找你", color = LivePale, fontWeight = FontWeight.SemiBold)
                    Text("应用内未读提醒 · ${state.spaceUnreadCount}", color = LiveMuted, fontSize = 11.sp)
                }
                LiveIconAction(LiveSymbol.REFRESH, "刷新提醒", "live-space-notifications-refresh",
                    "space-notifications" !in state.busy, model::loadSpaceNotifications)
            }
            if (state.spaceNotifications.isEmpty()) {
                val status = state.spaceNotificationError?.let { "提醒暂不可用：$it" }
                    ?: if ("space-notifications" in state.busy) "正在检查提醒…" else "暂无未读提醒"
                Text(status, color = LiveMuted, fontSize = 12.sp)
            }
            state.spaceNotifications.forEach { notification ->
                val id = notification.text("id")
                val comment = notification.objOrNull("comment")
                val reasons = notification.get("reasons")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
                val description = if (comment?.flag("mentions_user") == true || "MENTION" in reasons) "提到了你" else "回复了你"
                val name = comment?.objOrNull("author")?.text("name") ?: comment?.text("character_id").orEmpty()
                TextButton(onClick = { model.openSpaceNotification(id) }, modifier = Modifier.fillMaxWidth()
                    .testTag("live-space-notification-$id"), enabled = "space-notification-$id" !in state.busy) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${name.ifBlank { "角色" }} $description", color = LiveAccent, fontWeight = FontWeight.SemiBold)
                        Text(comment?.text("content").orEmpty(), color = LivePale, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("打开这条评论", color = LiveCyan, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LivePost(post: JsonObject, state: LiveState, model: LiveViewModel) {
    val id = post.text("id")
    var comment by rememberSaveable(state.config.coreUrl, id) { mutableStateOf("") }
    var reply by rememberSaveable(state.config.coreUrl, id) { mutableStateOf<Long?>(null) }
    var selectedMentions by rememberSaveable(state.config.coreUrl, id) { mutableStateOf(emptyList<String>()) }
    var mentionMenuExpanded by rememberSaveable(state.config.coreUrl, id) { mutableStateOf(false) }
    var showComments by rememberSaveable(state.config.coreUrl, id) { mutableStateOf(false) }
    val comments = post.items("comments")
    val mentionCharacters = state.spaceMentionCharacters.ifEmpty { state.characters }
    val receipt = state.commentReceipts[id]
    val owner = LocalLifecycleOwner.current
    val replyWindow = receipt?.replyWindow
    var awaitingReply by remember(receipt?.id) { mutableStateOf(false) }
    var handledReceipt by rememberSaveable(state.config.coreUrl, id) { mutableStateOf(receipt?.id) }
    LaunchedEffect(receipt?.id) {
        if (receipt != null && receipt.id != handledReceipt) {
            if (LiveRules.shouldClearCommentDraft(comment, reply, selectedMentions, receipt)) { comment = ""; reply = null; selectedMentions = emptyList() }
            showComments = true
            handledReceipt = receipt.id
        }
    }
    // Space reactions are asynchronous on Core. After a confirmed human comment, refresh only
    // this visible post for a bounded period; never create a second write or permanent poller.
    // LaunchedEffect is cancelled when the post/page leaves composition or comments collapse.
    LaunchedEffect(state.config.coreUrl, id, receipt?.id, showComments, owner) {
        if (receipt == null || replyWindow == null || !showComments) return@LaunchedEffect
        try { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (replyWindow.isOpen(SystemClock.elapsedRealtime())) {
                val currentPost = model.state.value.posts.firstOrNull { it.text("id") == id } ?: break
                if (currentPost.items("comments").any { candidate ->
                    candidate.text("reply_to_comment_id") == receipt.id &&
                        candidate.text("actor_type") == "CHARACTER"
                }) break
                awaitingReply = true
                delay(6_000L)
                if (!replyWindow.isOpen(SystemClock.elapsedRealtime())) break
                model.refreshPost(id)
            }
            awaitingReply = false
        } } finally { awaitingReply = false }
    }
    LaunchedEffect(state.focusedSpaceCommentId) {
        if (state.focusedSpacePostId == id && state.focusedSpaceCommentId != null) showComments = true
    }
    LivePanelCard(Modifier.fillMaxWidth().testTag("live-space-post-$id")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val author = post.objOrNull("author")
            val authorId = post.text("character_id", author?.text("id").orEmpty())
            val authorName = author?.text("name", authorId) ?: authorId
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                LiveAvatar(authorName, state.avatars[authorId].orEmpty(), model)
                Column(Modifier.weight(1f)) {
                    Text(authorName, color = LivePale, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val time = LiveTime.dateTime(post.text("created_at"))
                    if (time.isNotEmpty()) Text(time, color = LiveMuted, fontSize = 11.sp)
                }
            }
            Text(post.text("content"), color = LivePale, fontSize = 14.sp, lineHeight = 23.sp)
            post.items("media_items").forEachIndexed { index, media -> LiveMedia(media, model, "live-space-media-$id-$index") }
            if (post.items("media_items").isEmpty()) post.objOrNull("media")?.let { LiveMedia(it, model, "live-space-media-$id-0") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("♡ ${post.number("like_count")} 人物点赞", color = LivePurple, fontSize = 12.sp)
                TextButton(onClick = { showComments = !showComments }, modifier = Modifier.testTag("live-space-comments-toggle-$id")) {
                    Text(if (showComments) "收起评论" else "◌ ${comments.size} 条评论", color = LiveMuted, fontSize = 12.sp)
                }
            }
            if (showComments) {
                Column(Modifier.fillMaxWidth().background(Color(0xFF111B2C), RoundedCornerShape(12.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                comments.forEach { item ->
                    val commentId = item.text("id")
                    val focused = state.focusedSpacePostId == id && state.focusedSpaceCommentId == commentId
                    Column(Modifier.fillMaxWidth().background(
                        if (focused) Color(0xFF253A54) else Color.Transparent, RoundedCornerShape(10.dp))
                        .padding(6.dp).testTag("live-space-reply-$commentId")) {
                        val replyId = item.text("reply_to_comment_id")
                        val replyTarget = comments.firstOrNull { it.text("id") == replyId }
                        val replyAuthor = replyTarget?.objOrNull("author")?.text("name")
                        Text("${item.objOrNull("author")?.text("name", item.text("character_id")) ?: item.text("character_id")}${if (replyId.isNotBlank()) " 回复 ${replyAuthor?.takeIf { it.isNotBlank() } ?: "评论"}" else ""}：${item.text("content")}",
                            color = LivePale, fontSize = 13.sp, lineHeight = 20.sp)
                        val mentionedIds = item.get("mentions")?.takeIf { it.isJsonArray }?.asJsonArray
                            ?.mapNotNull { runCatching { it.asString }.getOrNull() }.orEmpty()
                        if (mentionedIds.isNotEmpty()) {
                            val names = mentionedIds.map { roleId -> mentionCharacters.firstOrNull { it.text("id") == roleId }?.text("name", roleId) ?: roleId }
                            Text("提及 ${names.joinToString(" ") { "@$it" }}", color = LiveCyan, fontSize = 11.sp,
                                modifier = Modifier.testTag("live-space-comment-mentions-$commentId"))
                        }
                        LiveRules.spaceRoleAttentionMarker(item, comments)?.let { marker ->
                            val markerTag = if (item.flag("mentions_user")) "live-space-comment-mentions-user-$commentId"
                                else "live-space-comment-reply-to-user-$commentId"
                            Text(marker, color = Color(0xFFFFB26B), fontWeight = FontWeight.Bold, fontSize = 11.sp,
                                modifier = Modifier.testTag(markerTag))
                        }
                        item.objOrNull("sticker")?.let { LiveMedia(it, model, "live-comment-sticker-${item.text("id")}") }
                        TextButton(onClick = { reply = item.number("id") }, modifier = Modifier.testTag("live-space-reply-to-$commentId")) { Text("回复") }
                    }
                }
                reply?.let { value ->
                    val name = comments.firstOrNull { it.number("id") == value }?.objOrNull("author")?.text("name").orEmpty()
                    TextButton(onClick = { reply = null }) { Text("回复${if (name.isNotBlank()) " $name" else "评论"} · 取消", color = LiveCyan, fontSize = 12.sp) }
                }
                OutlinedTextField(comment, { comment = it.take(1000) }, placeholder = { Text(if (reply == null) "写评论…" else "写回复…") },
                    shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().testTag("live-space-comment-$id"), maxLines = 4)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box {
                        TextButton(onClick = { mentionMenuExpanded = !mentionMenuExpanded },
                            modifier = Modifier.testTag("live-space-mention-toggle-$id")) { Text("＋ @角色 ${selectedMentions.size}/4", color = LiveCyan) }
                        DropdownMenu(expanded = mentionMenuExpanded, onDismissRequest = { mentionMenuExpanded = false }) {
                            mentionCharacters.forEach { character ->
                                val characterId = character.text("id")
                                val selected = characterId in selectedMentions
                                DropdownMenuItem(
                                    text = { Text("${if (selected) "✓ " else "@"}${character.text("name", characterId)}") },
                                    onClick = {
                                        selectedMentions = if (selected) selectedMentions - characterId else selectedMentions + characterId
                                        mentionMenuExpanded = false
                                    },
                                    enabled = selected || selectedMentions.size < 4,
                                    modifier = Modifier.testTag("live-space-mention-$id-$characterId")
                                )
                            }
                        }
                    }
                }
                if (selectedMentions.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    selectedMentions.forEach { roleId ->
                        val name = mentionCharacters.firstOrNull { it.text("id") == roleId }?.text("name", roleId) ?: roleId
                        AssistChip(onClick = { selectedMentions = selectedMentions - roleId },
                            label = { Text("@$name ×", maxLines = 1) },
                            modifier = Modifier.testTag("live-space-mention-chip-$id-$roleId"))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { model.refreshPost(id) }, enabled = "space-post-$id" !in state.busy,
                        modifier = Modifier.weight(1f).testTag("live-space-post-refresh-$id")) { Text("刷新人物回复", fontSize = 12.sp) }
                    LiveIconAction(LiveSymbol.SEND, "发送评论", "live-space-comment-send-$id", comment.isNotBlank() && "comment-$id" !in state.busy) {
                        model.comment(id, comment, reply, mentions = selectedMentions)
                    }
                }
                }
            }
            if (awaitingReply && showComments) {
                Text("评论已发送，正在等待人物回复。", color = LiveMuted,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("live-space-auto-refresh-$id"))
            }
        }
    }
}

@Composable
internal fun LiveMedia(media: JsonObject, model: LiveViewModel, tag: String) {
    val path = media.text("url")
    val mime = media.text("mime_type")
    val kind = media.text("media_type")
    val label = media.text("label", "媒体附件")
    if (path.isBlank() || (media.has("available") && !media.flag("available"))) {
        Text("$label · 资源暂不可用", color = LiveMuted, modifier = Modifier.testTag(tag)); return
    }
    val url = model.assetUrl(path)
    if (url.isBlank()) { Text("$label · 地址不可用", color = LiveMuted); return }
    when {
        mime.startsWith("audio/") || kind == "VOICE" -> LiveAudioPlayerButton(url, label, tag, "space:$tag\u0000$url")
        mime.startsWith("video/") || kind == "VIDEO" -> LiveVideo(url, label, tag)
        else -> {
            var failed by remember(url) { mutableStateOf(false) }
            Column(Modifier.testTag(tag)) {
                AsyncImage(url, label, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 260.dp),
                    onError = { failed = true }, onSuccess = { failed = false })
                if (failed) Text("$label · 图片加载失败", color = LiveMuted)
            }
        }
    }
}

@Composable
private fun LiveVideo(url: String, label: String, tag: String) {
    var playing by remember(url) { mutableStateOf(false) }
    var error by remember(url) { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val video = remember(url, context) { VideoView(context) }
    DisposableEffect(url, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { video.stopPlayback(); playing = false } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); video.stopPlayback() }
    }
    Column(Modifier.testTag(tag)) {
        TextButton(onClick = { playing = !playing; error = false; if (!playing) video.stopPlayback() }) { Text("$label · ${if (playing) "停止视频" else "播放视频"}") }
        if (error) Text("视频播放失败，可重试", color = LiveMuted)
        if (playing) AndroidView(factory = {
            video.apply {
                setMediaController(MediaController(context).apply { setAnchorView(video) })
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ -> error = true; playing = false; true }
                setVideoPath(url)
            }
        }, modifier = Modifier.fillMaxWidth().height(220.dp))
    }
}
