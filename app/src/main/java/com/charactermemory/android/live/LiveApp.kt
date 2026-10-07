package com.charactermemory.android.live

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.charactermemory.android.MainActivity
import com.charactermemory.android.data.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

internal data class CallPresentation(
    val isInPip: Boolean,
    val enterPip: () -> Unit,
    val setConsentInFlight: (name: String, inFlight: Boolean) -> Unit
)

internal val LocalCallPresentation = staticCompositionLocalOf {
    CallPresentation(false, {}, { _, _ -> })
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}

@Composable
fun LiveApp(model: LiveViewModel = viewModel(factory = LiveViewModel.factory(LocalContext.current))) {
    val state by model.state.collectAsStateWithLifecycle()
    val rssScope=rememberCoroutineScope()
    val rss=remember(state.config) {RssController(RssRepository(model.api),rssScope)}
    val rssState by rss.state.collectAsStateWithLifecycle()
    val rssGrid=rememberLazyStaggeredGridState()
    DisposableEffect(rss) {onDispose {rss.close()}}
    LaunchedEffect(state.page,rss) {if(state.page==LivePage.RSS && state.config.coreUrl.isNotBlank()) rss.start()}
    LaunchedEffect(rssState.query,rss) {rssGrid.scrollToItem(0)}
    val call by model.call.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findMainActivity() }
    val isInPip = activity?.isCallPipMode == true
    val fullScreenCall = call.active && state.page == LivePage.CHAT
    DisposableEffect(activity, model) {
        activity?.attachCallModel(model)
        onDispose { activity?.detachCallModel(model) }
    }
    SideEffect {
        activity?.updateCallPictureInPicture(state.target?.name ?: "人物", call, state.page == LivePage.CHAT)
    }
    val callPresentation = CallPresentation(
        isInPip = isInPip,
        enterPip = {
            activity?.enterCallPictureInPicture(
                state.target?.name ?: "人物",
                model.call.state.value,
                state.page == LivePage.CHAT
            )
        },
        setConsentInFlight = { name, inFlight -> activity?.setCallConsentInFlight(name, inFlight) }
    )
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
    val roots = setOf(LivePage.HOME, LivePage.SPACE, LivePage.RSS, LivePage.SETTINGS)
    BackHandler(state.page !in roots) { model.back() }
    CompositionLocalProvider(LocalCallPresentation provides callPresentation) {
      MaterialTheme(colorScheme = darkColorScheme(primary = LiveAccent, secondary = LivePurple, background = LiveNavy,
        surface = LivePanel, onSurface = LivePale, onPrimary = LiveNavy)) {
        Scaffold(containerColor = LiveNavy, contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (!isInPip && !fullScreenCall && state.page!=LivePage.RSS) {
                Row(Modifier.fillMaxWidth().background(LiveNavy).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .heightIn(min = 62.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.page !in roots) LiveIconAction(LiveSymbol.BACK, "返回", "live-back", onClick = model::back)
                    if (state.page == LivePage.CHAT && state.target?.group == false) {
                        val target = requireNotNull(state.target)
                        LiveAvatar(target.name, state.avatars[target.id].orEmpty(), model, 32.dp)
                    }
                    Text(when (state.page) {
                        LivePage.HOME -> "Character Memory"
                        LivePage.CHAT -> state.target?.name ?: "聊天"
                        LivePage.SETTINGS -> "连接设置"
                        LivePage.CHARACTER -> "创建人物"
                        LivePage.ENSEMBLE -> "创建群聊"
                        LivePage.SPACE -> "空间"
                        LivePage.RSS -> "信息流"
                        LivePage.IMAGE -> "图片草稿"
                        LivePage.DETAILS -> "人物详情"
                        LivePage.USAGE -> "LLM 使用情况"
                    }, color = LivePale, fontSize = if (state.page in roots) 22.sp else 20.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (state.page == LivePage.HOME) LiveIconAction(LiveSymbol.REFRESH, "刷新会话列表", "live-refresh",
                        "roster" !in state.busy, model::refresh)
                }
                }
            }, bottomBar = {
                if (!isInPip && state.page in roots && (state.page!=LivePage.RSS || rssState.page==RssPage.FEED)) NavigationBar(containerColor = Color(0xFF111B2C)) {
                    listOf(LivePage.HOME to "聊天", LivePage.SPACE to "空间", LivePage.RSS to "信息", LivePage.SETTINGS to "设置").forEach { (page, label) ->
                        NavigationBarItem(selected = state.page == page, onClick = { model.show(page) },
                            icon = {
                                if (page == LivePage.SPACE && state.spaceUnreadCount > 0) {
                                    BadgedBox(badge = { Badge {
                                        Text(if (state.spaceUnreadCount > 99) "99+" else state.spaceUnreadCount.toString(),
                                            modifier = Modifier.testTag("live-space-unread-badge"))
                                    } }) {
                                        LiveGlyph(if (page == LivePage.HOME) LiveSymbol.CHAT else if (page == LivePage.SPACE) LiveSymbol.SPACE else LiveSymbol.SETTINGS,
                                            tint = if (state.page == page) LiveAccent else LiveMuted)
                                    }
                                } else {
                                    LiveGlyph(if (page == LivePage.HOME) LiveSymbol.CHAT else if (page == LivePage.SPACE) LiveSymbol.SPACE else if(page==LivePage.RSS) LiveSymbol.INFO else LiveSymbol.SETTINGS,
                                        tint = if (state.page == page) LiveAccent else LiveMuted)
                                }
                            },
                            label = { Text(label) }, modifier = Modifier.testTag("live-tab-${page.name.lowercase()}"))
                    }
                }
            }) { inner ->
            if (isInPip) {
                val target = state.target
                if (call.active && target != null) {
                    CallPipCompactContent(state, target, model, call.startedAtMs)
                } else {
                    Box(Modifier.fillMaxSize().padding(inner).background(LiveNavy), contentAlignment = Alignment.Center) {
                        Text("通话已结束", color = LivePale, modifier = Modifier.testTag("live-call-pip-ended"))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding()
                    .background(Brush.verticalGradient(listOf(LiveNavy, Color(0xFF0C1428), LiveNavy)))) {
                    if (call.active && state.page != LivePage.CHAT) {
                        LiveOngoingCall(state, model, call)
                    }
                    state.error?.let { LiveFeedback(it, "live-error", true) }
                    state.notice?.let { LiveFeedback(it, "live-notice") }
                    Box(Modifier.weight(1f)) {
                        when (state.page) {
                            LivePage.HOME -> LiveHome(state, model)
                            LivePage.SETTINGS -> LiveSettings(state, model)
                            LivePage.CHAT -> LiveChat(state, model)
                            LivePage.CHARACTER -> LiveCharacter(state, model)
                            LivePage.ENSEMBLE -> LiveEnsemble(state, model)
                            LivePage.SPACE -> LiveSpace(state, model)
                            LivePage.RSS -> LiveRss(rss,rssGrid) {model.show(LivePage.SETTINGS)}
                            LivePage.IMAGE -> LiveImage(state, model)
                            LivePage.DETAILS -> LiveDetails(state)
                            LivePage.USAGE -> LiveUsage(state, model)
                        }
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun CallPipCompactContent(state: LiveState, target: ChatTarget, model: LiveViewModel, startedAtMs: Long) {
    val call by model.call.state.collectAsStateWithLifecycle()
    val duration = callElapsedText(startedAtMs)
    Box(Modifier.fillMaxSize().background(LiveNavy).testTag("live-call-pip-content")) {
        val avatar = state.avatars[target.id].orEmpty()
        if (avatar.isNotBlank()) {
            AsyncImage(
                model = model.assetUrl(avatar),
                contentDescription = "${target.name}人物画面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF29436B), LiveNavy))),
                contentAlignment = Alignment.Center) {
                Text(target.name.take(1), color = LivePale.copy(alpha = .45f), fontSize = 88.sp)
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            colorStops = arrayOf(0f to Color.Transparent, .5f to Color.Transparent, 1f to Color.Black.copy(alpha = .82f))
        )))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(target.name, color = Color.White, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("live-call-pip-name"))
            Text(duration, color = Color.White.copy(alpha = .82f),
                fontSize = 12.sp, modifier = Modifier.testTag("live-call-pip-duration"))
            Text(callStatusText(call), color = Color.White.copy(alpha = .82f), fontSize = 11.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("live-call-pip-status"))
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
        LiveAction("查看 LLM 使用情况", "live-usage-open", state.config.coreUrl.isNotBlank()) { model.show(LivePage.USAGE) }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveQuickAction(LiveSymbol.PERSON, "生成人物", Modifier.weight(1f), "live-create-character") { model.show(LivePage.CHARACTER) }
                LiveQuickAction(LiveSymbol.GROUP, "生成群聊", Modifier.weight(1f), "live-create-group") { model.show(LivePage.ENSEMBLE) }
                LiveQuickAction(LiveSymbol.SPACE, "空间", Modifier.weight(1f), "live-home-space") { model.show(LivePage.SPACE) }
            }
            LiveSection("全部对话", "人物与最近消息")
            if ("roster" in state.busy) Text("加载中…", color = LiveMuted)
            if (state.characters.isEmpty() && "roster" !in state.busy) Text("暂无人物，请连接 Core 或创建人物。", color = LiveMuted)
        }
        items(state.characters, key = { it.text("id") }) { character ->
            val id = character.text("id")
            LivePanelCard(Modifier.fillMaxWidth().clickable { model.openCharacter(id) }.testTag("live-character-$id")) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiveAvatar(character.text("name", id), state.avatars[id].orEmpty(), model)
                    Column(Modifier.weight(1f)) {
                        Text(character.text("name", id), color = LivePale, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        val latest = state.summaries[id]?.objOrNull("latest_message")
                        Text(latest?.text("preview", latest.text("content")) ?: character.text("identity", "暂无消息"),
                            color = LiveMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val time = LiveTime.short(state.summaries[id]?.objOrNull("latest_message")?.text("event_time").orEmpty())
                    if (time.isNotEmpty()) Text(time, color = LiveMuted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.testTag("live-character-time-$id"))
                }
            }
        }
        item { LiveSection("群聊", "一起聊天的空间") }
        items(state.groups, key = { it.text("id") }) { group ->
            val id = group.text("id")
            LivePanelCard(Modifier.fillMaxWidth().clickable { model.openGroup(id) }.testTag("live-group-$id")) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiveAvatar("群", "", model)
                    Column(Modifier.weight(1f)) {
                        Text(group.text("name", id), color = LivePale, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(group.items("members").joinToString(" · ") { it.text("name", it.text("id")) },
                            color = LiveMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item { if (state.groups.isEmpty()) Text("暂无群聊", color = LiveMuted) }
    }
}

