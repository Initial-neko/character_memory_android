package com.charactermemory.android.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import java.io.ByteArrayOutputStream
import java.io.Closeable

/** Foreground-only Camera2 capture. No storage, microphone or background service. */
class CameraCapture(private val context: Context, private val view: TextureView,
    private val failed: (String) -> Unit, private val frame: (ByteArray) -> Unit) : Closeable {
    private val worker = HandlerThread("chat-camera").apply { start() }
    private val handler = Handler(worker.looper)
    private val main = Handler(context.mainLooper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var ticket = 0L
    @Volatile private var closed = false
    private var front = false
    private var sensor = 0
    private var pending = false

    fun open(useFront: Boolean = false) {
        handler.post {
            if (closed) return@post
            release()
            val generation = ++ticket
            try {
                check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { "未获得摄像头权限" }
                val manager = context.getSystemService(CameraManager::class.java)
                val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == if (useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK }
                    ?: error("当前设备没有所选摄像头")
                val info = manager.getCameraCharacteristics(id)
                front = useFront
                sensor = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val map = requireNotNull(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
                val size = CameraFramePolicy.choose(map.getOutputSizes(ImageFormat.JPEG).map { it.width to it.height })
                val preview = map.getOutputSizes(SurfaceTexture::class.java).filter { it.width.toLong() * it.height <= 2_100_000 }
                    .minByOrNull { kotlin.math.abs(it.width.toDouble() / it.height - size.first.toDouble() / size.second) }
                    ?: error("摄像头没有可用预览")
                val texture = requireNotNull(view.surfaceTexture) { "摄像头预览尚未就绪" }
                texture.setDefaultBufferSize(preview.width, preview.height)
                surface = Surface(texture)
                reader = ImageReader.newInstance(size.first, size.second, ImageFormat.JPEG, 2).also { images ->
                    images.setOnImageAvailableListener({ source ->
                        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        image.use {
                            if (closed || ticket != generation || !pending) return@use
                            pending = false
                            try {
                                val bytes = ByteArray(it.planes[0].buffer.remaining()).also(it.planes[0].buffer::get)
                                val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("摄像头返回无效 JPEG")
                                try {
                                    @Suppress("DEPRECATION")
                                    val degrees = (view.display?.rotation ?: 0) * 90
                                    val upright = Bitmap.createBitmap(original, 0, 0, original.width, original.height,
                                        Matrix().apply { postRotate(CameraFramePolicy.rotation(sensor, degrees, front).toFloat()) }, true)
                                    try {
                                        val fitted = CameraFramePolicy.fit(upright.width, upright.height)
                                        val scaled = Bitmap.createScaledBitmap(upright, fitted.first, fitted.second, true)
                                        try {
                                            val output = ByteArrayOutputStream()
                                            check(scaled.compress(Bitmap.CompressFormat.JPEG, 78, output))
                                            val jpeg = output.toByteArray()
                                            check(jpeg.isNotEmpty() && jpeg.size <= 2 * 1024 * 1024) { "摄像帧超过 2 MiB 上限" }
                                            main.post { if (!closed && ticket == generation) frame(jpeg) }
                                        } finally { if (scaled !== upright) scaled.recycle() }
                                    } finally { if (upright !== original) upright.recycle() }
                                } finally { original.recycle() }
                            } catch (error: Exception) { report(error) }
                        }
                    }, handler)
                }
                // Permission checked above; security revocation is still reported by the catch.
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (closed || ticket != generation) { camera.close(); return }
                        device = camera
                        try {
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(listOf(requireNotNull(surface), requireNotNull(reader).surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configured: CameraCaptureSession) {
                                    if (closed || ticket != generation) { configured.close(); return }
                                    session = configured
                                    try {
                                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(requireNotNull(surface)) }.build()
                                        configured.setRepeatingRequest(request, null, handler)
                                    } catch (error: Exception) { report(error) }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) { report(IllegalStateException("摄像头预览配置失败")); session.close() }
                            }, handler)
                        } catch (error: Exception) { report(error) }
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close(); report(IllegalStateException("摄像头连接中断")) }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close(); report(IllegalStateException("摄像头错误 $error")) }
                }, handler)
            } catch (error: SecurityException) { report(error) }
            catch (error: Exception) { report(error) }
        }
    }

    fun capture() { handler.post {
        if (closed || pending) return@post
        try {
            val camera = device ?: error("摄像头尚未就绪")
            val current = session ?: error("预览尚未就绪")
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(requireNotNull(reader).surface)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }.build()
            pending = true
            current.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    pending = false; report(IllegalStateException("摄像头取帧失败"))
                }
            }, handler)
        } catch (error: Exception) { pending = false; report(error) }
    } }
    private fun report(error: Exception) { main.post { if (!closed) failed(error.message ?: "摄像头不可用") } }
    private fun release() { session?.close(); session = null; device?.close(); device = null; reader?.close(); reader = null; surface?.release(); surface = null; pending = false }
    override fun close() {
        closed = true
        handler.post { ++ticket; release(); worker.quitSafely() }
    }
}
