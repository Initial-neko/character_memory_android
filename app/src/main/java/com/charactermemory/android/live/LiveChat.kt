package com.charactermemory.android.live

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
        Text(when (state.reaction) {
            "queued" -> "已排队，等待人物"
            "typing" -> "人物正在处理"
            "superseded" -> "本轮已被新消息替代"
            "error" -> "人物回复失败，原消息保留"
            else -> "当前空闲；人物可以选择保持沉默"
        }, color = LiveAccent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("live-reaction-status"))
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
                Card(modifier = Modifier.fillMaxWidth().testTag("live-message-${message.text("id")}"),
                    colors = CardDefaults.cardColors(containerColor = if (message.text("role") == "user") androidx.compose.ui.graphics.Color(0xFF1D2B47) else LivePanel)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (message.text("role") == "user") "我 · 已保存" else message.text("actor_name", speakerName),
                            style = MaterialTheme.typography.labelMedium, color = LiveAccent)
                        if (message.text("content").isNotBlank()) Text(message.text("content"))
                        message.objOrNull("sticker")?.let { LiveMedia(it, model, "live-message-sticker-${message.text("id")}") }
                        message.objOrNull("image")?.let { LiveMedia(it, model, "live-message-image-${message.text("id")}") }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showStickers = !showStickers; if (showStickers) model.loadStickers() }, modifier = Modifier.testTag("live-stickers-open")) { Text("表情") }
            TextButton(onClick = { model.show(LivePage.IMAGE) }, modifier = Modifier.testTag("live-image-open")) { Text("生成图片") }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(state.composeText, model::editText, label = { Text("消息") }, maxLines = 4,
                modifier = Modifier.weight(1f).testTag("live-chat-input"))
            LiveAction(if ("send" in state.busy) "发送中" else "发送", "live-chat-send",
                "send" !in state.busy && state.composeText.isNotBlank()) { model.send() }
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

