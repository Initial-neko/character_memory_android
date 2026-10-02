package com.charactermemory.android.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground, user-initiated short recording only. Caller checks RECORD_AUDIO permission.
 * Raw capture never goes into app preferences/history, and is discarded on lifecycle stop.
 */
class ShortWavRecorder : Closeable {
    private val recording = AtomicBoolean(false)
    private val pcm = ByteArrayOutputStream()
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private var readError: IOException? = null

    val isRecording: Boolean get() = recording.get()

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        check(recorder == null) { "录音已经开始" }
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "设备不支持 16 kHz 单声道录音" }
        val bufferSize = maxOf(minimum * 2, 4_096)
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw IOException("麦克风初始化失败")
        }
        try {
            audio.startRecording()
        } catch (error: Exception) {
            audio.release()
            throw IOException("麦克风启动失败", error)
        }
        recorder = audio
        recording.set(true)
        captureJob = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(bufferSize)
            // Core /v1/asr defaults to 4 MiB; cap capture to 30 s/960 KiB of PCM.
            while (isActive && recording.get() && pcm.size() < 30 * 16_000 * 2) {
                val read = audio.read(chunk, 0, chunk.size)
                if (read > 0) pcm.write(chunk, 0, read)
                else if (read < 0 && recording.get()) {
                    readError = IOException("录音读取失败：$read")
                    break
                }
            }
            recording.set(false)
        }
    }

    suspend fun finish(): ByteArray {
        val audio = recorder ?: throw IOException("没有正在进行的录音")
        recording.set(false)
        runCatching { audio.stop() }
        try {
            captureJob?.join()
            readError?.let { throw it }
            val samples = pcm.toByteArray()
            require(samples.size >= 16_000 / 5 * 2) { "录音太短，请至少说话 0.2 秒" }
            return WavPcm16.encode(samples)
        } finally {
            close()
        }
    }

    override fun close() {
        recording.set(false)
        val audio = recorder
        recorder = null
        runCatching { audio?.stop() }
        runCatching { audio?.release() }
        captureJob?.cancel()
        captureJob = null
        pcm.reset()
    }
}

/** Header-only PCM16 WAV envelope. No platform codecs and no resampling. */
object WavPcm16 {
    fun encode(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
        require(pcm.size % 2 == 0) { "PCM16 must contain whole samples" }
        require(pcm.isNotEmpty() && pcm.size <= 4 * 1024 * 1024 - 44) { "音频大小超过上限" }
        require(sampleRate in 8_000..48_000)
        val bytes = ByteArray(44 + pcm.size)
        fun label(offset: Int, text: String) = text.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset)
        fun le(offset: Int, number: Int, width: Int) {
            for (index in 0 until width) bytes[offset + index] = (number ushr (index * 8)).toByte()
        }
        label(0, "RIFF"); le(4, 36 + pcm.size, 4); label(8, "WAVE")
        label(12, "fmt "); le(16, 16, 4); le(20, 1, 2)
        le(22, 1, 2); le(24, sampleRate, 4); le(28, sampleRate * 2, 4)
        le(32, 2, 2); le(34, 16, 2); label(36, "data"); le(40, pcm.size, 4)
        pcm.copyInto(bytes, 44)
        return bytes
    }
}
