package com.charactermemory.android.live

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.util.Log
import android.webkit.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.File

/** Only presentation commands; no session, capture, permission or arbitrary native JS bridge. */
internal class WebCallCharacterRenderer(private val web: WebView) : CallCharacterRenderer {
    private val gson = Gson()
    private fun command(method: String, value: String) = web.evaluateJavascript("window.androidLive2d?.$method(${gson.toJson(value)})", null)
    override fun setCharacterState(state: CallCharacterState) = command("phase", state.phase)
    override fun playAction(action: String) = command("action", action)
    override fun setExpression(expression: String) = command("expression", expression)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun Live2dCallCharacter(coreUrl: String, characterId: String, characterState: CallCharacterState,
    inset: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val policy = remember(coreUrl) { Live2dOriginPolicy(coreUrl) }
    var error by remember(coreUrl, characterId) { mutableStateOf<String?>(null) }
    var loaded by remember(coreUrl, characterId) { mutableStateOf(false) }
    val latestState by rememberUpdatedState(characterState)
    val latestInset by rememberUpdatedState(inset)
    // Key replacement destroys the previous document; its pending model load cannot bind a new target.
    key(coreUrl, characterId) {
        val mocRequests = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
        val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
        val web = remember {
            WebView(context).apply {
                File(context.filesDir, "live2d-renderer-evidence.json").writeText(Gson().toJson(mapOf(
                    "kind" to "live2d-web-renderer", "characterId" to characterId, "ready" to false,
                    "loading" to true, "observedAtMs" to System.currentTimeMillis())))
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setGeolocationEnabled(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.mediaPlaybackRequiresUserGesture = true
                settings.setSupportMultipleWindows(false)
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        if (!policy.allows(url)) return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        if (url == policy.pageUrl) {
                            val config = Gson().toJson(mapOf("characterId" to characterId))
                            val html = context.assets.open("live2d-stage.html").bufferedReader().use { it.readText() }.replace("__CONFIG__", config)
                            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                        }
                        if (request.url.path.orEmpty().endsWith(".moc3", true)) mocRequests.add(url)
                        return null
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!alive.get() || url != policy.pageUrl) return
                        loaded = true
                        WebCallCharacterRenderer(view).setCharacterState(latestState)
                        view.evaluateJavascript("window.androidLive2d?.layout($latestInset)", null)
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.evaluateJavascript("window.androidLive2d?.pause()", null)
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                        if (request.isForMainFrame) error = "Live2D 页面加载失败：${failure.description}"
                    }
                    override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, failure: android.net.http.SslError) {
                        handler.cancel()
                        error = "Live2D HTTPS 证书校验失败"
                    }
                }
                if (policy.pageUrl != null) loadUrl(policy.pageUrl!!) else error = "Live2D 需要有效的 Core HTTPS 地址"
            }
        }
        DisposableEffect(web, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> { web.onResume(); web.evaluateJavascript("window.androidLive2d?.resume()", null) }
                    Lifecycle.Event.ON_PAUSE -> { web.evaluateJavascript("window.androidLive2d?.pause()", null); web.onPause() }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                alive.set(false)
                lifecycle.removeObserver(observer)
                web.evaluateJavascript("window.androidLive2d?.stop()", null)
                web.stopLoading(); web.onPause(); web.removeAllViews(); web.destroy()
                val disposedEvidence = Gson().toJson(mapOf("kind" to "live2d-web-renderer", "characterId" to characterId,
                    "ready" to false, "destroyed" to true, "observedAtMs" to System.currentTimeMillis()))
                File(context.filesDir, "live2d-renderer-evidence.json").writeText(disposedEvidence)
                Log.i("Live2dRenderer", disposedEvidence)
            }
        }
        LaunchedEffect(web, characterState.phase, loaded) { if (loaded) WebCallCharacterRenderer(web).setCharacterState(characterState) }
        LaunchedEffect(web, inset, loaded) { if (loaded) web.evaluateJavascript("window.androidLive2d?.layout($inset)", null) }
        LaunchedEffect(web, loaded) {
            while (loaded) {
                delay(2000)
                web.evaluateJavascript("JSON.stringify(window.androidLive2d?.snapshot()||{})") { result ->
                    if (!alive.get()) return@evaluateJavascript
                    runCatching {
                        val decoded = JsonParser.parseString(result).asString
                        val evidence = JsonParser.parseString(decoded).asJsonObject
                        evidence.addProperty("observedAtMs", System.currentTimeMillis())
                        evidence.addProperty("nativeWidth", web.width)
                        evidence.addProperty("nativeHeight", web.height)
                        evidence.add("moc3Requests", Gson().toJsonTree(mocRequests.toList()))
                        val json = evidence.toString()
                        Log.i("Live2dRenderer", json)
                        File(context.filesDir, "live2d-renderer-evidence.json").writeText(json)
                    }.onFailure { Log.w("Live2dRenderer", "Evidence unavailable", it) }
                }
            }
        }
        Box(modifier.testTag("live-call-live2d-renderer")) {
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
            error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center)) }
        }
    }
}
