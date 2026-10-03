package com.charactermemory.android.screen

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import com.charactermemory.android.MainActivity
import com.charactermemory.android.data.CoreApi
import com.charactermemory.android.data.ServerConfig
import com.charactermemory.android.data.flag
import com.charactermemory.android.data.jsonObject
import com.charactermemory.android.data.number
import com.charactermemory.android.data.text
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class ScreenShareSnapshot(
    val active: Boolean = false,
    val characterId: String = "",
    val conversationId: String = "",
    val label: String = "未共享",
    val accepted: Int = 0,
    val manualAccepted: Int = 0,
    val manualBusy: Boolean = false
)

object ScreenShareStatus {
    val state = MutableStateFlow(ScreenShareSnapshot())
}

/**
 * Visible, user-consented direct DISPLAY observations.
 * No persistent recording, no audio capture and no background auto-restart.
 */
class ScreenShareService : Service() {
    companion object {
        private const val ACTION_START = "com.charactermemory.android.screen.START"
        private const val ACTION_STOP = "com.charactermemory.android.screen.STOP"
        private const val ACTION_ASK = "com.charactermemory.android.screen.ASK"
        private const val EXTRA_CONSENT = "screen_consent"
        private const val EXTRA_RESULT = "screen_result"
        private const val EXTRA_CORE = "screen_core"
        private const val EXTRA_CHARACTER = "screen_character"
        private const val EXTRA_CONVERSATION = "screen_conversation"
        private const val CHANNEL = "character_memory_display"
        private const val NOTIFICATION_ID = 4108

        fun start(context: Context, code: Int, consent: Intent, coreUrl: String, character: String, conversation: String) {
            require(code == Activity.RESULT_OK && character.isNotBlank() && conversation.isNotBlank())
            // The result Intent is an OS-issued, single-session token. It is never persisted.
            context.startForegroundService(Intent(context, ScreenShareService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT, code)
                putExtra(EXTRA_CONSENT, consent)
                putExtra(EXTRA_CORE, coreUrl)
                putExtra(EXTRA_CHARACTER, character)
                putExtra(EXTRA_CONVERSATION, conversation)
            })
            ScreenShareStatus.state.value = ScreenShareSnapshot(true, character, conversation, "等待前台服务启动")
        }

        fun stop(context: Context) {
            if (!ScreenShareStatus.state.value.active) return
            context.startService(Intent(context, ScreenShareService::class.java).apply { action = ACTION_STOP })
        }

