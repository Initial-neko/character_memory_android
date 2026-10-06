package com.charactermemory.android.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.charactermemory.android.live.LivePage
import com.charactermemory.android.live.LiveState
import com.charactermemory.android.live.LiveViewModel
import com.charactermemory.android.live.LocalCallPresentation
import kotlinx.coroutines.delay

@Composable
fun CameraControls(state: LiveState, model: LiveViewModel, callMode: Boolean = false,
    toggleRequest: Int = 0, hideToggle: Boolean = false, onOpenedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val presentation = LocalCallPresentation.current
    DisposableEffect(Unit) { onDispose { presentation.setConsentInFlight("camera", false) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val target = state.target ?: return
    val call by model.call.state.collectAsState()
    val visualSource by model.callVisualSource.collectAsState()
    var requested by remember { mutableStateOf<Long?>(null) }
    var requestedCallStart by remember { mutableStateOf<Long?>(null) }
    var opened by remember { mutableStateOf<Long?>(null) }
    var front by remember { mutableStateOf(callMode) }
    var capture by remember { mutableStateOf<CameraCapture?>(null) }
    var jpeg by remember { mutableStateOf<ByteArray?>(null) }
    var question by remember { mutableStateOf("请看看镜头中的画面，说说你注意到了什么。") }
    var error by remember { mutableStateOf<String?>(null) }
    var frameReady by remember { mutableStateOf(false) }
    var sourceEpoch by remember { mutableStateOf<Long?>(null) }
    val currentGeneration = model.captureGeneration
    fun close() { capture?.close(); capture = null; opened = null; jpeg = null; frameReady = false
        sourceEpoch?.let { model.endCallCamera(it) }; sourceEpoch = null }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        presentation.setConsentInFlight("camera", false)
        val ticket = requested; requested = null
        val callTicket = requestedCallStart; requestedCallStart = null
        if (ticket != null && ticket == model.captureGeneration && model.state.value.page == LivePage.CHAT &&
            (if (callMode) model.call.state.value.active && model.call.state.value.startedAtMs == callTicket else !model.call.state.value.active)) {
            if (granted) { if (callMode) sourceEpoch = model.beginCallCamera(); opened = ticket }
            else error = "未获得摄像头权限，仍可使用文字聊天。"
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) close() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); close() }
    }
    LaunchedEffect(currentGeneration, call.active) { if (opened != null && (opened != currentGeneration || call.active != callMode)) close() }
    LaunchedEffect(visualSource) {
        if (callMode && opened != null && sourceEpoch != visualSource.epoch) close()
    }
    fun toggle() {
        error = null
        if (opened != null) { close(); return }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (callMode) sourceEpoch = model.beginCallCamera()
            opened = model.captureGeneration
        }
        else { requested = model.captureGeneration; requestedCallStart = if (callMode) call.startedAtMs else null
            presentation.setConsentInFlight("camera", true); permission.launch(Manifest.permission.CAMERA) }
    }
    LaunchedEffect(toggleRequest) { if (toggleRequest > 0 && call.active == callMode && call.phase != "permission" && requested == null) toggle() }
    LaunchedEffect(opened) { onOpenedChange(opened != null) }
    LaunchedEffect(opened, frameReady, sourceEpoch) {
        if (callMode && opened != null && frameReady && sourceEpoch != null) {
            while (true) { capture?.capture(); delay(3000) }
        }
    }
    if (!hideToggle) OutlinedButton(onClick = { toggle() }, enabled = call.active == callMode && call.phase != "permission" && requested == null && "visual" !in state.busy,
        modifier = Modifier.heightIn(min = 48.dp).testTag(if (callMode) "live-call-camera-toggle" else "live-camera-open")) {
        Text(if (opened != null) "关闭摄像头" else if (callMode) "摄像头" else "摄像头画面")
    }
    error?.let { Text(it, modifier = Modifier.testTag("live-camera-error")) }
    val nativePreview: @Composable (Modifier) -> Unit = { previewModifier ->
        AndroidView(factory = { androidContext -> TextureView(androidContext).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    val generation = opened ?: return
                    val epoch = sourceEpoch
                    val callStart = call.startedAtMs
                    capture = CameraCapture(androidContext, this@apply,
                        { message -> error = if (callMode) "视频暂时不可用，请切换镜头或重新开启" else message }, { data ->
                            if (callMode && epoch != null) model.updateCallCameraFrame(generation, epoch, callStart, data)
                            else { jpeg = data; capture?.close(); capture = null }
                        }).also { it.open(front) }
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { frameReady = true }
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { capture?.close(); capture = null; return true }
            }
        } }, modifier = previewModifier.testTag("live-camera-preview"))
    }
    if (callMode) {
        if (opened != null) Box(modifier.background(Color.Black)
            .testTag(if (frameReady) "live-camera-ready" else "live-camera-opening")) {
            nativePreview(Modifier.fillMaxSize())
            TextButton(onClick = {
                frameReady = false; model.clearCallCameraFrame(sourceEpoch); front = !front; capture?.open(front)
            }, enabled = capture != null, modifier = Modifier.align(Alignment.TopStart)
                .padding(top = 80.dp, start = 16.dp).testTag("live-camera-flip")) {
                Text("切换镜头", color = Color.White)
            }
        }
        return
    }
    val previewContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().heightIn(max = if (callMode) 330.dp else 520.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = if (callMode) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val bytes = jpeg
            if (bytes == null) {
                Text(if (frameReady) "摄像头画面已就绪" else "等待摄像头画面…", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag(if (frameReady) "live-camera-ready" else "live-camera-opening"))
                nativePreview(Modifier.fillMaxWidth().height(220.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { frameReady = false; front = !front; capture?.open(front) }, enabled = capture != null,
                        modifier = Modifier.testTag("live-camera-flip")) { Text(if (callMode) "翻转" else "切换前后摄像头") }
                    TextButton(onClick = { capture?.capture() }, enabled = capture != null && frameReady,
                        modifier = Modifier.testTag("live-camera-capture")) { Text("取一帧") }
                }
            } else {
                val bitmap = remember(bytes) { requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) }
                DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
                Image(bitmap.asImageBitmap(), "待发送摄像帧", Modifier.fillMaxWidth().height(220.dp).testTag("live-camera-frame"))
                if (!callMode) OutlinedTextField(question, { question = it.take(12_000) }, label = { Text("询问内容") }, modifier = Modifier.testTag("live-camera-question"))
                Text("确认后仅向当前聊天发送这一帧，不保存到相册。")
                TextButton(onClick = { jpeg = null }, modifier = Modifier.testTag("live-camera-retake")) { Text("重新取帧") }
            }
            error?.let { Text(it) }
        }
    }
    val confirm: @Composable () -> Unit = { TextButton(onClick = {
            val bytes = jpeg ?: return@TextButton
            val ticket = opened ?: return@TextButton
            model.sendCameraFrame(ticket, bytes, question, sourceEpoch)
            close()
        }, enabled = jpeg != null && question.isNotBlank() && "visual" !in state.busy,
            modifier = Modifier.testTag("live-camera-send")) { Text("确认发送") } }
    if (opened != null) {
        if (callMode) {
            if (jpeg == null) previewContent()
            else AlertDialog(onDismissRequest = { close() }, title = { Text("发送当前摄像画面") },
                text = previewContent, confirmButton = confirm,
                dismissButton = { TextButton(onClick = { close() }) { Text("取消") } })
        } else AlertDialog(onDismissRequest = { close() }, title = { Text("摄像头 · ${target.name}") },
            text = previewContent, confirmButton = confirm,
            dismissButton = { TextButton(onClick = { close() }) { Text("取消") } })
    }
}
