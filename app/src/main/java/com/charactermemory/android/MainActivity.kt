package com.charactermemory.android

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Build
import android.util.Rational
import android.widget.Toast
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.charactermemory.android.live.LiveApp
import com.charactermemory.android.live.LiveViewModel
import com.charactermemory.android.data.ServerConfig
import com.charactermemory.android.audio.VoiceCallState
import com.charactermemory.android.audio.VoiceCallCoordinator

internal val Navy = Color(0xFF091120)
internal val Panel = Color(0xFF151F31)
internal val BluePanel = Color(0xFF1D2B47)
internal val Accent = Color(0xFF79A9FF)
internal val Muted = Color(0xFF9BAFCB)
internal val Pale = Color(0xFFEAF1FF)
internal val Purple = Color(0xFFB09CFF)
internal val Cyan = Color(0xFF76D4E9)

class MainActivity : ComponentActivity() {
    internal var isCallPipMode by mutableStateOf(false)
        private set

    private var attachedCallModel: LiveViewModel? = null
    private var callLeaseForPipDismissal: VoiceCallCoordinator? = null
    private var pipDismissalPending = false
    private val consentInFlight = mutableSetOf<String>()
    private val supportsPictureInPicture by lazy(LazyThreadSafetyMode.NONE) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(this)
            .components { add(coil.decode.SvgDecoder.Factory()) }.build())
        val provisioned = if (BuildConfig.DEBUG && !intent.getBooleanExtra("p1_mock", false)) {
            intent.getStringExtra("initial_core_url")?.let { core ->
                runCatching { ServerConfig.normalize(core, intent.getStringExtra("initial_media_url") ?: "") }.getOrNull()
            }
        } else null
        setContent {
            if (intent.getBooleanExtra("p1_mock", false)) CharacterMemoryPrototype()
            else LiveApp(model = viewModel(factory = LiveViewModel.factory(this, provisioned)))
        }
    }

    internal fun attachCallModel(model: LiveViewModel) {
        attachedCallModel = model
        callLeaseForPipDismissal = model.call
        CallPipActionBridge.attach(this, model)
    }

    internal fun detachCallModel(model: LiveViewModel) {
        if (attachedCallModel === model) attachedCallModel = null
        CallPipActionBridge.detach(model)
    }

    internal fun setCallConsentInFlight(name: String, inFlight: Boolean) {
        if (name.isBlank()) return
        if (inFlight) consentInFlight.add(name) else consentInFlight.remove(name)
        val model = attachedCallModel ?: return
        val state = model.state.value
        updateCallPictureInPicture(
            state.target?.name ?: "人物",
            model.call.state.value,
            state.page == com.charactermemory.android.live.LivePage.CHAT
        )
    }

    internal fun updateCallPictureInPicture(name: String, call: VoiceCallState, isCallScreen: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supportsPictureInPicture) return
        val params = pictureInPictureParams(name, call, isCallScreen)
        runCatching { setPictureInPictureParams(params) }
    }

    internal fun enterCallPictureInPicture(name: String, call: VoiceCallState, isCallScreen: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supportsPictureInPicture) {
            Toast.makeText(this, "此设备不支持画中画，通话会继续；可从最近任务或通话通知返回。", Toast.LENGTH_LONG).show()
            return false
        }
        if (consentInFlight.isNotEmpty()) return false
        val model = attachedCallModel
        val current = model?.call?.state?.value ?: call
        if (!CallPipPolicy.mayEnter(
                systemSupported = true,
                callActive = current.active,
                startedAtMs = current.startedAtMs,
                isCallScreen = isCallScreen
            )
        ) return false

        val params = pictureInPictureParams(name, current, isCallScreen)
        pipDismissalPending = true
        val entered = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
        if (!entered) {
            pipDismissalPending = false
            Toast.makeText(this, "暂时无法进入画中画，通话会继续；可从最近任务或通话通知返回。", Toast.LENGTH_LONG).show()
        }
        return entered
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || isInPictureInPictureMode || !supportsPictureInPicture || consentInFlight.isNotEmpty()) return
        val model = attachedCallModel ?: return
        val state = model.state.value
        enterCallPictureInPicture(
            state.target?.name ?: "人物",
            model.call.state.value,
            state.page == com.charactermemory.android.live.LivePage.CHAT
        )
    }

    private fun pictureInPictureParams(name: String, call: VoiceCallState, isCallScreen: Boolean): PictureInPictureParams {
        val mayEnter = CallPipPolicy.mayEnter(true, call.active, call.startedAtMs, isCallScreen)
        val actions = if (mayEnter) {
            listOf(
                remoteAction(
                    requestCode = CallPipIntent.MIC_REQUEST_CODE,
                    action = CallPipIntent.TOGGLE_MIC,
                    icon = if (call.microphoneMuted) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode,
                    title = if (call.microphoneMuted) "开启麦克风" else "静音麦克风",
                    description = if (call.microphoneMuted) "开启通话麦克风" else "静音通话麦克风",
                    startedAtMs = call.startedAtMs
                ),
                remoteAction(
                    requestCode = CallPipIntent.HANGUP_REQUEST_CODE,
                    action = CallPipIntent.HANG_UP,
                    icon = android.R.drawable.ic_menu_close_clear_cancel,
                    title = "挂断",
                    description = "结束与${name.ifBlank { "人物" }}的通话",
                    startedAtMs = call.startedAtMs
                )
            )
        } else emptyList()
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(3, 4))
            .setActions(actions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(CallPipPolicy.mayAutoEnter(
                systemSupported = true,
                callActive = call.active,
                startedAtMs = call.startedAtMs,
                isCallScreen = isCallScreen,
                consentInFlight = consentInFlight.size
            ))
        }
        return builder.build()
    }

    private fun remoteAction(
        requestCode: Int,
        action: String,
        icon: Int,
        title: String,
        description: String,
        startedAtMs: Long
    ): RemoteAction {
        val intent = Intent(this, CallPipActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("character-memory://call/$startedAtMs/${if (action == CallPipIntent.TOGGLE_MIC) "microphone" else "hangup"}"))
            .putExtra(CallPipIntent.EXTRA_STARTED_AT_MS, startedAtMs)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(Icon.createWithResource(this, icon), title, description, pendingIntent)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isCallPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) pipDismissalPending = true
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) pipDismissalPending = false
    }

    override fun onDestroy() {
        val callLease = callLeaseForPipDismissal
        val active = callLease?.state?.value?.active == true
        if (CallPipPolicy.shouldEndOnDismissal(
                wasInPictureInPicture = pipDismissalPending,
                activityFinishing = isFinishing,
                changingConfigurations = isChangingConfigurations,
                callActive = active
            )
        ) callLease?.end("画中画已关闭，通话已结束")
        attachedCallModel = null
        callLeaseForPipDismissal = null
        CallPipActionBridge.detachActivity(this)
        super.onDestroy()
    }
}

