package com.charactermemory.android.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.charactermemory.android.data.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject

@Composable
internal fun LiveAvatar(name: String, path: String, model: LiveViewModel) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(LivePanel), contentAlignment = Alignment.Center) {
        Text(name.take(1), color = LiveAccent)
        if (path.isNotBlank()) AsyncImage(model.assetUrl(path), "$name 头像", modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun LiveChat(state: LiveState, model: LiveViewModel) {
    val target = state.target ?: return
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && model.state.value.page == LivePage.CHAT && model.state.value.target?.id == target.id) {
            model.startDictation()
        } else if (!granted) model.dictationPermissionDenied()
    }
    var showStickers by rememberSaveable(target.id) { mutableStateOf(false) }
    var advanced by rememberSaveable(target.id) { mutableStateOf(false) }
    var conversationOverride by rememberSaveable(target.id, target.conversationId) { mutableStateOf(target.conversationId) }
    val list = rememberLazyListState()
    var lastCount by remember(target.id) { mutableIntStateOf(0) }
    LaunchedEffect(state.messages.size) {
        if (state.messages.size > lastCount && (lastCount == 0 || list.layoutInfo.visibleItemsInfo.lastOrNull()?.index == lastCount)) {
            // The history/loading row occupies index 0 before the message items.
            if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.size)
        }
        lastCount = state.messages.size
    }
    Column(Modifier.fillMaxSize().testTag("live-chat").padding(horizontal = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.streamStatus, color = LiveMuted, modifier = Modifier.weight(1f).testTag("live-stream-status"))
            TextButton(onClick = { model.loadHistory() }, modifier = Modifier.testTag("live-chat-refresh")) { Text("刷新历史") }
            if (!target.group) TextButton(onClick = model::loadPersona, modifier = Modifier.testTag("live-details-open")) { Text("详情") }
        }
        // Do not show protocol/debug states as permanent chat copy when the character is idle.
        if (state.reaction in setOf("queued", "typing", "superseded", "error")) {
            Text(when (state.reaction) {
                "queued" -> "已收到消息，等待人物"
                "typing" -> "人物正在回复…"
                "superseded" -> "本轮已被新消息替代"
                else -> "人物回复失败；你的消息已保存"
            }, color = LiveAccent, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("live-reaction-status"))
        }
        state.reactionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("live-reaction-error")) }
        if (target.group && state.memberProgress.isNotEmpty()) Text(state.memberProgress.joinToString(" · ") {
            "${it.text("character_id")}：${if (it.flag("silent")) "已完成 / 沉默" else "已完成"}"
        }, color = LiveMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("live-member-progress"))
        if (!target.group) {
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("live-conversation-options")) { Text("会话 ID 兼容设置") }
            if (advanced) {
                Text("历史按人物返回。此 ID 仅控制当前实时频道；如需兼容 Web，可手动填写 Web 的已知 ID，跨端同步仍待 Core 契约。",
                    color = LiveMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(conversationOverride, { conversationOverride = it }, singleLine = true, label = { Text("Direct 会话 ID") },
                    modifier = Modifier.fillMaxWidth().testTag("live-conversation-id"))
                LiveAction("使用此 ID", "live-conversation-save", "send" !in state.busy) { model.overrideConversationId(conversationOverride) }
            }
        }
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
                val fromUser = message.text("role") == "user"
                // The original mock storyboard uses separate right/left bubbles, not full-width cards.
                // Reserve room for the character avatar even on 320dp/narrow devices.
                BoxWithConstraints(Modifier.fillMaxWidth().testTag("live-message-${message.text("id")}")) {
                    val maxBubble = (maxWidth - if (fromUser) 24.dp else 80.dp).coerceAtMost(360.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Bottom) {
                        if (!fromUser) {
                            LiveAvatar(speakerName, state.avatars[speakerId.ifBlank { target.id }].orEmpty(), model)
                            Spacer(Modifier.width(8.dp))
                        }
                        Card(modifier = Modifier.widthIn(max = maxBubble)
                            .testTag("live-message-bubble-${if (fromUser) "user" else "character"}-${message.text("id")}"),
                            colors = CardDefaults.cardColors(containerColor =
                                if (fromUser) androidx.compose.ui.graphics.Color(0xFF234D9D) else LivePanel)) {
                            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(if (fromUser) "我 · 已保存" else message.text("actor_name", speakerName),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (fromUser) androidx.compose.ui.graphics.Color(0xFFD8EAFF) else LiveAccent)
                                if (message.text("content").isNotBlank()) Text(message.text("content"))
                                message.objOrNull("sticker")?.let { LiveMedia(it, model, "live-message-sticker-${message.text("id")}") }
                                message.objOrNull("image")?.let { LiveMedia(it, model, "live-message-image-${message.text("id")}") }
                                if (!fromUser && message.text("content").isNotBlank()) {
                                    TextButton(onClick = {
                                        model.toggleSpeech(message.text("id"), message.text("content"), speakerId)
                                    }, modifier = Modifier.testTag("live-message-speak-${message.text("id")}"),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)) {
                                        Text(if (state.speakingMessageId == message.text("id")) "■ 停止朗读" else "▶ 朗读",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (state.speakingMessageId == message.text("id") && state.speechStatus.isNotBlank())
                                        Text(state.speechStatus, color = LiveMuted, style = MaterialTheme.typography.labelSmall)
                                }
                                message.text("event_time").takeIf { it.length >= 16 }?.let { timestamp ->
                                    Text(timestamp.substring(11, 16), style = MaterialTheme.typography.labelSmall,
                                        color = LiveMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showStickers) {
            LazyColumn(Modifier.heightIn(max = 180.dp).fillMaxWidth().testTag("live-stickers")) {
                if (state.stickers.isEmpty()) item { Text(if ("stickers" in state.busy) "加载表情…" else "暂无表情", color = LiveMuted) }
                items(state.stickers, key = { it.text("id") }) { sticker ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AsyncImage(model.assetUrl(sticker.text("url", "/v1/stickers/${sticker.text("id")}/asset")), sticker.text("label"), modifier = Modifier.size(48.dp))
                        TextButton(onClick = { model.send(stickerId = sticker.text("id")); showStickers = false },
                            enabled = "send" !in state.busy, modifier = Modifier.testTag("live-sticker-send-${sticker.text("id")}")) { Text("发送 ${sticker.text("label", sticker.text("id"))}") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom) {
            TextButton(onClick = { showStickers = !showStickers; if (showStickers) model.loadStickers() },
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 9.dp),
                modifier = Modifier.testTag("live-stickers-open")) { Text("☺") }
            TextButton(onClick = { model.show(LivePage.IMAGE) },
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 9.dp),
                modifier = Modifier.testTag("live-image-open")) { Text("✦") }
            OutlinedTextField(state.composeText, model::editText, placeholder = { Text("输入消息…") }, maxLines = 4,
                modifier = Modifier.weight(1f).testTag("live-chat-input"))
            TextButton(onClick = {
                if (state.dictating) model.stopDictation()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    model.startDictation()
                } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = "asr" !in state.busy && "send" !in state.busy,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.testTag("live-dictation-toggle")) {
                Text(if (state.dictating) "停止" else "🎙")
            }
            Button(onClick = { model.send() }, enabled = "send" !in state.busy && state.composeText.isNotBlank() && !state.dictating,

                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF397CFF)),
                modifier = Modifier.testTag("live-chat-send")) {
                Text(if ("send" in state.busy) "…" else "发送")
            }
        }
        if (state.dictationStatus.isNotBlank()) {
            Text(state.dictationStatus, color = LiveMuted, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 4.dp).testTag("live-dictation-status"))
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

