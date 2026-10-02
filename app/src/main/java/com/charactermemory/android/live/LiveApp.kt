package com.charactermemory.android.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.charactermemory.android.data.*

internal val LiveNavy = Color(0xFF091120)
internal val LivePanel = Color(0xFF151F31)
internal val LiveAccent = Color(0xFF79A9FF)
internal val LiveMuted = Color(0xFF9BAFCB)

@Composable
fun LiveApp(model: LiveViewModel = viewModel(factory = LiveViewModel.factory(LocalContext.current))) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.activate()
                Lifecycle.Event.ON_STOP -> model.deactivate()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.activate()
        onDispose { owner.lifecycle.removeObserver(observer); model.deactivate() }
    }
    val roots = setOf(LivePage.HOME, LivePage.SPACE, LivePage.SETTINGS)
    BackHandler(state.page !in roots) { model.back() }
    MaterialTheme(colorScheme = darkColorScheme(primary = LiveAccent, background = LiveNavy,
        surface = LivePanel, onSurface = Color(0xFFEAF1FF), onPrimary = LiveNavy)) {
        Scaffold(containerColor = LiveNavy, contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Row(Modifier.fillMaxWidth().background(LiveNavy).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.page !in roots) TextButton(onClick = model::back, modifier = Modifier.testTag("live-back")) { Text("返回") }
                    Text(when (state.page) {
                        LivePage.HOME -> "Character Memory"
                        LivePage.CHAT -> state.target?.name ?: "聊天"
                        LivePage.SETTINGS -> "连接设置"
                        LivePage.CHARACTER -> "创建人物"
                        LivePage.ENSEMBLE -> "创建群聊"
                        LivePage.SPACE -> "Space"
                        LivePage.IMAGE -> "图片草稿"
                        LivePage.DETAILS -> "人物详情"
                    }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
            }, bottomBar = {
                if (state.page in roots) NavigationBar(containerColor = LivePanel) {
                    listOf(LivePage.HOME to "聊天", LivePage.SPACE to "空间", LivePage.SETTINGS to "设置").forEach { (page, label) ->
                        NavigationBarItem(selected = state.page == page, onClick = { model.show(page) },
                            icon = { Text(if (page == LivePage.HOME) "◉" else if (page == LivePage.SPACE) "◎" else "⚙") },
                            label = { Text(label) }, modifier = Modifier.testTag("live-tab-${page.name.lowercase()}"))
                    }
                }
            }) { inner ->
            Column(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding()) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()
                    .testTag("live-error").padding(horizontal = 16.dp, vertical = 8.dp)) }
                state.notice?.let { Text(it, color = LiveAccent, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().testTag("live-notice").padding(horizontal = 16.dp, vertical = 4.dp)) }
                Box(Modifier.weight(1f)) {
                    when (state.page) {
                        LivePage.HOME -> LiveHome(state, model)
                        LivePage.SETTINGS -> LiveSettings(state, model)
                        LivePage.CHAT -> LiveChat(state, model)
                        LivePage.CHARACTER -> LiveCharacter(state, model)
                        LivePage.ENSEMBLE -> LiveEnsemble(state, model)
                        LivePage.SPACE -> LiveSpace(state, model)
                        LivePage.IMAGE -> LiveImage(state, model)
                        LivePage.DETAILS -> LiveDetails(state)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LiveAction(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(label) }
}

@Composable
internal fun LiveSettings(state: LiveState, model: LiveViewModel) {
    var core by rememberSaveable(state.config.coreUrl) { mutableStateOf(state.config.coreUrl) }
    var media by rememberSaveable(state.config.mediaUrl) { mutableStateOf(state.config.mediaUrl) }
    Column(Modifier.fillMaxSize().testTag("live-settings").verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("输入 PC Core 的 HTTPS 地址；Media 留空时使用同主机 :8443。", color = LiveMuted)
        OutlinedTextField(core, { core = it }, label = { Text("Core HTTPS 地址") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("live-core-url"))
        OutlinedTextField(media, { media = it }, label = { Text("Media HTTPS 地址（可选）") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("live-media-url"))
        LiveAction("保存并检查", "live-save-config") { model.saveConfig(core, media) }
        Text("Core：${state.coreHealth}", modifier = Modifier.testTag("live-core-status"))
        Text("Media：${state.mediaHealth}", modifier = Modifier.testTag("live-media-status"))
        LiveAction("重新检查", "live-health-refresh", state.config.coreUrl.isNotBlank()) { model.checkHealth() }
        OutlinedButton(onClick = model::resetConfig, modifier = Modifier.testTag("live-reset-config")) { Text("清除服务器配置") }
        Text("配置只保存在此手机。文字聊天可独立于 Media 使用。当前 Core 的设备配对与统一跨端会话尚未提供；本页不代表设备授权。",
            style = MaterialTheme.typography.bodySmall, color = LiveMuted)
    }
}

@Composable
internal fun LiveHome(state: LiveState, model: LiveViewModel) {
    LazyColumn(Modifier.fillMaxSize().testTag("live-home"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveAction("创建人物", "live-create-character") { model.show(LivePage.CHARACTER) }
                LiveAction("创建群聊", "live-create-group") { model.show(LivePage.ENSEMBLE) }
            }
            TextButton(onClick = model::refresh, modifier = Modifier.testTag("live-refresh")) { Text(if ("roster" in state.busy) "加载中…" else "刷新") }
            if (state.characters.isEmpty() && "roster" !in state.busy) Text("暂无人物，请连接 Core 或创建人物。", color = LiveMuted)
        }
        items(state.characters, key = { it.text("id") }) { character ->
            val id = character.text("id")
            Card(onClick = { model.openCharacter(id) }, modifier = Modifier.fillMaxWidth().testTag("live-character-$id"),
                colors = CardDefaults.cardColors(containerColor = LivePanel)) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiveAvatar(character.text("name", id), state.avatars[id].orEmpty(), model)
                    Column(Modifier.weight(1f)) {
                        Text(character.text("name", id), style = MaterialTheme.typography.titleMedium)
                        Text(character.text("identity", id), color = LiveMuted, style = MaterialTheme.typography.bodySmall)
                        val latest = state.summaries[id]?.objOrNull("latest_message")
                        Text(latest?.text("preview", latest.text("content")) ?: "暂无消息", color = LiveMuted)
                    }
                }
            }
        }
        item { Text("群聊", style = MaterialTheme.typography.titleMedium) }
        items(state.groups, key = { it.text("id") }) { group ->
            val id = group.text("id")
            Card(onClick = { model.openGroup(id) }, modifier = Modifier.fillMaxWidth().testTag("live-group-$id"),
                colors = CardDefaults.cardColors(containerColor = LivePanel)) {
                Column(Modifier.padding(14.dp)) {
                    Text(group.text("name", id), style = MaterialTheme.typography.titleMedium)
                    Text(group.items("members").joinToString(" · ") { it.text("name", it.text("id")) }, color = LiveMuted)
                }
            }
        }
        item { if (state.groups.isEmpty()) Text("暂无群聊", color = LiveMuted) }
    }
}

