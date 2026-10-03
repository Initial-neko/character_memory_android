package com.charactermemory.android.audio

class AndroidVoiceRecorder : VoiceRecorderPort {
    private val recorder = AudioRecordRecorder()
    override suspend fun capture(onDurationMs: (Long) -> Unit) = recorder.capture(onDurationMs)
    override fun stop() = recorder.stop()
    override fun cancel() = recorder.cancel()
}
