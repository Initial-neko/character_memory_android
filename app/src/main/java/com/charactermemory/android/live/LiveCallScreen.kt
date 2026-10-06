package com.charactermemory.android.live

import com.charactermemory.android.data.text
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.charactermemory.android.R
import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.compose.animation.core.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.charactermemory.android.camera.CameraControls
import com.charactermemory.android.screen.ScreenShareControls
import com.charactermemory.android.screen.ScreenShareStatus
import com.charactermemory.android.screen.ScreenShareService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Presentation only: capture, sending and playback keep the existing session owner. */
@Composable
internal fun LiveCallScreen(state: LiveState, model: LiveViewModel) {
    val target = state.target ?: return
    val call by model.call.state.collectAsStateWithLifecycle()
    val share by ScreenShareStatus.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val presentation = LocalCallPresentation.current
    DisposableEffect(Unit) { onDispose { presentation.setConsentInFlight("notifications", false) } }
    var seconds by remember { mutableLongStateOf(0) }
    var cameraRequest by remember { mutableIntStateOf(0) }
    var screenRequest by remember { mutableIntStateOf(0) }
    var cameraOpened by remember { mutableStateOf(false) }
    var shareExplanation by remember { mutableStateOf(false) }
    var frozenScreen by remember { mutableStateOf<ByteArray?>(null) }
    val characterMode by model.callCharacterMode.collectAsStateWithLifecycle()
    var moreOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    val speakerEnabled = LiveAudioPlayback.callSpeakerEnabled
    LaunchedEffect(call.phase, call.replyText) { StaticCallCharacterRenderer.setCharacterState(CallCharacterState(call.phase, call.replyText)) }
    var previewError by remember { mutableStateOf<String?>(null) }
    val previewScope = rememberCoroutineScope()
    val sharing = share.active && share.characterId == target.id && share.conversationId == target.conversationId && share.coreUrl == state.config.coreUrl
    LaunchedEffect(sharing) { if (!sharing) frozenScreen = null }
    var notificationsEnabled by remember { mutableStateOf(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        presentation.setConsentInFlight("notifications", false)
        notificationsEnabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }
    LaunchedEffect(call.startedAtMs) {
        if (call.startedAtMs > 0) while (true) {
            seconds = ((System.nanoTime() / 1_000_000 - call.startedAtMs) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    BackHandler { model.back() }
    val status = when {
        call.phase == "permission" -> "等待麦克风授权"
        !call.transportAvailable -> if (call.microphoneMuted) "连接中断，正在重连 · 麦克风已静音" else "连接中断，正在重连 · 录音已暂停"
        call.microphoneMuted -> if (call.phase == "speaking") "对方正在说话 · 你的麦克风已静音" else "麦克风已静音 · 仍可接收回复"
        call.phase == "speaking" -> "对方正在说话 · 麦克风暂时暂停"
        call.phase == "transcribing" -> "正在识别你的话"
        call.phase == "waiting" -> "等待回复 · 可以继续说话"
        else -> "正在倾听"
    }
    if (call.phase == "permission") {
        ConnectingCallScreen(state, model, status)
        return
    }
    val avatar = state.avatars[target.id].orEmpty()
    val stage = CallStageState(characterMode)
    val mode = stage.mode(sharing, cameraOpened)
    val immersive = mode != CallStageMode.AVATAR
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0C1422)).testTag("live-call-screen")) {
        val short = maxHeight < 500.dp
        val bottomSpace = if (short) 145.dp else 225.dp
        // Keep capture controls mounted once. Display selection never creates a call or projection.
        CameraControls(state, model, callMode = true, toggleRequest = cameraRequest, hideToggle = true,
            onOpenedChange = { cameraOpened = it }, modifier = Modifier.fillMaxSize())
        if (mode != CallStageMode.VIDEO) Box(Modifier.fillMaxSize().testTag("live-call-character-art")
            .background(Brush.verticalGradient(listOf(Color(0xFF263650), Color(0xFF0C1422)))), contentAlignment = Alignment.Center) {
            if (mode == CallStageMode.SCREEN_SHARE) {
                val frame = frozenScreen ?: share.latestPreviewJpeg
                if (frame != null) AsyncImage(frame, "最近共享画面", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(top = 90.dp, bottom = bottomSpace).testTag("live-call-screen-preview"))
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveGlyph(LiveSymbol.DISPLAY, modifier = Modifier.size(72.dp), tint = LiveCyan)
                    Text("屏幕共享中", color = Color.White, fontSize = 24.sp)
                    Text("等待已授权共享的画面", color = LiveMuted)
                }
            } else if (mode == CallStageMode.LIVE2D) {
                Image(painterResource(R.drawable.call_room), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (avatar.isNotBlank()) AsyncImage(model.assetUrl(avatar), "${target.name}静态角色画面",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(top = 110.dp, bottom = 170.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.verticalGradient(0f to Color.Transparent, .25f to Color.White, .72f to Color.White, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                        }.testTag("live-call-live2d-placeholder"))
                else Text(target.name.take(1), color = LivePale.copy(alpha = .25f), fontSize = 160.sp)
            } else Column(Modifier.align(Alignment.Center).padding(bottom = 180.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.size(if (short) 100.dp else 230.dp).background(Color(0xFF1C2C49), CircleShape).padding(16.dp), contentAlignment = Alignment.Center) {
                    LiveAvatar(target.name, avatar, model, if (short) 76.dp else 194.dp)
                }
                Text(target.name, color = Color.White, fontSize = 32.sp)
            }
        }
        if (immersive) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent, Color.Black.copy(alpha = .85f)))))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::back, modifier = Modifier.testTag("live-back").semantics { contentDescription = "收起通话" }) { LiveGlyph(LiveSymbol.BACK, tint = Color.White) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(target.name, color = Color.White, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = Color.White.copy(alpha = .7f), fontSize = 13.sp, maxLines = 1, modifier = Modifier.testTag("live-call-status"))
                Text("%02d:%02d".format(seconds / 60, seconds % 60), color = LiveMuted, fontSize = 12.sp, modifier = Modifier.testTag("live-call-duration"))
            }
            IconButton(onClick = presentation.enterPip, modifier = Modifier.testTag("live-call-pip").semantics { contentDescription = "通话小窗" }) { LiveGlyph(LiveSymbol.DISPLAY, tint = Color.White) }
        }
        if (stage.hasCharacterOverlay(sharing, cameraOpened)) Box(Modifier.align(Alignment.TopEnd).padding(top = 110.dp, end = 16.dp)
            .size(if (short) 92.dp else 130.dp, if (short) 120.dp else 174.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF263650))
            .testTag("live-call-character-inset"), contentAlignment = Alignment.Center) {
            if (avatar.isNotBlank()) AsyncImage(model.assetUrl(avatar), "${target.name}悬浮角色", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(target.name.take(1), color = Color.White, fontSize = 32.sp)
        }
        if (sharing) Text("正在共享屏幕", color = LiveCyan, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 95.dp, start = 24.dp).testTag("live-call-sharing-badge"))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xB31B2232)).padding(16.dp).testTag("live-call-subtitles"), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.testTag("live-voice-input")) {
                    if (call.transcript.isNotBlank()) Text("你：${call.transcript}", color = Color.White.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("live-call-transcript"))
                    Text(if (call.replyText.isNotBlank()) call.replyText else "这里显示实时字幕", color = Color.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                (call.error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 2, modifier = Modifier.testTag("live-call-error")) }
            }
            // Permission/capture owner stays mounted, even while the More sheet is closed.
            Box(Modifier.height(0.dp)) { ScreenShareControls(state, model, callMode = true, toggleRequest = screenRequest, hideToggle = true) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallControl(if (call.microphoneMuted) "开启麦克风" else "麦克风", if (call.microphoneMuted) LiveSymbol.MIC_OFF else LiveSymbol.MIC,
                    "live-call-mic-toggle", if (call.microphoneMuted) LivePurple else Color.White.copy(alpha = .12f)) { model.call.setMicrophoneMuted(!call.microphoneMuted) }
                CallControl("扬声器", LiveSymbol.SPEAKER, "live-call-speaker-toggle", if (speakerEnabled) Color(0xFF3A5389) else Color.White.copy(alpha = .12f)) {
                    LiveAudioPlayback.setCallSpeakerEnabled(!speakerEnabled)
                }
                if (immersive) CallControl("挂断", LiveSymbol.PHONE, "live-call-hangup", Color(0xFFE54C52)) { model.call.end() }
                CallControl("更多", LiveSymbol.MORE, "live-call-more", Color.White.copy(alpha = .12f)) { moreOpen = true }
            }
            if (!immersive) Button(onClick = { model.call.end() }, modifier = Modifier.fillMaxWidth().height(58.dp).testTag("live-call-hangup"),
                shape = RoundedCornerShape(30.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE54C52))) {
                LiveGlyph(LiveSymbol.PHONE, modifier = Modifier.rotate(135f), tint = Color.White); Spacer(Modifier.width(12.dp)); Text("挂断", fontSize = 20.sp)
            }
            TextButton(onClick = { historyOpen = true }, modifier = Modifier.fillMaxWidth().testTag("live-call-history")) { Text("字幕 / 对话", color = Color.White.copy(alpha = .8f)) }
        }
    }
    if (moreOpen) AlertDialog(onDismissRequest = { moreOpen = false }, title = { Text("通话展示与控制") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { model.selectCallCharacterMode(CallStageMode.AVATAR); moreOpen = false }, modifier = Modifier.testTag("live-call-avatar-mode")) { Text("头像") }
                Button(onClick = { model.selectCallCharacterMode(CallStageMode.LIVE2D); moreOpen = false }, modifier = Modifier.testTag("live-call-live2d-mode")) { Text("Live2D") }
            }
            Text("Live2D 当前展示静态角色图", color = LiveMuted, fontSize = 12.sp)
            TextButton(onClick = { cameraRequest++; moreOpen = false }, modifier = Modifier.testTag("live-call-camera-toggle"), enabled = "visual" !in state.busy) { Text(if (cameraOpened) "关闭摄像头" else "开启视频") }
            TextButton(onClick = { moreOpen = false; if (sharing) screenRequest++ else shareExplanation = true }, modifier = Modifier.testTag("live-call-screen-toggle"), enabled = !target.group) { Text(if (sharing) "停止共享" else "共享屏幕") }
            if (sharing) {
                Text(share.label, color = LiveMuted, fontSize = 12.sp)
                TextButton(onClick = { model.sendSharedScreen() },
                    enabled = call.active && "visual" !in state.busy && !share.manualBusy && share.uploadState != "AUTH_REJECTED",
                    modifier = Modifier.testTag("live-call-screen-ask")) { Text(if (share.manualBusy) "正在读取…" else "询问当前画面") }
            }
            if (sharing) TextButton(onClick = {
                frozenScreen = share.latestPreviewJpeg
                if (frozenScreen == null) previewScope.launch { runCatching { ScreenShareService.currentFrame(context) }.onSuccess { frame ->
                    if (ScreenShareService.isCurrentProjection(frame.projectionEpoch)) frozenScreen = frame.jpeg
                }.onFailure { previewError = "暂时无法预览，请稍后重试" } }
            }, modifier = Modifier.testTag("live-call-screen-preview-toggle")) { Text("刷新最近共享画面") }
            previewError?.let { Text(it, color = LiveMuted) }
            if (Build.VERSION.SDK_INT >= 33 && !notificationsEnabled) TextButton(onClick = {
                presentation.setConsentInFlight("notifications", true); notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }, modifier = Modifier.testTag("live-call-notifications")) { Text("开启通话通知") }
        }
    }, confirmButton = { TextButton(onClick = { moreOpen = false }) { Text("完成") } })
    if (historyOpen) AlertDialog(onDismissRequest = { historyOpen = false }, title = { Text("字幕 / 对话") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            state.messages.forEach { message -> Text(message.text("content"), modifier = Modifier.padding(vertical = 8.dp)) }
        }
    }, confirmButton = { TextButton(onClick = { historyOpen = false }, modifier = Modifier.testTag("live-call-history-close")) { Text("返回通话") } })
    if (shareExplanation) AlertDialog(onDismissRequest = { shareExplanation = false }, title = { Text("共享屏幕") },
        text = { Text(if (Build.VERSION.SDK_INT >= 34) "下一步由系统选择共享单个应用或整个屏幕。仅上传用于角色观察的画面关键帧，可随时停止共享。" else "下一步由系统确认屏幕共享范围。当前系统可能只支持整个屏幕；仅上传画面关键帧，可随时停止共享。") },
        confirmButton = { TextButton(onClick = { shareExplanation = false; screenRequest++ }, modifier = Modifier.testTag("live-call-share-confirm")) { Text("继续选择") } },
        dismissButton = { TextButton(onClick = { shareExplanation = false }) { Text("取消") } })
}