        fun askCurrentScreen(context: Context) {
            if (!ScreenShareStatus.state.value.active) return
            context.startService(Intent(context, ScreenShareService::class.java).apply { action = ACTION_ASK })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var callback: MediaProjection.Callback? = null
    private var sampler: Job? = null
    private var coreApi: CoreApi? = null
    private var sessionCharacter = ""
    private var sessionConversation = ""
    private var density: Int = 1
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish("已停止共享")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ASK) {
            val client = coreApi
            if (!stopping && projection != null && client != null && !ScreenShareStatus.state.value.manualBusy) {
                val character = sessionCharacter
                val conversation = sessionConversation
                ScreenShareStatus.state.value = ScreenShareStatus.state.value.copy(manualBusy = true, label = "正在读取当前屏幕…")
                scope.launch {
                    try {
                        submitCurrentScreen(client, character, conversation)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (!stopping) ScreenShareStatus.state.value =
                            ScreenShareStatus.state.value.copy(label = "手动发送屏幕失败：${error.message}")
                    } finally {
                        ScreenShareStatus.state.value = ScreenShareStatus.state.value.copy(manualBusy = false)
                    }
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || projection != null || stopping) return START_NOT_STICKY
        val code = intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val consent = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
        } else intent.getParcelableExtra(EXTRA_CONSENT)
        val core = intent.getStringExtra(EXTRA_CORE).orEmpty()
        val character = intent.getStringExtra(EXTRA_CHARACTER).orEmpty()
        val conversation = intent.getStringExtra(EXTRA_CONVERSATION).orEmpty()
        if (code != Activity.RESULT_OK || consent == null || character.isBlank() || conversation.isBlank()) {
            finish("屏幕授权或会话信息无效")
            return START_NOT_STICKY
        }
        return try {
            ServerConfig.normalize(core)
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, notification("正在共享屏幕"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTIFICATION_ID, notification("正在共享屏幕"))
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val obtained = manager.getMediaProjection(code, consent)
                ?: error("系统未返回屏幕采集授权")
            projection = obtained
            density = resources.displayMetrics.densityDpi
            callback = object : MediaProjection.Callback() {
                override fun onStop() { finish("系统结束了屏幕共享") }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (width > 0 && height > 0 && !stopping) resizeCapture(width, height)
                }
            }.also { obtained.registerCallback(it, handler) }
            val metrics = if (Build.VERSION.SDK_INT >= 30)
                (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).maximumWindowMetrics.bounds
            else null
            @Suppress("DEPRECATION")
            val width = metrics?.width() ?: resources.displayMetrics.widthPixels
            @Suppress("DEPRECATION")
            val height = metrics?.height() ?: resources.displayMetrics.heightPixels
            synchronized(lock) {
                reader = ImageReader.newInstance(max(width, 1), max(height, 1), PixelFormat.RGBA_8888, 2)
                display = obtained.createVirtualDisplay("CharacterMemoryScreen", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            }
            coreApi = CoreApi(ServerConfig.normalize(core))
            sessionCharacter = character
            sessionConversation = conversation
            ScreenShareStatus.state.value = ScreenShareSnapshot(true, character, conversation, "共享中 · 等待画面变化")
            sampler = scope.launch { sampleLoop(requireNotNull(coreApi), character, conversation) }
            START_NOT_STICKY
        } catch (e: Exception) {
            finish("启动屏幕共享失败：${e.message ?: "系统拒绝"}")
            START_NOT_STICKY
        }
    }

    private fun resizeCapture(width: Int, height: Int) {
        synchronized(lock) {
            val current = display ?: return
            val previous = reader
            val replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            try {
                current.resize(width, height, density)
                current.setSurface(replacement.surface)
                reader = replacement
                previous?.close()
            } catch (e: Exception) {
                replacement.close()
                ScreenShareStatus.state.value = ScreenShareStatus.state.value.copy(label = "屏幕尺寸变更失败：${e.message}")
            }
        }
    }

