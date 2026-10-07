package com.charactermemory.android.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charactermemory.android.audio.VoiceCallState
import kotlinx.coroutines.delay

/** Derived from the existing call owner; this component cannot start or end a session. */
internal fun callStatusText(call: VoiceCallState): String = when {
    call.phase == "permission" -> "等待麦克风授权"
    !call.transportAvailable -> if (call.microphoneMuted) "连接中断，正在重连 · 麦克风已静音" else "连接中断，正在重连 · 录音已暂停"
    call.microphoneMuted -> if (call.phase == "speaking") "对方正在说话 · 你的麦克风已静音" else "麦克风已静音 · 仍可接收回复"
    call.phase == "speaking" -> "对方正在说话 · 麦克风暂时暂停"
    call.phase == "transcribing" -> "正在识别你的话"
    call.phase == "waiting" -> "等待回复 · 可以继续说话"
    else -> "正在倾听"
}

@Composable
internal fun callElapsedText(startedAtMs: Long): String {
    fun seconds() = if (startedAtMs > 0) ((System.nanoTime() / 1_000_000 - startedAtMs) / 1000).coerceAtLeast(0) else 0
    var elapsed by remember(startedAtMs) { mutableLongStateOf(seconds()) }
    LaunchedEffect(startedAtMs) {
        if (startedAtMs > 0) while (true) {
            elapsed = seconds()
            delay(1000)
        }
    }
    return "%02d:%02d".format(elapsed / 60, elapsed % 60)
}

@Composable
internal fun LiveOngoingCall(state: LiveState, model: LiveViewModel, call: VoiceCallState) {
    val target = state.target ?: return
    Surface(onClick = { model.show(LivePage.CHAT) },
        color = LiveAccent.copy(alpha = .10f), contentColor = LivePale,
        border = BorderStroke(1.dp, LiveAccent.copy(alpha = .35f)), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("live-call-return")) {
        Row(Modifier.heightIn(min = 76.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveAvatar(target.name, state.avatars[target.id].orEmpty(), model, 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(target.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("live-call-name"))
                Text(callStatusText(call), color = LiveAccent, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("live-call-status"))
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(callElapsedText(call.startedAtMs), color = LiveAccent, fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("live-call-duration"))
                Text("返回通话", color = LiveMuted, fontSize = 12.sp)
            }
        }
    }
}
