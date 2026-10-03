package com.charactermemory.android.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface VoiceCallPort {
    fun recorder(): VoiceRecorderPort
    suspend fun transcribe(pcm: ByteArray): String
    suspend fun send(text: String): String
    suspend fun speak(item: CallEffect.Speak)
    fun stopPlayback()
}

data class VoiceCallState(
    val active: Boolean = false,
    val phase: String = "idle",
    val transcript: String = "",
    val speaker: String? = null,
    val pendingCount: Int = 0,
    val error: String? = null
)

/** Owned by Main. The port is frozen before permission; native cancellation runs independently on IO. */
class VoiceCallCoordinator(
    private val scope: CoroutineScope,
    private val contextProvider: () -> VoiceContextSnapshot,
    private val portFactory: () -> VoiceCallPort,
    private val responseTimeoutMs: Long = 90_000
) {
    private val mutable = MutableStateFlow(VoiceCallState())
    val state = mutable.asStateFlow()
    private val turns = CallTurnQueue()
    private var generation = 0L
    private var context: VoiceContextSnapshot? = null
    private var port: VoiceCallPort? = null
    private var session: Job? = null
    private var capture: Job? = null
    private var recorder: VoiceRecorderPort? = null
    private var cleanup: Job? = null
    private var asr: Job? = null
    private var timeout: Job? = null
    private var speaking = false

    fun requestStart(): Long? {
        val target = contextProvider()
        if (mutable.value.active || cleanup?.isActive == true || !target.foreground || !target.isChatPage ||
            target.targetId.isBlank() || target.conversationId.isBlank()) return null
        context = target
        port = portFactory()
        session = SupervisorJob(scope.coroutineContext[Job])
        mutable.value = VoiceCallState(active = true, phase = "permission")
        return ++generation
    }
    fun permission(ticket: Long, granted: Boolean) {
        if (ticket != generation || mutable.value.phase != "permission") return
        if (!valid(ticket) || !granted) { end(if (granted) null else "麦克风授权被拒绝，仍可文字聊天"); return }
        turns.start(); port!!.stopPlayback(); listen(ticket)
    }
    fun end(error: String? = null) {
        val endedGeneration = ++generation
        if (!mutable.value.active && recorder == null && session == null && cleanup?.isActive != true) {
            turns.end(); timeout?.cancel(); timeout = null; speaking = false
            mutable.value = mutable.value.copy(active = false, phase = "idle", pendingCount = 0, speaker = null, error = error)
            return
        }
        turns.end(); speaking = false; timeout = null
        port?.stopPlayback()
        val oldRecorder = recorder; recorder = null
        val oldSession = session; session = null; capture = null; asr = null
        val previousCleanup = cleanup
        // Native input must be stopped even after the ViewModel's Job is cancelled.
        // Keep its dispatcher, but give this bounded cleanup its own Job lifetime.
        cleanup = CoroutineScope(scope.coroutineContext.minusKey(Job)).launch {
            previousCleanup?.join()
            withContext(Dispatchers.IO) { oldRecorder?.cancel() }
            oldSession?.cancelAndJoin()
            if (generation == endedGeneration) mutable.value = mutable.value.copy(phase = "idle")
        }
        mutable.value = mutable.value.copy(active = false, phase = "ending", pendingCount = 0, speaker = null, error = error)
    }
    fun reply(id: String, key: String, text: String, character: String) {
        if (!valid(generation) || !turns.active) return
        execute(turns.reply(id, key, text, character), generation)
    }
    fun completed(key: String) {
        if (!valid(generation) || !turns.active) return
        execute(turns.completed(key), generation)
    }
    fun reactionFailed(key: String, message: String) {
        if (!valid(generation)) return
        mutable.value = mutable.value.copy(error = message)
        completed(key)
    }
    private fun valid(ticket: Long) = ticket == generation && mutable.value.active && context == contextProvider()
    private fun launchTask(block: suspend CoroutineScope.() -> Unit): Job =
        CoroutineScope(scope.coroutineContext + requireNotNull(session)).launch(block = block)

    private fun listen(ticket: Long) {
        if (!valid(ticket) || speaking || capture?.isActive == true || asr?.isActive == true || !turns.active) return
        mutable.value = mutable.value.copy(phase = if (turns.waiting) "waiting" else "listening", speaker = null)
        val input = try { port!!.recorder() } catch (_: Exception) { end("录音初始化失败"); return }
        recorder = input
        capture = launchTask {
            try {
                val pcm = input.capture {}
                if (!valid(ticket)) return@launchTask
                if (pcm.isEmpty()) { capture = null; recorder = null; listen(ticket); return@launchTask }
                recorder = null; capture = null
                mutable.value = mutable.value.copy(phase = if (turns.waiting) "waiting" else "transcribing")
                asr = launchTask {
                    try {
                        val text = port!!.transcribe(pcm).trim()
                        if (valid(ticket)) {
                            mutable.value = mutable.value.copy(transcript = text,
                                error = if (text.isBlank()) "没有识别到有效内容" else null)
                            execute(turns.transcript(text), ticket)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (valid(ticket)) mutable.value = mutable.value.copy(error = failure.message ?: "转写失败") }
                    finally { if (valid(ticket)) { asr = null; listen(ticket) } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (valid(ticket)) end(failure.message ?: "录音失败") }
        }
    }
    private fun execute(effects: List<CallEffect>, ticket: Long) {
        if (!valid(ticket)) return
        mutable.value = mutable.value.copy(pendingCount = turns.pendingCount)
        effects.forEach { effect -> when (effect) {
            is CallEffect.Send -> {
                timeout?.cancel()
                timeout = launchTask { delay(responseTimeoutMs); if (valid(ticket)) end("回应超时，通话已结束；请刷新历史确认，勿重复发送") }
                mutable.value = mutable.value.copy(phase = "waiting")
                launchTask {
                    try {
                        val receipt = port!!.send(effect.text)
                        if (valid(ticket)) execute(turns.accepted(receipt), ticket)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (valid(ticket)) end("未确认服务器是否接收，通话已结束；请刷新历史确认，勿重复发送") }
                }
            }
            is CallEffect.Speak -> {
                speaking = true
                mutable.value = mutable.value.copy(phase = "speaking", speaker = effect.characterId)
                val oldRecorder = recorder; recorder = null
                val oldCapture = capture; capture = null
                launchTask {
                    try {
                        withContext(Dispatchers.IO) { oldRecorder?.cancel() }
                        oldCapture?.cancelAndJoin()
                        if (valid(ticket)) port!!.speak(effect)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (valid(ticket)) mutable.value = mutable.value.copy(error = "语音播放失败，文字回复仍保留") }
                    finally {
                        if (valid(ticket)) { speaking = false; execute(turns.played(effect.id), ticket); listen(ticket) }
                    }
                }
            }
        } }
        if (!turns.awaitingReaction) { timeout?.cancel(); timeout = null }
        if (!speaking) listen(ticket)
    }
}
