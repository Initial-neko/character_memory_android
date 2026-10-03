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

/** An ASR result is a draft; only the existing Send button persists a user message. */
@Composable
internal fun LiveVoiceInput(model: LiveViewModel) {
    val state by model.voice.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionTicket by remember { mutableStateOf<VoiceTaskTicket?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionTicket?.let { model.voice.onPermissionResult(it, granted) }
        permissionTicket = null
    }
    val ticket = state.ticket
    Column(Modifier.fillMaxWidth().testTag("live-voice-input")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.phase in setOf(VoiceCoordinatorPhase.IDLE, VoiceCoordinatorPhase.ERROR)) {
                TextButton(onClick = {
                    val next = model.voice.requestStart() ?: return@TextButton
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        model.voice.onPermissionResult(next, true)
                    else { permissionTicket = next; permission.launch(Manifest.permission.RECORD_AUDIO) }
                }, enabled = model.state.value.config.mediaUrl.isNotBlank(), modifier = Modifier.testTag("live-asr-start")) { Text("语音输入") }
            }
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
