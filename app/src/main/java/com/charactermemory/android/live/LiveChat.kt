package com.charactermemory.android.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.charactermemory.android.data.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.util.Locale

@Composable
internal fun LiveAvatar(name: String, path: String, model: LiveViewModel, size: Dp = 48.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF29436B), Color(0xFF293B65)))), contentAlignment = Alignment.Center) {
        Text(name.take(1), color = LivePale, fontSize = if (size >= 48.dp) 21.sp else 14.sp)
        if (path.isNotBlank()) AsyncImage(model.assetUrl(path), "$name 头像", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun PersistedVoiceMessageContent(message: JsonObject, assetUrl: String, playbackOwner: String, tag: String) {
    val presentation = persistedVoicePresentation(message)
    val content = message.text("content")
    if (content.isNotBlank()) Text(content, color = LivePale, fontSize = 15.sp, lineHeight = 23.sp,
        modifier = Modifier.testTag("$tag-content"))
    when (presentation.status) {
        PersistedVoiceStatus.PENDING -> Text("语音生成中", color = LiveMuted, fontSize = 12.sp,
            modifier = Modifier.testTag("$tag-status"))
        PersistedVoiceStatus.READY -> {
            if (!presentation.canPlay || assetUrl.isBlank()) {
                Text("语音资源不可用", color = LiveMuted, fontSize = 12.sp,
                    modifier = Modifier.testTag("$tag-status"))
            } else {
                val duration = presentation.durationMs?.let { " · ${it} 毫秒" }.orEmpty()
                Text("语音已就绪$duration", color = LiveMuted, fontSize = 12.sp,
                    modifier = Modifier.testTag("$tag-status"))
                LiveAudioPlayerButton(assetUrl, "播放语音", "$tag-play", playbackOwner)
            }
        }
        PersistedVoiceStatus.FAILED -> {
            Text("语音生成失败", color = LiveMuted, fontSize = 12.sp, modifier = Modifier.testTag("$tag-status"))
            presentation.error?.let { Text(it, color = LiveMuted, fontSize = 11.sp, modifier = Modifier.testTag("$tag-error")) }
        }
        PersistedVoiceStatus.UNKNOWN -> Text("语音状态未知", color = LiveMuted, fontSize = 12.sp,
            modifier = Modifier.testTag("$tag-status"))
    }
}

@Composable
internal fun LiveChat(state: LiveState, model: LiveViewModel) {
    val target = state.target ?: return
    val callState by model.call.state.collectAsStateWithLifecycle()
    var showStickers by rememberSaveable(target.id) { mutableStateOf(false) }
    var selectedStickerPackId by rememberSaveable(target.id) { mutableStateOf("") }
    val stickerPacks = remember(state.stickers) { LiveRules.stickerPacks(state.stickers) }
    LaunchedEffect(showStickers, stickerPacks) {
        if (showStickers && stickerPacks.isNotEmpty() && stickerPacks.none { it.id == selectedStickerPackId }) {
            selectedStickerPackId = stickerPacks.first().id
        }
    }
    var advanced by rememberSaveable(target.id) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var memberDetails by remember { mutableStateOf(false) }
    var showTools by rememberSaveable(target.id) { mutableStateOf(false) }
    var conversationOverride by rememberSaveable(target.id, target.conversationId) { mutableStateOf(target.conversationId) }
    val list = rememberLazyListState()
    var lastCount by remember(target.id) { mutableIntStateOf(0) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) { if (imeVisible) { showStickers = false; showTools = false } }
    LaunchedEffect(state.messages.size) {
        if (state.messages.size > lastCount && (lastCount == 0 || list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == lastCount)) {
            // The history/loading row occupies index 0 before the message items.
            if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.size)
        }
        lastCount = state.messages.size
    }
    Column(Modifier.fillMaxSize().testTag("live-chat").padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.streamStatus, color = LiveMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("live-stream-status"))
                Text(when (state.reaction) {
                    "queued" -> "等待处理"
                    "typing" -> "正在处理…"
                    "superseded" -> "已转入新消息"
                    "error" -> "回复失败"
                    else -> "空闲"
                }, color = if (state.reaction == "idle") LiveMuted else LiveAccent, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("live-reaction-status"))
            }
            Box {
                LiveIconAction(LiveSymbol.MORE, "更多聊天操作", "live-chat-more") { more = true }
                DropdownMenu(more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("刷新历史") }, onClick = { more = false; model.loadHistory() }, modifier = Modifier.testTag("live-chat-refresh"))
                    if (!target.group) {
                        DropdownMenuItem(text = { Text("人物详情") }, onClick = { more = false; model.loadPersona() }, modifier = Modifier.testTag("live-details-open"))
                        DropdownMenuItem(text = { Text("会话 ID 兼容设置") }, onClick = { more = false; advanced = true }, modifier = Modifier.testTag("live-conversation-options"))
                    }
                }
            }
        }
        state.reactionError?.let { LiveFeedback(it, "live-reaction-error", true) }
        if (target.group && state.memberProgress.isNotEmpty()) TextButton(onClick = { memberDetails = true }, modifier = Modifier.testTag("live-member-progress")) {
            Text("本轮已完成 ${state.memberProgress.size}/${target.memberIds.size} 位成员", color = LiveMuted, fontSize = 11.sp)
        }
        if (memberDetails) AlertDialog(onDismissRequest = { memberDetails = false }, title = { Text("本轮成员进度") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                state.memberProgress.forEach { Text("${it.text("character_id")}：${if (it.flag("silent")) "已完成 / 沉默" else "已完成"}") }
            } }, confirmButton = { TextButton(onClick = { memberDetails = false }) { Text("关闭") } })
        if (advanced) AlertDialog(onDismissRequest = { advanced = false }, title = { Text("Direct 会话 ID") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("历史按人物返回。此 ID 控制当前实时频道；如需兼容 Web，可填写 Web 的已知 ID。跨端同步仍待 Core 契约。", color = LiveMuted)
                OutlinedTextField(conversationOverride, { conversationOverride = it }, singleLine = true, label = { Text("会话 ID") },
                    modifier = Modifier.fillMaxWidth().testTag("live-conversation-id"))
            } }, confirmButton = { TextButton(onClick = { model.overrideConversationId(conversationOverride); advanced = false }, enabled = "send" !in state.busy,
                modifier = Modifier.testTag("live-conversation-save")) { Text("使用此 ID") } },
            dismissButton = { TextButton(onClick = { advanced = false }) { Text("取消") } })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("live-messages"), state = list,
            verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            item {
                if (state.historyCursor != null) TextButton(onClick = { model.loadHistory(true) },
                    enabled = "history" !in state.busy, modifier = Modifier.testTag("live-history-older")) { Text("加载更早消息") }
                if ("history" in state.busy) Text("加载历史…", color = LiveMuted)
                if (state.messages.isEmpty() && "history" !in state.busy) Text("暂无消息", color = LiveMuted)
            }
            items(state.messages, key = { it.text("id") }) { message ->
                val speakerId = message.text("actor_id")
                val speakerName = if (target.group) state.groups.firstOrNull { it.text("id") == target.id }
                    ?.items("members")?.firstOrNull { it.text("id") == speakerId }?.text("name", speakerId)
                    ?: speakerId else target.name
                LiveMessageBubble(message, message.text("actor_name", speakerName), if (target.group) speakerId else target.id, state, model)
            }
        }
        if (showStickers) {
            Column(Modifier.heightIn(max = 230.dp).fillMaxWidth().testTag("live-stickers")) {
                if (stickerPacks.isEmpty()) {
                    Text(if ("stickers" in state.busy) "加载表情…" else "暂无表情", color = LiveMuted)
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("live-sticker-pack-tabs"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        stickerPacks.forEach { pack ->
                            FilterChip(
                                selected = selectedStickerPackId == pack.id,
                                onClick = { selectedStickerPackId = pack.id },
                                label = { Text(pack.name, maxLines = 1) },
                                modifier = Modifier.testTag("live-sticker-pack-${pack.id}")
                            )
                        }
                    }
                    val selectedPack = stickerPacks.firstOrNull { it.id == selectedStickerPackId } ?: stickerPacks.first()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp, max = 184.dp).testTag("live-sticker-grid"),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        gridItems(selectedPack.stickers, key = { it.text("id") }) { sticker ->
                            val stickerId = sticker.text("id")
                            Column(Modifier.fillMaxWidth().testTag("live-sticker-$stickerId"),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                AsyncImage(
                                    model = model.assetUrl(sticker.text("url", "/v1/stickers/$stickerId/asset")),
                                    contentDescription = sticker.text("label"),
                                    modifier = Modifier.size(48.dp).clickable(enabled = "send" !in state.busy) {
                                        model.send(stickerId = stickerId); showStickers = false
                                    }
                                )
                                TextButton(onClick = { model.send(stickerId = stickerId); showStickers = false },
                                    enabled = "send" !in state.busy,
                                    modifier = Modifier.testTag("live-sticker-send-$stickerId")) {
                                    Text("发送 ${sticker.text("label", stickerId)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!imeVisible || showTools) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            LiveIconAction(LiveSymbol.STICKER, "表情", "live-stickers-open") { showStickers = !showStickers; if (showStickers) model.loadStickers() }
            LiveIconAction(LiveSymbol.SPARKLE, "生成图片草稿", "live-image-open") { model.show(LivePage.IMAGE) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            if (imeVisible && !showTools) LiveIconAction(LiveSymbol.TOOLS, "展开聊天工具", "live-chat-tools") { showTools = true }
            OutlinedTextField(state.composeText, model::editText, enabled = !callState.active, placeholder = { Text("输入消息…", fontSize = 14.sp) }, maxLines = 4,
                shape = RoundedCornerShape(17.dp), textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp),
                modifier = Modifier.weight(1f).testTag("live-chat-input"))
            LiveIconAction(LiveSymbol.SEND, if ("send" in state.busy) "正在发送" else "发送消息", "live-chat-send",
                !callState.active && "send" !in state.busy && state.composeText.isNotBlank()) { model.send() }
        }
        if (!imeVisible || callState.active || model.voice.state.collectAsStateWithLifecycle().value.phase != com.charactermemory.android.audio.VoiceCoordinatorPhase.IDLE)
            LiveVoiceInput(model)
    }
}

@Composable
private fun LiveMessageBubble(message: JsonObject, author: String, characterId: String, state: LiveState, model: LiveViewModel) {
    val outbound = message.text("role") == "user"
    val id = message.text("id")
    val voiceMessage = message.text("action") == "VOICE_MESSAGE"
    val voice = if (voiceMessage) persistedVoicePresentation(message) else null
    val assetPath = voice?.let { if (it.canPlay) voiceMediaPath(it.mediaId) else null }
    val assetUrl = assetPath?.let(model::assetUrl).orEmpty()
    val target = state.target
    val playbackOwner = listOf("chat", state.config.coreUrl, target?.group.toString(), target?.id.orEmpty(),
        target?.conversationId.orEmpty(), id).joinToString("\u0000")
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("live-message-$id")) {
        val maxBubble = minOf(310.dp, maxWidth * 0.78f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outbound) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top) {
            if (!outbound) {
                LiveAvatar(author, state.avatars[characterId].orEmpty(), model, 32.dp)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.widthIn(max = maxBubble), horizontalAlignment = if (outbound) Alignment.End else Alignment.Start) {
                if (!outbound) Text(author, color = LiveMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Card(Modifier.widthIn(max = maxBubble).padding(top = 4.dp).testTag("live-message-bubble-$id"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (outbound) Color(0xFF2959A0) else Color(0xFF283447))) {
                    Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (voiceMessage) PersistedVoiceMessageContent(message, assetUrl, playbackOwner, "live-message-voice-$id")
                        else if (message.text("content").isNotBlank()) Text(message.text("content"), color = LivePale, fontSize = 15.sp, lineHeight = 23.sp)
                        if (!outbound && !voiceMessage && message.text("content").isNotBlank())
                            LiveSpeechButton(message.text("content"), playbackOwner, model, "live-message-tts-$id")
                        message.objOrNull("sticker")?.let { LiveMedia(it, model, "live-message-sticker-$id") }
                        message.objOrNull("image")?.let { LiveMedia(it, model, "live-message-image-$id") }
                    }
                }
                val time = LiveTime.short(message.text("event_time"))
                if (time.isNotEmpty()) Text(time, color = LiveMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
internal fun LiveDetails(state: LiveState) {
    Column(Modifier.fillMaxSize().testTag("live-details").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("人物：${state.target?.name.orEmpty()} · ${state.target?.id.orEmpty()}")
        if ("persona" in state.busy) Text("加载人物详情…")
        state.persona?.let { LiveJsonDetails(it) }
    }
}

/** Structured draft/persona content, including unknown additive fields, without raw JSON chrome. */
@Composable
internal fun LiveJsonDetails(value: JsonElement, depth: Int = 0) {
    when {
        value.isJsonNull -> Unit
        value.isJsonObject -> value.asJsonObject.entrySet().filter { it.key !in setOf("data_url", "persona_path") }.forEach { (key, child) ->
            Column(Modifier.padding(start = (depth.coerceAtMost(3) * 8).dp, bottom = 6.dp)) {
                Text(detailLabel(key), color = LiveAccent, style = MaterialTheme.typography.labelLarge)
                LiveJsonDetails(child, depth + 1)
            }
        }
        value.isJsonArray -> value.asJsonArray.forEach { LiveJsonDetails(it, depth + 1) }
        else -> Text(value.asString, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun detailLabel(key: String): String = when (key) {
    "name", "canonical_name" -> "姓名"
    "description" -> "描述"
    "identity" -> "身份"
    "age" -> "年龄"
    "tags" -> "标签"
    "personality" -> "性格"
    "background" -> "背景"
    "overview" -> "概况"
    "sources" -> "资料来源"
    "persona" -> "人物档案"
    else -> key
}

