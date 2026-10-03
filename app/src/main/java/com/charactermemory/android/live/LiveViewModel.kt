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

class LiveViewModel(
    context: Context,
    private val apiFactory: (ServerConfig) -> CoreApi = { CoreApi(it) },
    initialConfig: ServerConfig? = null,
    preferencesName: String = "live-core-v1",
    private val mediaApiFactory: (ServerConfig) -> MediaApi = { MediaApi(it) },
    recorderFactory: () -> VoiceRecorderPort = { AndroidVoiceRecorder() }
) : ViewModel() {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val savedConfig = runCatching {
        ServerConfig.normalize(preferences.getString("core", ServerConfig.DEFAULT_CORE) ?: ServerConfig.DEFAULT_CORE,
            preferences.getString("media", "") ?: "")
    }.getOrElse { ServerConfig("", "") }
    internal val mutable = MutableStateFlow(LiveState(initialConfig ?: savedConfig,
        page = if ((initialConfig ?: savedConfig).coreUrl.isBlank()) LivePage.SETTINGS else LivePage.HOME))
    val state: StateFlow<LiveState> = mutable.asStateFlow()
    internal var api: CoreApi = apiFactory(mutable.value.config)
        private set
    private val fence = GenerationFence()
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
        contextProvider = { voiceContext() },
        recorderFactory = recorderFactory,
        transcribePcm = { error("Missing bound Media client") },
        transcribeFactory = {
            val client = mediaApiFactory(state.value.config)
            val transcribe: suspend (ByteArray) -> String = { pcm -> client.transcribe(Pcm16Wav.encode(pcm)).text("text") }
            transcribe
        },
        stopPlayback = { LiveAudioPlayback.stopAll() }
    )
    private fun voiceContext(): VoiceContextSnapshot {
        val current = state.value
        val target = current.target
        return VoiceContextSnapshot(fence.current, if (target?.group == true) VoiceTargetScope.GROUP else VoiceTargetScope.DIRECT,
            target?.id.orEmpty(), target?.conversationId.orEmpty(), current.page == LivePage.CHAT, active)
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
    private var usageReturnPage = LivePage.SETTINGS
    private var pendingMemberProgress: List<JsonObject> = emptyList()

    init {
        LiveAudioPlayback.initialize(context)
        viewModelScope.launch {
            voice.state.collect { value ->
                LiveAudioPlayback.blocked = value.phase !in setOf(VoiceCoordinatorPhase.IDLE, VoiceCoordinatorPhase.ERROR, VoiceCoordinatorPhase.DRAFT)
            }
        }
        if (initialConfig != null) preferences.edit().putString("core", initialConfig.coreUrl).putString("media", initialConfig.mediaUrl).apply()
        refresh(); checkHealth(); loadSpaceNotifications(); startSpaceNotificationPolling()
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

    private fun cancelSession(reason: String? = null) {
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
    internal fun operation(key: String, write: Boolean = false, block: suspend (CoreApi) -> Unit) {
        if (!active || key in mutable.value.busy) return
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
        cancelSession()
        preferences.edit().putString("core", config.coreUrl).putString("media", config.mediaUrl).apply()
        api = apiFactory(config)
        lastEventId = null
        update { LiveState(config, page = LivePage.SETTINGS, notice = "配置已保存在此手机。") }
        refresh(); checkHealth(); loadSpaceNotifications(); startSpaceNotificationPolling()
    }
    fun resetConfig() {
        spaceNotificationPoll?.cancel(); spaceNotificationPoll = null
        cancelSession(); preferences.edit().remove("core").remove("media").apply()
        val config = ServerConfig("", "")
        api = apiFactory(config); lastEventId = null
        update { LiveState(config, page = LivePage.SETTINGS, notice = "服务器配置已清除。") }
    }

    fun checkHealth() {
        if (mutable.value.config.coreUrl.isBlank()) return
        listOf(false, true).forEach { media ->
            operation(if (media) "media-health" else "core-health") { client ->
                update { if (media) it.copy(mediaHealth = "检查中") else it.copy(coreHealth = "检查中") }
                val result = try { client.get("/health", media = media); "可连接" }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { "不可连接：${error.message ?: "请求失败"}" }
                currentCoroutineContext().ensureActive()
                update { if (media) it.copy(mediaHealth = result) else it.copy(coreHealth = result) }
            }
        }
    }

    fun refresh() {
        if (mutable.value.config.coreUrl.isBlank()) return
        operation("roster") { client ->
            val characters = client.get("/v1/characters").items("characters")
            currentCoroutineContext().ensureActive()
            update { it.copy(characters = characters) }
            characters.forEach { profile ->
                val id = profile.text("id")
                operation("avatar-$id") { avatarClient ->
                    val avatar = avatarClient.get("/v1/characters/${pathId(id)}/avatar").text("avatar_url")
                    currentCoroutineContext().ensureActive(); update { it.copy(avatars = it.avatars + (id to avatar)) }
                }
            }
        }
        operation("summaries") { client ->
            val rows = client.get("/v1/characters/summaries").items("characters")
            currentCoroutineContext().ensureActive(); update { it.copy(summaries = rows.associateBy { row -> row.text("id") }) }
        }
        operation("groups") { client ->
            val groups = client.get("/v1/groups").items("groups")
            currentCoroutineContext().ensureActive(); update { it.copy(groups = groups) }
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
        lastEventId = null; reconnectAttempt = 0; historyPaged = false
        update { it.copy(page = LivePage.CHAT, target = target, messages = emptyList(), composeText = "",
            historyCursor = null, memberProgress = emptyList(), groupTurnId = null, reactionError = null, imageDraft = null,
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
        voice.invalidate()
        LiveAudioPlayback.stopAll()
        if (page == LivePage.USAGE) usageReturnPage = old
        val chatPages = setOf(LivePage.CHAT, LivePage.IMAGE, LivePage.DETAILS)
        if (old in chatPages && page !in chatPages) cancelSession()
        // Prevent late draft writes from repopulating a feature after navigation.
        if (old !in chatPages && old !in setOf(LivePage.HOME, LivePage.SETTINGS) && old != page) cancelSession()
        update { it.copy(page = page, error = null, capacityConfirmation = null) }
        when (page) {
            LivePage.HOME -> refresh()
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

    fun loadHistory(older: Boolean = false) {
        if (historyLoading || !active) return
        val target = mutable.value.target ?: return
        val before = if (older) mutable.value.historyCursor ?: return else null
        historyLoading = true
        operation("history") { client ->
            try {
                val query = mutableMapOf("limit" to "50")
                before?.let { query["before_id"] = it }
                if (!target.group) query["character_id"] = target.id
                val page = client.get(if (target.group) "/v1/groups/${pathId(target.id)}/history" else "/v1/chat/history-page", query)
                currentCoroutineContext().ensureActive()
                update { it.copy(messages = ConversationProjection.merge(it.messages, page.items("messages")),
                    historyCursor = if (older || !historyPaged) LiveRules.nextCursor(page) else it.historyCursor,
                    groupTurnId = if (target.group && it.groupTurnId == null && "send" !in it.busy)
                        page.items("messages").lastOrNull { message -> message.text("role") == "user" }?.text("turn_id")?.takeIf { id -> id.isNotBlank() }
                        else it.groupTurnId) }
                if (older) historyPaged = true
            } finally { if (currentCoroutineContext().isActive) historyLoading = false }
        }
    }

    private fun connectStream() {
        if (!active || mutable.value.page !in setOf(LivePage.CHAT, LivePage.IMAGE, LivePage.DETAILS)) return
        val target = mutable.value.target ?: return
        stream?.close()
        streamOpenedAt = 0L
        val generation = fence.current
        update { it.copy(streamStatus = "连接中") }
        stream = api.stream(if (target.group) "group" else "direct", if (target.group) null else target.id,
            target.conversationId, lastEventId,
            onOpen = { viewModelScope.launch {
                if (!fence.accepts(generation) || !active) return@launch
                streamOpenedAt = android.os.SystemClock.elapsedRealtime()
                update { it.copy(streamStatus = "已连接") }; loadHistory()
            } },
            onEvent = { type, id, payload -> viewModelScope.launch {
                if (!fence.accepts(generation) || !active) return@launch
                if (streamOpenedAt > 0L && android.os.SystemClock.elapsedRealtime() - streamOpenedAt >= 15000L) reconnectAttempt = 0
                if (!id.isNullOrBlank()) lastEventId = id
                ConversationProjection.message(type, payload)?.let { message ->
                    update { it.copy(messages = ConversationProjection.merge(it.messages, listOf(message))) }
                }
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
                if (!fence.accepts(generation) || !active) return@launch
                if (streamOpenedAt > 0L && android.os.SystemClock.elapsedRealtime() - streamOpenedAt >= 15000L) reconnectAttempt = 0
                // Core/channel leases may reset ephemeral sequence IDs. A fresh status
                // snapshot plus history after opening prevents a stale cursor blackout.
                lastEventId = null
                update { it.copy(streamStatus = "断线，准备重连", reaction = "idle") }
                reconnect?.cancel()
                reconnect = CoroutineScope(viewModelScope.coroutineContext + session).launch {
                    delay((1500L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(30000L))
                    reconnectAttempt++
                    if (fence.accepts(generation) && active) { loadHistory(); connectStream() }
                }
                if (error is ApiFailure && error.status in setOf(401, 403, 404)) {
                    reconnect?.cancel(); update { it.copy(streamStatus = "连接被拒绝", error = error.message) }
                }
            } })
    }

    fun send(stickerId: String? = null, image: JsonObject? = null) {
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
    fun loadStickers() = operation("stickers") { client ->
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
        refresh(); checkHealth()
        loadSpaceNotifications(); startSpaceNotificationPolling()
        if (mutable.value.page in setOf(LivePage.CHAT, LivePage.IMAGE, LivePage.DETAILS)) { loadHistory(); connectStream() }
        if (mutable.value.page == LivePage.SPACE) { loadSpace(); loadSpaceMentionCharacters() }
        if (mutable.value.page == LivePage.ENSEMBLE) resumeEnsemble()
    }
    fun deactivate() { if (active) { active = false; spaceNotificationPoll?.cancel(); spaceNotificationPoll = null; cancelSession() } }
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
        cancelSession(); session.cancel(); super.onCleared()
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


