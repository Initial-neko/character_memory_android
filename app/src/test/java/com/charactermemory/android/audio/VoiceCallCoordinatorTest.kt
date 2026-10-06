package com.charactermemory.android.audio

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceCallCoordinatorTest {
    @Test fun permissionReceiptRejectsDuplicateAndStaleCallbacks() = runBlocking {
        val port = FakePort()
        val context = VoiceContextSnapshot(1, VoiceTargetScope.DIRECT, "a", "c", true, true)
        val call = VoiceCallCoordinator(this, { context }, { port })
        val ticket = call.requestStart()!!
        assertEquals(false, call.permission(ticket - 1, true))
        assertEquals(true, call.permission(ticket, true))
        assertEquals(false, call.permission(ticket, true))
        call.end()
    }
    @Test fun userMuteCancelsCaptureWithoutEndingCallAndUnmuteStartsOneRecorder() = runBlocking {
        val port = FakePort()
        val context = VoiceContextSnapshot(1, VoiceTargetScope.DIRECT, "a", "c", true, true)
        val call = VoiceCallCoordinator(this, { context }, { port })
        call.permission(call.requestStart()!!, true)
        withTimeout(2000) { while (port.captures != 1) yield() }
        setMute(call, true)
        withTimeout(2000) { while (!port.cancelled) yield() }
        port.pcm.complete(byteArrayOf(1, 2))
        delay(30)
        assertTrue(call.state.value.active)
        assertEquals(0, port.sent)
        assertEquals(1, port.captures)
        setMute(call, false); setMute(call, false)
        withTimeout(2000) { while (port.captures != 2) yield() }
        call.end()
    }
    @Test fun userMuteRejectsLateAsrButKeepsCallActive() = runBlocking {
        val port = FakePort().apply { delayedAsr = CompletableDeferred() }
        val context = VoiceContextSnapshot(1, VoiceTargetScope.DIRECT, "a", "c", true, true)
        val call = VoiceCallCoordinator(this, { context }, { port })
        call.permission(call.requestStart()!!, true)
        port.pcm.complete(byteArrayOf(1, 2))
        withTimeout(2000) { while (!port.transcribing) yield() }
        setMute(call, true)
        port.delayedAsr!!.complete("late transcript")
        delay(30)
        assertTrue(call.state.value.active)
        assertEquals(0, port.sent)
        call.end()
    }
    @Test fun transportPausePreservesQueuedTranscriptUntilAcceptedTurnCompletes() = runBlocking {
        val port = FakePort()
        val context = VoiceContextSnapshot(1, VoiceTargetScope.DIRECT, "a", "c", true, true)
        val call = VoiceCallCoordinator(this, { context }, { port })
        call.permission(call.requestStart()!!, true)
        port.pcm.complete(byteArrayOf(1, 2))
        withTimeout(2000) { while (port.sent != 1 || port.captures < 2) yield() }
        assertEquals("1", call.state.value.receiptKey)
        port.nextPcm.complete(byteArrayOf(3, 4))
        withTimeout(2000) { while (call.state.value.pendingCount != 1) yield() }

        call.setTransportAvailable(false)
        withTimeout(2000) { while (!port.cancelled) yield() }
        assertEquals(1, call.state.value.pendingCount)
        call.setTransportAvailable(true)
        withTimeout(2000) { while (port.captures < 3) yield() }

        call.reply("reply-1", "1", "answer", "a")
        call.completed("1")
        withTimeout(2000) { while (port.sent < 2) yield() }
        assertEquals("2", call.state.value.receiptKey)
        call.end()
    }
    @Test fun historyReplyReconciliationRequiresTheCurrentAcceptedReceipt() = runBlocking {
        val port = FakePort()
        val context = VoiceContextSnapshot(1, VoiceTargetScope.DIRECT, "a", "c", true, true)
        val call = VoiceCallCoordinator(this, { context }, { port })
        call.permission(call.requestStart()!!, true)
        port.pcm.complete(byteArrayOf(1, 2))
        withTimeout(2000) { while (call.state.value.receiptKey == null) yield() }

        assertFalse(call.reconcileHistoryReply("stale", "reply-1", "wrong", "a"))
        assertFalse(call.reconcileHistoryCompleted("stale"))
        assertTrue(call.reconcileHistoryReply("1", "reply-1", "durable reply", "a"))
        assertTrue(call.reconcileHistoryCompleted("1"))
        withTimeout(2000) { while (port.spoken == 0) yield() }
        assertEquals("durable reply", call.state.value.replyText)
        call.end()
    }
    @Test fun playbackCompletionNeverReopensUserMutedMicrophone() = runBlocking {
        val port = FakePort().apply { playbackGate = CompletableDeferred() }
        val context = VoiceContextSnapshot(1, VoiceTargetScope.DIRECT, "a", "c", true, true)
        val call = VoiceCallCoordinator(this, { context }, { port })
        call.permission(call.requestStart()!!, true)
        port.pcm.complete(byteArrayOf(1, 2))
        withTimeout(2000) { while (port.sent != 1) yield() }
        call.reply("reply", "1", "accepted response", "a")
        call.completed("1")
        withTimeout(2000) { while (call.state.value.phase != "speaking") yield() }
        setMute(call, true)
        val before = port.captures
        port.playbackGate!!.complete(Unit)
        delay(60)
        assertEquals(before, port.captures)
        assertTrue(call.state.value.active)
        assertEquals(1, port.sent)
        call.end()
    }
    private fun setMute(call: VoiceCallCoordinator, muted: Boolean) {
        call.setMicrophoneMuted(muted)
    }
    @Test fun endingAnIdleCallDoesNotTransientlyDisableDictationOrPlayback() = runBlocking {
        val context = VoiceContextSnapshot(1, VoiceTargetScope.DIRECT, "a", "c", true, true)
        val call = VoiceCallCoordinator(this, { context }, { FakePort() })
        call.end()
        assertEquals("idle", call.state.value.phase)
        call.end()
        assertEquals("idle", call.state.value.phase)
    }
    @Test fun matchedReactionCompletionStopsTimeoutWhileLongAudioIsStillPlaying() = runBlocking {
        val port = FakePort().apply { playbackGate = CompletableDeferred() }
        val context = VoiceContextSnapshot(1, VoiceTargetScope.GROUP, "g", "c", true, true)
        val call = VoiceCallCoordinator(this, { context }, { port }, responseTimeoutMs = 40)
        call.permission(call.requestStart()!!, true)
        port.pcm.complete(byteArrayOf(1, 2))
        withTimeout(2000) { while (port.sent == 0) yield() }
        call.reply("a", "1", "reply", "alice")
        call.completed("1")
        delay(100)
        assertTrue("Completed server reply must not time out during queued playback", call.state.value.active)
        call.end(); port.playbackGate!!.complete(Unit)
        Unit
    }
    @Test fun destroyedOwnerStillReleasesNativeRecorder() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val port = FakePort()
        val context = VoiceContextSnapshot(1, VoiceTargetScope.DIRECT, "a", "c", true, true)
        val call = VoiceCallCoordinator(owner, { context }, { port })
        call.permission(call.requestStart()!!, true)
        assertEquals(1, port.captures)
        owner.cancel()
        call.end()
        withTimeout(500) { while (!port.cancelled) yield() }
        assertFalse(call.state.value.active)
    }
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
        val nextPcm = CompletableDeferred<ByteArray>()
        var captures = 0; @Volatile var cancelled = false; var sent = 0
        var spoken = 0
        var transcribing = false
        var delayedAsr: CompletableDeferred<String>? = null
        var playbackFails = false
        var playbackGate: CompletableDeferred<Unit>? = null
        override fun recorder(): VoiceRecorderPort = object : VoiceRecorderPort {
            override suspend fun capture(onDurationMs: (Long) -> Unit): ByteArray {
                captures++
                return when (captures) { 1 -> pcm.await(); 2 -> nextPcm.await(); else -> CompletableDeferred<ByteArray>().await() }
            }
            override fun cancel() { cancelled = true }
            override fun stop() {}
        }
        override suspend fun transcribe(pcm: ByteArray): String { transcribing = true; return delayedAsr?.await() ?: "late" }
        override suspend fun send(text: String): String { sent++; return sent.toString() }
        override suspend fun speak(item: CallEffect.Speak) { spoken++; if (playbackFails) throw java.io.IOException("broken audio"); playbackGate?.await() }
        override fun stopPlayback() {}
    }
}
