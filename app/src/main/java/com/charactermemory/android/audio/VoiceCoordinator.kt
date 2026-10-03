package com.charactermemory.android.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VoiceTargetScope { DIRECT, GROUP }

/** Snapshot supplied by the caller; a task is usable only while this exact chat remains foreground. */
data class VoiceContextSnapshot(
    val serverGeneration: Long,
    val scope: VoiceTargetScope,
    val targetId: String,
    val conversationId: String,
    val isChatPage: Boolean,
    val foreground: Boolean
)

/** Permission, capture, ASR, and draft work all share this complete identity. */
data class VoiceTaskTicket(
    val serverGeneration: Long,
    val scope: VoiceTargetScope,
    val targetId: String,
    val conversationId: String,
    val taskGeneration: Long
)

enum class VoiceCoordinatorPhase {
    IDLE,
    CANCELLING,
    WAITING_PERMISSION,
    STARTING,
    RECORDING,
    STOPPING,
    TRANSCRIBING,
    DRAFT,
    ERROR
}

data class VoiceCoordinatorState(
    val phase: VoiceCoordinatorPhase = VoiceCoordinatorPhase.IDLE,
    val ticket: VoiceTaskTicket? = null,
    val elapsedMs: Long = 0L,
    val draft: String? = null,
    val error: String? = null
)

/** Result of an explicit user request to append the editable draft to typed text. */
sealed class DraftAppendResult {
    data class Appended(val text: String) : DraftAppendResult()
    data class TooLong(val composerText: String, val transcript: String) : DraftAppendResult()
    object Unavailable : DraftAppendResult()
}

/**
 * Adapter boundary for AudioRecordRecorder. [capture] owns native release; [stop] preserves PCM,
 * while [cancel] synchronously interrupts capture and discards its PCM before the task job is cancelled.
 */
interface VoiceRecorderPort {
    suspend fun capture(onDurationMs: (Long) -> Unit): ByteArray
    fun stop()
    fun cancel()
}

/**
 * Coordinates one foreground, user-started recording and its batch transcription. It never owns
 * the chat composer or a send operation. The caller supplies its lifecycle scope and dispatchers,
 * and must call [invalidate] when leaving CHAT, changing target/server, or entering the background.
 * [mainDispatcher] must dispatch to Android Main so [stopPlayback] completes there before capture
 * begins. [dispatcher] must support blocking capture and stop concurrently; [cancelDispatcher] must
 * have capacity to run native cancellation while a read blocks on [dispatcher] (normally both use
 * Dispatchers.IO). Progress updates use [dispatcher].
 */
class VoiceCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher,
    private val contextProvider: () -> VoiceContextSnapshot,
    private val recorderFactory: () -> VoiceRecorderPort,
    private val transcribePcm: suspend (ByteArray) -> String,
    private val stopPlayback: () -> Unit,
    private val cancelDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val watchdogDelay: suspend (Long) -> Unit = { delay(it) }
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(VoiceCoordinatorState())
    val state: StateFlow<VoiceCoordinatorState> = mutableState.asStateFlow()

    private var nextTaskGeneration = 0L
    private var activeTask: TaskSession? = null

    /** Returns a permission ticket only for a usable foreground CHAT context. */
    fun requestStart(): VoiceTaskTicket? {
        val initialContext = readContext() ?: return null
        if (!initialContext.isUsable()) return null

        return synchronized(lock) {
            if (activeTask != null || mutableState.value.phase !in setOf(
                    VoiceCoordinatorPhase.IDLE,
                    VoiceCoordinatorPhase.ERROR
                )
            ) return@synchronized null

            val currentContext = readContext() ?: return@synchronized null
            if (!currentContext.isUsable()) return@synchronized null

            nextTaskGeneration += 1L
            val ticket = currentContext.toTicket(nextTaskGeneration)
            activeTask = TaskSession(ticket)
            mutableState.value = VoiceCoordinatorState(
                phase = VoiceCoordinatorPhase.WAITING_PERMISSION,
                ticket = ticket
            )
            ticket
        }
    }

    /**
     * Delivers an external Android permission result. Returns true when the ticket was current;
     * a current denial records ERROR and also returns true. Stale grants never create a recorder.
     */
    fun onPermissionResult(ticket: VoiceTaskTicket, granted: Boolean): Boolean {
        var jobToStart: Job? = null
        var staleContext = false
        var accepted = false

        synchronized(lock) {
            val session = activeTask ?: return false
            if (session.ticket != ticket || mutableState.value.phase != VoiceCoordinatorPhase.WAITING_PERMISSION) {
                return false
            }
            if (!contextMatches(ticket)) {
                staleContext = true
            } else if (!granted) {
                activeTask = null
                nextTaskGeneration += 1L
                mutableState.value = VoiceCoordinatorState(
                    phase = VoiceCoordinatorPhase.ERROR,
                    ticket = ticket,
                    error = "录音权限未授予"
                )
                accepted = true
            } else {
                mutableState.value = mutableState.value.copy(
                    phase = VoiceCoordinatorPhase.STARTING,
                    error = null
                )
                val taskJob = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                    runTask(session)
                }
                session.taskJob = taskJob
                jobToStart = taskJob
                accepted = true
            }
        }

        if (staleContext) {
            cancel(ticket)
            return false
        }
        jobToStart?.start()
        return accepted
    }

    /** Gracefully stops the current recording; the shared stop gate makes repeated calls harmless. */
    fun stop(ticket: VoiceTaskTicket): Boolean {
        val session = synchronized(lock) {
            activeTask?.takeIf { it.ticket == ticket }
        } ?: return false
        return requestStop(session)
    }

    /**
     * Invalidates the matching task immediately. Potentially blocking native cancellation runs on
     * [cancelDispatcher], before the task job is cancelled, so AudioRecord.stop() can unblock read().
     * The state remains CANCELLING until the capture and cleanup jobs have finished.
     */
    fun cancel(ticket: VoiceTaskTicket? = null) {
        var recorderToCancel: VoiceRecorderPort? = null
        var taskJob: Job? = null
        var stopJob: Job? = null
        var timerJob: Job? = null
        var cleanupRequired = false

        synchronized(lock) {
            val current = activeTask
            if (current != null) {
                if (ticket != null && current.ticket != ticket) return
                activeTask = null
                nextTaskGeneration += 1L
                recorderToCancel = claimRecorderCancellationLocked(current)
                taskJob = current.taskJob
                stopJob = current.stopJob
                timerJob = current.watchdogJob
                current.stopJob = null
                current.watchdogJob = null
                cleanupRequired = recorderToCancel != null || taskJob?.isActive == true ||
                    stopJob?.isActive == true || timerJob?.isActive == true
                mutableState.value = if (cleanupRequired) {
                    VoiceCoordinatorState(phase = VoiceCoordinatorPhase.CANCELLING)
                } else {
                    VoiceCoordinatorState()
                }
            } else {
                if (ticket != null && mutableState.value.ticket != ticket) return
                if (mutableState.value.phase == VoiceCoordinatorPhase.CANCELLING) return
                if (mutableState.value.phase != VoiceCoordinatorPhase.IDLE) {
                    nextTaskGeneration += 1L
                    mutableState.value = VoiceCoordinatorState()
                }
                return
            }
        }

        if (!cleanupRequired) return

        // Do not run AudioRecord.stop() on the caller (often Main). It must complete before the
        // capture job is cancelled so its blocking read can return and execute recorder cleanup.
        CoroutineScope(Job() + cancelDispatcher).launch {
            try {
                runCatching { recorderToCancel?.cancel() }
            } finally {
                timerJob?.cancel()
                stopJob?.cancel()
                taskJob?.cancel()
                listOfNotNull(timerJob, stopJob, taskJob).joinAll()
                synchronized(lock) {
                    if (activeTask == null && mutableState.value.phase == VoiceCoordinatorPhase.CANCELLING) {
                        mutableState.value = VoiceCoordinatorState()
                    }
                }
            }
        }
    }

    /** Invalidates permission/results and releases any current task on page, target, or app changes. */
    fun invalidate() = cancel()

    /** Updates only the isolated transcript draft; it does not mutate the composer. */
    fun editDraft(ticket: VoiceTaskTicket, text: String): Boolean {
        var invalidContext = false
        val updated = synchronized(lock) {
            val session = activeTask
            if (session == null || session.ticket != ticket || mutableState.value.phase != VoiceCoordinatorPhase.DRAFT) {
                return@synchronized false
            }
            if (!contextMatches(ticket)) {
                invalidContext = true
                return@synchronized false
            }
            mutableState.value = mutableState.value.copy(draft = text)
            true
        }
        if (invalidContext) cancel(ticket)
        return updated
    }

    /**
     * Returns a proposed newline append. Oversized text and the editable draft are both preserved;
     * the caller remains responsible for applying an Appended value to its existing composer.
     */
    fun useDraft(ticket: VoiceTaskTicket, composerText: String): DraftAppendResult {
        var invalidContext = false
        val result = synchronized(lock) {
            val session = activeTask
            if (session == null || session.ticket != ticket || mutableState.value.phase != VoiceCoordinatorPhase.DRAFT) {
                return@synchronized DraftAppendResult.Unavailable
            }
            if (!contextMatches(ticket)) {
                invalidContext = true
                return@synchronized DraftAppendResult.Unavailable
            }
            val transcript = mutableState.value.draft?.takeIf { it.isNotBlank() }
                ?: return@synchronized DraftAppendResult.Unavailable
            val combined = if (composerText.isEmpty()) transcript else "$composerText\n$transcript"
            if (combined.length > MAX_COMPOSE_LENGTH) {
                return@synchronized DraftAppendResult.TooLong(composerText, transcript)
            }

            activeTask = null
            nextTaskGeneration += 1L
            mutableState.value = VoiceCoordinatorState()
            DraftAppendResult.Appended(combined)
        }
        if (invalidContext) cancel(ticket)
        return result
    }

    private suspend fun runTask(session: TaskSession) {
        var watchdog: Job? = null
        try {
            currentCoroutineContext().ensureActive()
            if (!isCurrentPhase(session, VoiceCoordinatorPhase.STARTING)) return

            // Playback may touch MediaPlayer and Compose state; wait for its Main-thread stop before
            // constructing AudioRecord. Returning to the task dispatcher keeps capture off Main.
            withContext(mainDispatcher) { stopPlayback() }
            currentCoroutineContext().ensureActive()
            if (!isCurrentPhase(session, VoiceCoordinatorPhase.STARTING)) return

            val recorder = recorderFactory()
            if (!attachRecorder(session, recorder)) return
            if (!moveToPhase(session, VoiceCoordinatorPhase.STARTING, VoiceCoordinatorPhase.RECORDING)) return

            val timer = CoroutineScope(currentCoroutineContext()).launch(dispatcher) {
                watchdogDelay(MAX_RECORDING_DURATION_MS)
                requestStop(session)
            }
            watchdog = timer
            storeWatchdog(session, timer)

            val pcm = recorder.capture { duration -> reportProgress(session, duration) }
            currentCoroutineContext().ensureActive()
            timer.cancel()

            if (pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > Pcm16Wav.MAX_PCM_BYTES) {
                throw IllegalStateException("录音 PCM 无效")
            }
            if (!moveToPhase(
                    session,
                    VoiceCoordinatorPhase.RECORDING,
                    VoiceCoordinatorPhase.TRANSCRIBING,
                    VoiceCoordinatorPhase.STOPPING
                )
            ) return

            val transcript = transcribePcm(pcm)
            currentCoroutineContext().ensureActive()
            if (transcript.isBlank()) throw IllegalStateException("识别结果为空")
            publishDraft(session, transcript)
        } catch (cancelled: CancellationException) {
            cancelRecorderOnce(session)
            settleExternalCancellation(session)
            throw cancelled
        } catch (error: Exception) {
            failTask(session, error, cancelTaskJob = false)
        } finally {
            watchdog?.cancel()
            synchronized(lock) {
                if (session.watchdogJob === watchdog) session.watchdogJob = null
            }
        }
    }

    private fun requestStop(session: TaskSession): Boolean {
        var invalidContext = false
        var recorder: VoiceRecorderPort? = null
        var timerJob: Job? = null
        val accepted = synchronized(lock) {
            if (activeTask !== session || mutableState.value.phase != VoiceCoordinatorPhase.RECORDING) {
                return@synchronized false
            }
            if (!contextMatches(session.ticket)) {
                invalidContext = true
                return@synchronized false
            }
            recorder = session.recorder
            if (recorder == null) return@synchronized false
            mutableState.value = mutableState.value.copy(phase = VoiceCoordinatorPhase.STOPPING)
            timerJob = session.watchdogJob
            session.watchdogJob = null
            true
        }

        if (invalidContext) {
            cancel(session.ticket)
            return false
        }
        if (!accepted) return false
        timerJob?.cancel()

        val selectedRecorder = recorder ?: return false
        val stopJob = scope.launch(dispatcher) {
            try {
                if (isCurrentPhase(session, VoiceCoordinatorPhase.STOPPING)) selectedRecorder.stop()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failTask(session, error, cancelTaskJob = true)
            }
        }
        synchronized(lock) {
            if (activeTask === session) session.stopJob = stopJob else stopJob.cancel()
        }
        return true
    }

    private fun attachRecorder(session: TaskSession, recorder: VoiceRecorderPort): Boolean {
        var invalidContext = false
        val attached = synchronized(lock) {
            if (activeTask === session && mutableState.value.phase == VoiceCoordinatorPhase.STARTING &&
                contextMatches(session.ticket)
            ) {
                session.recorder = recorder
                true
            } else {
                invalidContext = activeTask === session && !contextMatches(session.ticket)
                session.recorder = recorder
                false
            }
        }
        if (!attached) {
            cancelRecorderOnce(session)
            if (invalidContext) cancel(session.ticket)
        }
        return attached
    }

    private fun storeWatchdog(session: TaskSession, watchdog: Job) {
        var invalidContext = false
        val stored = synchronized(lock) {
            if (activeTask === session && mutableState.value.phase == VoiceCoordinatorPhase.RECORDING &&
                contextMatches(session.ticket)
            ) {
                session.watchdogJob = watchdog
                true
            } else {
                invalidContext = activeTask === session && !contextMatches(session.ticket)
                false
            }
        }
        if (!stored) {
            watchdog.cancel()
            if (invalidContext) cancel(session.ticket)
        }
    }

    private fun moveToPhase(
        session: TaskSession,
        expected: VoiceCoordinatorPhase,
        next: VoiceCoordinatorPhase,
        alsoExpected: VoiceCoordinatorPhase? = null
    ): Boolean {
        var invalidContext = false
        val moved = synchronized(lock) {
            if (activeTask !== session || mutableState.value.phase != expected && mutableState.value.phase != alsoExpected) {
                return@synchronized false
            }
            if (!contextMatches(session.ticket)) {
                invalidContext = true
                return@synchronized false
            }
            mutableState.value = mutableState.value.copy(phase = next, error = null)
            true
        }
        if (invalidContext) cancel(session.ticket)
        return moved
    }

    private fun isCurrentPhase(session: TaskSession, phase: VoiceCoordinatorPhase): Boolean {
        var invalidContext = false
        val current = synchronized(lock) {
            if (activeTask !== session || mutableState.value.phase != phase) return@synchronized false
            if (!contextMatches(session.ticket)) {
                invalidContext = true
                return@synchronized false
            }
            true
        }
        if (invalidContext) cancel(session.ticket)
        return current
    }

    private fun reportProgress(session: TaskSession, elapsedMs: Long) {
        val bounded = elapsedMs.coerceIn(0L, MAX_RECORDING_DURATION_MS)
        scope.launch(dispatcher) {
            synchronized(lock) {
                if (activeTask === session && mutableState.value.phase == VoiceCoordinatorPhase.RECORDING &&
                    contextMatches(session.ticket) && bounded >= mutableState.value.elapsedMs
                ) {
                    mutableState.value = mutableState.value.copy(elapsedMs = bounded)
                }
            }
        }
    }

    private fun publishDraft(session: TaskSession, transcript: String) {
        var invalidContext = false
        synchronized(lock) {
            if (activeTask !== session || mutableState.value.phase != VoiceCoordinatorPhase.TRANSCRIBING) return
            if (!contextMatches(session.ticket)) {
                invalidContext = true
            } else {
                mutableState.value = mutableState.value.copy(
                    phase = VoiceCoordinatorPhase.DRAFT,
                    draft = transcript,
                    error = null
                )
            }
        }
        if (invalidContext) cancel(session.ticket)
    }

    private fun failTask(session: TaskSession, error: Exception, cancelTaskJob: Boolean) {
        var recorderToCancel: VoiceRecorderPort? = null
        var taskJob: Job? = null
        var stopJob: Job? = null
        var timerJob: Job? = null
        val failed = synchronized(lock) {
            if (activeTask !== session) return@synchronized false
            activeTask = null
            nextTaskGeneration += 1L
            recorderToCancel = claimRecorderCancellationLocked(session)
            taskJob = session.taskJob
            stopJob = session.stopJob
            timerJob = session.watchdogJob
            session.stopJob = null
            session.watchdogJob = null
            mutableState.value = VoiceCoordinatorState(
                phase = VoiceCoordinatorPhase.ERROR,
                ticket = session.ticket,
                elapsedMs = mutableState.value.elapsedMs,
                error = error.message?.takeIf { it.isNotBlank() } ?: "录音或识别失败"
            )
            true
        }
        if (!failed) return
        try {
            runCatching { recorderToCancel?.cancel() }
        } finally {
            timerJob?.cancel()
            stopJob?.cancel()
            if (cancelTaskJob) taskJob?.cancel()
        }
    }

    private fun settleExternalCancellation(session: TaskSession) {
        synchronized(lock) {
            if (activeTask === session) {
                activeTask = null
                nextTaskGeneration += 1L
                mutableState.value = VoiceCoordinatorState()
            }
        }
    }

    private fun cancelRecorderOnce(session: TaskSession) {
        val recorder = synchronized(lock) { claimRecorderCancellationLocked(session) } ?: return
        runCatching { recorder.cancel() }
    }

    private fun claimRecorderCancellationLocked(session: TaskSession): VoiceRecorderPort? {
        val recorder = session.recorder ?: return null
        if (session.recorderCancelIssued) return null
        session.recorderCancelIssued = true
        return recorder
    }

    private fun contextMatches(ticket: VoiceTaskTicket): Boolean {
        val context = readContext() ?: return false
        return context.isUsable() &&
            context.serverGeneration == ticket.serverGeneration &&
            context.scope == ticket.scope &&
            context.targetId == ticket.targetId &&
            context.conversationId == ticket.conversationId
    }

    private fun readContext(): VoiceContextSnapshot? = try {
        contextProvider()
    } catch (_: Exception) {
        null
    }

    private fun VoiceContextSnapshot.isUsable(): Boolean =
        isChatPage && foreground && targetId.isNotBlank() && conversationId.isNotBlank()

    private fun VoiceContextSnapshot.toTicket(taskGeneration: Long) = VoiceTaskTicket(
        serverGeneration = serverGeneration,
        scope = scope,
        targetId = targetId,
        conversationId = conversationId,
        taskGeneration = taskGeneration
    )

    private class TaskSession(val ticket: VoiceTaskTicket) {
        var recorder: VoiceRecorderPort? = null
        var taskJob: Job? = null
        var stopJob: Job? = null
        var watchdogJob: Job? = null
        var recorderCancelIssued: Boolean = false
    }

    companion object {
        const val MAX_RECORDING_DURATION_MS = 30_000L
        const val MAX_COMPOSE_LENGTH = 12_000
    }
}
