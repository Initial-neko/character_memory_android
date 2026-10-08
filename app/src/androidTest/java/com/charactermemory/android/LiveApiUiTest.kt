package com.charactermemory.android

import com.charactermemory.android.live.LiveAudioPlayback

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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
        com.charactermemory.android.screen.ScreenShareService.apiFactory = { CoreApi(it, client) }
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
        if (::model.isInitialized) compose.runOnUiThread { model.call.end(); model.deactivate() }
        compose.runOnUiThread { com.charactermemory.android.screen.ScreenShareService.stop(compose.activity, "TEST_CLEANUP") }
        compose.waitUntil(5000) { !com.charactermemory.android.screen.ScreenShareStatus.state.value.active }
        com.charactermemory.android.screen.ScreenShareService.apiFactory = { CoreApi(it) }
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
        if (tag in setOf("live-image-open", "live-call-start") && compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) {
            closeActualIme()
            compose.onNodeWithTag("live-visual-tools").performClick()
        }
        if (tag in setOf("live-call-camera-toggle", "live-call-screen-toggle", "live-call-screen-preview-toggle", "live-call-notifications") &&
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("live-call-more").performClick()
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
        screenshotDisplay(name)
    }
    private fun screenshotDisplay(name: String) {
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

    @Test fun returningToChatShowsCachedHistoryBeforeSlowRefreshAndReusesDirectories() {
        tap("live-character-rin")
        compose.waitUntil(10_000) { model.state.value.messages.isNotEmpty() && "history" !in model.state.value.busy && "stickers" !in model.state.value.busy }
        val rosterReads = dispatcher.reads.count { it == "/v1/characters" }
        val stickerReads = dispatcher.reads.count { it == "/v1/stickers" }
        val avatarReads = dispatcher.reads.count { it.endsWith("/avatar") }
        dispatcher.historyDelayMs = 2_000
        compose.runOnUiThread { model.show(LivePage.HOME); model.openCharacter("rin") }
        assertTrue("Cached history must exist while refresh is still running", model.state.value.messages.isNotEmpty())
        assertTrue("Authoritative refresh must still run", "history" in model.state.value.busy)
        compose.onNodeWithTag("live-message-1").assertIsDisplayed()
        compose.waitUntil(10_000) { "history" !in model.state.value.busy }
        assertEquals(rosterReads, dispatcher.reads.count { it == "/v1/characters" })
        assertEquals(stickerReads, dispatcher.reads.count { it == "/v1/stickers" })
        assertEquals(avatarReads, dispatcher.reads.count { it.endsWith("/avatar") })
        compose.runOnUiThread { model.refresh(); model.loadStickers(true) }
        compose.waitUntil(10_000) { "roster" !in model.state.value.busy && "stickers" !in model.state.value.busy }
        assertTrue(dispatcher.reads.count { it == "/v1/characters" } > rosterReads)
        assertTrue(dispatcher.reads.count { it == "/v1/stickers" } > stickerReads)
        assertTrue(dispatcher.writes.isEmpty())
    }

    @Test fun completedSpeechIsReusedAfterLeavingAndReturningToChat() {
        ttsAvailable = true
        tap("live-character-rin")
        compose.onNodeWithTag("live-message-tts-1").assertDoesNotExist()
        revealSpeech("1")
        assertEquals("Long press must not synthesize automatically", 0, ttsRequests.get())
        tap("live-message-tts-1")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("live-message-tts-1") and hasStateDescription("停止")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { model.show(LivePage.HOME) }
        waitTag("live-character-rin")
        compose.onNodeWithTag("live-message-tts-1").assertDoesNotExist()
        tap("live-character-rin")
        revealSpeech("1")
        tap("live-message-tts-1")
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("live-message-tts-1") and hasStateDescription("停止")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("A completed speech cache hit must not synthesize again", 1, ttsRequests.get())
        assertTrue(dispatcher.writes.isEmpty())
    }

    @Test fun composerKeepsVoiceAndEmojiInlineAndCallInsideTools() {
        tap("live-character-rin")
        compose.onNodeWithTag("live-voice-mode").assertDoesNotExist()
        compose.onNodeWithTag("live-asr-start").assertIsDisplayed()
        compose.onNodeWithTag("live-stickers-open").assertIsDisplayed()
        compose.onNodeWithTag("live-call-start").assertDoesNotExist()
        compose.onNodeWithTag("live-image-open").assertDoesNotExist()
        val input = compose.onNodeWithTag("live-chat-input").fetchSemanticsNode().boundsInWindow
        val emoji = compose.onNodeWithTag("live-stickers-open").fetchSemanticsNode().boundsInWindow
        assertTrue("Emoji must share the input row", emoji.center.y in input.top..input.bottom)
        tap("live-visual-tools")
        compose.onNodeWithTag("live-call-start").assertIsDisplayed()
        compose.onNodeWithTag("live-image-open").assertIsDisplayed()
        val imageTool = compose.onNodeWithTag("live-image-open").fetchSemanticsNode().boundsInRoot
        val callTool = compose.onNodeWithTag("live-call-start").fetchSemanticsNode().boundsInRoot
        assertTrue("Tool cards must have equal dimensions", kotlin.math.abs(imageTool.width - callTool.width) < 1f && kotlin.math.abs(imageTool.height - callTool.height) < 1f)
        screenshot("21-composer-tools", "live-chat")
    }

    @Test fun selfieSwitchAlsoControlsAvatarReferenceWithoutASecondOption() {
        tap("live-character-rin")
        tap("live-image-open")
        compose.onNodeWithTag("live-image-avatar").assertDoesNotExist()
        tap("live-image-selfie", true)
        compose.onNodeWithTag("live-image-instruction").performTextInput("quiet coffee shop")
        // Background sticker loading must not disable image generation. Assert
        // the request was actually accepted before awaiting the resulting UI.
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("live-image-generate") and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        tap("live-image-generate", true)
        compose.waitUntil(15_000) { dispatcher.writes.any { it.first.endsWith("/images/generate") } }
        compose.waitUntil(15_000) { model.state.value.imageDraft != null || model.state.value.error != null }
        assertNotNull("Image draft generation failed: ${model.state.value.error}", model.state.value.imageDraft)
        waitTag("live-image-draft")
        compose.waitUntil(10_000) { "image-generate" !in model.state.value.busy }
        val selfie = dispatcher.writes.last { it.first.endsWith("/images/generate") }.second
        assertEquals("SELFIE", selfie.text("purpose"))
        assertTrue(selfie.get("use_avatar_reference").asBoolean)
        closeActualIme()
        tap("live-image-selfie", true)
        tap("live-image-generate", true)
        compose.waitUntil(10_000) { dispatcher.writes.count { it.first.endsWith("/images/generate") } == 2 }
        val scene = dispatcher.writes.last { it.first.endsWith("/images/generate") }.second
        assertEquals("SCENE", scene.text("purpose"))
        assertFalse(scene.get("use_avatar_reference").asBoolean)
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
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
        revealSpeech("1")
        waitTag("live-message-tts-1")
        tap("live-message-tts-1")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("live-message-tts-1") and playbackStateContains("朗读失败")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, ttsRequests.get())
        ttsAvailable = true
        tap("live-message-tts-1")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("live-message-tts-1") and hasStateDescription("停止")).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("16-tts-playback", "live-chat")
        tap("live-message-tts-1")
        compose.onNodeWithTag("live-message-tts-1").assert(hasStateDescription("播放"))
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
        compose.onNodeWithTag("live-camera-open").assertDoesNotExist()
        compose.onNodeWithTag("live-screen-share-start").assertDoesNotExist()
        assertFalse(com.charactermemory.android.screen.ScreenShareStatus.state.value.active)
        assertFalse(dispatcher.writes.any { it.first.startsWith("/v1/visual/") })
        screenshot("19-visual-controls", "live-call-start")
    }

    @Test fun mediaAddressPointingAtCoreIsRejectedEvenWhenHealthReturns200() {
        compose.runOnUiThread { model.saveConfig(config.coreUrl, config.coreUrl) }
        compose.waitUntil(10_000) { model.state.value.mediaHealth.contains("不是 Media 服务") }
        assertEquals("可连接", model.state.value.coreHealth)
        assertTrue(model.state.value.mediaHealth.startsWith("不可用"))
        assertTrue(dispatcher.requests.contains("GET /openapi.json"))
        screenshot("20-media-routing-error", "live-settings")
        assertEquals(0, asrRequests.get())
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
        // NavigationBar merges descendant semantics; the fixed bottom badge is not scrollable.
        compose.onNodeWithTag("live-space-unread-badge", useUnmergedTree = true)
            .assertIsDisplayed().assertTextContains("1", substring = true)
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


    @Test fun cameraPreviewInCallKeepsMuteAndHangupAvailable() {
        asrResponse = """{"text":"看看我现在的画面"}"""
        dispatcher.showCallPortrait = true
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            "com.charactermemory.android", android.Manifest.permission.CAMERA)
        dispatcher.keepCallStreamOpen = true
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        compose.runOnUiThread { model.call.permission(requireNotNull(model.requestCall()), true) }
        compose.waitUntil(10000) { fakeCallRecording != null }
        val stage = compose.onNodeWithTag("live-call-screen").fetchSemanticsNode().boundsInRoot
        val art = compose.onNodeWithTag("live-call-character-art").fetchSemanticsNode().boundsInRoot
        assertTrue("Character must dominate the call page", art.height >= stage.height * .7f)
        for (tag in listOf("live-call-hangup", "live-call-mic-toggle", "live-call-more")) {
            val control = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Bottom call control: $tag", control.center.y > stage.top + stage.height * .7f)
        }
        tap("live-call-camera-toggle")
        compose.onNodeWithTag("live-camera-preview").assertIsDisplayed()
        val camera = compose.onNodeWithTag("live-camera-preview").fetchSemanticsNode().boundsInRoot
        assertTrue("Video fills the call stage", camera.width >= stage.width * .95f && camera.height >= stage.height * .95f)
        compose.onNodeWithTag("live-camera-capture").assertDoesNotExist()
        compose.onNodeWithTag("live-camera-send").assertDoesNotExist()
        compose.onNodeWithTag("live-call-character-inset").assertIsDisplayed()
        compose.waitUntil(20000) { compose.onAllNodesWithTag("live-camera-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("live-call-mic-toggle").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { model.call.state.value.microphoneMuted }
        compose.onNodeWithTag("live-call-hangup").assertIsDisplayed()
        assertFalse(dispatcher.writes.any { it.first.contains("/visual/") })
        screenshot("23-call-camera", "live-call-screen")
        compose.waitUntil(10000) { model.latestCallCameraFrame() != null }
        val firstFrame = requireNotNull(model.latestCallCameraFrame())
        assertTrue("Actual camera JPEG", firstFrame[0] == 0xff.toByte() && firstFrame[1] == 0xd8.toByte())
        val cameraStart = model.call.state.value.startedAtMs
        compose.onNodeWithTag("live-camera-flip").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("live-camera-ready").fetchSemanticsNodes().isNotEmpty() }
        // Sampling is paced by the Compose coroutine clock; advance its retry after lens setup.
        compose.mainClock.advanceTimeBy(3500)
        // Speaking carries a current camera image without a second manual visual message.
        compose.runOnUiThread { fakeCallRecording = null; model.call.setMicrophoneMuted(false) }
        compose.waitUntil(10000) { fakeCallRecording != null }
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeBy(1000) // The sampling LaunchedEffect uses the Compose clock.
            model.latestCallCameraFrame() != null
        }
        compose.runOnUiThread { requireNotNull(fakeCallRecording).complete(ByteArray(3200)) }
        compose.waitUntil(10000) { dispatcher.writes.any { it.first == "/v1/visual/direct/messages" } }
        val visual = dispatcher.writes.single { it.first == "/v1/visual/direct/messages" }.second
        assertEquals("CAMERA", visual.getAsJsonArray("visual_frames")[0].asJsonObject.text("source"))
        assertEquals("看看我现在的画面", visual.text("message"))
        assertEquals(cameraStart, model.call.state.value.startedAtMs)
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
        assertFalse(dispatcher.writes.any { it.first == "/v1/visual/direct/observations" })
        tap("live-call-camera-toggle")
        compose.waitUntil(10000) { model.latestCallCameraFrame() == null }
        compose.onNodeWithTag("live-camera-preview").assertDoesNotExist()
        compose.onNodeWithTag("live-call-character-inset").assertDoesNotExist()
        tap("live-call-camera-toggle")
        compose.waitUntil(10000) { compose.onAllNodesWithTag("live-camera-ready").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(3500)
        compose.waitUntil(10000) { model.latestCallCameraFrame() != null }
        tap("live-call-hangup")
        compose.waitUntil(10000) { !model.call.state.value.active && model.latestCallCameraFrame() == null }
    }
    @Test fun reopeningStickersRefreshesPcRemovalWithoutDeletingHistory() {
        tap("live-character-rin")
        compose.waitUntil(10000) { "stickers" !in model.state.value.busy && "history" !in model.state.value.busy &&
            model.state.value.messages.isNotEmpty() && model.state.value.stickers.any { it.text("id") == "wave" } }
        val history = model.state.value.messages.map { it.text("id") }
        val before = dispatcher.reads.count { it == "/v1/stickers" }
        dispatcher.stickerRemoved = true
        tap("live-stickers-open")
        compose.waitUntil(10000) { dispatcher.reads.count { it == "/v1/stickers" } > before && "stickers" !in model.state.value.busy }
        assertFalse(model.state.value.stickers.any { it.text("id") == "wave" })
        assertEquals(history, model.state.value.messages.map { it.text("id") })
        assertTrue(dispatcher.writes.isEmpty())
    }
    @Test fun minimizedExplicitCallHasReturnControlWithoutNewSession() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            "com.charactermemory.android", android.Manifest.permission.RECORD_AUDIO)
        dispatcher.keepCallStreamOpen = true
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        compose.runOnUiThread { model.grantCallPermission(requireNotNull(model.requestCall()), true) }
        compose.waitUntil(10000) { fakeCallRecording != null }
        val start = model.call.state.value.startedAtMs
        val recording = fakeCallRecording
        tap("live-back")
        assertTrue(model.call.state.value.active)
        compose.onNodeWithTag("live-call-status", useUnmergedTree = true).assertTextContains("正在倾听")
        compose.onNodeWithTag("live-call-name", useUnmergedTree = true).assertTextContains("Rin")
        compose.onNodeWithTag("live-call-duration", useUnmergedTree = true).assertIsDisplayed()
        screenshot("40-minimized-call-home", "live-call-return")
        for (page in listOf(LivePage.SPACE, LivePage.RSS, LivePage.SETTINGS, LivePage.CHARACTER)) {
            compose.runOnUiThread { model.show(page) }
            compose.onNodeWithTag("live-call-return").assertIsDisplayed()
            compose.onNodeWithTag("live-call-status", useUnmergedTree = true).assertTextContains("正在倾听")
            assertEquals(start, model.call.state.value.startedAtMs)
            assertSame(recording, fakeCallRecording)
        }
        screenshot("41-minimized-call-create", "live-call-return")
        compose.runOnUiThread { model.call.setMicrophoneMuted(true) }
        compose.onNodeWithTag("live-call-status", useUnmergedTree = true).assertTextContains("麦克风已静音", substring = true)
        compose.runOnUiThread { model.call.setTransportAvailable(false) }
        compose.onNodeWithTag("live-call-status", useUnmergedTree = true).assertTextContains("正在重连", substring = true)
        assertEquals(start, model.call.state.value.startedAtMs)
        compose.onNodeWithTag("live-call-return").assertIsDisplayed().performClick()
        compose.onNodeWithTag("live-call-screen").assertIsDisplayed()
        assertEquals(start, model.call.state.value.startedAtMs)
        tap("live-call-hangup")
        compose.runOnUiThread { model.show(LivePage.HOME) }
        compose.onNodeWithTag("live-call-return").assertDoesNotExist()
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
    }
    @Test fun notificationHangupReleasesTheSameCallAndRecorder() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission("com.charactermemory.android", android.Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(
            "com.charactermemory.android", android.Manifest.permission.POST_NOTIFICATIONS)
        dispatcher.keepCallStreamOpen = true
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        compose.runOnUiThread { model.grantCallPermission(requireNotNull(model.requestCall()), true) }
        val manager = compose.activity.getSystemService(android.app.NotificationManager::class.java)
        val hangup = awaitCallHangupAction(manager)
        compose.runOnUiThread { hangup.send() }
        compose.waitUntil(10000) { !model.call.state.value.active && callCancellations.get() > 0 }
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
    }

    @Test fun screenShareInCallSurvivesNetworkPauseAndHomeThenHangupReleasesProjection() {
        dispatcher.showCallPortrait = true
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            "com.charactermemory.android", android.Manifest.permission.RECORD_AUDIO)
        dispatcher.keepCallStreamOpen = true
        dispatcher.visualConfigFailures.set(3)
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        compose.runOnUiThread { model.grantCallPermission(requireNotNull(model.requestCall()), true) }
        compose.waitUntil(10000) { fakeCallRecording != null }
        tap("live-call-mic-toggle")
        tap("live-call-screen-toggle")
        screenshot("26-call-share-selector", "live-call-share-confirm")
        tap("live-call-share-confirm")
        // Advance Compose's test clock so the counter-triggered launcher runs before native UI polling.
        compose.waitForIdle()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val singleApp = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("A single app")), 2000)
        if (singleApp != null) {
            singleApp.click()
            val entireScreen = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Entire screen")), 3000)
            assertNotNull("System entire-screen choice missing", entireScreen)
            screenshotDisplay("28-call-system-share-selector")
            entireScreen!!.click()
        } else screenshotDisplay("28-call-system-share-selector")
        val start = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(
            java.util.regex.Pattern.compile("Start now|Start recording|Start|立即开始|开始"))), 5000)
        assertNotNull("System capture confirmation missing", start)
        start!!.click()
        compose.waitUntil(10000) { com.charactermemory.android.screen.ScreenShareStatus.state.value.uploadState == "PAUSED_NETWORK" }
        assertTrue(com.charactermemory.android.screen.ScreenShareStatus.state.value.active)
        device.pressHome(); Thread.sleep(1000)
        assertTrue(model.call.state.value.active)
        assertTrue(com.charactermemory.android.screen.ScreenShareStatus.state.value.active)
        device.executeShellCommand("am start -n com.charactermemory.android/.MainActivity")
        compose.waitUntil(20000) { com.charactermemory.android.screen.ScreenShareStatus.state.value.uploadState == "AUTO_DISABLED" }
        assertTrue(com.charactermemory.android.screen.ScreenShareStatus.state.value.active)
        tap("live-call-screen-preview-toggle")
        waitTag("live-call-screen-preview")
        screenshot("24-call-screen-shared", "live-call-screen")
        tap("live-call-more"); tap("live-call-screen-ask")
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(10000) { dispatcher.writes.any { it.first == "/v1/visual/direct/messages" } }
        assertEquals(1, dispatcher.writes.count { it.first == "/v1/visual/direct/messages" })
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
        tap("live-call-hangup")
        compose.waitUntil(10000) { !com.charactermemory.android.screen.ScreenShareStatus.state.value.active }
        assertEquals("CALL_HANGUP", com.charactermemory.android.screen.ScreenShareStatus.state.value.stopReason)
    }

    @Test fun explicitCallRemainsActiveWhenChatUiBackgrounds() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            "com.charactermemory.android", android.Manifest.permission.RECORD_AUDIO)
        dispatcher.keepCallStreamOpen = true
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        compose.runOnUiThread { model.grantCallPermission(requireNotNull(model.requestCall()), true) }
        compose.waitUntil(10000) { fakeCallRecording != null }
        compose.runOnUiThread { model.deactivate() }
        assertTrue("Explicit call must outlive the UI foreground", model.call.state.value.active)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        Thread.sleep(1000)
        assertTrue("Actual home navigation must preserve the explicit call", model.call.state.value.active)
        assertTrue(device.executeShellCommand("dumpsys activity services com.charactermemory.android").contains("CallSessionService"))
        device.executeShellCommand("am start -n com.charactermemory.android/.MainActivity")
        compose.runOnUiThread { model.activate(); model.call.end() }
    }

    @Test fun nativePipKeepsSessionAndMuteActionDoesNotRestoreFullScreen() {
        dispatcher.showCallPortrait = true
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            "com.charactermemory.android", android.Manifest.permission.RECORD_AUDIO)
        dispatcher.keepCallStreamOpen = true
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        compose.waitUntil(10000) { !model.state.value.avatars["rin"].isNullOrBlank() }
        compose.waitForIdle()
        compose.runOnUiThread { model.grantCallPermission(requireNotNull(model.requestCall()), true) }
        compose.waitUntil(10000) { fakeCallRecording != null }
        val activity = compose.activity
        val started = model.call.state.value.startedAtMs
        tap("live-call-pip")
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!activity.isInPictureInPictureMode && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Actual native PiP must open", activity.isInPictureInPictureMode)
        // The framework flag changes before the native transition callback; draw frames until it arrives.
        val renderDeadline = SystemClock.uptimeMillis() + 10000
        while (!activity.isCallPipMode && SystemClock.uptimeMillis() < renderDeadline) {
            compose.mainClock.advanceTimeBy(100)
            SystemClock.sleep(100)
        }
        assertTrue("Native PiP transition callback must arrive", activity.isCallPipMode)
        compose.mainClock.advanceTimeBy(1000)
        SystemClock.sleep(1000)
        screenshotDisplay("27-call-pip")
        val actionIntent = Intent(activity, CallPipActionReceiver::class.java)
            .setAction(CallPipIntent.TOGGLE_MIC)
            .setData(android.net.Uri.parse("character-memory://call/$started/microphone"))
        val nativeAction = android.app.PendingIntent.getBroadcast(activity, CallPipIntent.MIC_REQUEST_CODE,
            actionIntent, android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
        assertNotNull("Use the real registered PiP action", nativeAction)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val name = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Rin")), 5000)
        assertNotNull("Native PiP character name must be visible", name)
        requireNotNull(name).click()
        val mute = device.wait(androidx.test.uiautomator.Until.findObject(
            androidx.test.uiautomator.By.desc("静音通话麦克风")), 5000)
        assertNotNull("System PiP menu must expose microphone action", mute)
        assertNotNull("System PiP menu must expose hangup action", device.findObject(
            androidx.test.uiautomator.By.desc("结束与Rin的通话")))
        screenshotDisplay("29-call-pip-actions")
        // PiP refreshes its accessibility tree while the screenshot is captured.
        // Reacquire the actual system control; retry only a stale, unclicked node.
        val clickDeadline = SystemClock.uptimeMillis() + 5000
        var clicked = false
        while (!clicked && SystemClock.uptimeMillis() < clickDeadline) {
            try {
                device.findObject(androidx.test.uiautomator.By.desc("静音通话麦克风"))?.let {
                    it.click(); clicked = true
                }
                // A slow screenshot can outlast the system menu's visibility.
                // Reopen the real PiP menu before reacquiring its action.
                if (!clicked && activity.isInPictureInPictureMode) {
                    device.findObject(androidx.test.uiautomator.By.text("Rin"))?.click()
                }
            } catch (_: androidx.test.uiautomator.StaleObjectException) { }
            if (!clicked) SystemClock.sleep(100)
        }
        assertTrue("System PiP microphone action must be clicked", clicked)
        val muteDeadline = SystemClock.uptimeMillis() + 10000
        while (!model.call.state.value.microphoneMuted && SystemClock.uptimeMillis() < muteDeadline) SystemClock.sleep(100)
        assertTrue(model.call.state.value.microphoneMuted)
        assertTrue("Muting must remain in native PiP", activity.isInPictureInPictureMode)
        assertEquals(started, model.call.state.value.startedAtMs)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("am start -n com.charactermemory.android/.MainActivity --activity-single-top")
        compose.waitUntil(10000) { !activity.isInPictureInPictureMode }
        compose.onNodeWithTag("live-call-screen").assertIsDisplayed()
        assertEquals(started, model.call.state.value.startedAtMs)
        assertTrue(model.call.state.value.microphoneMuted)
        tap("live-call-hangup")
    }

    @Test fun previousNativeNotificationCannotHangUpRedialedCall() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission("com.charactermemory.android", android.Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission("com.charactermemory.android", android.Manifest.permission.POST_NOTIFICATIONS)
        dispatcher.keepCallStreamOpen = true
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        compose.runOnUiThread { model.grantCallPermission(requireNotNull(model.requestCall()), true) }
        val manager = compose.activity.getSystemService(android.app.NotificationManager::class.java)
        val oldHangup = awaitCallHangupAction(manager)
        val oldStarted = model.call.state.value.startedAtMs
        tap("live-call-hangup")
        var ticket: Long? = null
        compose.waitUntil(10000) {
            compose.runOnUiThread { if (ticket == null) ticket = model.requestCall() }
            ticket != null
        }
        compose.runOnUiThread { model.grantCallPermission(requireNotNull(ticket), true) }
        compose.waitUntil(10000) { com.charactermemory.android.audio.CallSessionService.owns(model.call) }
        val currentStarted = model.call.state.value.startedAtMs
        assertNotEquals(oldStarted, currentStarted)
        oldHangup.send()
        SystemClock.sleep(500)
        assertTrue("Old actual notification must not hang up the new call", model.call.state.value.active)
        assertEquals(currentStarted, model.call.state.value.startedAtMs)
        assertTrue(com.charactermemory.android.audio.CallSessionService.owns(model.call))
        tap("live-call-hangup")
    }

    @Test fun callConnectingCanCancelWithoutSendingAnyMessage() {
        dispatcher.showCallPortrait = true
        dispatcher.keepCallStreamOpen = true
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { assertNotNull(model.requestCall()) }
            compose.mainClock.advanceTimeBy(32)
            val wave = compose.onNodeWithTag("live-call-connecting-wave")
            val before = wave.captureToImage().let { image ->
                IntArray(image.width * image.height).also { image.readPixels(it) }
            }
            compose.mainClock.advanceTimeBy(350)
            val after = wave.captureToImage().let { image ->
                IntArray(image.width * image.height).also { image.readPixels(it) }
            }
            val foreground = com.charactermemory.android.live.LivePurple.toArgb()
            val beforeCount = before.count { it == foreground }
            val afterCount = after.count { it == foreground }
            assertTrue("Waiting bars must be rendered", beforeCount > 0)
            assertTrue("Waiting bar height must actually animate", afterCount > beforeCount)
            android.util.Log.i("CallVisualAcceptance", "waiting_wave_foreground_pixels=$beforeCount->$afterCount")
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithTag("live-call-connecting").assertIsDisplayed()
        compose.onNodeWithTag("live-call-connecting-avatar").assertIsDisplayed()
        compose.onNodeWithTag("live-call-connecting-wave").assertIsDisplayed()
        compose.onNodeWithTag("live-call-camera-toggle").assertDoesNotExist()
        compose.onNodeWithTag("live-call-screen-toggle").assertDoesNotExist()
        compose.onNodeWithTag("live-call-subtitles").assertDoesNotExist()
        screenshot("25-call-connecting", "live-call-screen")
        tap("live-call-hangup")
        assertFalse(model.call.state.value.active)
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" || it.first == "/v1/visual/direct/messages" })
    }

    @Test fun callSurfaceShowsRealControlsAndMuteDoesNotHangup() {
        dispatcher.showCallPortrait = true
        dispatcher.keepCallStreamOpen = true
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        compose.runOnUiThread { model.call.permission(requireNotNull(model.requestCall()), true) }
        compose.waitUntil(10000) { fakeCallRecording != null }
        compose.onNodeWithTag("live-call-screen").assertIsDisplayed()
        compose.onNodeWithTag("live-chat-input").assertDoesNotExist()
        compose.runOnUiThread {
            com.charactermemory.android.screen.ScreenShareStatus.state.value =
                com.charactermemory.android.screen.ScreenShareSnapshot(label = "已停止共享", stopReason = "USER_STOP")
        }
        tap("live-call-mic-toggle")
        compose.waitUntil(10000) { callCancellations.get() > 0 }
        assertTrue(model.call.state.value.active)
        compose.onNodeWithTag("live-call-mic-toggle").assertIsDisplayed()
        tap("live-call-more")
        compose.onNodeWithTag("live-call-camera-toggle").assertIsDisplayed()
        compose.onNodeWithTag("live-call-screen-toggle").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
        compose.onNodeWithTag("live-screen-share-status").assertDoesNotExist()
        screenshot("22-call-muted", "live-call-screen")
        tap("live-call-hangup")
        compose.waitUntil(10000) { !model.call.state.value.active }
    }

    @Test fun callStageReferenceScreensPreserveOneSession() {
        dispatcher.showCallPortrait = true
        dispatcher.keepCallStreamOpen = true
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            "com.charactermemory.android", android.Manifest.permission.RECORD_AUDIO)
        compose.runOnUiThread { model.grantCallPermission(requireNotNull(model.requestCall()), true) }
        compose.waitUntil(10000) { model.call.state.value.phase == "listening" && fakeCallRecording != null }
        compose.waitUntil(10000) { !model.state.value.avatars["rin"].isNullOrBlank() }
        Thread.sleep(1500) // Allow Coil to decode the local fixture before visual capture.
        val started = model.call.state.value.startedAtMs
        val writesBefore = dispatcher.writes.size
        screenshot("30-call-reference-normal", "live-call-screen")
        tap("live-call-more"); tap("live-call-live2d-mode")
        compose.onNodeWithTag("live-call-live2d-renderer").assertIsDisplayed()
        compose.onNodeWithTag("live-call-live2d-placeholder").assertDoesNotExist()
        screenshot("31-call-reference-live2d", "live-call-screen")
        assertEquals(started, model.call.state.value.startedAtMs)
        tap("live-back"); tap("live-call-return")
        compose.onNodeWithTag("live-call-live2d-renderer").assertIsDisplayed()
        assertEquals(started, model.call.state.value.startedAtMs)
        tap("live-call-history"); tap("live-call-history-close")
        assertEquals(started, model.call.state.value.startedAtMs)
        // UI reference fixture: not a hardware MediaProjection pass.
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(17, 27, 45))
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(154, 180, 240); textSize = 44f }
        canvas.drawText("Character Memory", 48f, 120f, paint)
        canvas.drawText("产品设计思路", 48f, 220f, paint)
        repeat(10) { i -> paint.color = android.graphics.Color.rgb(43 + i * 2, 62 + i * 2, 98); canvas.drawRoundRect(48f, 300f + i * 70f, 640f, 342f + i * 70f, 12f, 12f, paint) }
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        compose.runOnUiThread {
            val target = requireNotNull(model.state.value.target)
            com.charactermemory.android.screen.ScreenShareStatus.state.value = com.charactermemory.android.screen.ScreenShareSnapshot(
                active = true, characterId = target.id, conversationId = target.conversationId,
                coreUrl = model.state.value.config.coreUrl, latestPreviewJpeg = bytes)
        }
        compose.onNodeWithTag("live-call-character-inset").assertIsDisplayed()
        screenshot("32-call-reference-share", "live-call-screen")
        assertEquals(started, model.call.state.value.startedAtMs)
        assertEquals(writesBefore, dispatcher.writes.size)
        compose.runOnUiThread { com.charactermemory.android.screen.ScreenShareStatus.state.value = com.charactermemory.android.screen.ScreenShareSnapshot() }
        tap("live-call-more"); tap("live-call-avatar-mode")
        assertEquals(started, model.call.state.value.startedAtMs)
        tap("live-call-speaker-toggle")
        assertFalse(LiveAudioPlayback.callSpeakerEnabled)
        tap("live-back"); tap("live-call-return")
        assertFalse(LiveAudioPlayback.callSpeakerEnabled)
        assertEquals(started, model.call.state.value.startedAtMs)
        tap("live-call-speaker-toggle")
        assertTrue(LiveAudioPlayback.callSpeakerEnabled)
        tap("live-call-hangup")
        compose.waitUntil(10000) { !model.call.state.value.active }
    }

    @Test fun callUsesSystemPermissionAndHangupReleasesCaptureWithoutSendingSilence() {
        dispatcher.showCallPortrait = true
        dispatcher.keepCallStreamOpen = true
        tap("live-character-rin")
        compose.waitUntil(10000) { model.state.value.streamStatus == "已连接" }
        tap("live-call-start")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        if (androidx.core.content.ContextCompat.checkSelfPermission(compose.activity, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // AOSP and Google system images use different permission-controller packages.
            val grant = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res(
                java.util.regex.Pattern.compile("[^:]+:id/permission_allow_foreground_only_button"))), 5000)
            assertNotNull("Microphone permission button missing; foreground=${device.currentPackageName}", grant)
            requireNotNull(grant).click()
        }
        compose.waitUntil(10000) { model.call.state.value.phase == "listening" && fakeCallRecording != null }
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            androidx.core.content.ContextCompat.checkSelfPermission(compose.activity, android.Manifest.permission.RECORD_AUDIO))
        screenshot("17-call-listening", "live-voice-input")
        tap("live-call-hangup")
        compose.waitUntil(10000) { model.call.state.value.phase == "idle" && callCancellations.get() > 0 }
        assertFalse(dispatcher.writes.any { it.first == "/v1/chat/messages" })
    }

    @Test fun callTranscriptionWritesOnceThenPlaysCorrectSpeakerAndHangupStopsAudio() {
        dispatcher.showCallPortrait = true
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

    private fun awaitCallHangupAction(manager: android.app.NotificationManager): android.app.PendingIntent {
        var action: android.app.PendingIntent? = null
        compose.waitUntil(10000) {
            action = manager.activeNotifications.firstOrNull { it.id == 4109 }?.notification
                ?.actions?.singleOrNull { it.title.toString() == "挂断" }?.actionIntent
            action != null
        }
        return requireNotNull(action)
    }

    private fun revealSpeech(id: String) {
        waitTag("live-message-bubble-$id")
        compose.onNodeWithTag("live-message-bubble-$id").performTouchInput { longClick() }
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

private fun playbackStateContains(value: String) = androidx.compose.ui.test.SemanticsMatcher("playback state contains $value") {
    it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)?.contains(value) == true
}
