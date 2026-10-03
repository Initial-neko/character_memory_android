package com.charactermemory.android.live

import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    LazyColumn(Modifier.fillMaxSize().testTag("live-space"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { LiveSection("最新动态", if ("space" in state.busy) "加载中…" else "人物分享的日常") }
                LiveIconAction(LiveSymbol.REFRESH, "刷新动态", "live-space-refresh", "space" !in state.busy) { model.loadSpace() }
            }
            if (state.posts.isEmpty() && "space" !in state.busy) Text("暂无动态", color = LiveMuted)
        }
        items(state.posts, key = { it.text("id") }) { post -> LivePost(post, state, model) }
        if (state.spaceCursor != null) item {
            LiveAction("加载更多", "live-space-older", "space" !in state.busy) { model.loadSpace(true) }
        }
    }
}

@Composable
private fun LivePost(post: JsonObject, state: LiveState, model: LiveViewModel) {
    val id = post.text("id")
    var comment by rememberSaveable(state.config.coreUrl, id) { mutableStateOf("") }
    var reply by rememberSaveable(state.config.coreUrl, id) { mutableStateOf<Long?>(null) }
    var showComments by rememberSaveable(state.config.coreUrl, id) { mutableStateOf(false) }
    val comments = post.items("comments")
    val receipt = state.commentReceipts[id]
    val owner = LocalLifecycleOwner.current
    val replyDeadline = remember(state.config.coreUrl, id, receipt?.id) { SystemClock.elapsedRealtime() + 90_000L }
    var handledReceipt by rememberSaveable(state.config.coreUrl, id) { mutableStateOf(receipt?.id) }
    LaunchedEffect(receipt?.id) {
        if (receipt != null && receipt.id != handledReceipt) {
            if (LiveRules.shouldClearCommentDraft(comment, reply, receipt)) { comment = ""; reply = null }
            showComments = true
            handledReceipt = receipt.id
        }
    }
    // Space reactions are asynchronous on Core. After a confirmed human comment, refresh only
    // this visible post for a bounded period; never create a second write or permanent poller.
    // LaunchedEffect is cancelled when the post/page leaves composition or comments collapse.
    LaunchedEffect(state.config.coreUrl, id, receipt?.id, showComments, owner) {
        if (receipt == null || !showComments) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (SystemClock.elapsedRealtime() < replyDeadline) {
                val currentPost = model.state.value.posts.firstOrNull { it.text("id") == id } ?: break
                if (currentPost.items("comments").any { candidate ->
                    candidate.text("reply_to_comment_id") == receipt.id &&
                        candidate.text("actor_type") == "CHARACTER"
                }) break
                delay(6_000L)
                if (SystemClock.elapsedRealtime() >= replyDeadline) break
                model.refreshPost(id)
            }
        }
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
                    Column(Modifier.testTag("live-space-reply-${item.text("id")}")) {
                        val replyId = item.text("reply_to_comment_id")
                        val replyTarget = comments.firstOrNull { it.text("id") == replyId }
                        val replyAuthor = replyTarget?.objOrNull("author")?.text("name")
                        Text("${item.objOrNull("author")?.text("name", item.text("character_id")) ?: item.text("character_id")}${if (replyId.isNotBlank()) " 回复 ${replyAuthor?.takeIf { it.isNotBlank() } ?: "评论"}" else ""}：${item.text("content")}",
                            color = LivePale, fontSize = 13.sp, lineHeight = 20.sp)
                        item.objOrNull("sticker")?.let { LiveMedia(it, model, "live-comment-sticker-${item.text("id")}") }
                        TextButton(onClick = { reply = item.number("id") }, modifier = Modifier.testTag("live-space-reply-to-${item.text("id")}")) { Text("回复") }
                    }
                }
                reply?.let { value ->
                    val name = comments.firstOrNull { it.number("id") == value }?.objOrNull("author")?.text("name").orEmpty()
                    TextButton(onClick = { reply = null }) { Text("回复${if (name.isNotBlank()) " $name" else "评论"} · 取消", color = LiveCyan, fontSize = 12.sp) }
                }
                OutlinedTextField(comment, { comment = it.take(1000) }, placeholder = { Text(if (reply == null) "写评论…" else "写回复…") },
                    shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().testTag("live-space-comment-$id"), maxLines = 4)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { model.refreshPost(id) }, enabled = "space-post-$id" !in state.busy,
                        modifier = Modifier.weight(1f).testTag("live-space-post-refresh-$id")) { Text("刷新人物回复", fontSize = 12.sp) }
                    LiveIconAction(LiveSymbol.SEND, "发送评论", "live-space-comment-send-$id", comment.isNotBlank() && "comment-$id" !in state.busy) {
                        model.comment(id, comment, reply)
                    }
                }
                }
            }
            if (receipt != null && showComments) {
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
        mime.startsWith("audio/") || kind == "VOICE" -> LiveAudio(url, label, tag)
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
private fun LiveAudio(url: String, label: String, tag: String) {
    var player by remember(url) { mutableStateOf<MediaPlayer?>(null) }
    var status by remember(url) { mutableStateOf("播放") }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(url, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { player?.release(); player = null; status = "播放" }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player?.release(); player = null }
    }
    TextButton(onClick = {
        if (player != null) { player?.release(); player = null; status = "播放" }
        else {
            val created = MediaPlayer()
            player = created; status = "加载中…"
            created.setOnPreparedListener { prepared ->
                if (player === prepared) { prepared.start(); status = "停止" }
            }
            created.setOnCompletionListener { completed ->
                if (player === completed) { completed.release(); player = null; status = "播放" }
            }
            created.setOnErrorListener { failed, _, _ ->
                if (player === failed) { failed.release(); player = null; status = "播放失败，重试" }; true
            }
            runCatching { created.setDataSource(url); created.prepareAsync() }.onFailure {
                created.release(); player = null; status = "播放失败，重试"
            }
        }
    }, modifier = Modifier.testTag(tag)) { Text("$label · $status") }
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
