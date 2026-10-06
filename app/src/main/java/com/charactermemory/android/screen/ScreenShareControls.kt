package com.charactermemory.android.screen

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.charactermemory.android.live.LiveState
import com.charactermemory.android.live.LiveViewModel
import com.charactermemory.android.live.LivePage
import com.charactermemory.android.live.LiveMuted
import com.charactermemory.android.live.LocalCallPresentation

@Composable
fun ScreenShareControls(state: LiveState, model: LiveViewModel, callMode: Boolean = false,
    toggleRequest: Int = 0, hideToggle: Boolean = false) {
    val context = LocalContext.current
    val presentation = LocalCallPresentation.current
    DisposableEffect(Unit) { onDispose { presentation.setConsentInFlight("screen", false) } }
    val capture by ScreenShareStatus.state.collectAsState()
    val target = state.target ?: return
    LaunchedEffect(capture.manualAccepted) {
        if (capture.manualAccepted > 0 && capture.characterId == target.id &&
            capture.conversationId == target.conversationId && model.state.value.page == LivePage.CHAT) {
            model.loadHistory()
        }
    }
    val call by model.call.state.collectAsState()
    val visualSource by model.callVisualSource.collectAsState()
    var requested by remember { mutableStateOf<Triple<Long, String, String>?>(null) }
    var requestedCallStart by remember { mutableStateOf<Long?>(null) }
    var sourceEpoch by remember { mutableStateOf<Long?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        presentation.setConsentInFlight("screen", false)
        val expected = requested
        requested = null
        val callTicket = requestedCallStart; requestedCallStart = null
        val selected = model.state.value.target
        if (expected != null && result.resultCode == Activity.RESULT_OK && result.data != null &&
            model.state.value.page == LivePage.CHAT && selected?.group == false &&
            (if (callMode) model.call.state.value.active && model.call.state.value.startedAtMs == callTicket else !model.call.state.value.active) &&
            expected == Triple(model.screenCaptureGeneration, model.state.value.config.coreUrl, selected.id + ":" + selected.conversationId)) {
            runCatching {
                if (callMode) sourceEpoch = model.beginCallScreenShare()
                ScreenShareService.start(context, result.resultCode, result.data!!,
                    model.state.value.config.coreUrl, selected.id, selected.conversationId, callOwned = callMode)
            }.onFailure { error -> sourceEpoch?.let { model.endCallScreenShare(it) }; sourceEpoch = null
                model.update { it.copy(error = "无法启动屏幕共享：${error.message}") } }
        }
    }
    if (target.group) return // Core only supports direct periodic DISPLAY observations.
    val mine = capture.active && capture.characterId == target.id && capture.conversationId == target.conversationId && capture.coreUrl == state.config.coreUrl
    LaunchedEffect(mine) {
        // Adopt an already-authorized ordinary-chat share without restarting projection
        // or changing which session owns its eventual hangup cleanup.
        if (callMode && mine && visualSource.source == com.charactermemory.android.live.CallVisualSource.NONE)
            sourceEpoch = model.beginCallScreenShare()
    }
    fun stopShare() {
        if (callMode) model.endCallScreenShare(sourceEpoch ?: visualSource.epoch)
        else ScreenShareService.stop(context)
        sourceEpoch = null
    }
    LaunchedEffect(capture.active, capture.stopReason) {
        if (!capture.active && capture.stopReason != null) { sourceEpoch?.let { model.endCallScreenShare(it) }; sourceEpoch = null }
    }
    fun startShare() {
        if (capture.active) model.update { it.copy(error = "请先结束当前人物的共享，再切换目标。") }
        else {
            requested = Triple(model.screenCaptureGeneration, state.config.coreUrl, target.id + ":" + target.conversationId)
            requestedCallStart = if (callMode) call.startedAtMs else null
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            presentation.setConsentInFlight("screen", true)
            launcher.launch(manager.createScreenCaptureIntent())
        }
    }
    LaunchedEffect(toggleRequest) {
        if (toggleRequest > 0 && call.active == callMode && call.phase != "permission" && requested == null) {
            if (mine) stopShare()
            else startShare()
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mine) {
            TextButton(onClick = { if (callMode) model.sendSharedScreen() else ScreenShareService.askCurrentScreen(context) },
                enabled = call.active == callMode && call.phase != "permission" && "visual" !in state.busy && !capture.manualBusy && capture.uploadState != "AUTH_REJECTED", modifier = Modifier.testTag("live-screen-share-ask")) {
                Text(if (capture.manualBusy) "正在读取…" else "询问当前画面")
            }
            if (!hideToggle) OutlinedButton(onClick = { stopShare() },
                modifier = Modifier.testTag("live-screen-share-stop")) { Text("停止共享") }
        } else if (!hideToggle) {
            OutlinedButton(onClick = {
                if (capture.active) {
                    model.update { it.copy(error = "请先结束当前人物的共享，再切换目标。") }
                } else {
                    requested = Triple(model.screenCaptureGeneration, state.config.coreUrl, target.id + ":" + target.conversationId)
                    requestedCallStart = if (callMode) call.startedAtMs else null
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    presentation.setConsentInFlight("screen", true)
                    launcher.launch(manager.createScreenCaptureIntent())
                }
            }, enabled = call.active == callMode && call.phase != "permission" && requested == null && !capture.active && state.config.coreUrl.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp).testTag(if (callMode) "live-call-screen-toggle" else "live-screen-share-start")) { Text("共享手机屏幕") }
        }
    }
    if (mine || (!callMode && capture.label != "未共享")) {
        Text(capture.label, style = MaterialTheme.typography.bodySmall, color = LiveMuted,
            modifier = Modifier.testTag("live-screen-share-status"))
    }
    if (mine && !callMode) Text("可主动询问当前画面；自动观察仅上传显著变化的关键帧。系统授权范围内的其他 App 可能被采集，可从此处或系统通知停止。",
        style = MaterialTheme.typography.bodySmall, color = LiveMuted)
}
