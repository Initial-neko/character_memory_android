package com.charactermemory.android.live

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** On-demand speech uses the shared player, never persists a synthetic chat event. */
@Composable
internal fun LiveSpeechButton(text: String, owner: String, model: LiveViewModel, tag: String) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsState()
    var job by remember(owner, text) { mutableStateOf<Job?>(null) }
    var cached by remember(owner, text) { mutableStateOf<File?>(null) }
    var error by remember(owner, text) { mutableStateOf<String?>(null) }
    var generation by remember(owner, text) { mutableLongStateOf(0) }
    fun stop() { generation++; job?.cancel(); job = null; LiveAudioPlayback.release(owner); cached?.delete(); cached = null }
    DisposableEffect(owner, text, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); stop() }
    }
    TextButton(onClick = {
        if (job?.isActive == true) { stop(); return@TextButton }
        cached?.let { LiveAudioPlayback.toggle(owner, it.absolutePath); return@TextButton }
        error = null
        val ticket = ++generation
        val playbackTicket = LiveAudioPlayback.reserveSpeech(owner) { job?.cancel() }
        val client = model.api
        job = scope.launch {
            var file: File? = null
            try {
                val audio = client.synthesizeSpeech(text)
                ensureActive()
                val created = File(context.cacheDir, "tts-${UUID.randomUUID()}.${if (audio.mimeType in setOf("audio/mpeg", "audio/mp3")) "mp3" else "wav"}")
                file = created
                withContext(Dispatchers.IO) { created.writeBytes(audio.bytes) }
                ensureActive()
                if (ticket != generation || LiveAudioPlayback.blocked) return@launch
                if (LiveAudioPlayback.claimSpeech(playbackTicket, requireNotNull(file).absolutePath)) {
                    cached = file
                    file = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (ticket == generation) error = "朗读失败，点击重试" }
            finally { file?.delete(); if (ticket == generation) job = null }
        }
    }, enabled = !LiveAudioPlayback.blocked && state.config.mediaUrl.isNotBlank() && text.trim().length in 1..4000,
        modifier = Modifier.testTag(tag)) {
        Text(if (job?.isActive == true) "取消合成" else error ?: "朗读 · ${LiveAudioPlayback.status(owner)}")
    }
}
