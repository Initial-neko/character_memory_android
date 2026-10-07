package com.charactermemory.android

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.charactermemory.android.live.CallCharacterState
import com.charactermemory.android.live.Live2dCallCharacter
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Live2dRealCore

/** Opt-in real Core + GPU probe. Does not start any voice/camera/projection owner. */
@RunWith(AndroidJUnit4::class)
class Live2dRendererGpuTest {
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<Live2dProbeActivity>(Intent(ApplicationProvider.getApplicationContext(), Live2dProbeActivity::class.java)),
        { rule -> var activity: Live2dProbeActivity? = null; rule.scenario.onActivity { activity = it }; requireNotNull(activity) })

    @Test @Live2dRealCore fun realMoc3LoadsAndProducesGpuScreenshots() {
        val args = InstrumentationRegistry.getArguments()
        val core = args.getString("live2d_core_url").orEmpty()
        val character = args.getString("live2d_character_id").orEmpty()
        require(core.isNotBlank() && character.isNotBlank()) { "Real Core URL and character arguments required; fixture CI excludes Live2dRealCore" }
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val evidence = File(app.filesDir, "live2d-renderer-evidence.json")
        evidence.delete()
        val inset = mutableStateOf(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                Box(Modifier.fillMaxSize().background(Color(0xFF102030))) {
                    Live2dCallCharacter(core, character, CallCharacterState("listening", ""), inset.value,
                        if (inset.value) Modifier.size(150.dp, 200.dp) else Modifier.fillMaxSize())
                }
            }
        }
        compose.waitUntil(60000) {
            runCatching { val value = JsonParser.parseString(evidence.readText()).asJsonObject
                value.get("characterId").asString == character && value.get("ready").asBoolean && value.get("realModel").asBoolean &&
                    value.getAsJsonArray("moc3Requests").size() > 0 && value.get("canvasWidth").asInt > 0 && value.get("canvasHeight").asInt > 0
            }.getOrDefault(false)
        }
        val before = JsonParser.parseString(evidence.readText()).asJsonObject.get("animationFrames").asLong
        val folder = File(app.filesDir, "live2d-gpu-probe").apply { mkdirs() }
        File(folder, "ready.json").writeText(evidence.readText())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.takeScreenshot().let { bitmap -> File(folder, "frame-1.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        compose.waitUntil(10000) { runCatching { JsonParser.parseString(evidence.readText()).asJsonObject.get("animationFrames").asLong > before + 20 }.getOrDefault(false) }
        automation.takeScreenshot().let { bitmap -> File(folder, "frame-2.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        File(folder, "running.json").writeText(evidence.readText())
        assertTrue(File(folder, "frame-1.png").length() > 0)
        val full = JsonParser.parseString(evidence.readText()).asJsonObject
        val token = full.getAsJsonObject("context").get("token").asString
        compose.runOnUiThread { inset.value = true }
        compose.waitUntil(15000) {
            runCatching { val current = JsonParser.parseString(evidence.readText()).asJsonObject
                current.get("ready").asBoolean && current.get("canvasWidth").asInt < full.get("canvasWidth").asInt
            }.getOrDefault(false)
        }
        val small = JsonParser.parseString(evidence.readText()).asJsonObject
        assertEquals("Inset must keep the loaded renderer presentation", token, small.getAsJsonObject("context").get("token").asString)
        File(folder, "inset.json").writeText(evidence.readText())
        automation.takeScreenshot().let { bitmap -> File(folder, "inset.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(10000) { runCatching { JsonParser.parseString(evidence.readText()).asJsonObject.get("paused").asBoolean }.getOrDefault(false) }
        File(folder, "paused.json").writeText(evidence.readText())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { inset.value = false }
        compose.waitUntil(15000) { runCatching { val value = JsonParser.parseString(evidence.readText()).asJsonObject
            !value.get("paused").asBoolean && value.get("canvasWidth").asInt == full.get("canvasWidth").asInt
        }.getOrDefault(false) }
        assertEquals(token, JsonParser.parseString(evidence.readText()).asJsonObject.getAsJsonObject("context").get("token").asString)
        File(folder, "resumed.json").writeText(evidence.readText())
        // Pixel review still required: animationFrames measures document RAF, not verified model motion.
    }
}
