package com.charactermemory.android.data

import com.charactermemory.android.audio.Pcm16Wav
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
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Narrow binary client for the existing Media batch ASR route. */
class MediaApi(val config: ServerConfig, client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(wav: ByteArray, source: String = "dictation"): JsonObject {
        require(source in setOf("dictation", "call")) { "Unknown ASR source" }
        require(wav.isNotEmpty()) { "WAV audio must not be empty" }
        require(wav.size <= Pcm16Wav.MAX_WAV_BYTES) { "WAV audio exceeds 30 seconds" }
        val audio = wav.copyOf()
        val body = object : RequestBody() {
            override fun contentType() = AUDIO_WAV
            override fun contentLength() = audio.size.toLong()
            override fun writeTo(sink: BufferedSink) { sink.write(audio) }
            override fun isOneShot() = true
        }
        val request = Request.Builder()
            .url(asrUrl())
            .header("X-ASR-Source", source)
            .post(body)
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use(::readTranscript)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    private fun asrUrl(): HttpUrl {
        val base = config.mediaUrl.toHttpUrl()
        val route = requireNotNull(base.resolve("/v1/asr")) { "Invalid Media ASR route" }
        require(route.scheme == base.scheme && route.host == base.host && route.port == base.port) {
            "Media ASR route changed configured server"
        }
        return route
    }

    private fun readTranscript(response: Response): JsonObject {
        val body = response.body?.string().orEmpty()
        val parsed = runCatching { JsonParser.parseString(body) }.getOrNull()
        if (!response.isSuccessful) {
            val detail: JsonElement? = parsed?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("detail")?.takeUnless { it.isJsonNull }
            throw ApiFailure(response.code, detail, "媒体识别请求失败（HTTP ${response.code}）")
        }
        if (parsed?.isJsonObject != true) {
            throw ApiFailure(response.code, null, "媒体识别返回的数据格式无效")
        }
        val result = parsed.asJsonObject
        val text = result.get("text")
        if (text?.isJsonPrimitive != true || !text.asJsonPrimitive.isString || text.asString.isBlank()) {
            throw ApiFailure(response.code, null, "媒体识别返回的文本为空或格式无效")
        }
        return result
    }

    private companion object {
        val AUDIO_WAV = "audio/wav".toMediaType()
    }
}
