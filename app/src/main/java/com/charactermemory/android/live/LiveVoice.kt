package com.charactermemory.android.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.charactermemory.android.audio.*

internal class VoiceUiActions(val dictation: () -> Unit, val call: () -> Unit)

@Composable
internal fun rememberVoiceUiActions(model: LiveViewModel): VoiceUiActions {
    val context = LocalContext.current
    var permissionTicket by remember { mutableStateOf<VoiceTaskTicket?>(null) }
    var callTicket by remember { mutableStateOf<Long?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionTicket?.let { model.voice.onPermissionResult(it, granted) }
        callTicket?.let { model.grantCallPermission(it, granted) }
        permissionTicket = null
        callTicket = null
    }
    return VoiceUiActions(dictation = {
        model.voice.requestStart()?.let { next ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                model.voice.onPermissionResult(next, true)
            else { permissionTicket = next; permission.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }, call = {
        model.requestCall()?.let { next ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                model.grantCallPermission(next, true)
            else { callTicket = next; permission.launch(Manifest.permission.RECORD_AUDIO) }
        }
    })
}

/** An ASR result is a draft; only the existing Send button persists a user message. */
@Composable
internal fun LiveVoiceInput(model: LiveViewModel) {
    val state by model.voice.state.collectAsStateWithLifecycle()
    val call by model.call.state.collectAsStateWithLifecycle()
    val ticket = state.ticket
    Column(Modifier.fillMaxWidth().testTag("live-voice-input")) {
        if (call.active) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(when (call.phase) {
                    "permission" -> "等待麦克风授权"
                    "transcribing" -> "通话 · 正在转写"
                    "speaking" -> "${call.speaker ?: "角色"}正在说话 · 麦克风暂停"
                    "waiting" -> "通话 · 等待回应，可继续说话"
                    else -> "通话 · 正在听"
                }, modifier = Modifier.weight(1f).testTag("live-call-status"))
                TextButton(onClick = { model.call.end() }, modifier = Modifier.testTag("live-call-hangup")) { Text("挂断") }
            }
            if (call.transcript.isNotBlank()) Text("你：${call.transcript} · 待发送 ${call.pendingCount}段", modifier = Modifier.testTag("live-call-transcript"))
            call.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("live-call-error")) }
            return@Column
        }
        call.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("live-call-error")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.phase == VoiceCoordinatorPhase.RECORDING && ticket != null) {
                TextButton(onClick = { model.voice.stop(ticket) }, modifier = Modifier.testTag("live-asr-stop")) {
                    Text("结束录音 ${state.elapsedMs / 1000}秒 / 30秒")
                }
            }
            if (ticket != null) TextButton(onClick = { model.voice.cancel(ticket) }, modifier = Modifier.testTag("live-asr-cancel")) { Text("取消") }
        }
        if (state.phase in setOf(VoiceCoordinatorPhase.STARTING, VoiceCoordinatorPhase.STOPPING, VoiceCoordinatorPhase.TRANSCRIBING,
                VoiceCoordinatorPhase.CANCELLING, VoiceCoordinatorPhase.WAITING_PERMISSION)) {
            Text(when (state.phase) {
                VoiceCoordinatorPhase.TRANSCRIBING -> "正在转写…"
                VoiceCoordinatorPhase.WAITING_PERMISSION -> "等待麦克风授权"
                VoiceCoordinatorPhase.CANCELLING -> "正在释放录音…"
                else -> "正在处理录音…"
            }, modifier = Modifier.testTag("live-asr-status"))
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("live-asr-error")) }
        if (state.phase == VoiceCoordinatorPhase.DRAFT && ticket != null) {
            OutlinedTextField(state.draft.orEmpty(), { model.voice.editDraft(ticket, it) }, label = { Text("转写草稿（尚未发送）") },
                maxLines = 3, modifier = Modifier.fillMaxWidth().testTag("live-asr-draft"))
            TextButton(onClick = { model.useVoiceDraft(ticket) }, modifier = Modifier.testTag("live-asr-use-draft")) { Text("加入输入框") }
        }
    }
}
