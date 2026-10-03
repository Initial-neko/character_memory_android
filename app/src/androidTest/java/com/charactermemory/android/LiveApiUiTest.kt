package com.charactermemory.android

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.charactermemory.android.data.CoreApi
import com.charactermemory.android.data.ServerConfig
import com.charactermemory.android.data.text
import com.charactermemory.android.data.items
import com.charactermemory.android.live.LiveApp
import com.charactermemory.android.live.LivePage
import com.charactermemory.android.live.LiveViewModel
import com.charactermemory.android.live.SpaceReplyWindow
import com.charactermemory.android.live.refreshPost
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual emulator UI against a synthetic HTTPS backend; no tailnet/model calls. */
@RunWith(AndroidJUnit4::class)
class LiveApiUiTest {
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).putExtra("p1_mock", true)
        ),
        { rule ->
            var activity: MainActivity? = null
            rule.scenario.onActivity { activity = it }
            requireNotNull(activity)
        }
    )
    private lateinit var core: MockWebServer
    private lateinit var media: MockWebServer
    private lateinit var model: LiveViewModel
    private lateinit var dispatcher: P2FixtureDispatcher
    private lateinit var client: OkHttpClient
    private lateinit var config: ServerConfig
    private lateinit var preferenceName: String
    @Volatile private var imeBottom: Int = 0
    @Volatile private var asrResponse: String? = null
    private val asrRequests = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var ttsAvailable = false
    private val ttsRequests = java.util.concurrent.atomic.AtomicInteger()
    private var fakeRecording: kotlinx.coroutines.CompletableDeferred<ByteArray>? = null
    @Volatile private var fakeCallRecording: kotlinx.coroutines.CompletableDeferred<ByteArray>? = null
    private val callCancellations = java.util.concurrent.atomic.AtomicInteger()

    @Before fun launchSyntheticLiveApp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        // Only the synthetic server certificate is trusted; production TLS remains unchanged.
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).okHttpClient(client)
            .components { add(coil.decode.SvgDecoder.Factory()) }.build())
        core = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false) }
        media = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false) }
        dispatcher = P2FixtureDispatcher()
        core.dispatcher = dispatcher
        media.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path == "/v1/tts") {
                    ttsRequests.incrementAndGet()
                    if (ttsAvailable) return MockResponse().setHeader("Content-Type", "audio/wav")
                        .setBody(okio.Buffer().write(com.charactermemory.android.audio.Pcm16Wav.encode(ByteArray(320_000))))
                }
                if (request.path == "/v1/asr" && asrResponse != null) {
                    asrRequests.incrementAndGet()
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(requireNotNull(asrResponse))
                }
                return MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setBody("{\"detail\":\"media fixture offline\"}")
            }
        }
        core.start(); media.start()
        config = ServerConfig(core.url("/").toString().trimEnd('/'), media.url("/").toString().trimEnd('/'))
        preferenceName = "p2-ui-" + UUID.randomUUID()
        compose.runOnUiThread {
            model = LiveViewModel(compose.activity, { CoreApi(it, client) }, config, preferenceName,
                mediaApiFactory = { com.charactermemory.android.data.MediaApi(it, client) },
                recorderFactory = { object : com.charactermemory.android.audio.VoiceRecorderPort {
                    val pcm = kotlinx.coroutines.CompletableDeferred<ByteArray>().also { fakeRecording = it }
                    override suspend fun capture(onDurationMs: (Long) -> Unit): ByteArray { onDurationMs(1_000); return pcm.await() }
                    override fun stop() { pcm.complete(ByteArray(3200)) }
                    override fun cancel() { pcm.cancel() }
                } }, callRecorderFactory = { object : com.charactermemory.android.audio.VoiceRecorderPort {
                    val pcm = kotlinx.coroutines.CompletableDeferred<ByteArray>().also { fakeCallRecording = it }
                    override suspend fun capture(onDurationMs: (Long) -> Unit) = pcm.await()
                    override fun stop() { pcm.complete(ByteArray(3200)) }
                    override fun cancel() { callCancellations.incrementAndGet(); pcm.cancel() }
                } })
            compose.activity.setContent {
                // Read platform IME insets before the app's Compose padding consumes them.
                val keyboardBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                SideEffect { imeBottom = keyboardBottom }
                LiveApp(model)
            }
        }
        compose.waitUntil(15_000) { model.state.value.characters.size == 2 }
    }

    @After fun stopSyntheticBackend() {
        if (::dispatcher.isInitialized) dispatcher.releasePostRead.countDown()
        if (::model.isInitialized) compose.runOnUiThread { model.deactivate() }
        if (::core.isInitialized) core.shutdown()
        if (::media.isInitialized) media.shutdown()
        if (::preferenceName.isInitialized) compose.activity.getSharedPreferences(preferenceName, 0).edit().clear().commit()
        coil.Coil.reset()
    }

    private fun dismissIme() {
        // Espresso.closeSoftKeyboard() can wait for an Activity root that has no focus
        // while the IME owns the focused window. Request hiding directly from Android.
        compose.runOnUiThread {
            val service = compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            service.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun tap(tag: String, scroll: Boolean = false) {
        waitTag(tag)
        // Compose owns the actionable node; dialogs and the IME may own window focus.
        // Tests that require the actual IME assert its platform insets separately.
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.performClick()
    }
    private fun closeActualIme() {
        if (imeBottom > 0) {
            assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack())
            compose.waitUntil(10_000) { imeBottom == 0 }
        }
        compose.waitForIdle()
    }
    private fun assertBubbleAlignment(id: String, outbound: Boolean) {
        compose.onNodeWithTag("live-message-$id").performScrollTo().assertIsDisplayed()
        val row = compose.onNodeWithTag("live-message-$id").fetchSemanticsNode().boundsInWindow
        val bubble = compose.onNodeWithTag("live-message-bubble-$id").fetchSemanticsNode().boundsInWindow
        assertTrue("Message $id rendered as a full-width card", bubble.width < row.width * 0.80f)
        val leftGap = bubble.left - row.left
        val rightGap = row.right - bubble.right
        if (outbound) assertTrue("Outbound message must align right", rightGap < leftGap)
        else assertTrue("Inbound message must align left", leftGap < rightGap)
    }
    private fun screenshot(name: String, tag: String) {
        waitTag(tag); compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); SystemClock.sleep(450)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "p2-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CharacterMemoryP2/")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun configAndHealthPersistWhileMediaOutageKeepsRosterUsable() {
        compose.runOnUiThread { model.show(LivePage.SETTINGS) }
        waitTag("live-settings")
        compose.waitUntil(10_000) { model.state.value.coreHealth == "可连接" && model.state.value.mediaHealth.startsWith("不可连接") }
        screenshot("01-settings", "live-settings")
        tap("live-save-config", true)
        compose.waitUntil(10_000) { "roster" !in model.state.value.busy }
        val preferences = compose.activity.getSharedPreferences(preferenceName, 0)
        assertEquals(config.coreUrl, preferences.getString("core", null))
        assertEquals(config.mediaUrl, preferences.getString("media", null))
        compose.runOnUiThread { model.show(LivePage.HOME) }
        waitTag("live-character-rin")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("live-character-time-rin"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("live-character-time-rin", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("live-home-space").assertIsDisplayed()
        screenshot("02-roster", "live-home")
        tap("live-character-rin")
        waitTag("live-chat")
        compose.onNodeWithTag("live-chat-input").assertIsDisplayed()
        assertTrue(dispatcher.writes.isEmpty())
    }

    @Test fun directAcceptedMessageDeduplicatesHistoryAndComposerClearsActualIme() {
        tap("live-character-rin")
        compose.waitUntil(10_000) { model.state.value.messages.any { it.text("id") == "1" } }
        assertEquals(1, model.state.value.messages.count { it.text("id") == "1" })
        assertBubbleAlignment("1", false)
        screenshot("03-direct-chat", "live-chat")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val original = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        try {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            tap("live-chat-input")
            compose.onNodeWithTag("live-chat-input").performTextInput("fixture send")
            compose.waitUntil(10_000) {
                imeBottom > 0
            }
            val decor = compose.activity.window.decorView
            val keyboardTop = decor.height - imeBottom
            compose.onNodeWithTag("live-chat-input").assertIsDisplayed()
            compose.onNodeWithTag("live-chat-send").assertIsDisplayed()
            val bounds = compose.onNodeWithTag("live-chat-send").fetchSemanticsNode().boundsInWindow
            assertTrue("Live send button is covered by actual IME", bounds.bottom <= keyboardTop + 8f)
            val inputBounds = compose.onNodeWithTag("live-chat-input").fetchSemanticsNode().boundsInWindow
            assertTrue("Live input is covered by actual IME", inputBounds.bottom <= keyboardTop + 8f)
            compose.onNodeWithTag("live-chat-send").assertIsEnabled()
            screenshot("04-chat-ime", "live-chat")
            tap("live-chat-send")
            compose.waitUntil(10_000) { model.state.value.messages.any { it.text("id") == "10" } }
            assertEquals(1, model.state.value.messages.count { it.text("id") == "1" })
            assertEquals(1, model.state.value.messages.count { it.text("id") == "10" })
            assertEquals(1, dispatcher.writes.count { it.first == "/v1/chat/messages" })
            assertEquals("rin", dispatcher.writes.first().second.text("character_id"))
            assertTrue(dispatcher.writes.first().second.text("conversation_id").isNotBlank())
            closeActualIme()
            assertBubbleAlignment("10", true)
        } finally {
            dismissIme()
            if (original == "0" || original == "1") device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $original")
            else device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
        }
    }

    @Test fun groupAndSpaceUseActualTargetAndHumanCommentRoutes() {
        tap("live-group-g1", true)
        waitTag("live-chat")
        compose.waitUntil(10_000) { model.state.value.messages.any { it.text("id") == "1" } }
        assertBubbleAlignment("1", false)
        screenshot("09-group-chat", "live-chat")
        tap("live-chat-input")
        compose.onNodeWithTag("live-chat-input").performTextInput("fixture send")
        compose.waitUntil(10_000) { model.state.value.composeText == "fixture send" }
        dismissIme()
        compose.waitUntil(10_000) { imeBottom == 0 }
        compose.onNodeWithTag("live-chat-send").assertIsEnabled()
        tap("live-chat-send")
        compose.waitUntil(10_000) { dispatcher.writes.any { it.first == "/v1/groups/g1/messages" } }
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        waitTag("live-space")
        compose.waitUntil(10_000) { model.state.value.posts.isNotEmpty() }
        closeActualIme()
        screenshot("05-space", "live-space")
        assertTrue("Space editors must start collapsed", compose.onAllNodes(hasTestTag("live-space-comment-1")).fetchSemanticsNodes().isEmpty())
        tap("live-space-comments-toggle-1", true)
        compose.onNodeWithTag("live-space-comment-1").performScrollTo().performTextInput("fixture comment")
        tap("live-space-comment-send-1", true)
        compose.waitUntil(10_000) { dispatcher.writes.any { it.first == "/v1/space/posts/1/comments" } }
        val comment = dispatcher.writes.first { it.first == "/v1/space/posts/1/comments" }.second
        assertFalse("Human comments must omit character_id", comment.has("character_id"))
    }

    @Test fun characterAndEnsemblePreviewRequireSeparateConfirmationWrites() {
        tap("live-create-character")
        compose.onNodeWithTag("live-character-description").performTextInput("deterministic friend")
        tap("live-character-draft", true)
        waitTag("live-character-preview")
        closeActualIme()
        compose.onNodeWithTag("live-character-preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fixture Friend").assertIsDisplayed()
        compose.onNodeWithText("Deterministic character draft").assertIsDisplayed()
        screenshot("06-character-draft", "live-character-preview")
        assertFalse(dispatcher.writes.any { it.first == "/v1/characters" })
        compose.onNodeWithTag("live-character-confirm").performScrollTo().assertIsDisplayed()
        tap("live-character-confirm", true)
        compose.waitUntil(10_000) { model.state.value.page == LivePage.HOME }
        assertEquals(1, dispatcher.writes.count { it.first == "/v1/characters" })
        assertTrue(dispatcher.writes.first { it.first == "/v1/characters" }.second.has("draft"))
        tap("live-create-group")
        compose.onNodeWithTag("live-group-prompt").performTextInput("deterministic ensemble")
        tap("live-group-prepare", true)
        waitTag("live-ensemble-preview")
        closeActualIme()
        compose.onNodeWithTag("live-member-0").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Fixture member") and hasAnyAncestor(hasTestTag("live-member-0"))).assertIsDisplayed()
        screenshot("07-ensemble-preview", "live-ensemble-preview")
        assertFalse(dispatcher.writes.any { it.first.endsWith("/confirm") })
        compose.onNodeWithTag("live-ensemble-confirm").performScrollTo().assertIsDisplayed()
        tap("live-ensemble-confirm", true)
        compose.waitUntil(10_000) { model.state.value.page == LivePage.HOME }
        val confirmation = dispatcher.writes.filter { it.first == "/v1/ensembles/e1/confirm" }
        assertEquals(1, confirmation.size)
        assertEquals(2, confirmation.single().second.getAsJsonArray("selected_indices").size())
    }

    @Test fun generatedImageRemainsDraftUntilUserExplicitlySends() {
        tap("live-character-rin")
        tap("live-image-open")
        compose.onNodeWithTag("live-image-instruction").performTextInput("quiet coffee shop")
        tap("live-image-generate", true)
        waitTag("live-image-draft")
        closeActualIme()
        compose.onNodeWithTag("live-image-confirm-send").performScrollTo().assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("live-image-preview") and hasStateDescription("已加载")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("live-image-preview").assertIsDisplayed()
        screenshot("08-image-draft", "live-image-draft")
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
        val generation = dispatcher.writes.first { it.first.endsWith("/images/generate") }.second
        assertFalse(generation.get("persist_result").asBoolean)
        tap("live-image-confirm-send", true)
        compose.waitUntil(10_000) { model.state.value.page == LivePage.CHAT && model.state.value.imageDraft == null }
        val sends = dispatcher.writes.filter { it.first == "/v1/chat/messages" }
        assertEquals(1, sends.size)
        assertTrue(sends.single().second.getAsJsonObject("image").text("data_url").startsWith("data:image/"))
    }

    @Test fun spaceRefreshAndLateConfirmationPreserveDraftAndConfirmedComment() {
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        compose.waitUntil(10_000) { model.state.value.posts.isNotEmpty() }
        tap("live-space-comments-toggle-1", true)
        val input = compose.onNodeWithTag("live-space-comment-1")
        input.performScrollTo().performTextInput("draft A")
        dispatcher.externalReply.set(true)
        compose.runOnUiThread { model.refreshPost("1") }
        compose.waitUntil(10_000) { "space-post-1" !in model.state.value.busy }
        input.assertTextContains("draft A", substring = true)

        dispatcher.holdPostRead = true
        dispatcher.commentDelayMs = 1500
        compose.runOnUiThread { model.refreshPost("1") }
        assertTrue(dispatcher.postReadStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        tap("live-space-comment-send-1", true)
        input.performScrollTo().performTextReplacement("draft B")
        compose.waitUntil(10_000) { model.state.value.commentReceipts["1"] != null }
        input.assertTextContains("draft B", substring = true)
        dispatcher.releasePostRead.countDown()
        compose.waitUntil(10_000) { "space-post-1" !in model.state.value.busy }
        assertTrue(model.state.value.posts.first().getAsJsonArray("comments").any { it.asJsonObject.text("id") == "5" })
        input.assertTextContains("draft B", substring = true)
    }
    @Test fun confirmedSpaceCommentReceivesAsyncReplyWithoutManualRefresh() {
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        compose.waitUntil(10_000) { model.state.value.posts.isNotEmpty() }
        tap("live-space-comments-toggle-1", true)
        compose.onNodeWithTag("live-space-comment-1").performScrollTo().performTextInput("fixture comment")
        tap("live-space-comment-send-1", true)
        compose.waitUntil(10_000) { model.state.value.commentReceipts["1"] != null }
        // The status lives below the comment editor in a scrollable feed item.
        compose.onNodeWithTag("live-space-auto-refresh-1").performScrollTo().assertIsDisplayed()
        dispatcher.externalReply.set(true)
        compose.waitUntil(17_000) {
            model.state.value.posts.firstOrNull()?.getAsJsonArray("comments")?.any { item ->
                item.asJsonObject.text("id") == "6" && item.asJsonObject.text("reply_to_comment_id") == "5"
            } == true
        }
        assertEquals(1, dispatcher.writes.count { it.first == "/v1/space/posts/1/comments" })
    }

    @Test fun corruptImageDraftCannotBeSent() {
        dispatcher.corruptImage = true
        tap("live-character-rin")
        tap("live-image-open")
        compose.onNodeWithTag("live-image-instruction").performTextInput("invalid image fixture")
        tap("live-image-generate", true)
        waitTag("live-image-draft")
        closeActualIme()
        compose.onNodeWithTag("live-image-confirm-send").performScrollTo()
        waitTag("live-image-preview-error")
        compose.onNodeWithTag("live-image-confirm-send").assertIsNotEnabled()
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
    }

    @Test fun collapsedSpaceThreadStopsReplyPolling() {
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        compose.waitUntil(10_000) { model.state.value.posts.isNotEmpty() }
        tap("live-space-comments-toggle-1", true)
        compose.onNodeWithTag("live-space-comment-1").performScrollTo().performTextInput("fixture comment")
        tap("live-space-comment-send-1", true)
        compose.waitUntil(10_000) { model.state.value.commentReceipts["1"] != null }
        closeActualIme()
        tap("live-space-comments-toggle-1", true)
        compose.waitForIdle()
        val reads = dispatcher.postReads.get()
        val started = SystemClock.elapsedRealtime()
        compose.waitUntil(8_000) { SystemClock.elapsedRealtime() - started >= 6_500 }
        assertEquals("Collapsed threads must not poll Core", reads, dispatcher.postReads.get())
        assertEquals(1, dispatcher.writes.count { it.first == "/v1/space/posts/1/comments" })
        // Expire this confirmed receipt, dispose its page, and reopen the thread.
        // A new composition must never turn the same receipt into another 90s window.
        compose.runOnUiThread {
            model.update { current -> current.copy(commentReceipts = current.commentReceipts.mapValues { (_, receipt) ->
                receipt.copy(replyWindow = SpaceReplyWindow(SystemClock.elapsedRealtime() - 90_000L))
            }) }
            model.show(LivePage.HOME)
        }
        waitTag("live-home")
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        compose.waitUntil(10_000) { model.state.value.posts.isNotEmpty() }
        tap("live-space-comments-toggle-1", true)
        val reopened = SystemClock.elapsedRealtime()
        compose.waitUntil(8_000) { SystemClock.elapsedRealtime() - reopened >= 6_500 }
        assertEquals("Expired receipts must not renew when their page reopens", reads, dispatcher.postReads.get())
    }

    @Test fun ttsFailureIsVisibleAndExplicitRetryUsesTheSharedPlayer() {
        tap("live-character-rin")
        waitTag("live-message-tts-1")
        tap("live-message-tts-1")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("live-message-tts-1") and hasText("朗读失败", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, ttsRequests.get())
        ttsAvailable = true
        tap("live-message-tts-1")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("live-message-tts-1") and hasText("停止", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("16-tts-playback", "live-chat")
        tap("live-message-tts-1")
        compose.onNodeWithTag("live-message-tts-1").assertTextContains("播放", substring = true)
        assertEquals(2, ttsRequests.get())
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
    }

    @Test fun brokenStickerCannotSendAndDefaultSvgRenders() {
        dispatcher.brokenSticker = true
        tap("live-character-rin")
        tap("live-stickers-open")
        waitTag("live-sticker-send-wave")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("live-sticker-error-wave")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("live-sticker-send-wave").assertIsNotEnabled()
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
        tap("live-sticker-pack-custom")
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("live-sticker-send-sparkle").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("live-sticker-send-sparkle").assertIsDisplayed()
    }

    @Test fun cameraFramesUseRealVisualRoutesAndStaleTargetCannotSend() {
        tap("live-character-rin")
        val jpeg = java.io.ByteArrayOutputStream().also { stream ->
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream); bitmap.recycle()
            }
        }.toByteArray()
        val stale = model.captureGeneration
        compose.runOnUiThread { model.sendCameraFrame(stale, jpeg, "fixture camera question") }
        compose.waitUntil(10_000) { dispatcher.writes.any { it.first == "/v1/visual/direct/messages" } }
        val direct = dispatcher.writes.single { it.first == "/v1/visual/direct/messages" }.second
        assertEquals("rin", direct.text("character_id"))
        assertEquals("CAMERA", direct.getAsJsonArray("visual_frames")[0].asJsonObject.text("source"))
        compose.waitUntil(10_000) { "visual" !in model.state.value.busy }
        compose.runOnUiThread { model.show(LivePage.HOME) }
        tap("live-group-g1")
        compose.runOnUiThread { model.sendCameraFrame(stale, jpeg, "stale camera question") }
        compose.waitForIdle()
        assertFalse(dispatcher.writes.any { it.first == "/v1/visual/groups/g1/messages" })
        compose.runOnUiThread { model.sendCameraFrame(model.captureGeneration, jpeg, "fixture group camera") }
        compose.waitUntil(10_000) { dispatcher.writes.any { it.first == "/v1/visual/groups/g1/messages" } }
        assertEquals(2, dispatcher.writes.count { it.first.startsWith("/v1/visual/") })
    }

    @Test fun visualToolsExposeExplicitCaptureControlsWithoutStartingCapture() {
        tap("live-character-rin")
        tap("live-visual-tools")
        compose.onNodeWithTag("live-camera-open").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("live-screen-share-start").assertIsDisplayed().assertIsEnabled()
        assertFalse(com.charactermemory.android.screen.ScreenShareStatus.state.value.active)
        assertFalse(dispatcher.writes.any { it.first.startsWith("/v1/visual/") })
        screenshot("19-visual-controls", "live-camera-open")
    }

    @Test fun asrDraftStaysEditableAndDoesNotSendUntilTheExistingSendAction() {
        asrResponse = """{"text":"fixture transcript"}"""
        tap("live-character-rin")
        compose.runOnUiThread {
            val ticket = requireNotNull(model.voice.requestStart())
            model.voice.onPermissionResult(ticket, true) // Fake recorder; hardware permission is tested separately.
        }
        waitTag("live-asr-stop")
        tap("live-asr-stop")
        waitTag("live-asr-draft")
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
        compose.onNodeWithTag("live-asr-draft").performTextReplacement("fixture transcript edited")
        closeActualIme()
        screenshot("13-asr-draft", "live-voice-input")
        tap("live-asr-use-draft")
        compose.onNodeWithTag("live-chat-input").assertTextContains("fixture transcript edited", substring = true)
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
        tap("live-chat-send")
        compose.waitUntil(10_000) { dispatcher.writes.count { it.first == "/v1/chat/messages" } == 1 }
        assertEquals(1, asrRequests.get())
    }

    @Test fun deniedAsrPermissionLeavesTextChatUsable() {
        tap("live-character-rin")
        compose.runOnUiThread {
            val ticket = requireNotNull(model.voice.requestStart())
            model.voice.onPermissionResult(ticket, false)
        }
        waitTag("live-asr-error")
        compose.onNodeWithTag("live-asr-error").assertTextContains("录音权限未授予", substring = true)
        compose.onNodeWithTag("live-chat-input").performTextInput("text still works")
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
    }

    @Test fun usageViewLoadsOneHourSummaryFeatureModelAndRecentRows() {
        tap("live-tab-settings")
        waitTag("live-settings")
        tap("live-usage-open", true)
        waitTag("live-usage")
        compose.waitUntil(10_000) { model.state.value.llmUsage?.summary?.requests == 2L }
        assertTrue(dispatcher.requests.contains("GET /v1/llm/usage?hours=1&limit=80"))

        screenshot("10-usage", "live-usage")
        compose.onNodeWithTag("live-usage-summary").assertTextContains("410", substring = true)
        compose.onNodeWithTag("live-usage-by-feature").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("live-usage-by-feature").assertTextContains("DIRECT_REACTION", substring = true)
        compose.onNodeWithTag("live-usage-by-model").performScrollTo().assertTextContains("fixture-model", substring = true)
        compose.onNodeWithTag("live-usage-recent").performScrollTo().assertTextContains("example.test", substring = true)

        tap("live-back")
        assertEquals(LivePage.SETTINGS, model.state.value.page)
    }

    @Test fun usageViewMarksAnEmptySuccessfulWindowWithoutInventingCoverageOrLatency() {
        dispatcher.usageResponseBody = """{"window_hours":1,"summary":{"requests":0,"logical_calls":0,"input_tokens":0,"output_tokens":0,"total_tokens":0,"token_known_requests":0,"retried_logical_calls":0,"errors":0,"avg_latency_ms":0.0,"input_chars":0,"output_chars":0,"token_coverage":1.0},"by_feature":[],"by_model":[],"recent":[]}"""
        tap("live-tab-settings")
        waitTag("live-settings")
        tap("live-usage-open", true)

        compose.waitUntil(10_000) { model.state.value.llmUsage?.summary?.requests == 0L }
        compose.onNodeWithTag("live-usage-empty").assertIsDisplayed()
        compose.onNodeWithTag("live-usage-token-coverage", useUnmergedTree = true).assertTextContains("—", substring = true)
        compose.onNodeWithTag("live-usage-average-latency", useUnmergedTree = true).assertTextContains("—", substring = true)
    }

    @Test fun usageViewDoesNotShowTheSuccessfulEmptyStateAfterTheFirstRequestFails() {
        dispatcher.usageStatus = 503
        tap("live-tab-settings")
        waitTag("live-settings")
        tap("live-usage-open", true)

        compose.waitUntil(10_000) { model.state.value.error != null && "llm-usage" !in model.state.value.busy }
        assertTrue(dispatcher.requests.contains("GET /v1/llm/usage?hours=1&limit=80"))
        compose.onNodeWithTag("live-error").assertIsDisplayed()
        compose.onNodeWithTag("live-usage-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("live-usage-empty").assertDoesNotExist()
    }

    @Test fun stickerPickerShowsPackTabsAndSendsTheSelectedStickerAssetId() {
        tap("live-character-rin")
        tap("live-stickers-open")
        waitTag("live-stickers")
        waitTag("live-sticker-pack-custom")
        compose.onNodeWithTag("live-sticker-pack-custom").assertTextContains("自定义", substring = true)
        compose.onNodeWithTag("live-sticker-pack-custom").performClick()
        compose.onNodeWithTag("live-sticker-send-sparkle").assertIsDisplayed()
        compose.onNodeWithTag("live-sticker-send-wave").assertDoesNotExist()
        compose.waitUntil(10_000) { dispatcher.requests.contains("GET /v1/stickers/sparkle/asset") }
        screenshot("11-sticker-packs", "live-stickers")

        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("live-sticker-send-sparkle").assertIsEnabled() }.isSuccess }
        tap("live-sticker-send-sparkle", true)
        compose.waitUntil(10_000) {
            dispatcher.writes.any { it.first == "/v1/chat/messages" && it.second.text("sticker_id") == "sparkle" }
        }
        val send = dispatcher.writes.single { it.first == "/v1/chat/messages" }
        assertEquals("sparkle", send.second.text("sticker_id"))
        assertTrue(dispatcher.requests.contains("GET /v1/stickers/sparkle/asset"))
    }

    @Test fun spaceCommentSendsStructuredRoleMentionsAndIdempotencyKey() {
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        compose.waitUntil(10_000) { model.state.value.posts.isNotEmpty() }
        compose.waitUntil(10_000) { model.state.value.spaceMentionCharacters.size == 3 }
        assertTrue(dispatcher.reads.any { it == "/v1/characters?archived=false&include_deferred=true" })
        tap("live-space-comments-toggle-1", true)
        tap("live-space-mention-toggle-1", true)
        compose.onNodeWithTag("live-space-mention-1-nova").assertIsDisplayed()
        compose.onNodeWithTag("live-space-mention-1-rin").performClick()
        tap("live-space-mention-toggle-1", true)
        compose.onNodeWithTag("live-space-mention-1-lex").performClick()
        compose.onNodeWithTag("live-space-mention-chip-1-rin").assertIsDisplayed()
        compose.onNodeWithTag("live-space-mention-chip-1-lex").assertIsDisplayed()
        screenshot("12-space-mentions", "live-space")
        compose.onNodeWithTag("live-space-comment-1").performScrollTo().performTextInput("你们愿意一起去吗？")
        tap("live-space-comment-send-1", true)
        compose.waitUntil(10_000) { dispatcher.writes.any { it.first == "/v1/space/posts/1/comments" } }
        val request = dispatcher.writes.first { it.first == "/v1/space/posts/1/comments" }.second
        assertEquals("你们愿意一起去吗？", request.text("content"))
        assertEquals(listOf("rin", "lex"), request.getAsJsonArray("mentions").map { it.asString })
        assertTrue(request.text("client_request_id").isNotBlank())
        assertFalse("Mention identity must not be inferred by rewriting the user's comment", request.text("content").contains("@Rin"))
        compose.waitUntil(10_000) { model.state.value.posts.first().items("comments").any { it.text("id") == "5" } }
        compose.onNodeWithTag("live-space-comment-mentions-5").assertIsDisplayed().assertTextContains("@Rin", substring = true)
    }

    @Test fun characterReplyToUserCommentGetsAttentionMarker() {
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        compose.waitUntil(10_000) { model.state.value.posts.isNotEmpty() }
        dispatcher.externalReply.set(true)
        compose.runOnUiThread { model.refreshPost("1") }
        compose.waitUntil(10_000) { model.state.value.posts.first().items("comments").any { it.text("id") == "6" } }
        tap("live-space-comments-toggle-1", true)
        compose.onNodeWithTag("live-space-comment-reply-to-user-6").assertIsDisplayed().assertTextContains("角色回复了你", substring = true)
    }

    @Test fun spaceUnreadNotificationOpensItsCommentAndMarksItRead() {
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        compose.waitUntil(10_000) {
            model.state.value.posts.isNotEmpty() && model.state.value.spaceUnreadCount == 1 &&
                model.state.value.spaceNotifications.isNotEmpty()
        }
        assertTrue(dispatcher.reads.any { it == "/v1/space/notifications?unread_only=true&limit=50" })
        compose.onNodeWithTag("live-space-unread-badge").performScrollTo().assertIsDisplayed().assertTextContains("1", substring = true)
        tap("live-space-notification-7", true)
        compose.waitUntil(10_000) { dispatcher.notificationRead.get() && model.state.value.spaceUnreadCount == 0 }
        assertEquals("1", model.state.value.focusedSpacePostId)
        assertEquals("59", model.state.value.focusedSpaceCommentId)
        val post = model.state.value.posts.first()
        assertEquals("media-1", post.items("media_items").single().text("media_id"))
        assertEquals(1, post.get("like_count").asInt)
        assertEquals("rin", post.getAsJsonArray("likes").single().asJsonObject.get("character_id").asString)
        assertEquals(listOf("51", "59"), post.items("comments").map { it.text("id") })
        assertEquals(0, dispatcher.requests.count { it == "GET /v1/space/posts/1" })
        assertEquals(1, dispatcher.writes.count { it.first == "/v1/space/notifications/7/read" })
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("live-space-comment-mentions-user-59").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag("live-space-comment-mentions-user-59").assertIsDisplayed()
    }

    @Test fun spaceUnreadNotificationLoadsFullPostBeforeMarkingReadWhenFeedHasNotLoaded() {
        dispatcher.spaceFeedEmpty = true
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        compose.waitUntil(10_000) {
            model.state.value.spaceNotifications.isNotEmpty() && "space" !in model.state.value.busy
        }
        assertTrue(model.state.value.posts.isEmpty())
        tap("live-space-notification-7", true)

        compose.waitUntil(10_000) { dispatcher.notificationRead.get() && model.state.value.spaceUnreadCount == 0 }
        val post = model.state.value.posts.single()
        assertEquals("media-1", post.items("media_items").single().text("media_id"))
        assertEquals(1, post.get("like_count").asInt)
        assertEquals(listOf("51", "59"), post.items("comments").map { it.text("id") })
        val fullPostRead = dispatcher.requests.indexOf("GET /v1/space/posts/1")
        val markRead = dispatcher.requests.indexOf("POST /v1/space/notifications/7/read")
        assertTrue("Full post must load before the notification is marked read", fullPostRead >= 0 && fullPostRead < markRead)
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("live-space-comment-mentions-user-59").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithTag("live-space-comment-mentions-user-59").assertIsDisplayed()
    }

    @Test fun lateSpaceFeedResponsePreservesOpenedNotificationCommentAndFocus() {
        dispatcher.holdSpaceFeedRead = true
        try {
            compose.runOnUiThread { model.show(LivePage.SPACE) }
            assertTrue(dispatcher.spaceFeedReadStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.waitUntil(10_000) {
                model.state.value.spaceNotifications.isNotEmpty() && "space" in model.state.value.busy
            }

            tap("live-space-notification-7", true)
            compose.waitUntil(10_000) {
                dispatcher.notificationRead.get() && model.state.value.spaceUnreadCount == 0 &&
                    "space-notification-7" !in model.state.value.busy
            }
            assertTrue("The feed request remains in flight while the notification opens", "space" in model.state.value.busy)
            assertOpenedNotificationPost()

            val feedRead = dispatcher.requests.indexOfFirst { it.startsWith("GET /v1/space/posts?") }
            val fullPostRead = dispatcher.requests.indexOf("GET /v1/space/posts/1")
            val markRead = dispatcher.requests.indexOf("POST /v1/space/notifications/7/read")
            assertTrue("The older feed starts before opening the notification", feedRead >= 0 && feedRead < fullPostRead)
            assertTrue("The full post is loaded before marking the notification read", fullPostRead < markRead)

            dispatcher.releaseSpaceFeedRead.countDown()
            compose.waitUntil(10_000) { "space" !in model.state.value.busy }
            assertOpenedNotificationPost()
        } finally {
            dispatcher.releaseSpaceFeedRead.countDown()
        }
    }

    @Test fun lateSinglePostResponsePreservesOpenedNotificationCommentAndFocus() {
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        compose.waitUntil(10_000) {
            model.state.value.posts.isNotEmpty() && model.state.value.spaceNotifications.isNotEmpty() &&
                "space" !in model.state.value.busy
        }

        dispatcher.holdPostRead = true
        try {
            compose.runOnUiThread { model.refreshPost("1") }
            assertTrue(dispatcher.postReadStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            tap("live-space-notification-7", true)
            compose.waitUntil(10_000) {
                dispatcher.notificationRead.get() && model.state.value.spaceUnreadCount == 0 &&
                    "space-notification-7" !in model.state.value.busy
            }
            assertTrue("The older single-post request remains in flight", "space-post-1" in model.state.value.busy)
            assertOpenedNotificationPost()

            dispatcher.releasePostRead.countDown()
            compose.waitUntil(10_000) { "space-post-1" !in model.state.value.busy }
            assertOpenedNotificationPost()
        } finally {
            dispatcher.releasePostRead.countDown()
        }
    }

    @Test fun spaceNotificationPostLoadFailureKeepsReminderUnreadAndShowsError() {
        dispatcher.spaceFeedEmpty = true
        dispatcher.postReadStatus = 503
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        compose.waitUntil(10_000) {
            model.state.value.spaceNotifications.isNotEmpty() && "space" !in model.state.value.busy
        }
        assertTrue(model.state.value.posts.isEmpty())

        tap("live-space-notification-7", true)

        compose.waitUntil(10_000) { model.state.value.error != null && "space-notification-7" !in model.state.value.busy }
        assertEquals(1, model.state.value.spaceUnreadCount)
        assertEquals("7", model.state.value.spaceNotifications.single().text("id"))
        assertNull(model.state.value.focusedSpacePostId)
        assertEquals(0, dispatcher.writes.count { it.first == "/v1/space/notifications/7/read" })
        compose.onNodeWithTag("live-error").assertIsDisplayed()
    }

    @Test fun spaceNotificationReadFailureKeepsReminderUnreadAndShowsError() {
        dispatcher.spaceFeedEmpty = true
        dispatcher.notificationReadStatus = 503
        compose.runOnUiThread { model.show(LivePage.SPACE) }
        compose.waitUntil(10_000) {
            model.state.value.spaceNotifications.isNotEmpty() && "space" !in model.state.value.busy
        }
        assertTrue(model.state.value.posts.isEmpty())

        tap("live-space-notification-7", true)

        compose.waitUntil(10_000) { model.state.value.error != null && "space-notification-7" !in model.state.value.busy }
        assertEquals(1, model.state.value.spaceUnreadCount)
        assertEquals("7", model.state.value.spaceNotifications.single().text("id"))
        assertNull(model.state.value.focusedSpacePostId)
        assertTrue(model.state.value.confirmedComments["1"].orEmpty().none { it.text("id") == "59" })
        assertEquals(1, dispatcher.writes.count { it.first == "/v1/space/notifications/7/read" })
        compose.onNodeWithTag("live-error").assertIsDisplayed()
    }


    @Test fun callUsesSystemPermissionAndHangupReleasesCaptureWithoutSendingSilence() {
        dispatcher.keepCallStreamOpen = true
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        tap("live-call-start")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val grant = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res(
            "com.android.permissioncontroller", "permission_allow_foreground_only_button")), 5000)
        grant?.click()
        compose.waitUntil(10000) { model.call.state.value.phase == "listening" && fakeCallRecording != null }
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            androidx.core.content.ContextCompat.checkSelfPermission(compose.activity, android.Manifest.permission.RECORD_AUDIO))
        screenshot("17-call-listening", "live-voice-input")
        tap("live-call-hangup")
        compose.waitUntil(10000) { model.call.state.value.phase == "idle" && callCancellations.get() > 0 }
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
    }

    @Test fun callTranscriptionWritesOnceThenPlaysCorrectSpeakerAndHangupStopsAudio() {
        dispatcher.keepCallStreamOpen = true; ttsAvailable = true
        asrResponse = """{"text":"fixture call transcript"}"""
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        compose.runOnUiThread { model.call.permission(requireNotNull(model.requestCall()), true) }
        compose.waitUntil(10000) { fakeCallRecording != null }
        compose.runOnUiThread { requireNotNull(fakeCallRecording).complete(ByteArray(3200)) }
        compose.waitUntil(10000) { dispatcher.writes.any { it.first == "/v1/chat/messages" } }
        // SSE timing/correlation is covered by the reducer; inject its same live event boundary here.
        compose.runOnUiThread { model.call.reply("call-reply", "10", "fixture reply", "rin"); model.call.completed("10") }
        compose.waitUntil(10000) { model.call.state.value.phase == "speaking" && ttsRequests.get() == 1 }
        screenshot("18-call-speaking", "live-voice-input")
        tap("live-call-hangup")
        compose.waitUntil(10000) { model.call.state.value.phase == "idle" }
        assertEquals(1, dispatcher.writes.count { it.first == "/v1/chat/messages" })
        assertEquals(1, asrRequests.get())
        compose.onNodeWithTag("live-call-hangup").assertDoesNotExist()
    }

    private fun assertOpenedNotificationPost() {
        val state = model.state.value
        assertEquals(0, state.spaceUnreadCount)
        assertTrue(state.spaceNotifications.none { it.text("id") == "7" })
        assertEquals("1", state.focusedSpacePostId)
        assertEquals("59", state.focusedSpaceCommentId)
        val post = state.posts.single()
        assertEquals("fixture space post", post.text("content"))
        assertEquals("rin", post.getAsJsonObject("author").text("id"))
        assertEquals("2026-10-02T10:00:00+08:00", post.text("created_at"))
        assertEquals("media-1", post.items("media_items").single().text("media_id"))
        assertEquals(1, post.get("like_count").asInt)
        assertEquals("rin", post.getAsJsonArray("likes").single().asJsonObject.get("character_id").asString)
        val comments = post.items("comments")
        assertEquals(listOf("51", "59"), comments.map { it.text("id") })
        assertEquals("我看到你喊我啦。", comments.single { it.text("id") == "59" }.text("content"))
    }
}
