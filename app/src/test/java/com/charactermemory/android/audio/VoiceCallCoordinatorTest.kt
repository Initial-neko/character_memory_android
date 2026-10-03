package com.charactermemory.android.audio

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceCallCoordinatorTest {
    @Test fun deniedOrStalePermissionNeverStartsCapture() = runBlocking {
        var context = VoiceContextSnapshot(1, VoiceTargetScope.DIRECT, "a", "c", true, true)
        val port = FakePort()
        val call = VoiceCallCoordinator(this, { context }, { port })
        val denied = call.requestStart()!!
        call.permission(denied, false)
        assertFalse(call.state.value.active); assertEquals(0, port.captures)
        var stale: Long? = null
        withTimeout(2000) { while (stale == null) { stale = call.requestStart(); yield() } }
        context = context.copy(targetId = "b")
        call.permission(stale!!, true)
        yield()
        assertFalse(call.state.value.active); assertEquals(0, port.captures)
    }
    @Test fun hangupCancelsNativeCaptureAndDoesNotSendLateTranscript() = runBlocking {
        val port = FakePort()
        val context = VoiceContextSnapshot(1, VoiceTargetScope.DIRECT, "a", "c", true, true)
        val call = VoiceCallCoordinator(this, { context }, { port })
        call.permission(call.requestStart()!!, true)
        yield(); assertEquals(1, port.captures)
        call.end()
        withTimeout(2000) { while (!port.cancelled) yield() }
        port.pcm.complete(byteArrayOf(1, 2)); yield()
        assertEquals(0, port.sent); assertFalse(call.state.value.active)
    }
    @Test fun hangupDuringAsrNeverPersistsLateText() = runBlocking {
        val port = FakePort().apply { delayedAsr = CompletableDeferred() }
        val context = VoiceContextSnapshot(1, VoiceTargetScope.DIRECT, "a", "c", true, true)
        val call = VoiceCallCoordinator(this, { context }, { port })
        call.permission(call.requestStart()!!, true)
        port.pcm.complete(byteArrayOf(1, 2))
        withTimeout(2000) { while (!port.transcribing) yield() }
        call.end()
        port.delayedAsr!!.complete("late text")
        withTimeout(2000) { while (call.state.value.phase != "idle") yield() }
        assertEquals(0, port.sent)
    }
    @Test fun failedPlaybackKeepsTextAndResumesCaptureAfterReactionCompletes() = runBlocking {
        val port = FakePort().apply { playbackFails = true }
        val context = VoiceContextSnapshot(1, VoiceTargetScope.GROUP, "g", "c", true, true)
        val call = VoiceCallCoordinator(this, { context }, { port })
        call.permission(call.requestStart()!!, true)
        port.pcm.complete(byteArrayOf(1, 2))
        withTimeout(2000) { while (port.sent == 0 || port.captures < 2) yield() }
        call.reply("a", "1", "reply", "alice"); call.completed("1")
        withTimeout(2000) { while (call.state.value.phase != "listening" || port.captures < 3) yield() }
        assertEquals("语音播放失败，文字回复仍保留", call.state.value.error)
        assertEquals(1, port.sent)
        assertTrue(port.cancelled)
        call.end()
    }
    private class FakePort : VoiceCallPort {
        val pcm = CompletableDeferred<ByteArray>()
        var captures = 0; @Volatile var cancelled = false; var sent = 0
        var transcribing = false
        var delayedAsr: CompletableDeferred<String>? = null
        var playbackFails = false
        override fun recorder(): VoiceRecorderPort = object : VoiceRecorderPort {
            override suspend fun capture(onDurationMs: (Long) -> Unit): ByteArray { captures++; return if (captures == 1) pcm.await() else CompletableDeferred<ByteArray>().await() }
            override fun cancel() { cancelled = true }
            override fun stop() {}
        }
        override suspend fun transcribe(pcm: ByteArray): String { transcribing = true; return delayedAsr?.await() ?: "late" }
        override suspend fun send(text: String): String { sent++; return "1" }
        override suspend fun speak(item: CallEffect.Speak) { if (playbackFails) throw java.io.IOException("broken audio") }
        override fun stopPlayback() {}
    }
}
