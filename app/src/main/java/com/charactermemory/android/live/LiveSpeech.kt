package com.charactermemory.android.live

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import com.charactermemory.android.audio.SpeechAudioCache

private var sharedSpeechCache: SpeechAudioCache? = null

/** On-demand speech uses the shared player, never persists a synthetic chat event. */
@Composable
internal fun LiveSpeechButton(text: String, owner: String, model: LiveViewModel, tag: String) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsState()
    val cache = remember(context) {
        sharedSpeechCache ?: SpeechAudioCache(File(context.cacheDir, "speech-audio-v1")).also { sharedSpeechCache = it }
    }
    val key = SpeechAudioCache.Key(state.config.coreUrl, state.config.mediaUrl, owner, text)
    var job by remember(owner, text) { mutableStateOf<Job?>(null) }
    var error by remember(owner, text) { mutableStateOf<String?>(null) }
    var generation by remember(owner, text) { mutableLongStateOf(0) }
    fun stop() { generation++; job?.cancel(); job = null; LiveAudioPlayback.release(owner) }
    DisposableEffect(owner, text, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); stop() }
    }
    LivePlaybackAction("朗读消息", if (job?.isActive == true) "正在合成，点击取消" else error ?: LiveAudioPlayback.status(owner), tag,
        !LiveAudioPlayback.blocked && state.config.mediaUrl.isNotBlank() && text.trim().length in 1..4000) {
        if (job?.isActive == true) { stop(); return@LivePlaybackAction }
        cache.get(key)?.let {
            LiveAudioPlayback.toggle(owner, it.absolutePath); return@LivePlaybackAction
        }
        error = null
        val ticket = ++generation
        val playbackTicket = LiveAudioPlayback.reserveSpeech(owner) { job?.cancel() }
        val client = model.api
        job = scope.launch {
            try {
                val audio = client.synthesizeSpeech(text)
                ensureActive()
                val extension = if (audio.mimeType in setOf("audio/mpeg", "audio/mp3")) "mp3" else "wav"
                val created = withContext(Dispatchers.IO) {
                    requireNotNull(cache.put(key, audio.bytes, extension)) { "音频无法缓存" }
                }
                ensureActive()
                if (ticket != generation || LiveAudioPlayback.blocked) return@launch
                if (LiveAudioPlayback.claimSpeech(playbackTicket, created.absolutePath)) {
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (ticket == generation) error = "朗读失败，点击重试" }
            finally { if (ticket == generation) job = null }
        }
    }
}
