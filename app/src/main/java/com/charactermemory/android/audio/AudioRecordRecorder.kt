package com.charactermemory.android.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch

/** Captures one bounded mono PCM16 segment. Call [cancel] when the owner leaves or backgrounds. */
class AudioRecordRecorder internal constructor(
    private val inputFactory: AudioInputFactory,
    private val segmenter: PcmSpeechSegmenter? = null
) {
    constructor() : this(AndroidAudioInputFactory())
    constructor(segmenter: PcmSpeechSegmenter) : this(AndroidAudioInputFactory(), segmenter)

    private enum class State { NEW, INITIALIZING, RECORDING, STOP_REQUESTED, CANCEL_REQUESTED, FINISHED }

    private val lock = Any()
    private var state = State.NEW
    private var input: AudioInput? = null
    private var started = false
    private var stopOperation: StopOperation? = null

    private class StopOperation(val input: AudioInput) {
        val completed = CountDownLatch(1)
    }

    private data class StopClaim(val operation: StopOperation, val invokeStop: Boolean)

    /**
     * Captures once and returns little-endian PCM16 bytes. The progress callback runs on IO;
     * cancellation or invalid/empty capture throws instead of returning uploadable audio.
     */
    suspend fun capture(onDurationMs: (Long) -> Unit = {}): ByteArray =
        withContext(Dispatchers.IO) { captureBlocking(onDurationMs) }

    /** Completes capture with samples already read. Safe to call repeatedly. */
    fun stop() = requestTermination(cancel = false)

    /** Stops the active read, discards all captured samples, and releases the input. */
    fun cancel() = requestTermination(cancel = true)

    private fun requestTermination(cancel: Boolean) {
        val stopClaim = synchronized(lock) {
            when (state) {
                State.NEW -> {
                    if (cancel) state = State.CANCEL_REQUESTED
                    return
                }
                State.INITIALIZING, State.RECORDING -> state =
                    if (cancel) State.CANCEL_REQUESTED else State.STOP_REQUESTED
                State.STOP_REQUESTED -> if (cancel) state = State.CANCEL_REQUESTED
                State.CANCEL_REQUESTED, State.FINISHED -> return
            }
            if (started) input?.let(::claimStopLocked) else null
        }
        stopClaim?.let(::invokeStopIfOwner)
    }

    private fun captureBlocking(onDurationMs: (Long) -> Unit): ByteArray {
        synchronized(lock) {
            check(state == State.NEW) { "AudioRecordRecorder can capture only once" }
            state = State.INITIALIZING
        }

        val pcm = ByteArrayOutputStream(READ_BUFFER_SAMPLES * BYTES_PER_SAMPLE)
        var sampleCount = 0
        try {
            val created = inputFactory.create()
                ?: throw IllegalStateException("16 kHz mono PCM16 recording is unavailable")
            synchronized(lock) { input = created }

            checkNotCancelled()
            check(stateSnapshot() == State.INITIALIZING) { "Capture stopped before recording started" }
            check(created.initialized) { "AudioRecord failed to initialize" }

            created.start()
            synchronized(lock) {
                started = true
                if (state == State.INITIALIZING) state = State.RECORDING
            }
            stopInputIfRequested(created)

            val readBuffer = ShortArray(READ_BUFFER_SAMPLES)
            while (sampleCount < MAX_SAMPLE_COUNT) {
                val currentState = stateSnapshot()
                if (currentState == State.CANCEL_REQUESTED) throw CancellationException("Recording cancelled")
                if (currentState == State.STOP_REQUESTED) break
                check(currentState == State.RECORDING) { "Recorder is not active" }

                val requestedSamples = minOf(READ_BUFFER_SAMPLES, MAX_SAMPLE_COUNT - sampleCount)
                val read = created.read(readBuffer, 0, requestedSamples)
                val afterReadState = stateSnapshot()
                if (afterReadState == State.CANCEL_REQUESTED) throw CancellationException("Recording cancelled")
                if (read < 0) {
                    if (afterReadState == State.STOP_REQUESTED) break
                    throw IOException("AudioRecord read failed: $read")
                }
                if (read == 0) {
                    if (afterReadState == State.STOP_REQUESTED) break
                    throw IOException("AudioRecord returned no samples")
                }
                if (read > requestedSamples) throw IOException("AudioRecord returned more samples than requested")

                for (index in 0 until read) {
                    val sample = readBuffer[index].toInt()
                    pcm.write(sample and 0xFF)
                    pcm.write((sample ushr 8) and 0xFF)
                }
                sampleCount += read
                onDurationMs(sampleCount.toLong() * 1_000L / SAMPLE_RATE_HZ)
                segmenter?.accept(readBuffer, read)?.let { return it }
            }

            if (sampleCount == 0) throw IllegalStateException("Recording contains no PCM samples")
            val result = segmenter?.finish() ?: pcm.toByteArray()
            synchronized(lock) {
                if (state == State.CANCEL_REQUESTED) throw CancellationException("Recording cancelled")
                state = State.FINISHED
            }
            return result
        } finally {
            val stopClaim = synchronized(lock) {
                if (started) input?.let(::claimStopLocked) else null
            }
            stopClaim?.let { claim ->
                if (claim.invokeStop) invokeStopIfOwner(claim)
                awaitUninterruptibly(claim.operation.completed)
            }

            val toRelease = synchronized(lock) {
                val current = input
                input = null
                started = false
                state = State.FINISHED
                current
            }
            toRelease?.let { runCatching { it.release() } }
        }
    }

    private fun stopInputIfRequested(candidate: AudioInput) {
        val stopClaim = synchronized(lock) {
            if (input === candidate &&
                (state == State.STOP_REQUESTED || state == State.CANCEL_REQUESTED) &&
                started
            ) {
                claimStopLocked(candidate)
            } else {
                null
            }
        }
        stopClaim?.let(::invokeStopIfOwner)
    }

    private fun claimStopLocked(candidate: AudioInput): StopClaim {
        val existing = stopOperation
        if (existing != null) return StopClaim(existing, invokeStop = false)
        val created = StopOperation(candidate)
        stopOperation = created
        return StopClaim(created, invokeStop = true)
    }

    private fun invokeStopIfOwner(claim: StopClaim) {
        if (!claim.invokeStop) return
        try {
            runCatching { claim.operation.input.stop() }
        } finally {
            claim.operation.completed.countDown()
        }
    }

    private fun awaitUninterruptibly(completion: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                completion.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun checkNotCancelled() {
        if (stateSnapshot() == State.CANCEL_REQUESTED) throw CancellationException("Recording cancelled")
    }

    private fun stateSnapshot(): State = synchronized(lock) { state }

    private class AndroidAudioInputFactory : AudioInputFactory {
        override fun create(): AudioInput? {
            val minimumBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimumBufferBytes <= 0) return null

            val bufferBytes = maxOf(minimumBufferBytes, READ_BUFFER_SAMPLES * BYTES_PER_SAMPLE)
            val record = try { AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            ) } catch (denied: SecurityException) {
                throw SecurityException("Microphone permission is unavailable or was revoked", denied)
            }
            return AndroidAudioInput(record)
        }
    }

    private class AndroidAudioInput(private val record: AudioRecord) : AudioInput {
        override val initialized: Boolean
            get() = record.state == AudioRecord.STATE_INITIALIZED

        override fun start() = record.startRecording()
        override fun read(buffer: ShortArray, offset: Int, size: Int): Int =
            record.read(buffer, offset, size, AudioRecord.READ_BLOCKING)
        override fun stop() = record.stop()
        override fun release() = record.release()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MAX_SAMPLE_COUNT = 480_000
        const val BYTES_PER_SAMPLE = 2
        const val READ_BUFFER_SAMPLES = 4_096
    }
}

internal fun interface AudioInputFactory {
    fun create(): AudioInput?
}

internal interface AudioInput {
    val initialized: Boolean
    fun start()
    fun read(buffer: ShortArray, offset: Int, size: Int): Int
    fun stop()
    fun release()
}
