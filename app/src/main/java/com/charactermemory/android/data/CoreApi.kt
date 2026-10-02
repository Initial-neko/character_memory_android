package com.charactermemory.android.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(val status: Int, val detail: JsonElement?, message: String) : IOException(message)

/** Transport has no automatic retries, including redirects which can replay a write. */
class CoreApi(val config: ServerConfig, client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val streamClient = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS).build()

    suspend fun get(path: String, query: Map<String, String> = emptyMap(), media: Boolean = false): JsonObject =
        execute(Request.Builder().url(url(path, query, media)).get().build())

    suspend fun post(path: String, body: JsonObject): JsonObject = write("POST", path, body)
    suspend fun patch(path: String, body: JsonObject): JsonObject = write("PATCH", path, body)

    /** Batch ASR on Media Runtime, never on Core. WAV is one-shot and must not be replayed. */
    suspend fun transcribeWav(wav: ByteArray): String {
        require(wav.size in 45..(4 * 1024 * 1024)) { "WAV 音频大小无效" }
        val audio = wav.toRequestBody("audio/wav".toMediaType())
        val oneShot = object : RequestBody() {
            override fun contentType() = audio.contentType()
            override fun contentLength() = audio.contentLength()
            override fun writeTo(sink: BufferedSink) = audio.writeTo(sink)
            override fun isOneShot() = true
        }
        val response = execute(Request.Builder().url(url("/v1/asr", media = true))
            .header("X-ASR-Source", "dictation").post(oneShot).build())
        return response.text("text").trim().ifBlank { throw IOException("未识别到可用文本") }
    }

    private suspend fun write(method: String, path: String, body: JsonObject): JsonObject {
        val jsonBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        // retryOnConnectionFailure(false) does not suppress HTTP 503 follow-ups.
        // One-shot bodies also prohibit a response-based replay after acceptance is ambiguous.
        val once = object : RequestBody() {
            override fun contentType() = jsonBody.contentType()
            override fun contentLength() = jsonBody.contentLength()
            override fun writeTo(sink: BufferedSink) = jsonBody.writeTo(sink)
            override fun isOneShot() = true
        }
        return execute(Request.Builder().url(url(path)).method(method, once).build())
    }

    private fun url(path: String, query: Map<String, String> = emptyMap(), media: Boolean = false): HttpUrl {
        require(path.startsWith("/") && !path.startsWith("//") && !path.contains('#')) { "API path must be relative to the configured server" }
        val base = (if (media) config.mediaUrl else config.coreUrl).toHttpUrl()
        val resolved = requireNotNull(base.resolve(path)) { "Invalid API path" }
        require(resolved.host == base.host && resolved.port == base.port && resolved.scheme == base.scheme) { "API path changed server" }
        return resolved.newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
    }

    fun assetUrl(path: String): String {
        if (path.isBlank()) return ""
        val base = config.coreUrl.toHttpUrl()
        require(!path.startsWith("//")) { "Invalid asset URL" }
        val resolved = requireNotNull(base.resolve(path)) { "Invalid asset URL" }
        require(resolved.username.isEmpty() && resolved.password.isEmpty()) { "Asset URL cannot contain credentials" }
        require(resolved.isHttps || (!base.isHttps && resolved.scheme == base.scheme && resolved.host == base.host && resolved.port == base.port)) { "Asset URL must use HTTPS" }
        return resolved.toString()
    }

    private suspend fun execute(request: Request): JsonObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val result = readObject(response)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    fun stream(
        scope: String,
        characterId: String?,
        conversationId: String,
        lastEventId: String? = null,
        onOpen: () -> Unit,
        onEvent: (String, String?, JsonObject) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Closeable {
        require(scope == "direct" || scope == "group") { "Unknown conversation scope" }
        require(scope != "direct" || !characterId.isNullOrBlank()) { "Direct stream needs a character" }
        val query = linkedMapOf("scope" to scope, "conversation_id" to conversationId)
        if (!characterId.isNullOrBlank()) query["character_id"] = characterId
        val request = Request.Builder().url(url("/v1/events/stream", query)).header("Accept", "text/event-stream").apply {
            if (lastEventId != null) header("Last-Event-ID", lastEventId)
        }.build()
        val stopped = AtomicBoolean(false)
        val eventSource = EventSources.createFactory(streamClient).newEventSource(request, object : EventSourceListener() {
            private fun failure(source: EventSource, error: Throwable) {
                if (stopped.compareAndSet(false, true)) {
                    source.cancel()
                    onFailure(error)
                }
            }

            override fun onOpen(eventSource: EventSource, response: Response) {
                if (!stopped.get()) try { onOpen() } catch (error: Exception) { failure(eventSource, error) }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (stopped.get()) return
                try {
                    val value = JsonParser.parseString(data)
                    if (!value.isJsonObject) throw ApiFailure(200, null, "事件数据格式无效")
                    onEvent(type ?: "message", id, value.asJsonObject)
                } catch (error: Exception) {
                    failure(eventSource, if (error is ApiFailure) error else ApiFailure(200, null, "事件数据格式无效"))
                }
            }

            override fun onClosed(eventSource: EventSource) { failure(eventSource, EOFException("事件连接已关闭")) }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val error = if (response != null && !response.isSuccessful) {
                    runCatching { response.use { readObject(it) }; ApiFailure(response.code, null, "事件连接失败（HTTP ${response.code}）") }
                        .exceptionOrNull() ?: ApiFailure(response.code, null, "事件连接失败（HTTP ${response.code}）")
                } else t ?: IOException("事件连接失败")
                failure(eventSource, error)
            }
        })
        return Closeable { stopped.set(true); eventSource.cancel() }
    }

    private fun readObject(response: Response): JsonObject {
        val body = response.body?.string().orEmpty()
        val parsed = runCatching { JsonParser.parseString(body) }.getOrNull()
        if (!response.isSuccessful) {
            val detail = parsed?.takeIf { it.isJsonObject }?.asJsonObject?.get("detail")?.takeUnless { it.isJsonNull }
            throw ApiFailure(response.code, detail, "请求失败（HTTP ${response.code}）")
        }
        if (response.code == 204 && body.isBlank()) return JsonObject()
        if (parsed?.isJsonObject != true) throw ApiFailure(response.code, null, "服务器返回的数据格式无效")
        return parsed.asJsonObject
    }
}
