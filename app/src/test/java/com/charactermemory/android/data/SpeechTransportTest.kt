package com.charactermemory.android.data

import com.charactermemory.android.audio.Pcm16Wav
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class SpeechTransportTest {
    @Test fun ttsUsesMediaBinaryAndDoesNotReplay503() = runBlocking {
        MockWebServer().use { core -> MockWebServer().use { media ->
            core.start(); media.start()
            val client = CoreApi(ServerConfig(core.url("/").toString(), media.url("/").toString()))
            val wav = Pcm16Wav.encode(ByteArray(3200))
            media.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav)))
            val audio = client.synthesizeSpeech("你好")
            assertArrayEquals(wav, audio.bytes)
            assertEquals("audio/wav", audio.mimeType)
            val request = media.takeRequest()
            assertEquals("/v1/tts", request.path)
            assertEquals("POST", request.method)
            assertEquals("你好", com.google.gson.JsonParser.parseString(request.body.readUtf8()).asJsonObject.text("text"))
            assertEquals(0, core.requestCount)
            media.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("""{"detail":"unavailable"}"""))
            media.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav)))
            try { client.synthesizeSpeech("不要重试"); fail("503 must fail") }
            catch (failure: ApiFailure) { assertEquals(503, failure.status) }
            assertEquals(2, media.requestCount)
        } }
    }

    @Test fun invalidBinaryAndEmptySpeechAreRejected() = runBlocking {
        MockWebServer().use { media ->
            media.start()
            val client = CoreApi(ServerConfig(media.url("/").toString(), media.url("/").toString()))
            for ((mime, body) in listOf("application/json" to "{}", "audio/wav" to "")) {
                media.enqueue(MockResponse().setHeader("Content-Type", mime).setBody(body))
                try { client.synthesizeSpeech("test"); fail("Invalid audio must fail") }
                catch (_: java.io.IOException) { }
            }
            try { client.synthesizeSpeech(" "); fail("Empty text must fail") }
            catch (_: IllegalArgumentException) { }
            assertEquals(2, media.requestCount)
        }
    }
}
