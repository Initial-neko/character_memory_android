package com.charactermemory.android.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VoiceCoordinatorTest {
    @Test fun transcriptionClientIsFrozenWhenRequestStarts() {
        var destination = "old-media"
        val harness = Harness(transcriberFactory = { val frozen = destination; { _: ByteArray -> frozen } })
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            destination = "new-media"
            assertTrue(harness.coordinator.onPermissionResult(ticket, true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }
            harness.coordinator.stop(ticket)
            runBlocking { withTimeout(2_000L) { harness.coordinator.state.first { it.phase == VoiceCoordinatorPhase.DRAFT } } }
            assertEquals("old-media", harness.coordinator.state.value.draft)
        } finally { harness.close() }
    }
    @Test
    fun startRequiresForegroundChatAndCompleteTarget() {
        val inactive = listOf(
            activeContext().copy(isChatPage = false),
            activeContext().copy(foreground = false),
            activeContext().copy(targetId = ""),
            activeContext().copy(conversationId = "")
        )

        inactive.forEach { context ->
            val harness = Harness(context)
            try {
                assertNull(harness.coordinator.requestStart())
                assertEquals(0, harness.factoryCalls.get())
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun latePermissionGrantMustMatchEveryTicketField() {
        val original = activeContext()
        val changed = listOf(
            original.copy(serverGeneration = original.serverGeneration + 1),
            original.copy(scope = VoiceTargetScope.GROUP),
            original.copy(targetId = "another-target"),
            original.copy(conversationId = "another-conversation"),
            original.copy(isChatPage = false),
            original.copy(foreground = false)
        )

        changed.forEach { context ->
            val harness = Harness(original)
            try {
                val ticket = requireNotNull(harness.coordinator.requestStart())
                harness.context = context

                assertFalse(harness.coordinator.onPermissionResult(ticket, granted = true))
                assertEquals(0, harness.factoryCalls.get())
                assertEquals(0, harness.playerStops.get())
            } finally {
                harness.close()
            }
        }
    }

    @Test
    fun duplicateStartIsRejectedAndPlayerStopsBeforeRecorderStarts() {
        val harness = Harness()
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }

            assertNull(harness.coordinator.requestStart())
            assertTrue(harness.events.indexOf("player-stop") < harness.events.indexOf("recorder-create"))
            assertTrue(harness.events.indexOf("recorder-create") < harness.events.indexOf("capture-start"))
            assertEquals(125L, harness.coordinator.state.value.elapsedMs)
            assertEquals(1, harness.playerStops.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun playerStopRunsOnMainDispatcherAndCompletesBeforeRecorderCreation() {
        val audioDispatcher = singleThreadDispatcher("voice-audio-io")
        val mainDispatcher = singleThreadDispatcher("voice-ui-main")
        val playerStopEntered = CountDownLatch(1)
        val allowPlayerStopReturn = CountDownLatch(1)
        val harness = Harness(
            dispatcher = audioDispatcher,
            mainDispatcher = mainDispatcher,
            playerStopEntered = playerStopEntered,
            allowPlayerStopReturn = allowPlayerStopReturn
        )
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            assertTrue(playerStopEntered.await(2, TimeUnit.SECONDS))
            assertEquals(0, harness.factoryCalls.get())

            allowPlayerStopReturn.countDown()
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }

            assertEquals("voice-ui-main", harness.playerThreadName.get().substringBefore(" @coroutine#"))
            assertEquals("voice-audio-io", harness.factoryThreadName.get().substringBefore(" @coroutine#"))
            assertEquals("voice-audio-io", harness.recorder.captureThreadName.get().substringBefore(" @coroutine#"))
            assertTrue(harness.events.indexOf("player-stop-complete") < harness.events.indexOf("recorder-create"))

            assertTrue(harness.coordinator.stop(ticket))
            runBlocking {
                withTimeout(2_000) {
                    harness.coordinator.state.first { it.phase == VoiceCoordinatorPhase.DRAFT }
                }
            }
        } finally {
            allowPlayerStopReturn.countDown()
            harness.close()
            mainDispatcher.close()
            audioDispatcher.close()
        }
    }

    @Test
    fun deniedPermissionDoesNotCreateRecorder() {
        val harness = Harness()
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())

            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = false))

            assertEquals(0, harness.factoryCalls.get())
            assertEquals(VoiceCoordinatorPhase.ERROR, harness.coordinator.state.value.phase)
        } finally {
            harness.close()
        }
    }

    @Test
    fun cancelDiscardsCaptureAndCancelsRecorderBeforeTaskJob() {
        val harness = Harness()
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }

            harness.coordinator.cancel(ticket)

            assertEquals(1, harness.recorder.cancelCalls.get())
            assertEquals(1, harness.recorder.releaseCalls.get())
            assertEquals(0, harness.transcribeCalls.get())
            assertEquals(VoiceCoordinatorPhase.IDLE, harness.coordinator.state.value.phase)
            assertTrue(harness.events.indexOf("recorder-cancel-start") < harness.events.indexOf("capture-finished"))
        } finally {
            harness.close()
        }
    }

    @Test
    fun cancellationReturnsOnCallerThenStopsCaptureOnIoBeforeFinishingCleanup() {
        val enteredCancel = CountDownLatch(1)
        val allowCancelReturn = CountDownLatch(1)
        val cancelDispatcher = singleThreadDispatcher("voice-cancel-io")
        val caller = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "voice-ui-caller") }
        val harness = Harness(
            cancelDispatcher = cancelDispatcher,
            cancelEntered = enteredCancel,
            allowCancelReturn = allowCancelReturn
        )
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }

            val returnedOn = caller.submit<String> {
                harness.coordinator.cancel(ticket)
                Thread.currentThread().name
            }.get(2, TimeUnit.SECONDS)

            assertEquals("voice-ui-caller", returnedOn)
            assertTrue(enteredCancel.await(2, TimeUnit.SECONDS))
            assertEquals("voice-cancel-io", harness.recorder.cancelThreadName.get().substringBefore(" @coroutine#"))
            assertEquals(VoiceCoordinatorPhase.CANCELLING, harness.coordinator.state.value.phase)
            assertNull(harness.coordinator.state.value.ticket)
            assertNull(harness.coordinator.requestStart())
            assertEquals(0, harness.recorder.releaseCalls.get())

            allowCancelReturn.countDown()
            runBlocking {
                withTimeout(2_000) {
                    harness.coordinator.state.first { it.phase == VoiceCoordinatorPhase.IDLE }
                }
            }
            assertTrue(harness.events.indexOf("recorder-cancel-return") < harness.events.indexOf("capture-finished"))
            assertEquals(1, harness.recorder.cancelCalls.get())
        } finally {
            allowCancelReturn.countDown()
            harness.close()
            caller.shutdownNow()
            cancelDispatcher.close()
        }
    }

    @Test
    fun watchdogStopsAtThirtySecondsAndManualStopRaceTranscribesOnce() {
        val watchdogStarted = CompletableDeferred<Long>()
        val fireWatchdog = CompletableDeferred<Unit>()
        val harness = Harness(
            watchdogDelay = { duration ->
                watchdogStarted.complete(duration)
                fireWatchdog.await()
            }
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }
            assertEquals(30_000L, runBlocking { withTimeout(2_000L) { watchdogStarted.await() } })

            val ready = CountDownLatch(2)
            val begin = CountDownLatch(1)
            val manualStop = pool.submit<Boolean> {
                ready.countDown()
                begin.await(2, TimeUnit.SECONDS)
                harness.coordinator.stop(ticket)
            }
            val timeout = pool.submit<Boolean> {
                ready.countDown()
                begin.await(2, TimeUnit.SECONDS)
                fireWatchdog.complete(Unit)
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            begin.countDown()
            manualStop.get(2, TimeUnit.SECONDS)
            timeout.get(2, TimeUnit.SECONDS)

            assertEquals(1, harness.recorder.stopCalls.get())
            assertEquals(1, harness.recorder.releaseCalls.get())
            assertEquals(1, harness.transcribeCalls.get())
            assertEquals(VoiceCoordinatorPhase.DRAFT, harness.coordinator.state.value.phase)
        } finally {
            harness.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun thirtySecondWatchdogStopsAndTranscribesWithoutManualStop() {
        val watchdogStarted = CompletableDeferred<Long>()
        val fireWatchdog = CompletableDeferred<Unit>()
        val harness = Harness(
            watchdogDelay = { duration ->
                watchdogStarted.complete(duration)
                fireWatchdog.await()
            }
        )
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }
            assertEquals(30_000L, runBlocking { withTimeout(2_000L) { watchdogStarted.await() } })

            fireWatchdog.complete(Unit)

            assertEquals(1, harness.recorder.stopCalls.get())
            assertEquals(1, harness.recorder.releaseCalls.get())
            assertEquals(1, harness.transcribeCalls.get())
            assertEquals(VoiceCoordinatorPhase.DRAFT, harness.coordinator.state.value.phase)
        } finally {
            harness.close()
        }
    }

    @Test
    fun stopProducesEditableDraftWithoutChangingComposerOrSending() {
        val harness = Harness(transcript = "first recognition")
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }

            assertTrue(harness.coordinator.stop(ticket))
            assertFalse(harness.coordinator.stop(ticket))

            assertEquals(1, harness.transcribeCalls.get())
            assertEquals(1, harness.recorder.stopCalls.get())
            assertEquals(1, harness.recorder.releaseCalls.get())
            assertEquals("first recognition", harness.coordinator.state.value.draft)
            assertEquals(VoiceCoordinatorPhase.DRAFT, harness.coordinator.state.value.phase)
            assertTrue(harness.coordinator.editDraft(ticket, "edited recognition"))
            assertEquals("edited recognition", harness.coordinator.state.value.draft)
            // Composer and send are deliberately outside this coordinator's ports and state.
            assertFalse(harness.events.any { it == "send" })
        } finally {
            harness.close()
        }
    }

    @Test
    fun successfulUseReturnsAppendTextAndConsumesOnlyDraft() {
        val harness = Harness(transcript = "recognized")
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }
            assertTrue(harness.coordinator.stop(ticket))

            val result = harness.coordinator.useDraft(ticket, composerText = "typed")

            assertTrue(result is DraftAppendResult.Appended)
            assertEquals("typed\nrecognized", (result as DraftAppendResult.Appended).text)
            assertEquals(VoiceCoordinatorPhase.IDLE, harness.coordinator.state.value.phase)
            assertEquals(1, harness.transcribeCalls.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun emptyComposerDoesNotGainLeadingNewline() {
        val harness = Harness(transcript = "recognized")
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }
            assertTrue(harness.coordinator.stop(ticket))

            val result = harness.coordinator.useDraft(ticket, composerText = "")

            assertEquals("recognized", (result as DraftAppendResult.Appended).text)
        } finally {
            harness.close()
        }
    }

    @Test
    fun appendOverTwelveThousandKeepsComposerAndDraftUnchanged() {
        val transcript = "B"
        val harness = Harness(transcript = transcript)
        val composer = "A".repeat(12_000)
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }
            assertTrue(harness.coordinator.stop(ticket))

            val result = harness.coordinator.useDraft(ticket, composerText = composer)

            assertTrue(result is DraftAppendResult.TooLong)
            assertEquals(composer, (result as DraftAppendResult.TooLong).composerText)
            assertEquals(transcript, result.transcript)
            assertEquals(VoiceCoordinatorPhase.DRAFT, harness.coordinator.state.value.phase)
            assertEquals(transcript, harness.coordinator.state.value.draft)
        } finally {
            harness.close()
        }
    }

    @Test
    fun appendLimitCountsUtf16CodeUnitsAtTheExactBoundary() {
        val harness = Harness(transcript = "X")
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }
            assertTrue(harness.coordinator.stop(ticket))

            val composer = "🙂".repeat(5_999) // 11,998 UTF-16 code units.
            val result = harness.coordinator.useDraft(ticket, composer)

            assertTrue(result is DraftAppendResult.Appended)
            assertEquals(12_000, (result as DraftAppendResult.Appended).text.length)
        } finally {
            harness.close()
        }
    }

    @Test
    fun blankTranscriptionDoesNotCreateDraft() {
        val harness = Harness(transcript = " \n ")
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }
            assertTrue(harness.coordinator.stop(ticket))

            assertEquals(VoiceCoordinatorPhase.ERROR, harness.coordinator.state.value.phase)
            assertNull(harness.coordinator.state.value.draft)
            assertEquals(1, harness.transcribeCalls.get())
        } finally {
            harness.close()
        }
    }

    @Test
    fun cancellingInflightRecognitionSuppressesLateTranscript() {
        val transcribeStarted = CompletableDeferred<Unit>()
        val cancellationSwallowed = CompletableDeferred<Unit>()
        val harness = Harness(transcriber = {
            transcribeStarted.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
                "unreachable"
            } catch (_: CancellationException) {
                cancellationSwallowed.complete(Unit)
                "late transcript"
            }
        })
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }
            assertTrue(harness.coordinator.stop(ticket))
            runBlocking { withTimeout(2_000L) { transcribeStarted.await() } }

            harness.coordinator.cancel(ticket)
            runBlocking { withTimeout(2_000L) { cancellationSwallowed.await() } }

            assertEquals(VoiceCoordinatorPhase.IDLE, harness.coordinator.state.value.phase)
            assertNull(harness.coordinator.state.value.ticket)
            assertNull(harness.coordinator.state.value.draft)
        } finally {
            harness.close()
        }
    }

    @Test
    fun changedTargetSuppressesLateRecognitionResult() {
        val transcribeStarted = CompletableDeferred<Unit>()
        val finishTranscription = CompletableDeferred<Unit>()
        val harness = Harness(transcriber = {
            transcribeStarted.complete(Unit)
            finishTranscription.await()
            "late transcript"
        })
        try {
            val ticket = requireNotNull(harness.coordinator.requestStart())
            assertTrue(harness.coordinator.onPermissionResult(ticket, granted = true))
            runBlocking { withTimeout(2_000L) { harness.recorder.captureStarted.await() } }
            assertTrue(harness.coordinator.stop(ticket))
            runBlocking { withTimeout(2_000L) { transcribeStarted.await() } }

            harness.context = harness.context.copy(conversationId = "replacement-conversation")
            finishTranscription.complete(Unit)

            assertEquals(VoiceCoordinatorPhase.IDLE, harness.coordinator.state.value.phase)
            assertNull(harness.coordinator.state.value.ticket)
            assertNull(harness.coordinator.state.value.draft)
        } finally {
            harness.close()
        }
    }

    @Test
    fun externalInvalidationMakesOldPermissionAndResultTicketsStale() {
        val harness = Harness()
        try {
            val oldTicket = requireNotNull(harness.coordinator.requestStart())
            harness.coordinator.invalidate()
            val newTicket = requireNotNull(harness.coordinator.requestStart())

            assertTrue(newTicket.taskGeneration > oldTicket.taskGeneration)
            assertFalse(harness.coordinator.onPermissionResult(oldTicket, granted = true))
            assertTrue(harness.coordinator.onPermissionResult(newTicket, granted = false))
            assertEquals(0, harness.factoryCalls.get())
        } finally {
            harness.close()
        }
    }

    private class Harness(
        @Volatile var context: VoiceContextSnapshot = activeContext(),
        transcript: String = "recognized",
        transcriber: (suspend (ByteArray) -> String)? = null,
        transcriberFactory: (() -> (suspend (ByteArray) -> String))? = null,
        watchdogDelay: suspend (Long) -> Unit = { CompletableDeferred<Unit>().await() },
        private val dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        private val mainDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        private val cancelDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        playerStopEntered: CountDownLatch? = null,
        allowPlayerStopReturn: CountDownLatch? = null,
        cancelEntered: CountDownLatch? = null,
        allowCancelReturn: CountDownLatch? = null
    ) : AutoCloseable {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val factoryCalls = AtomicInteger()
        val playerStops = AtomicInteger()
        val transcribeCalls = AtomicInteger()
        val playerThreadName = AtomicReference<String>()
        val factoryThreadName = AtomicReference<String>()
        val recorder = FakeRecorder(events, cancelEntered, allowCancelReturn)
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = VoiceCoordinator(
            scope = scope,
            dispatcher = dispatcher,
            mainDispatcher = mainDispatcher,
            cancelDispatcher = cancelDispatcher,
            contextProvider = { context },
            recorderFactory = {
                events += "recorder-create"
                factoryThreadName.set(Thread.currentThread().name)
                factoryCalls.incrementAndGet()
                recorder
            },
            transcribePcm = { pcm ->
                assertTrue(pcm.isNotEmpty())
                transcribeCalls.incrementAndGet()
                events += "transcribe"
                transcriber?.invoke(pcm) ?: transcript
            },
            stopPlayback = {
                events += "player-stop"
                playerStops.incrementAndGet()
                playerThreadName.set(Thread.currentThread().name)
                playerStopEntered?.countDown()
                allowPlayerStopReturn?.await(5, TimeUnit.SECONDS)
                events += "player-stop-complete"
            },
            watchdogDelay = watchdogDelay,
            transcribeFactory = transcriberFactory
        )

        override fun close() {
            coordinator.invalidate()
            scope.cancel()
        }
    }

    private class FakeRecorder(
        private val events: MutableList<String>,
        private val cancelEntered: CountDownLatch? = null,
        private val allowCancelReturn: CountDownLatch? = null
    ) : VoiceRecorderPort {
        val captureStarted = CompletableDeferred<Unit>()
        private val pcm = CompletableDeferred<ByteArray>()
        val stopCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        val captureThreadName = AtomicReference<String>()
        val cancelThreadName = AtomicReference<String>()

        override suspend fun capture(onDurationMs: (Long) -> Unit): ByteArray {
            events += "capture-start"
            captureThreadName.set(Thread.currentThread().name)
            captureStarted.complete(Unit)
            onDurationMs(125)
            return try {
                pcm.await()
            } finally {
                releaseCalls.incrementAndGet()
                events += "capture-finished"
            }
        }

        override fun stop() {
            events += "recorder-stop"
            stopCalls.incrementAndGet()
            pcm.complete(byteArrayOf(0x01, 0x02))
        }

        override fun cancel() {
            cancelThreadName.set(Thread.currentThread().name)
            events += "recorder-cancel-start"
            cancelCalls.incrementAndGet()
            cancelEntered?.countDown()
            allowCancelReturn?.await(5, TimeUnit.SECONDS)
            events += "recorder-cancel-return"
            pcm.cancel(CancellationException("capture cancelled"))
        }
    }

    companion object {
        private fun singleThreadDispatcher(name: String) =
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }.asCoroutineDispatcher()

        private fun activeContext() = VoiceContextSnapshot(
            serverGeneration = 7L,
            scope = VoiceTargetScope.DIRECT,
            targetId = "character-1",
            conversationId = "conversation-1",
            isChatPage = true,
            foreground = true
        )
    }
}
