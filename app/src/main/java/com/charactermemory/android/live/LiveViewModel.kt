package com.charactermemory.android.live

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.charactermemory.android.data.*
import com.charactermemory.android.audio.*
import com.google.gson.JsonObject
import java.io.Closeable
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class CallVisualSource { NONE, CAMERA, SCREEN }

internal data class CallVisualSourceState(
    val source: CallVisualSource = CallVisualSource.NONE,
    val epoch: Long = 0L
)

/** Pure ownership fence shared by call camera and screen capture callbacks. */
internal class CallVisualSourceFence {
    private val mutable = MutableStateFlow(CallVisualSourceState())
    val state: StateFlow<CallVisualSourceState> = mutable.asStateFlow()

    @Synchronized fun begin(source: CallVisualSource): Long {
        require(source != CallVisualSource.NONE)
        val epoch = mutable.value.epoch + 1
        mutable.value = CallVisualSourceState(source, epoch)
        return epoch
    }

    @Synchronized fun end(source: CallVisualSource, epoch: Long): Boolean {
        if (!accepts(source, epoch)) return false
        mutable.value = CallVisualSourceState(CallVisualSource.NONE, epoch + 1)
        return true
    }

    @Synchronized fun clear() {
        mutable.value = CallVisualSourceState(CallVisualSource.NONE, mutable.value.epoch + 1)
    }

    @Synchronized fun accepts(source: CallVisualSource, epoch: Long): Boolean =
        mutable.value.source == source && mutable.value.epoch == epoch
}

internal data class StreamFailurePlan(
    val lastEventId: String?,
    val reconnect: Boolean,
    val reconcileHistoryOnOpen: Boolean,
    val pauseCallInput: Boolean
)

/** Keeps the last SSE cursor while requiring history reconciliation after every recoverable reopen. */
internal object StreamFailurePolicy {
    fun onFailure(lastEventId: String?, sessionAlive: Boolean, callActive: Boolean, terminal: Boolean) =
        StreamFailurePlan(
            lastEventId = lastEventId,
            reconnect = sessionAlive && !terminal,
            reconcileHistoryOnOpen = sessionAlive && !terminal,
            pauseCallInput = sessionAlive && callActive
        )
}

/** Only current-call assistant rows may be offered to the playback reducer from durable history. */
internal object CallReceiptRecoveryPolicy {
    private val speakableActions = setOf("MESSAGE", "REPLY", "MINIMAL_RESPONSE", "PROACTIVE_MESSAGE", "VOICE_MESSAGE")

    fun matches(
        receiptKey: String?,
        group: Boolean,
        sourceEventId: String?,
        turnId: String?,
        role: String,
        action: String,
        characterId: String,
        targetCharacterId: String,
        groupMemberIds: Set<String>
    ): Boolean {
        if (receiptKey.isNullOrBlank() || role != "assistant" ||
            action.uppercase() !in speakableActions || characterId.isBlank()) return false
        val receiptMatches = if (group) turnId == receiptKey else sourceEventId == receiptKey
        val characterMatches = if (group) characterId in groupMemberIds else characterId == targetCharacterId
        return receiptMatches && characterMatches
    }
}

