package com.charactermemory.android

import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.charactermemory.android.live.PersistedVoiceMessageContent
import com.charactermemory.android.live.LiveMuted
import com.charactermemory.android.live.LiveNavy
import com.charactermemory.android.live.LivePale
import com.charactermemory.android.live.LivePanel
import com.google.gson.JsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Self-contained emulator fixture for persisted VOICE_MESSAGE UI; it never contacts Core or Media. */
@RunWith(AndroidJUnit4::class)
class VoiceMessageUiTest {
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

    private val tag = "voice-first"
    private val ownerKey = "voice-first-ui-test"
    private lateinit var lifecycleOwner: TestLifecycleOwner
    private lateinit var wavFile: File
    private val playTag get() = "$tag-play"

    @Before fun prepareLocalAudioFixture() {
        lifecycleOwner = TestLifecycleOwner()
        wavFile = writeSilentWav(File(compose.activity.cacheDir, "voice-${UUID.randomUUID()}.wav"))
        compose.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    @After fun releaseLocalAudioFixture() {
        compose.runOnUiThread {
            if (lifecycleOwner.registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            compose.activity.setContent { }
        }
        compose.waitForIdle()
        wavFile.delete()
    }

    @Test fun pendingKeepsTranscriptVisibleAndHasNoPlaybackControl() {
        render(message("pending"), "")

        compose.onNodeWithTag("$tag-content").assertIsDisplayed().assertTextContains("persisted words", substring = true)
        compose.onNodeWithTag("$tag-status").assertTextContains("语音生成中", substring = true)
        compose.onNodeWithTag(playTag).assertDoesNotExist()
    }

    @Test fun readyWithAssetShowsExplicitPlayActionWithoutStartingOnComposition() {
        render(message("ready", mediaId = "asset-1"), wavFile.toURI().toString())

        compose.onNodeWithTag("$tag-content").assertIsDisplayed().assertTextContains("persisted words", substring = true)
        compose.onNodeWithTag("$tag-status").assertTextContains("语音已就绪", substring = true)
        compose.onNodeWithTag(playTag).assertIsDisplayed().assertTextContains("播放语音", substring = true)
        compose.onNodeWithTag(playTag).assertTextContains("· 播放", substring = true)
    }

    @Test fun readyWithoutAssetIdKeepsTranscriptAndExplainsUnavailableAudio() {
        render(message("ready"), "")

        compose.onNodeWithTag("$tag-content").assertIsDisplayed().assertTextContains("persisted words", substring = true)
        compose.onNodeWithTag("$tag-status").assertTextContains("语音资源不可用", substring = true)
        compose.onNodeWithTag(playTag).assertDoesNotExist()
    }

    @Test fun failedVoiceKeepsTranscriptAndServerErrorVisible() {
        render(message("failed", error = "synthesis unavailable"), "")

        compose.onNodeWithTag("$tag-content").assertIsDisplayed().assertTextContains("persisted words", substring = true)
        compose.onNodeWithTag("$tag-status").assertTextContains("语音生成失败", substring = true)
        compose.onNodeWithTag("$tag-error").assertIsDisplayed().assertTextContains("synthesis unavailable", substring = true)
        compose.onNodeWithTag(playTag).assertDoesNotExist()
    }

    @Test fun unknownVoiceStateKeepsTranscriptWithoutInventingAnAudioAction() {
        render(message("processing", mediaId = "asset-1"), "")

        compose.onNodeWithTag("$tag-content").assertIsDisplayed().assertTextContains("persisted words", substring = true)
        compose.onNodeWithTag("$tag-status").assertTextContains("语音状态未知", substring = true)
        compose.onNodeWithTag(playTag).assertDoesNotExist()
    }

    @Test fun explicitTapStartsPlaybackAndOnStopReleasesItForAnExplicitRetry() {
        render(message("ready", mediaId = "asset-1"), wavFile.toURI().toString())
        compose.onNodeWithTag(playTag).performClick()
        waitForPlayerLabel("停止")

        compose.runOnUiThread { lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        compose.waitForIdle()
        compose.onNodeWithTag(playTag).assertTextContains("· 播放", substring = true)

        compose.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.onNodeWithTag(playTag).performClick()
        waitForPlayerLabel("停止")
    }

    @Test fun failedAssetReadStaysVisibleAndCanBeRetriedOnlyByAnotherTap() {
        val missingFile = File(compose.activity.cacheDir, "missing-${UUID.randomUUID()}.wav")
        render(message("ready", mediaId = "asset-1"), missingFile.toURI().toString())
        compose.onNodeWithTag(playTag).performClick()
        waitForPlayerLabel("点击重试")
        compose.onNodeWithTag(playTag).assertTextContains("播放失败", substring = true)

        compose.onNodeWithTag(playTag).performClick()
        waitForPlayerLabel("点击重试")
    }

    @Test fun changingPlaybackOwnerReleasesThePreviousSessionPlayer() {
        render(message("ready", mediaId = "asset-1"), wavFile.toURI().toString())
        compose.onNodeWithTag(playTag).performClick()
        waitForPlayerLabel("停止")

        render(message("ready", mediaId = "asset-1"), wavFile.toURI().toString(), "$ownerKey-next")
        compose.onNodeWithTag(playTag).assertTextContains("· 播放", substring = true)
        compose.onNodeWithTag(playTag).performClick()
        waitForPlayerLabel("停止")
    }

    @Test fun capturesPendingReadyFailedAndMissingIdStates() {
        renderStateGallery()
        compose.onNodeWithTag("voice-gallery-pending-status").assertTextContains("语音生成中", substring = true)
        compose.onNodeWithTag("voice-gallery-ready-status").assertTextContains("语音已就绪", substring = true)
        compose.onNodeWithTag("voice-gallery-missing-status").assertTextContains("语音资源不可用", substring = true)
        compose.onNodeWithTag("voice-gallery-failed-status").assertTextContains("语音生成失败", substring = true)
        captureScreenshot("p2-14-voice-states.png")
    }

    @Test fun capturesVisiblePlaybackFailureAndExplicitRetry() {
        val missingFile = File(compose.activity.cacheDir, "missing-${UUID.randomUUID()}.wav")
        render(message("ready", mediaId = "asset-1"), missingFile.toURI().toString())
        compose.onNodeWithTag(playTag).performClick()
        waitForPlayerLabel("点击重试")
        compose.onNodeWithTag(playTag).assertTextContains("播放失败", substring = true)
        captureScreenshot("p2-15-voice-playback-error.png")
    }

    private fun render(message: JsonObject, assetUrl: String, playbackOwner: String = ownerKey) {
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    MaterialTheme {
                        PersistedVoiceMessageContent(message, assetUrl, playbackOwner, tag)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun renderStateGallery() {
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    MaterialTheme(colorScheme = darkColorScheme(primary = LiveMuted, background = LiveNavy,
                        surface = LivePanel, onSurface = LivePale)) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(
                                "pending" to message("pending"),
                                "ready" to message("ready", mediaId = "asset-1", error = null),
                                "missing" to message("ready"),
                                "failed" to message("failed", error = "synthesis unavailable")
                            ).forEach { (key, voiceMessage) ->
                                Card(colors = CardDefaults.cardColors(containerColor = LivePanel)) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        PersistedVoiceMessageContent(voiceMessage,
                                            if (key == "ready") wavFile.toURI().toString() else "",
                                            "$ownerKey-$key", "voice-gallery-$key")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun captureScreenshot(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        assertNoSystemAnrDialog()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val resolver = instrumentation.targetContext.contentResolver
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf(fileName, "Pictures/CharacterMemoryP2/"), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                resolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))), null, null)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CharacterMemoryP2/")
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun assertNoSystemAnrDialog() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val markers = listOf("isn't responding", "is not responding", "无响应", "没有响应")
        val visibleMarker = markers.firstOrNull { device.hasObject(By.textContains(it)) }
        assertNull("System ANR dialog is visible ($visibleMarker); voice screenshot evidence is invalid", visibleMarker)
    }

    private fun waitForPlayerLabel(value: String) {
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag(playTag).assertTextContains(value, substring = true) }.isSuccess
        }
        assertTrue("Playback control should show '$value'", runCatching {
            compose.onNodeWithTag(playTag).assertTextContains(value, substring = true)
        }.isSuccess)
    }

    private fun message(status: String, mediaId: String? = null, error: String? = null) = JsonObject().apply {
        addProperty("id", "event-voice-1")
        addProperty("action", "VOICE_MESSAGE")
        addProperty("content", "persisted words")
        addProperty("voice_status", status)
        mediaId?.let { addProperty("voice_media_id", it) }
        error?.let { addProperty("voice_error", it) }
    }

    private fun writeSilentWav(file: File): File {
        val sampleRate = 16_000
        val seconds = 8
        val dataBytes = sampleRate * seconds * 2
        val bytes = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII)); put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(2); putShort(16); put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
            repeat(dataBytes / 2) { putShort(0) }
        }.array()
        file.writeBytes(bytes)
        return file
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