@Composable
private fun ConnectingCallScreen(state: LiveState, model: LiveViewModel, status: String) {
    val target = state.target ?: return
    val avatar = state.avatars[target.id].orEmpty()
    val transition = rememberInfiniteTransition(label = "call-waiting")
    val pulse by transition.animateFloat(.35f, 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "call-waiting-pulse")
    Box(Modifier.fillMaxSize().background(LiveNavy).testTag("live-call-screen")) {
        Box(Modifier.fillMaxSize().testTag("live-call-character-art")) {
            if (avatar.isNotBlank()) AsyncImage(model.assetUrl(avatar), null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .78f)))
        }
        Column(Modifier.align(Alignment.Center).testTag("live-call-connecting"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.testTag("live-call-connecting-avatar")) {
                LiveAvatar(target.name, avatar, model, 96.dp)
            }
            Text(target.name, color = Color.White, fontSize = 24.sp)
            Text(status, color = Color.White.copy(alpha = .7f), modifier = Modifier.testTag("live-call-status"))
            Canvas(Modifier.size(128.dp, 40.dp).testTag("live-call-connecting-wave")
                .semantics { contentDescription = "等待麦克风授权动画" }) {
                repeat(9) { index ->
                    val prominence = 1f - kotlin.math.abs(index - 4) / 5f
                    val height = size.height * (.2f + prominence * .7f * pulse)
                    val x = size.width * (index + 1) / 10f
                    drawLine(LivePurple, Offset(x, (size.height - height) / 2),
                        Offset(x, (size.height + height) / 2), 4.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            CallControl("取消", LiveSymbol.PHONE, "live-call-hangup", Color(0xFFE85068)) { model.call.end() }
        }
    }
}

@Composable
private fun CallControl(label: String, symbol: LiveSymbol, tag: String, background: Color, enabled: Boolean = true, action: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = action, enabled = enabled, modifier = Modifier.size(54.dp).testTag(tag).semantics { contentDescription = label },
            shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = background, contentColor = Color.White)) {
            LiveGlyph(symbol, modifier = if (symbol == LiveSymbol.PHONE) Modifier.rotate(135f) else Modifier, tint = Color.White)
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}