internal object CallPipActionBridge {
    @Volatile private var activity: MainActivity? = null
    @Volatile private var model: LiveViewModel? = null

    fun attach(activity: MainActivity, model: LiveViewModel) {
        this.activity = activity
        this.model = model
    }

    fun detach(model: LiveViewModel) {
        if (this.model === model) {
            this.model = null
            activity = null
        }
    }

    fun detachActivity(activity: MainActivity) {
        if (this.activity === activity) {
            this.activity = null
            model = null
        }
    }

    fun dispatch(intent: Intent) {
        val currentModel = model ?: return
        val call = currentModel.call.state.value
        val expectedStart = intent.getLongExtra(CallPipIntent.EXTRA_STARTED_AT_MS, 0L)
        if (!CallPipPolicy.matchesCallAction(call.active, expectedStart, call.startedAtMs)) return
        when (intent.action) {
            CallPipIntent.TOGGLE_MIC -> currentModel.call.setMicrophoneMuted(!call.microphoneMuted)
            CallPipIntent.HANG_UP -> {
                currentModel.call.end()
                activity?.let { if (it.isInPictureInPictureMode) it.finish() }
            }
        }
    }
}

internal class CallPipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent != null) CallPipActionBridge.dispatch(intent)
    }
}

internal object CallPipIntent {
    const val TOGGLE_MIC = "com.charactermemory.android.call.PIP_TOGGLE_MIC"
    const val HANG_UP = "com.charactermemory.android.call.PIP_HANG_UP"
    const val EXTRA_STARTED_AT_MS = "com.charactermemory.android.call.PIP_STARTED_AT_MS"
    const val MIC_REQUEST_CODE = 0xCA11
    const val HANGUP_REQUEST_CODE = 0xCA12
}

/** Pure call/PiP lifecycle rules, kept separate from Android entry points for JVM tests. */
internal object CallPipPolicy {
    fun mayEnter(systemSupported: Boolean, callActive: Boolean, startedAtMs: Long, isCallScreen: Boolean): Boolean =
        systemSupported && callActive && startedAtMs > 0 && isCallScreen

