package com.charactermemory.android

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1 screenshots come from an ACTUAL running emulator test, not from generated
 * artwork or static SVG. All files remain in target app external files/Pictures/p1.
 */
@RunWith(AndroidJUnit4::class)
class PrototypeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pictures = requireNotNull(context.getExternalFilesDir("Pictures"))
        val directory = File(pictures, "p1")
        check(directory.exists() || directory.mkdirs())
        FileOutputStream(File(directory, name + ".png")).use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun screenNavigationAndVisualEvidence() {
        compose.onNodeWithTag("screen-home").assertExists()
        screenshot("01-chat-list")

        compose.onNodeWithTag("home-create-character").performClick()
        compose.onNodeWithTag("screen-character").assertExists()
        compose.onNodeWithTag("character-description").performTextInput("喜欢摄影、旅行和咖啡的朋友")
        compose.onNodeWithTag("character-preview").assertIsEnabled().performClick()
        compose.onNodeWithTag("character-preview-card").assertExists()
        screenshot("03-character-create")

        compose.onNodeWithTag("nav-back").performClick()
        compose.onNodeWithTag("home-create-group").performClick()
        compose.onNodeWithTag("group-description").performTextInput("喜欢二次元、技术和旅行的朋友小队")
        compose.onNodeWithTag("group-preview").assertIsEnabled().performClick()
        screenshot("04-group-create")

        compose.onNodeWithTag("nav-back").performClick()
        compose.onNodeWithTag("character-rin").performClick()
        compose.onNodeWithTag("screen-chat").assertExists()
        screenshot("02-direct-chat")
        compose.onNodeWithTag("chat-open-call").performClick()
        compose.onNodeWithTag("screen-call").assertExists()
        screenshot("05-call-mock")

        compose.onNodeWithTag("call-end").performClick()
        compose.onNodeWithTag("nav-back").performClick()
        compose.onNodeWithTag("tab-space").performClick()
        compose.onNodeWithTag("screen-space").assertExists()
        screenshot("06-space-feed")

        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("screen-settings").assertExists()
        screenshot("07-settings")
    }

    @Test fun localChatAndSpaceActionsRemainInteractive() {
        compose.onNodeWithTag("character-rin").performClick()
        compose.onNodeWithTag("chat-input").performTextInput("这是本地测试，不应调用后端")
        compose.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        compose.onNodeWithTag("screen-chat").assertExists()
        compose.onNodeWithTag("nav-back").performClick()
        compose.onNodeWithTag("tab-space").performClick()
        compose.onNodeWithTag("space-like-p1").performClick()
        compose.onNodeWithTag("screen-space").assertExists()
    }

    @Test fun callControlsAreMockOnly() {
        compose.onNodeWithTag("character-rin").performClick()
        compose.onNodeWithTag("chat-open-call").performClick()
        compose.onNodeWithTag("call-mic-demo").performClick()
        compose.onNodeWithTag("call-camera-demo").performClick()
        compose.onNodeWithTag("call-screen-demo").performClick()
        compose.onNodeWithTag("call-end").performClick()
        compose.onNodeWithTag("screen-chat").assertExists()
    }
}
