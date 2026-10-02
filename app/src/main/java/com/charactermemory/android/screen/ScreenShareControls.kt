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

@Composable
fun ScreenShareControls(state: LiveState, model: LiveViewModel) {
    val context = LocalContext.current
    val capture by ScreenShareStatus.state.collectAsState()
    val target = state.target ?: return
    LaunchedEffect(capture.manualAccepted) {
        if (capture.manualAccepted > 0 && capture.characterId == target.id &&
            capture.conversationId == target.conversationId && model.state.value.page == LivePage.CHAT) {
            model.loadHistory()
        }
    }
    var requested by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expected = requested
        requested = null
        val selected = model.state.value.target
        if (expected != null && result.resultCode == Activity.RESULT_OK && result.data != null &&
            model.state.value.page == LivePage.CHAT && selected?.group == false &&
            expected == selected.id + ":" + selected.conversationId) {
            runCatching {
                ScreenShareService.start(context, result.resultCode, result.data!!,
                    model.state.value.config.coreUrl, selected.id, selected.conversationId)
            }.onFailure { error -> model.update { it.copy(error = "无法启动屏幕共享：${error.message}") } }
        }
    }
    if (target.group) return // Core only supports direct periodic DISPLAY observations.
    val mine = capture.active && capture.characterId == target.id && capture.conversationId == target.conversationId
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mine) {
            TextButton(onClick = { ScreenShareService.askCurrentScreen(context) },
                enabled = !capture.manualBusy, modifier = Modifier.testTag("live-screen-share-ask")) {
                Text(if (capture.manualBusy) "正在读取…" else "询问当前画面")
            }
            OutlinedButton(onClick = { ScreenShareService.stop(context) },
                modifier = Modifier.testTag("live-screen-share-stop")) { Text("停止共享") }
        } else {
            OutlinedButton(onClick = {
                if (capture.active) {
                    model.update { it.copy(error = "请先结束当前人物的共享，再切换目标。") }
                } else {
                    requested = target.id + ":" + target.conversationId
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    launcher.launch(manager.createScreenCaptureIntent())
                }
            }, enabled = !capture.active && state.config.coreUrl.isNotBlank(),
                modifier = Modifier.testTag("live-screen-share-start")) { Text("共享手机屏幕") }
        }
    }
    if (mine || capture.label != "未共享") {
        Text(capture.label, style = MaterialTheme.typography.bodySmall, color = LiveMuted,
            modifier = Modifier.testTag("live-screen-share-status"))
    }
    if (mine) Text("可主动询问当前画面；自动观察仅上传显著变化的关键帧。系统授权范围内的其他 App 可能被采集，可从此处或系统通知停止。",
        style = MaterialTheme.typography.bodySmall, color = LiveMuted)
}
