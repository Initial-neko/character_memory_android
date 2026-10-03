package com.charactermemory.android.live

import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.Context
import com.charactermemory.android.audio.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException

/** One app-wide MediaPlayer owner shared by chat voice messages and Space attachments. */
@Composable
internal fun LiveAudioPlayerButton(url: String, label: String, tag: String, playbackOwner: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(context) { LiveAudioPlayback.initialize(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val status = LiveAudioPlayback.status(playbackOwner)
    androidx.compose.runtime.DisposableEffect(playbackOwner, url, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) LiveAudioPlayback.stop(playbackOwner)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            LiveAudioPlayback.release(playbackOwner)
        }
    }
    LivePlaybackAction(label, status, tag, url.isNotBlank() && !LiveAudioPlayback.blocked) {
        LiveAudioPlayback.toggle(playbackOwner, url)
    }
}

internal object LiveAudioPlayback {
    var blocked by mutableStateOf(false)
    private var audioManager: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private val speechRequests = AudioRequestFence()
    fun reserveSpeech(owner: String, cancel: () -> Unit): AudioRequestTicket {
        stopAll()
        return speechRequests.begin(owner, cancel)
    }
    fun claimSpeech(ticket: AudioRequestTicket, url: String): Boolean {
        if (blocked || !speechRequests.consume(ticket)) return false
        toggle(ticket.owner, url)
        return true
    }
    fun initialize(context: Context) { audioManager = context.applicationContext.getSystemService(AudioManager::class.java) }
    fun stopAll() { speechRequests.cancelAll(); releasePlayer(); state.value = UiState(null, "播放") }
    private data class UiState(val owner: String?, val status: String)
    private val state = mutableStateOf(UiState(null, "播放"))
    private var activeOwner: String? = null
    private var activePlayer: MediaPlayer? = null
    private var generation: Long = 0L
    private var completion: CompletableDeferred<Unit>? = null

    fun status(owner: String): String {
        val current = state.value
        return if (current.owner == owner) current.status else "播放"
    }

    fun toggle(owner: String, url: String) = play(owner, url, false)
    suspend fun playCall(owner: String, url: String) {
        play(owner, url, true)
        val finished = completion?.takeIf { activeOwner == owner } ?: throw IOException("音频播放未启动")
        try { finished.await() } finally { stop(owner) }
    }
    private fun play(owner: String, url: String, allowBlocked: Boolean) {
        if (blocked && !allowBlocked) return
        speechRequests.cancelAll()
        if (activeOwner == owner && activePlayer != null) {
            stop(owner)
            return
        }
        releasePlayer()
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                if (focus === request && shouldStopForAudioFocus(change)) stopAll()
            }.build()
        if (audioManager?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            state.value = UiState(owner, "音频被占用，点击重试")
            return
        }
        focus = request
        val ticket = ++generation
        val created = MediaPlayer()
        created.setAudioAttributes(attributes)
        activeOwner = owner
        activePlayer = created
        completion = CompletableDeferred()
        state.value = UiState(owner, "加载中…")
        created.setOnPreparedListener { prepared ->
            if (isCurrent(owner, prepared, ticket)) {
                runCatching { prepared.start() }.onFailure { finish(owner, prepared, ticket, "播放失败，点击重试") }
                if (isCurrent(owner, prepared, ticket)) state.value = UiState(owner, "停止")
            }
        }
        created.setOnCompletionListener { completed -> finish(owner, completed, ticket, "播放") }
        created.setOnErrorListener { failed, _, _ ->
            finish(owner, failed, ticket, "播放失败，点击重试")
            true
        }
        runCatching { created.setDataSource(url); created.prepareAsync() }.onFailure {
            finish(owner, created, ticket, "播放失败，点击重试")
        }
    }

    fun stop(owner: String) {
        speechRequests.cancelOwner(owner)
        if (activeOwner == owner && activePlayer != null) {
            releasePlayer()
            state.value = UiState(owner, "播放")
        }
    }

    fun release(owner: String) {
        stop(owner)
        if (state.value.owner == owner && activeOwner != owner) state.value = UiState(null, "播放")
    }

    private fun isCurrent(owner: String, player: MediaPlayer, ticket: Long): Boolean =
        activeOwner == owner && activePlayer === player && generation == ticket

    private fun finish(owner: String, player: MediaPlayer, ticket: Long, status: String) {
        if (!isCurrent(owner, player, ticket)) return
        activePlayer = null
        activeOwner = null
        generation++
        runCatching { player.release() }
        abandonFocus()
        state.value = UiState(owner, status)
        val finished = completion; completion = null
        if (status == "播放") finished?.complete(Unit) else finished?.completeExceptionally(IOException(status))
    }

    private fun releasePlayer() {
        completion?.completeExceptionally(IOException("音频播放已停止")); completion = null
        val oldPlayer = activePlayer
        activePlayer = null
        activeOwner = null
        generation++
        if (oldPlayer != null) runCatching { oldPlayer.release() }
        abandonFocus()
    }
    private fun abandonFocus() { focus?.let { audioManager?.abandonAudioFocusRequest(it) }; focus = null }
}