    fun matchesCallAction(callActive: Boolean, expectedStartedAtMs: Long, currentStartedAtMs: Long): Boolean =
        callActive && expectedStartedAtMs > 0 && expectedStartedAtMs == currentStartedAtMs

    fun mayAutoEnter(
        systemSupported: Boolean,
        callActive: Boolean,
        startedAtMs: Long,
        isCallScreen: Boolean,
        consentInFlight: Int
    ): Boolean = mayEnter(systemSupported, callActive, startedAtMs, isCallScreen) && consentInFlight == 0

    fun shouldEndOnDismissal(
        wasInPictureInPicture: Boolean,
        activityFinishing: Boolean,
        changingConfigurations: Boolean,
        callActive: Boolean
    ): Boolean = wasInPictureInPicture && activityFinishing && !changingConfigurations && callActive
}

@Composable
fun CharacterMemoryPrototype(model: PrototypeViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val colors = darkColorScheme(
        primary = Accent, onPrimary = Navy,
        secondary = Purple, background = Navy,
        surface = Panel, onSurface = Pale
    )
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = Navy,
            topBar = {
                Header(
                    title = when (state.screen) {
                        Screen.HOME -> "Character Memory"
                        Screen.CHAT -> MockContent.characters.firstOrNull { it.id == state.selectedCharacterId }?.name ?: "聊天"
                        Screen.GROUP_CHAT -> state.selectedGroupName
                        Screen.CHARACTER -> "创建新人物"
                        Screen.GROUP -> "创建群聊"
                        Screen.CALL -> "通话"
                        Screen.SPACE -> "空间"
                        Screen.SETTINGS -> "基础设置"
                    },
                    isRoot = state.screen in setOf(Screen.HOME, Screen.SPACE, Screen.SETTINGS),
                    back = model::back
                )
            },
            bottomBar = {
                if (state.screen in setOf(Screen.HOME, Screen.SPACE, Screen.SETTINGS)) {
                    NavigationBar(containerColor = Color(0xFF111B2C)) {
                        listOf(
                            Triple(Screen.HOME, "聊天", "◉"),
                            Triple(Screen.SPACE, "空间", "◎"),
                            Triple(Screen.SETTINGS, "设置", "⚙")
                        ).forEach { (destination, label, symbol) ->
                            NavigationBarItem(
                                selected = state.screen == destination,
                                onClick = { model.show(destination) },
                                icon = { Text(symbol, fontSize = 22.sp) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("tab-" + destination.name.lowercase())
                            )
                        }
                    }
                }
            }
        ) { inner ->
            Box(
                Modifier.fillMaxSize().padding(inner).background(
                    Brush.verticalGradient(listOf(Navy, Color(0xFF0C1428), Navy))
                )
            ) {
                when (state.screen) {
                    Screen.HOME -> HomeScreen(model)
                    Screen.CHAT -> ChatScreen(state, model)
                    Screen.GROUP_CHAT -> GroupChatScreen(state, model)
                    Screen.CHARACTER -> CreateCharacterScreen()
                    Screen.GROUP -> CreateGroupScreen()
                    Screen.CALL -> CallScreen(state, model)
                    Screen.SPACE -> SpaceScreen(state, model)
                    Screen.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
internal fun Header(title: String, isRoot: Boolean, back: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Navy)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .height(62.dp).padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isRoot) {
            TextButton(onClick = back, modifier = Modifier.testTag("nav-back")) {
                Text("‹", fontSize = 29.sp, color = Pale)
            }
        }
        Text(title, color = Pale, fontSize = if (isRoot) 22.sp else 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("prototype-header-title"))
        Text("MOCK", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("prototype-header-mock")
                .background(BluePanel, RoundedCornerShape(7.dp)).padding(7.dp))
    }
}

@Composable
internal fun Avatar(label: String, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(tint, Color(0xFF293B65)))),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, Color(0xFF263956))
    ) { content() }
}

@Composable
internal fun SectionHeading(title: String, detail: String? = null) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(title, color = Pale, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        if (detail != null) Text(detail, color = Muted, fontSize = 12.sp)
    }
}

@Composable
internal fun DemoBanner(text: String) {
    Text(text, fontSize = 12.sp, color = Cyan, modifier = Modifier.fillMaxWidth()
        .background(Color(0xFF142A3C), RoundedCornerShape(9.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp))
}
