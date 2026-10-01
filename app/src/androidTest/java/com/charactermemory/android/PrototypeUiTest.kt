package com.charactermemory.android

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
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

    private fun screenshot(name: String, expectedTag: String) {
        // Asserting the Compose semantics tree is not sufficient to synchronize
        // Android SurfaceFlinger frame presentation. Verify the intended route,
        // await idle on both runtimes, and settle one display frame before
        // UiAutomation captures the actual composed DEVICE pixels.
        compose.onNodeWithTag(expectedTag).assertExists()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(450)
        // The image draft is an Android Dialog: activity + dialog are separate
        // Compose semantics roots. Capture the DEVICE display, not onRoot(),
        // so overlays, permission UI and the soft keyboard are visible too.
        val bitmap = requireNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        ) { "Could not capture the Android emulator display" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // App-specific /sdcard/Android/data is removed when connectedAndroidTest
            // uninstalls the target APK. A public MediaStore image survives cleanup.
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "p1-" + name + ".png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CharacterMemoryP1/")
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            requireNotNull(resolver.openOutputStream(uri)).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } else {
            // Legacy devices can run the UI assertions but need a separate
            // screenshot extraction mechanism before instrumented APK uninstall.
            val pictures = requireNotNull(context.getExternalFilesDir("Pictures"))
            val directory = File(pictures, "p1")
            check(directory.exists() || directory.mkdirs())
            FileOutputStream(File(directory, name + ".png")).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    @Test fun screenNavigationAndVisualEvidence() {
        compose.onNodeWithTag("screen-home").assertExists()
        screenshot("01-chat-list", "screen-home")

        compose.onNodeWithTag("home-create-character").performClick()
        compose.onNodeWithTag("screen-character").assertExists()
        compose.onNodeWithTag("character-description").performTextInput("喜欢摄影、旅行和咖啡的朋友")
        compose.onNodeWithTag("character-preview").assertIsEnabled().performClick()
        compose.onNodeWithTag("character-preview-card").assertExists()
        Espresso.closeSoftKeyboard()
        screenshot("03-character-create", "screen-character")

        compose.onNodeWithTag("nav-back").performClick()
        compose.onNodeWithTag("home-create-group").performClick()
        compose.onNodeWithTag("group-description").performTextInput("喜欢二次元、技术和旅行的朋友小队")
        compose.onNodeWithTag("group-preview").assertIsEnabled().performClick()
        Espresso.closeSoftKeyboard()
        screenshot("04-group-create", "screen-group")

        compose.onNodeWithTag("nav-back").performClick()
        compose.onNodeWithTag("character-rin").performClick()
        compose.onNodeWithTag("screen-chat").assertExists()
        screenshot("02-direct-chat", "screen-chat")
        compose.onNodeWithTag("chat-image-preview").performClick()
        compose.onNodeWithTag("image-preview-dialog").assertExists()
        compose.onNodeWithTag("image-prompt").performTextInput("两个人一起喝咖啡的温暖画面")
        Espresso.closeSoftKeyboard()
        screenshot("08-imagegen-draft", "image-preview-dialog")
        compose.onNodeWithTag("image-preview-close").performClick()
        compose.onNodeWithTag("chat-open-call").performClick()
        compose.onNodeWithTag("screen-call").assertExists()
        screenshot("05-call-mock", "screen-call")

        compose.onNodeWithTag("call-end").performClick()
        compose.onNodeWithTag("nav-back").performClick()
        compose.onNodeWithTag("tab-space").performClick()
        compose.onNodeWithTag("screen-space").assertExists()
        screenshot("06-space-feed", "screen-space")

        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("screen-settings").assertExists()
        screenshot("07-settings", "screen-settings")

        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithTag("screen-home")
            .performScrollToNode(hasTestTag("group-0"))
        compose.onNodeWithTag("group-0").performClick()
        compose.onNodeWithTag("screen-group-chat").assertExists()
        compose.onNodeWithTag("group-chat-input").performTextInput("本地群聊消息")
        compose.onNodeWithTag("group-chat-send").assertIsEnabled().performClick()
        Espresso.closeSoftKeyboard()
        screenshot("09-group-chat", "screen-group-chat")
    }

    /**
     * Real emulator evidence: force a compact 720x1280-pixel window, then rotate it
     * to landscape. Buttons must be discoverable and clickable without sideways
     * scrolling; the landscape screen may scroll vertically.
     */
    @Test fun callControlsRemainReachableOnNarrowAndLandscapeDisplays() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            device.executeShellCommand("wm size 720x1280")
            compose.onNodeWithTag("character-rin").performClick()
            compose.onNodeWithTag("chat-open-call").performClick()
            // Regression: the local camera overlay must not cover the
            // compact character's description as it did in the initial capture.
            val caption = compose.onNodeWithTag("call-person-caption")
                .fetchSemanticsNode().boundsInRoot
            val preview = compose.onNodeWithTag("call-local-preview")
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Camera mock preview overlaps the character caption in narrow portrait",
                caption.right <= preview.left || caption.left >= preview.right ||
                    caption.bottom <= preview.top || caption.top >= preview.bottom
            )
            compose.onNodeWithTag("call-mic-demo").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("call-camera-demo").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("call-screen-demo").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("call-end").performScrollTo().assertIsDisplayed()
            screenshot("10-call-narrow", "screen-call")

            device.setOrientationLeft()
            compose.waitForIdle()
            compose.onNodeWithTag("call-mic-demo").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("call-camera-demo").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("call-screen-demo").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("call-end").performScrollTo().assertIsDisplayed()
            screenshot("11-call-landscape", "screen-call")
        } finally {
            device.setOrientationNatural()
            device.executeShellCommand("wm size reset")
            compose.waitForIdle()
        }
    }

    @Test fun compactChatComposerRemainsVisibleWithIme() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val originalKeyboardSetting = device.executeShellCommand(
            "settings get secure show_ime_with_hard_keyboard"
        ).trim()
        try {
            // CI emulators often emulate a hardware keyboard and suppress
            // the soft keyboard. Merely typing text does NOT prove the IME
            // was shown; force it and assert Android's actual IME inset.
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
            device.executeShellCommand("wm size 720x1280")
            compose.onNodeWithTag("character-rin").performClick()
            compose.onNodeWithTag("chat-input").performClick()
            compose.onNodeWithTag("chat-input").performTextInput("输入法展开时聊天输入框仍应可用")
            compose.waitUntil(timeoutMillis = 10_000L) {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            compose.onNodeWithTag("chat-send").assertIsDisplayed()
            val decor = compose.activity.window.decorView
            val imeHeight = ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            assertTrue("The OS reports no keyboard height", imeHeight > 0)
            val keyboardTop = decor.height - imeHeight
            val sendBounds = compose.onNodeWithTag("chat-send")
                .fetchSemanticsNode().boundsInWindow
            val inputBounds = compose.onNodeWithTag("chat-input")
                .fetchSemanticsNode().boundsInWindow
            assertTrue(
                "Chat input hidden by keyboard: ${inputBounds.bottom} > $keyboardTop",
                inputBounds.bottom <= keyboardTop + 8f
            )
            assertTrue(
                "Chat send hidden by keyboard: ${sendBounds.bottom} > $keyboardTop",
                sendBounds.bottom <= keyboardTop + 8f
            )
            screenshot("12-chat-ime", "screen-chat")
        } finally {
            Espresso.closeSoftKeyboard()
            device.executeShellCommand("wm size reset")
            if (originalKeyboardSetting == "0" || originalKeyboardSetting == "1") {
                device.executeShellCommand(
                    "settings put secure show_ime_with_hard_keyboard $originalKeyboardSetting"
                )
            } else {
                device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
            }
            compose.waitForIdle()
        }
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
