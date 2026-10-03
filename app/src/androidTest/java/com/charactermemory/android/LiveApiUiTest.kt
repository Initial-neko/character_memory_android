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

    @Before fun launchSyntheticLiveApp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        core = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false) }
        media = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false) }
        dispatcher = P2FixtureDispatcher()
        core.dispatcher = dispatcher
        media.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setBody("{\"detail\":\"media fixture offline\"}")
        }
        core.start(); media.start()
        config = ServerConfig(core.url("/").toString().trimEnd('/'), media.url("/").toString().trimEnd('/'))
        preferenceName = "p2-ui-" + UUID.randomUUID()
        compose.runOnUiThread {
            model = LiveViewModel(compose.activity, { CoreApi(it, client) }, config, preferenceName)
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
        compose.waitUntil(10_000) { imeBottom > 0 && model.state.value.composeText == "fixture send" }
        closeActualIme()
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
        input.assertTextContains("draft A")

        dispatcher.holdPostRead = true
        dispatcher.commentDelayMs = 1500
        compose.runOnUiThread { model.refreshPost("1") }
        assertTrue(dispatcher.postReadStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        tap("live-space-comment-send-1", true)
        input.performScrollTo().performTextReplacement("draft B")
        compose.waitUntil(10_000) { model.state.value.commentReceipts["1"] != null }
        input.assertTextContains("draft B")
        dispatcher.releasePostRead.countDown()
        compose.waitUntil(10_000) { "space-post-1" !in model.state.value.busy }
        assertTrue(model.state.value.posts.first().getAsJsonArray("comments").any { it.asJsonObject.text("id") == "5" })
        input.assertTextContains("draft B")
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

}
