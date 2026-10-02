package com.charactermemory.android.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.charactermemory.android.data.SynthesizedAudio
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * A single short on-demand TTS playback. The response is temporarily spooled to app-private
 * cache, never added to conversation storage, and removed on completion/exit/process cleanup.
 * No background service: leaving the app stops playback via LiveViewModel lifecycle.
 */
class SpeechPlayback(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var focus: AudioFocusRequest? = null

    fun play(audio: SynthesizedAudio, onFinished: () -> Unit) {
        close()
        val suffix = if (audio.mimeType in setOf("audio/mpeg", "audio/mp3")) ".mp3" else ".wav"
        val file = File.createTempFile("cm-tts-", suffix, appContext.cacheDir)
        audioFile = file
        try {
            file.outputStream().use { it.write(audio.bytes) }
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(speechAttributes)
                .setOnAudioFocusChangeListener { state ->
                    if (state == AudioManager.AUDIOFOCUS_LOSS ||
                        state == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        close()
                        onFinished()
                    }
                }.build()
            if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                throw IOException("其他 App 正在使用音频输出")
            focus = request
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(speechAttributes)
            media.setOnPreparedListener { ready -> if (player === ready) ready.start() }
            media.setOnCompletionListener { completed ->
                if (player === completed) { close(); onFinished() }
            }
            media.setOnErrorListener { failed, _, _ ->
                if (player === failed) { close(); onFinished() }
                true
            }
            media.setDataSource(file.absolutePath)
            media.prepareAsync()
        } catch (error: Exception) {
            close()
            throw IOException("播放音频失败：${error.message}", error)
        }
    }

    override fun close() {
        val old = player
        player = null
        runCatching { old?.release() }
        focus?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
        focus = null
        audioFile?.let { runCatching { it.delete() } }
        audioFile = null
    }
}
