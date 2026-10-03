package com.charactermemory.android.audio

class AndroidVoiceRecorder(automaticSegment: Boolean = false) : VoiceRecorderPort {
    private val recorder = if (automaticSegment) AudioRecordRecorder(PcmSpeechSegmenter()) else AudioRecordRecorder()
    override suspend fun capture(onDurationMs: (Long) -> Unit) = recorder.capture(onDurationMs)
    override fun stop() = recorder.stop()
    override fun cancel() = recorder.cancel()
}
