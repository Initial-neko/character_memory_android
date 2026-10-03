package com.charactermemory.android.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
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

@Composable
fun CameraControls(state: LiveState, model: LiveViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val target = state.target ?: return
    val call by model.call.state.collectAsState()
    var requested by remember { mutableStateOf<Long?>(null) }
    var opened by remember { mutableStateOf<Long?>(null) }
    var front by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<CameraCapture?>(null) }
    var jpeg by remember { mutableStateOf<ByteArray?>(null) }
    var question by remember { mutableStateOf("请看看镜头中的画面，说说你注意到了什么。") }
    var error by remember { mutableStateOf<String?>(null) }
    val currentGeneration = model.captureGeneration
    fun close() { capture?.close(); capture = null; opened = null; jpeg = null }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ticket = requested; requested = null
        if (ticket != null && ticket == model.captureGeneration && model.state.value.page == LivePage.CHAT && !model.call.state.value.active) {
            if (granted) opened = ticket else error = "未获得摄像头权限，仍可使用文字聊天。"
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) close() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); close() }
    }
    LaunchedEffect(currentGeneration, call.active) { if (opened != null && (opened != currentGeneration || call.active)) close() }
    OutlinedButton(onClick = {
        error = null
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) opened = model.captureGeneration
        else { requested = model.captureGeneration; permission.launch(Manifest.permission.CAMERA) }
    }, enabled = !call.active && requested == null && "visual" !in state.busy,
        modifier = Modifier.testTag("live-camera-open")) { Text("摄像头画面") }
    error?.let { Text(it, modifier = Modifier.testTag("live-camera-error")) }
    if (opened != null) AlertDialog(onDismissRequest = { close() }, title = { Text("摄像头 · ${target.name}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val bytes = jpeg
            if (bytes == null) {
                AndroidView(factory = { androidContext -> TextureView(androidContext).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            capture = CameraCapture(androidContext, this@apply,
                                { message -> error = message }, { data -> jpeg = data; capture?.close(); capture = null }).also { it.open(front) }
                        }
                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { capture?.close(); capture = null; return true }
                    }
                } }, modifier = Modifier.fillMaxWidth().height(220.dp).testTag("live-camera-preview"))
                Row {
                    TextButton(onClick = { front = !front; capture?.open(front) }, enabled = capture != null,
                        modifier = Modifier.testTag("live-camera-flip")) { Text("切换前后摄像头") }
                    TextButton(onClick = { capture?.capture() }, enabled = capture != null,
                        modifier = Modifier.testTag("live-camera-capture")) { Text("取一帧") }
                }
            } else {
                val bitmap = remember(bytes) { requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) }
                DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
                Image(bitmap.asImageBitmap(), "待发送摄像帧", Modifier.fillMaxWidth().height(220.dp).testTag("live-camera-frame"))
                OutlinedTextField(question, { question = it.take(12_000) }, label = { Text("询问内容") }, modifier = Modifier.testTag("live-camera-question"))
                Text("确认后仅向当前聊天发送这一帧，不保存到相册。")
                TextButton(onClick = { jpeg = null }, modifier = Modifier.testTag("live-camera-retake")) { Text("重新取帧") }
            }
            error?.let { Text(it) }
        } }, confirmButton = { TextButton(onClick = {
            val bytes = jpeg ?: return@TextButton
            val ticket = opened ?: return@TextButton
            model.sendCameraFrame(ticket, bytes, question)
            close()
        }, enabled = jpeg != null && question.isNotBlank() && "visual" !in state.busy,
            modifier = Modifier.testTag("live-camera-send")) { Text("确认发送") } },
        dismissButton = { TextButton(onClick = { close() }) { Text("取消") } })
}