    private suspend fun sampleLoop(api: CoreApi, character: String, conversation: String) {
        try {
            val config = api.get("/v1/visual/periodic/config")
            if (!config.flag("enabled") || config.text("scope") != "DIRECT_DISPLAY_ONLY") {
                ScreenShareStatus.state.value = ScreenShareStatus.state.value.copy(label = "共享中 · 自动观察关闭，可手动询问")
                return
            }
            val intervalSeconds = config.text("interval_seconds", "30").toDoubleOrNull() ?: 30.0
            val minimumMillis = (intervalSeconds.coerceAtLeast(10.0) * 1000).toLong()
            var lastAttempt = 0L
            var baseline: IntArray? = null
            var failures = 0
            while (currentCoroutineContext().isActive && !stopping) {
                delay(3000)
                val frame = readFrame() ?: continue
                val pixels = frame.first
                if (!ScreenSamplingPolicy.significant(baseline, pixels)) { frame.second.recycle(); continue }
                val now = SystemClock.elapsedRealtime()
                if (lastAttempt != 0L && now - lastAttempt < minimumMillis) {
                    frame.second.recycle()
                    continue
                }
                lastAttempt = now
                val bitmap = frame.second
                try {
                    val bytes = encode(bitmap)
                    if (bytes.size > 2 * 1024 * 1024) {
                        ScreenShareStatus.state.value = ScreenShareStatus.state.value.copy(label = "当前帧太大，已跳过")
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    val response = api.post("/v1/visual/direct/observations",
                        ScreenVisualPayload.observation(character, conversation, bytes))
                    failures = 0
                    if (response.flag("accepted")) {
                        baseline = pixels
                        val old = ScreenShareStatus.state.value
                        ScreenShareStatus.state.value = old.copy(accepted = old.accepted + 1, label = "共享中 · 已接受 ${old.accepted + 1} 帧")
                    } else {
                        val reason = response.text("reason", "NOT_ACCEPTED")
                        ScreenShareStatus.state.value = ScreenShareStatus.state.value.copy(label = "共享中 · 本帧未接受：$reason")
                        if (reason == "DISABLED" || reason == "HOURLY_LIMIT_DISABLED") {
                            withContext(Dispatchers.Main) { finish("Core 关闭了屏幕观察") }; return
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    failures++
                    ScreenShareStatus.state.value = ScreenShareStatus.state.value.copy(label = "发送画面失败 (${failures}/3)：${e.message}")
                    if (failures >= 3) {
                        withContext(Dispatchers.Main) { finish("网络连续失败，已停止共享") }; return
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { finish("无法获取屏幕观察配置：${e.message}") }
        }
    }

    private suspend fun submitCurrentScreen(api: CoreApi, character: String, conversation: String) {
        repeat(12) {
            delay(350)
            val frame = readFrame() ?: return@repeat
            val bitmap = frame.second
            try {
                val bytes = encode(bitmap)
                require(bytes.size <= 2 * 1024 * 1024) { "当前画面超过单帧上限" }
                currentCoroutineContext().ensureActive()
                val response = api.post("/v1/visual/direct/messages",
                    ScreenVisualPayload.directQuestion(character, conversation, bytes))
                if (!response.flag("accepted")) error("Core 未返回视觉消息接收凭据")
                val snapshot = ScreenShareStatus.state.value
                if (!stopping && snapshot.characterId == character && snapshot.conversationId == conversation) {
                    ScreenShareStatus.state.value = snapshot.copy(
                        manualAccepted = snapshot.manualAccepted + 1,
                        label = "当前屏幕已发送给人物，等待回复")
                }
            } finally {
                bitmap.recycle()
            }
            return
        }
        if (!stopping) ScreenShareStatus.state.value = ScreenShareStatus.state.value.copy(
            label = "暂无可用屏幕画面，请稍候重试")
    }

    private fun readFrame(): Pair<IntArray, Bitmap>? = synchronized(lock) {
        val image = try { reader?.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return null
        image.use {
            val plane = image.planes.firstOrNull() ?: return null
            if (plane.pixelStride != 4 || image.width <= 0 || image.height <= 0) return null
            val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
            try {
                padded.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                try {
                    val factor = minOf(1.0, 1080.0 / max(image.width, image.height))
                    // Never expose a bitmap that the finally block may recycle. createScaledBitmap
                    // may return the input when the dimensions are unchanged.
                    val scaled = if (factor == 1.0) cropped.copy(Bitmap.Config.ARGB_8888, false)
                        else Bitmap.createScaledBitmap(cropped,
                            max(1, (image.width * factor).roundToInt()), max(1, (image.height * factor).roundToInt()), true)
                    val sample = Bitmap.createScaledBitmap(scaled, 32, 32, true)
                    try {
                        val argb = IntArray(32 * 32)
                        sample.getPixels(argb, 0, 32, 0, 0, 32, 32)
                        val gray = IntArray(argb.size) { j ->
                            val p = argb[j]
                            ((p shr 16 and 255) * 30 + (p shr 8 and 255) * 59 + (p and 255) * 11) / 100
                        }
                        return gray to scaled
                    } finally { sample.recycle() }
                } finally { if (cropped !== padded) cropped.recycle() }
            } finally { padded.recycle() }
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 78, stream)
        return stream.toByteArray()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "屏幕共享", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(label: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenShareService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Character Memory · 手机屏幕共享")
            .setContentText(label + " · 点击停止可立即结束")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止共享", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun finish(message: String) {
        if (stopping) return
        stopping = true
        scope.cancel()
        sampler?.cancel()
        coreApi = null
        sessionCharacter = ""
        sessionConversation = ""
        synchronized(lock) {
            display?.release(); display = null
            reader?.close(); reader = null
            val previous = projection; projection = null
            callback?.let { runCatching { previous?.unregisterCallback(it) } }; callback = null
            runCatching { previous?.stop() }
        }
        ScreenShareStatus.state.value = ScreenShareSnapshot(label = message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish("服务已结束")
        scope.cancel()
        super.onDestroy()
    }
}