class LiveViewModel(
    context: Context,
    private val apiFactory: (ServerConfig) -> CoreApi = { CoreApi(it) },
    initialConfig: ServerConfig? = null,
    preferencesName: String = "live-core-v1",
    private val mediaApiFactory: (ServerConfig) -> MediaApi = { MediaApi(it) },
    recorderFactory: () -> VoiceRecorderPort = { AndroidVoiceRecorder() },
    private val callRecorderFactory: () -> VoiceRecorderPort = { AndroidVoiceRecorder(automaticSegment = true) }
) : ViewModel() {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val savedConfig = runCatching {
        ServerConfig.normalize(preferences.getString("core", ServerConfig.DEFAULT_CORE) ?: ServerConfig.DEFAULT_CORE,
            preferences.getString("media", "") ?: "")
    }.getOrElse { ServerConfig("", "") }
    internal val mutable = MutableStateFlow(LiveState(initialConfig ?: savedConfig,
        page = if ((initialConfig ?: savedConfig).coreUrl.isBlank()) LivePage.SETTINGS else LivePage.HOME))
    val state: StateFlow<LiveState> = mutable.asStateFlow()
    private val mutableCallStage = MutableStateFlow(CallStageMode.AVATAR)
    val callCharacterMode: StateFlow<CallStageMode> = mutableCallStage.asStateFlow()
    fun selectCallCharacterMode(mode: CallStageMode) {
        require(mode == CallStageMode.AVATAR || mode == CallStageMode.LIVE2D)
        mutableCallStage.value = mode
    }
    internal var api: CoreApi = apiFactory(mutable.value.config)
        private set
    private val fence = GenerationFence()
    internal val captureGeneration get() = fence.current
    private var screenEpoch = 0L
    internal val screenCaptureGeneration get() = screenEpoch
    private val callVisualSourceFence = CallVisualSourceFence()
    private val cameraFrames = com.charactermemory.android.camera.CallCameraFrames()
    internal val callVisualSource: StateFlow<CallVisualSourceState> = callVisualSourceFence.state

    fun beginCallCamera(): Long {
        cameraFrames.clear()
        val epoch = callVisualSourceFence.begin(CallVisualSource.CAMERA)
        com.charactermemory.android.screen.ScreenShareService.stop(appContext, "CALL_CAMERA")
        return epoch
    }

    fun endCallCamera(epoch: Long) {
        if (callVisualSourceFence.end(CallVisualSource.CAMERA, epoch)) cameraFrames.clear()
    }

    fun beginCallScreenShare(): Long {
        cameraFrames.clear()
        return callVisualSourceFence.begin(CallVisualSource.SCREEN)
    }

    internal fun updateCallCameraFrame(generation: Long, epoch: Long, callStart: Long, bytes: ByteArray) {
        if (generation != fence.current || !call.state.value.active || call.state.value.startedAtMs != callStart ||
            !callVisualSourceFence.accepts(CallVisualSource.CAMERA, epoch)) return
        cameraFrames.update(generation, epoch, callStart, android.os.SystemClock.elapsedRealtime(), bytes)
    }

    internal fun latestCallCameraFrame(): ByteArray? {
        val source = callVisualSourceFence.state.value
        if (!call.state.value.active || source.source != CallVisualSource.CAMERA) return null
        return cameraFrames.latest(fence.current, source.epoch, call.state.value.startedAtMs,
            android.os.SystemClock.elapsedRealtime())
    }

    internal fun clearCallCameraFrame(epoch: Long?) {
        if (epoch != null && callVisualSourceFence.accepts(CallVisualSource.CAMERA, epoch)) cameraFrames.clear()
    }

    fun endCallScreenShare(epoch: Long) {
        if (callVisualSourceFence.end(CallVisualSource.SCREEN, epoch))
            com.charactermemory.android.screen.ScreenShareService.stop(appContext, "CALL_SCREEN_STOP")
    }

    fun sendSharedScreen() {
        val target = state.value.target ?: return
        val snapshot = com.charactermemory.android.screen.ScreenShareStatus.state.value
        val source = callVisualSourceFence.state.value
        if (!call.state.value.active || !snapshot.active || snapshot.characterId != target.id ||
            snapshot.conversationId != target.conversationId || snapshot.coreUrl != state.value.config.coreUrl ||
            source.source != CallVisualSource.SCREEN) return
        val ticket = fence.current
        val callStart = call.state.value.startedAtMs
        val sourceEpoch = source.epoch
        operation("visual") {
            val frame = com.charactermemory.android.screen.ScreenShareService.currentFrame(appContext)
            val latest = com.charactermemory.android.screen.ScreenShareStatus.state.value
            if (fence.accepts(ticket) && call.state.value.active && call.state.value.startedAtMs == callStart &&
                callVisualSourceFence.accepts(CallVisualSource.SCREEN, sourceEpoch) &&
                com.charactermemory.android.screen.ScreenShareService.isCurrentProjection(frame.projectionEpoch) &&
                latest.active && latest.characterId == target.id && latest.conversationId == target.conversationId &&
                latest.coreUrl == state.value.config.coreUrl) {
                call.visual("请看看我当前共享的手机画面，说说你注意到了什么。",
                    com.charactermemory.android.screen.ScreenVisualPayload.frame(frame.jpeg, "DISPLAY").toString())
            }
        }
    }
    fun sendCameraFrame(generation: Long, jpeg: ByteArray, question: String, sourceEpoch: Long? = null) {
        val target = mutable.value.target ?: return
        if (generation != fence.current || mutable.value.page != LivePage.CHAT) return
        val text = question.trim()
        if (text.isEmpty() || text.length > 12_000) return
        val frame = com.charactermemory.android.screen.ScreenVisualPayload.frame(jpeg, "CAMERA")
        if (call.state.value.active) {
            if (sourceEpoch == null || !callVisualSourceFence.accepts(CallVisualSource.CAMERA, sourceEpoch)) return
            call.visual(text, frame.toString())
            return
        }
        operation("visual", write = true) { client ->
            val body = jsonObject("message" to text, "visual_frames" to listOf(frame))
            val path = if (target.group) "/v1/visual/groups/${target.id}/messages" else {
                body.addProperty("character_id", target.id); body.addProperty("conversation_id", target.conversationId)
                "/v1/visual/direct/messages"
            }
            val result = client.post(path, body)
            check(result.flag("accepted")) { "Core 未返回摄像帧接收凭据" }
            update { it.copy(notice = "摄像帧已发送，等待角色回复。") }
            loadHistory()
        }
    }
    private var session = SupervisorJob(viewModelScope.coroutineContext[Job])
    private var stream: Closeable? = null
    private var reconnect: Job? = null
    private var spaceNotificationPoll: Job? = null
    private var lastEventId: String? = null
    private var reconnectAttempt = 0
    private var streamOpenedAt = 0L
    private var active = true
    val voice = VoiceCoordinator(
        scope = viewModelScope, dispatcher = Dispatchers.IO, mainDispatcher = Dispatchers.Main.immediate,
        contextProvider = { voiceContext().let { it.copy(isChatPage = it.isChatPage && call.state.value.phase == "idle") } },
        recorderFactory = recorderFactory,
        transcribePcm = { error("Missing bound Media client") },
        transcribeFactory = {
            val client = mediaApiFactory(state.value.config)
            val transcribe: suspend (ByteArray) -> String = { pcm -> client.transcribe(Pcm16Wav.encode(pcm)).text("text") }
            transcribe
        },
        stopPlayback = { LiveAudioPlayback.stopAll() }
    )
    val call = VoiceCallCoordinator(viewModelScope, { voiceContext() }, { createCallPort() })
    private val sessionAlive get() = active || CallSessionService.owns(call)
    fun grantCallPermission(ticket: Long, granted: Boolean) {
        val accepted = call.permission(ticket, granted)
        if (accepted) runCatching {
            CallSessionService.begin(appContext, call, state.value.target?.name ?: "人物")
        }.onFailure { call.end("无法保持后台通话：${it.message}") }
    }
    fun requestCall(): Long? {
        if (voice.state.value.phase !in setOf(VoiceCoordinatorPhase.IDLE, VoiceCoordinatorPhase.ERROR) ||
            state.value.streamStatus != "已连接" || "send" in state.value.busy) return null
        LiveAudioPlayback.stopAll()
        LiveAudioPlayback.setCallSpeakerEnabled(true)
        selectCallCharacterMode(CallStageMode.AVATAR)
        return call.requestStart()
    }
    private fun createCallPort(): VoiceCallPort {
        val target = requireNotNull(state.value.target)
        val client = api
        val media = mediaApiFactory(state.value.config)
        return object : VoiceCallPort {
            override fun recorder() = callRecorderFactory()
            override suspend fun transcribe(pcm: ByteArray) = media.transcribe(Pcm16Wav.encode(pcm), source = "call").text("text")
            override suspend fun send(text: String): String {
                // Camera input accompanies actual speech; Core has no periodic CAMERA observation API.
                latestCallCameraFrame()?.let { jpeg ->
                    return sendVisual(text, com.charactermemory.android.screen.ScreenVisualPayload.frame(jpeg, "CAMERA").toString())
                }
                val body = jsonObject("message" to text)
                if (!target.group) { body.addProperty("character_id", target.id); body.addProperty("conversation_id", target.conversationId) }
                val receipt = client.post(if (target.group) "/v1/groups/${pathId(target.id)}/messages" else "/v1/chat/messages", body)
                currentCoroutineContext().ensureActive()
                val message = receipt.objOrNull("message")
                check(receipt.flag("accepted") && message != null) { "服务器未返回接收凭据" }
                update { it.copy(messages = ConversationProjection.merge(it.messages, listOf(message)),
                    groupTurnId = if (target.group) receipt.text("turn_id") else it.groupTurnId) }
                return if (target.group) receipt.text("turn_id") else message.text("id")
            }
            override suspend fun speak(item: CallEffect.Speak) {
                val audio = client.synthesizeSpeech(item.text, voice = item.characterId)
                val file = java.io.File(appContext.cacheDir, "call-${UUID.randomUUID()}.${if (audio.mimeType in setOf("audio/mpeg", "audio/mp3")) "mp3" else "wav"}")
                try {
                    withContext(Dispatchers.IO) { file.writeBytes(audio.bytes) }
                    withTimeout(60000) { LiveAudioPlayback.playCall("call-${item.id}", file.absolutePath) }
                } finally { file.delete() }
            }
            override suspend fun sendVisual(text: String, frame: String): String {
                val body = jsonObject("message" to text,
                    "visual_frames" to listOf(com.google.gson.JsonParser.parseString(frame).asJsonObject))
                if (!target.group) { body.addProperty("character_id", target.id); body.addProperty("conversation_id", target.conversationId) }
                val result = client.post(if (target.group) "/v1/visual/groups/${pathId(target.id)}/messages" else "/v1/visual/direct/messages", body)
                currentCoroutineContext().ensureActive()
                check(result.flag("accepted")) { "Core 未返回视觉接收凭据" }
                loadHistory()
                return (if (target.group) result.text("turn_id") else result.text("event_id"))
                    .also { check(it.isNotBlank()) { "Core 未返回视觉关联凭据" } }
            }
            override fun stopPlayback() = LiveAudioPlayback.stopAll()
        }
    }
    private val appContext = context.applicationContext
    private fun voiceContext(): VoiceContextSnapshot {
        val current = state.value
        val target = current.target
        return VoiceContextSnapshot(fence.current, if (target?.group == true) VoiceTargetScope.GROUP else VoiceTargetScope.DIRECT,
            target?.id.orEmpty(), target?.conversationId.orEmpty(), current.page == LivePage.CHAT || CallSessionService.owns(call), sessionAlive)
    }
    fun useVoiceDraft(ticket: VoiceTaskTicket) {
        when (val result = voice.useDraft(ticket, state.value.composeText)) {
            is DraftAppendResult.Appended -> editText(result.text)
            is DraftAppendResult.TooLong -> update { it.copy(error = "加入后超过消息长度上限；请先缩短文字。转写草稿已保留。") }
            DraftAppendResult.Unavailable -> Unit
        }
    }
    private var activeWrites = 0
    private var historyLoading = false
    private var historyPaged = false
    private val conversationCache = ConversationSnapshotCache()
    private val refreshWindow = RefreshWindow(60_000L, { android.os.SystemClock.elapsedRealtime() })
    private var usageReturnPage = LivePage.SETTINGS
    private var pendingMemberProgress: List<JsonObject> = emptyList()

    init {
        LiveAudioPlayback.initialize(context)
        viewModelScope.launch {
            voice.state.collect { value ->
                LiveAudioPlayback.blocked = call.state.value.phase != "idle" || value.phase !in setOf(VoiceCoordinatorPhase.IDLE, VoiceCoordinatorPhase.ERROR, VoiceCoordinatorPhase.DRAFT)
            }
        }
        viewModelScope.launch {
            var previouslyActive = false
            var previousReceipt: String? = null
            call.state.collect { value ->
                LiveAudioPlayback.blocked = value.phase != "idle" || voice.state.value.phase !in setOf(VoiceCoordinatorPhase.IDLE, VoiceCoordinatorPhase.ERROR, VoiceCoordinatorPhase.DRAFT)
                if (previouslyActive && !value.active) {
                    cameraFrames.clear()
                    callVisualSourceFence.clear()
                    if (com.charactermemory.android.screen.ScreenShareStatus.state.value.callOwned)
                        com.charactermemory.android.screen.ScreenShareService.stop(appContext, "CALL_HANGUP")
                }
                if (value.active && value.receiptKey != null && value.receiptKey != previousReceipt) {
                    val receipt = value.receiptKey
                    viewModelScope.launch {
                        // If reconnect reconciliation already has a history read in flight,
                        // run another after it so an acceptance racing the open cannot be missed.
                        while (isActive && historyLoading && call.state.value.receiptKey == receipt) delay(40)
                        if (call.state.value.active && call.state.value.receiptKey == receipt)
                            loadHistory(allowBackgroundCall = CallSessionService.owns(call))
                    }
                }
                previouslyActive = value.active
                previousReceipt = value.receiptKey
            }
        }
        viewModelScope.launch {
            com.charactermemory.android.screen.ScreenShareStatus.state.collect { capture ->
                val target = state.value.target
                if (capture.active && capture.coreUrl == state.value.config.coreUrl && capture.characterId == target?.id &&
                    capture.conversationId == target?.conversationId && capture.lastEventId.isNotBlank()) call.externalAccepted(capture.lastEventId)
            }
        }
        // Persist normalized legacy routing, so upgrades do not keep sending Media requests to Core.
        val resolvedConfig = mutable.value.config
        if (resolvedConfig.coreUrl.isNotBlank()) preferences.edit().putString("core", resolvedConfig.coreUrl).putString("media", resolvedConfig.mediaUrl).apply()
        refresh(); loadStickers(); checkHealth(); loadSpaceNotifications(); startSpaceNotificationPolling()
    }

    internal fun update(transform: (LiveState) -> LiveState) = mutable.update(transform)
    internal fun pathId(id: String): String = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
    fun assetUrl(path: String): String = runCatching { api.assetUrl(path) }.getOrDefault("")
    fun editText(value: String) = update { it.copy(composeText = value.take(12000)) }
    fun editCharacterPrompt(value: String) = update { it.copy(characterPrompt = value.take(4000)) }
    fun editEnsemblePrompt(value: String) = update { it.copy(ensemblePrompt = value.take(2000)) }
    fun editImageInstruction(value: String) = update { it.copy(imageInstruction = value.take(1600)) }
    fun imageOptions(character: String, purpose: String, avatar: Boolean) = update {
        it.copy(imageCharacterId = character, imagePurpose = purpose, imageUseAvatar = avatar)
    }

    private fun cancelSession(reason: String? = null, stopCapture: Boolean = true) {
        cacheConversation()
        refreshWindow.cancelInFlight()
        if (stopCapture) { screenEpoch++; com.charactermemory.android.screen.ScreenShareService.stop(appContext, reason ?: "TARGET_CHANGED") }
        callVisualSourceFence.clear()
        call.end()
        voice.invalidate()
        LiveAudioPlayback.stopAll()
        fence.advance()
        stream?.close(); stream = null
        reconnect?.cancel(); reconnect = null
        lastEventId = null
        session.cancel()
        session = SupervisorJob(viewModelScope.coroutineContext[Job])
        historyLoading = false
        pendingMemberProgress = emptyList()
        val ambiguous = activeWrites > 0
        activeWrites = 0
        update { it.copy(busy = emptySet(), streamStatus = "未连接", reaction = "idle",
            notice = if (ambiguous) "操作已取消，服务器可能已经接收；请刷新记录确认，勿直接重复提交。" else reason ?: it.notice) }
    }

    /** A keyed operation disables repeat taps immediately and never retries a write. */
    internal fun operation(key: String, write: Boolean = false, allowBackgroundCall: Boolean = false,
        block: suspend (CoreApi) -> Unit) {
        if ((!active && !(allowBackgroundCall && CallSessionService.owns(call))) || key in mutable.value.busy) return
        if (mutable.value.config.coreUrl.isBlank()) {
            update { it.copy(error = "请先在设置中填写 Core 的 HTTPS 地址。") }; return
        }
        val generation = fence.current
        val client = api
        update { it.copy(busy = it.busy + key, error = null) }
        if (write) activeWrites++
        CoroutineScope(viewModelScope.coroutineContext + session).launch {
            try {
                block(client)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (fence.accepts(generation)) {
                    val detail = (error as? ApiFailure)?.detail
                    val soft = detail?.takeIf { it.isJsonObject }?.asJsonObject
                    val message = when {
                        soft != null -> soft.text("message", error.message ?: "请求失败")
                        detail?.isJsonPrimitive == true -> detail.asString
                        else -> error.message ?: "请求失败"
                    }
                    update { it.copy(error = message,
                        capacityConfirmation = if ((error as? ApiFailure)?.status == 409 && soft?.flag("confirmation_required") == true) key else null,
                        notice = if (write && error !is ApiFailure) "未确认服务器是否已接收。请先刷新持久化记录，勿直接重复提交。" else it.notice) }
                }
            } finally {
                if (fence.accepts(generation)) {
                    if (write) activeWrites--
                    update { it.copy(busy = it.busy - key) }
                }
            }
        }
    }

    fun saveConfig(core: String, media: String) {
        val config = try { ServerConfig.normalize(core, media) } catch (error: Exception) {
            update { it.copy(error = error.message ?: "服务器地址无效") }; return
        }
        cancelSession("SERVER_CHANGED")
        clearLoadingCaches()
        preferences.edit().putString("core", config.coreUrl).putString("media", config.mediaUrl).apply()
        api = apiFactory(config)
        lastEventId = null
        update { LiveState(config, page = LivePage.SETTINGS, notice = "配置已保存在此手机。") }
        refresh(); loadStickers(); checkHealth(); loadSpaceNotifications(); startSpaceNotificationPolling()
    }
    fun resetConfig() {
        spaceNotificationPoll?.cancel(); spaceNotificationPoll = null
        cancelSession(); preferences.edit().remove("core").remove("media").apply()
        clearLoadingCaches()
        val config = ServerConfig("", "")
        api = apiFactory(config); lastEventId = null
        update { LiveState(config, page = LivePage.SETTINGS, notice = "服务器配置已清除。") }
    }

    fun checkHealth() {
        if (mutable.value.config.coreUrl.isBlank()) return
        listOf(false, true).forEach { media ->
            operation(if (media) "media-health" else "core-health") { client ->
                update { if (media) it.copy(mediaHealth = "检查中") else it.copy(coreHealth = "检查中") }
                val result = try {
                    val health = client.get("/health", media = media)
                    if (media) ServiceHealth.media(health, client.get("/openapi.json", media = true)) else "可连接"
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { "不可连接：${error.message ?: "请求失败"}" }
                currentCoroutineContext().ensureActive()
                update { if (media) it.copy(mediaHealth = result) else it.copy(coreHealth = result) }
            }
        }
    }

    private fun clearLoadingCaches() {
        conversationCache.clear()
        refreshWindow.clear()
    }

    private fun conversationKey(target: ChatTarget): ConversationCacheKey = mutable.value.config.let {
        ConversationCacheKey(it.coreUrl, it.mediaUrl, target.group, target.id, target.conversationId)
    }

    private fun cacheConversation() {
        val current = mutable.value
        val target = current.target ?: return
        conversationCache.put(conversationKey(target), current.messages, current.historyCursor, historyPaged)
    }

    private fun cachedRead(key: String, force: Boolean = false, block: suspend (CoreApi) -> Unit) {
        if (!active || mutable.value.config.coreUrl.isBlank() || key in mutable.value.busy) return
        val ticket = refreshWindow.begin(key, force) ?: return
        val generation = fence.current
        operation(key) { client ->
            var success = false
            try {
                block(client)
                currentCoroutineContext().ensureActive()
                success = fence.accepts(generation)
            } finally {
                refreshWindow.finish(ticket, success)
            }
        }
    }

    /** Explicit refresh remains forced; routine navigation reuses successful short-lived reads. */
    fun refresh() = refresh(force = true)
    fun refresh(force: Boolean) {
        if (mutable.value.config.coreUrl.isBlank()) return
        // A navigation cancellation may have stopped avatars after the roster succeeded.
        // Retry those unfinished reads even while the roster itself is still fresh.
        if (!force) refreshAvatars(mutable.value.characters, force = false)
        cachedRead("roster", force) { client ->
            val characters = client.get("/v1/characters").items("characters")
            currentCoroutineContext().ensureActive()
            update { it.copy(characters = characters) }
            refreshAvatars(characters, force)
        }
        cachedRead("summaries", force) { client ->
            val rows = client.get("/v1/characters/summaries").items("characters")
            currentCoroutineContext().ensureActive(); update { it.copy(summaries = rows.associateBy { row -> row.text("id") }) }
        }
        cachedRead("groups", force) { client ->
            val groups = client.get("/v1/groups").items("groups")
            currentCoroutineContext().ensureActive(); update { it.copy(groups = groups) }
        }
    }

    private fun refreshAvatars(characters: List<JsonObject>, force: Boolean) {
        characters.forEach { profile ->
            val id = profile.text("id")
            cachedRead("avatar-$id", force) { client ->
                val avatar = client.get("/v1/characters/${pathId(id)}/avatar").text("avatar_url")
                currentCoroutineContext().ensureActive()
                update { it.copy(avatars = it.avatars + (id to avatar)) }
            }
        }
    }

    private fun directId(characterId: String): String {
        val key = LiveRules.directKey(mutable.value.config.coreUrl, characterId)
        return preferences.getString(key, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(key, it).apply()
        }
    }

    fun openCharacter(id: String) {
        val character = mutable.value.characters.firstOrNull { it.text("id") == id } ?: return
        select(ChatTarget(id, character.text("name", id), false, directId(id)))
    }
    fun openGroup(id: String) {
        val group = mutable.value.groups.firstOrNull { it.text("id") == id } ?: return
        val members = group.get("member_ids")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { value -> runCatching { value.asString }.getOrNull() } ?: emptyList()
        select(ChatTarget(id, group.text("name", id), true, id, members))
    }
    private fun select(target: ChatTarget) {
        cancelSession()
        val cached = conversationCache.get(conversationKey(target))
        lastEventId = null; reconnectAttempt = 0; historyPaged = cached?.historyPaged ?: false
        update { it.copy(page = LivePage.CHAT, target = target, messages = cached?.messages ?: emptyList(), composeText = "",
            historyCursor = cached?.historyCursor, memberProgress = emptyList(), groupTurnId = null, reactionError = null, imageDraft = null,
            imagePrompt = "", imageInstruction = "", error = null, persona = null,
            imageCharacterId = if (target.group) target.memberIds.firstOrNull().orEmpty() else target.id) }
        loadHistory(); connectStream()
    }
    fun overrideConversationId(id: String) {
        val target = mutable.value.target?.takeUnless { it.group } ?: return
        if (id.trim().isBlank() || id.length > 200) { update { it.copy(error = "会话 ID 需要 1–200 个字符") }; return }
        preferences.edit().putString(LiveRules.directKey(mutable.value.config.coreUrl, target.id), id.trim()).apply()
        select(target.copy(conversationId = id.trim()))
    }

    fun show(page: LivePage) {
        val old = mutable.value.page
        if (old == page) return
        val explicitCall = CallSessionService.owns(call)
        if (!explicitCall) { call.end(); voice.invalidate(); LiveAudioPlayback.stopAll() }
        if (page == LivePage.USAGE) usageReturnPage = old
        val chatPages = setOf(LivePage.CHAT, LivePage.IMAGE, LivePage.DETAILS)
        if (!explicitCall && old in chatPages && page !in chatPages) cancelSession(stopCapture = false)
        // Prevent late draft writes from repopulating a feature after navigation.
        if (!explicitCall && old !in chatPages && old !in setOf(LivePage.HOME, LivePage.SETTINGS) && old != page) cancelSession(stopCapture = false)
        update { it.copy(page = page, error = null, capacityConfirmation = null) }
        when (page) {
            LivePage.HOME -> refresh(force = false)
            LivePage.SPACE -> { loadSpace(); loadSpaceNotifications(); loadSpaceMentionCharacters() }
            LivePage.ENSEMBLE -> resumeEnsemble()
            LivePage.IMAGE -> loadStickers()
            LivePage.USAGE -> loadLlmUsage()
            LivePage.CHAT -> { loadHistory(); connectStream() }
            else -> Unit
        }
    }
    fun back() = show(when (mutable.value.page) {
        LivePage.IMAGE, LivePage.DETAILS -> LivePage.CHAT
        LivePage.USAGE -> usageReturnPage
        else -> LivePage.HOME
    })

    fun loadLlmUsage() = operation("llm-usage") { client ->
        val usage = LlmUsageRepository(client).load()
        currentCoroutineContext().ensureActive()
        update { it.copy(llmUsage = usage) }
    }

    fun loadHistory(older: Boolean = false, allowBackgroundCall: Boolean = false) {
        val backgroundCall = allowBackgroundCall && CallSessionService.owns(call)
        if (historyLoading || (!active && !backgroundCall) || mutable.value.config.coreUrl.isBlank()) return
        val target = mutable.value.target ?: return
        val generation = fence.current
        val before = if (older) mutable.value.historyCursor ?: return else null
        historyLoading = true
        operation("history", allowBackgroundCall = backgroundCall) { client ->
            try {
                val query = mutableMapOf("limit" to "50")
                before?.let { query["before_id"] = it }
                if (!target.group) query["character_id"] = target.id
                val atRequest = state.value.messages.map { it.deepCopy() }
                val page = client.get(if (target.group) "/v1/groups/${pathId(target.id)}/history" else "/v1/chat/history-page", query)
                currentCoroutineContext().ensureActive()
                if (!fence.accepts(generation) || (backgroundCall && !CallSessionService.owns(call))) return@operation
                val current = state.value
                val refreshed = ConversationHistoryRefresh.reconcile(
                    ConversationSnapshot(current.messages, current.historyCursor, historyPaged),
                    page.items("messages"), atRequest, LiveRules.nextCursor(page), older)
                update { it.copy(messages = refreshed.messages,
                    historyCursor = refreshed.historyCursor,
                    groupTurnId = if (target.group && it.groupTurnId == null && "send" !in it.busy)
                        page.items("messages").lastOrNull { message -> message.text("role") == "user" }?.text("turn_id")?.takeIf { id -> id.isNotBlank() }
                        else it.groupTurnId) }
                historyPaged = refreshed.historyPaged
                cacheConversation()
                if (!older && call.state.value.active) {
                    val receipt = call.state.value.receiptKey
                    if (!receipt.isNullOrBlank()) {
                        val allowedMembers = target.memberIds.toSet()
                        page.items("messages").forEach { message ->
                            val characterId = message.text("character_id", message.text("actor_id"))
                            if (CallReceiptRecoveryPolicy.matches(
                                    receiptKey = receipt,
                                    group = target.group,
                                    sourceEventId = message.text("source_event_id"),
                                    turnId = message.text("turn_id"),
                                    role = message.text("role"),
                                    action = message.text("action").uppercase(),
                                    characterId = characterId,
                                    targetCharacterId = target.id,
                                    groupMemberIds = allowedMembers
                                )) {
                                call.reconcileHistoryReply(receipt, message.text("id"),
                                    message.text("content"), characterId)
                            }
                        }
                    }
                }
            } finally { if (fence.accepts(generation)) historyLoading = false }
        }
    }

    private fun connectStream() {
        if (!sessionAlive || (mutable.value.page !in setOf(LivePage.CHAT, LivePage.IMAGE, LivePage.DETAILS) && !CallSessionService.owns(call))) return
        val target = mutable.value.target ?: return
        stream?.close()
        streamOpenedAt = 0L
        val generation = fence.current
        update { it.copy(streamStatus = "连接中") }
        stream = api.stream(if (target.group) "group" else "direct", if (target.group) null else target.id,
            target.conversationId, lastEventId,
            onOpen = { viewModelScope.launch {
                if (!fence.accepts(generation) || !sessionAlive) return@launch
                streamOpenedAt = android.os.SystemClock.elapsedRealtime()
                update { it.copy(streamStatus = "已连接") }
                loadHistory(allowBackgroundCall = true)
                call.setTransportAvailable(true)
            } },
            onEvent = { type, id, payload -> viewModelScope.launch {
                if (!fence.accepts(generation) || !sessionAlive) return@launch
                if (streamOpenedAt > 0L && android.os.SystemClock.elapsedRealtime() - streamOpenedAt >= 15000L) reconnectAttempt = 0
                if (!id.isNullOrBlank()) lastEventId = id
                ConversationProjection.message(type, payload)?.let { message ->
                    update { it.copy(messages = ConversationProjection.merge(it.messages, listOf(message))) }
                    if (message.text("role") == "assistant" && message.text("action").uppercase() in
                        setOf("MESSAGE", "REPLY", "MINIMAL_RESPONSE", "PROACTIVE_MESSAGE", "VOICE_MESSAGE")) {
                        call.reply(message.text("id"), if (target.group) message.text("turn_id") else message.text("source_event_id"),
                            message.text("content"), message.text("character_id", message.text("actor_id", if (target.group) "" else target.id)))
                    }
                }
                val callKey = if (target.group) payload.text("turn_id") else payload.text("watermark")
                if (type == "reaction_complete") call.completed(callKey)
                if (type == "reaction_error") call.reactionFailed(callKey, payload.text("message", "人物回复失败"))
                if (type == "group_member_complete" && "send" in mutable.value.busy && mutable.value.target?.group == true) {
                    pendingMemberProgress = (pendingMemberProgress + payload).takeLast(12)
                }
                update { current -> current.copy(reaction = ConversationProjection.reaction(current.reaction, type, payload),
                    reactionError = if (type == "reaction_error") payload.text("message", "人物回复失败") else current.reactionError,
                    memberProgress = if (type == "group_member_complete")
                        LiveRules.memberProgress(current.memberProgress, current.groupTurnId, payload)
                        else current.memberProgress) }
                if (type == "reaction_error" || (type == "reaction_status" && payload.text("state") == "idle")) loadHistory()
            } },
            onFailure = { error -> viewModelScope.launch {
                if (!fence.accepts(generation) || !sessionAlive) return@launch
                if (streamOpenedAt > 0L && android.os.SystemClock.elapsedRealtime() - streamOpenedAt >= 15000L) reconnectAttempt = 0
                val terminal = error is ApiFailure && error.status in setOf(401, 403, 404)
                val plan = StreamFailurePolicy.onFailure(lastEventId, sessionAlive,
                    callActive = call.state.value.active, terminal = terminal)
                lastEventId = plan.lastEventId
                if (plan.pauseCallInput) call.setTransportAvailable(false)
                update { it.copy(streamStatus = "断线，准备重连", reaction = "idle") }
                reconnect?.cancel()
                if (plan.reconnect) {
                    reconnect = CoroutineScope(viewModelScope.coroutineContext + session).launch {
                        delay((1500L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(30000L))
                        reconnectAttempt++
                        if (fence.accepts(generation) && sessionAlive) { loadHistory(allowBackgroundCall = true); connectStream() }
                    }
                }
                if (!plan.reconnect) {
                    update { it.copy(streamStatus = "连接被拒绝", error = error.message) }
                }
            } })
    }

    fun send(stickerId: String? = null, image: JsonObject? = null) {
        if (call.state.value.active) { update { it.copy(error = "请先挂断通话再发送文字或图片") }; return }
        val snapshot = mutable.value
        val target = snapshot.target ?: return
        val text = snapshot.composeText.trim()
        if (text.isBlank() && stickerId == null && image == null) return
        if (stickerId != null && image != null) return
        operation("send", write = true) { client ->
            if (target.group) pendingMemberProgress = emptyList()
            update { it.copy(notice = "发送中，尚未确认接收",
                memberProgress = if (target.group) emptyList() else it.memberProgress,
                groupTurnId = if (target.group) null else it.groupTurnId) }
            val body = jsonObject("message" to text, "sticker_id" to stickerId, "image" to image)
            if (!target.group) { body.addProperty("character_id", target.id); body.addProperty("conversation_id", target.conversationId) }
            val accepted = client.post(if (target.group) "/v1/groups/${pathId(target.id)}/messages" else "/v1/chat/messages", body)
            currentCoroutineContext().ensureActive()
            if (!accepted.flag("accepted") || accepted.objOrNull("message") == null) error("服务器未返回接收凭据，请刷新历史确认")
            val turnId = if (target.group) accepted.text("turn_id").takeIf { it.isNotBlank() } else null
            val progress = pendingMemberProgress.fold(emptyList<JsonObject>()) { rows, item -> LiveRules.memberProgress(rows, turnId, item) }
            pendingMemberProgress = emptyList()
            update { it.copy(messages = ConversationProjection.merge(it.messages, listOf(accepted.objOrNull("message")!!)),
                composeText = if (it.composeText == snapshot.composeText) "" else it.composeText,
                notice = "服务器已接收，等待人物决定是否回复", imageDraft = if (image != null) null else it.imageDraft,
                page = if (image != null) LivePage.CHAT else it.page, reactionError = null,
                groupTurnId = if (target.group) turnId else it.groupTurnId,
                memberProgress = if (target.group) progress else it.memberProgress) }
            loadHistory()
        }
    }
    fun loadStickers() = loadStickers(force = false)
    fun loadStickers(force: Boolean) = cachedRead("stickers", force) { client ->
        val stickers = client.get("/v1/stickers").items("stickers")
        currentCoroutineContext().ensureActive(); update { it.copy(stickers = stickers) }
    }
    fun loadPersona() {
        val target = mutable.value.target?.takeUnless { it.group } ?: return
        show(LivePage.DETAILS)
        operation("persona") { client ->
            val persona = client.get("/v1/characters/${pathId(target.id)}/persona")
            currentCoroutineContext().ensureActive(); update { it.copy(persona = persona) }
        }
    }

    fun activate() {
        if (active) return
        active = true
        refresh(force = false); loadStickers(); checkHealth()
        loadSpaceNotifications(); startSpaceNotificationPolling()
        if (mutable.value.page in setOf(LivePage.CHAT, LivePage.IMAGE, LivePage.DETAILS)) { loadHistory(); connectStream() }
        if (mutable.value.page == LivePage.SPACE) { loadSpace(); loadSpaceMentionCharacters() }
        if (mutable.value.page == LivePage.ENSEMBLE) resumeEnsemble()
    }
    fun deactivate() {
        if (!active) return
        active = false; spaceNotificationPoll?.cancel(); spaceNotificationPoll = null
        if (CallSessionService.owns(call)) voice.invalidate()
        else cancelSession(stopCapture = false)
    }
    private fun startSpaceNotificationPolling() {
        if (!active || mutable.value.config.coreUrl.isBlank() || spaceNotificationPoll?.isActive == true) return
        spaceNotificationPoll = viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                if (!active || mutable.value.config.coreUrl.isBlank()) break
                loadSpaceNotifications()
            }
        }
    }
    override fun onCleared() {
        active = false; spaceNotificationPoll?.cancel(); spaceNotificationPoll = null
        cancelSession(); clearLoadingCaches(); session.cancel(); super.onCleared()
    }

    companion object {
        fun factory(context: Context, initialConfig: ServerConfig? = null): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LiveViewModel(context, initialConfig = initialConfig) as T
            }
        }
    }
}


