package com.charactermemory.android.data

import com.charactermemory.android.audio.Pcm16Wav
import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MediaApiTest {
    @Test fun callSourceIsExplicitAndUsesTheSameOneShotMediaRoute() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"text":"call transcript"}"""))
            val api = MediaApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))
            api.transcribe(Pcm16Wav.encode(byteArrayOf(1, 0)), source = "call")
            assertEquals("call", server.takeRequest().getHeader("X-ASR-Source"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun postsRawWavToConfiguredMediaOriginAndReturnsNonEmptyTextWithoutCallingCore() = runBlocking {
        MockWebServer().use { core -> MockWebServer().use { media ->
            core.start()
            media.start()
            val wav = Pcm16Wav.encode(byteArrayOf(0x34, 0x12))
            media.enqueue(MockResponse().setBody("""{"text":" recognized words "}"""))
            val api = MediaApi(ServerConfig(core.url("/").toString(), media.url("/").toString()))

            val result = api.transcribe(wav)

            assertEquals(" recognized words ", result.get("text").asString)
            val request = media.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/asr", request.path)
            assertEquals("audio/wav", request.getHeader("Content-Type"))
            assertEquals("dictation", request.getHeader("X-ASR-Source"))
            assertArrayEquals(wav, request.body.readByteArray())
            assertEquals(1, media.requestCount)
            assertEquals(0, core.requestCount)
        } }
    }

    @Test fun preservesStringAndObjectDetailsForMediaHttpErrors() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = MediaApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))
            val cases = listOf(
                400 to """{"detail":"invalid WAV"}""",
                413 to """{"detail":{"code":"too_large","limit":960044}}""",
                415 to """{"detail":"unsupported media type"}""",
                422 to """{"detail":{"code":"invalid_audio"}}""",
                503 to """{"detail":"ASR unavailable"}""",
            )

            for ((status, body) in cases) {
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                try {
                    api.transcribe(Pcm16Wav.encode(byteArrayOf(1, 0)))
                    fail("expected HTTP $status failure")
                } catch (error: ApiFailure) {
                    assertEquals(status, error.status)
                    assertEquals(JsonParser.parseString(body).asJsonObject.get("detail"), error.detail)
                }
            }
            assertEquals(cases.size, server.requestCount)
        }
    }

    @Test fun rejectsEmptyMalformedOrBlankTranscriptSuccessBodies() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = MediaApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))
            val bodies = listOf(
                "",
                "not json",
                "[]",
                "{}",
                """{"text":""}""",
                """{"text":"   "}""",
                """{"text":null}""",
                """{"text":17}""",
            )

            for (body in bodies) {
                server.enqueue(MockResponse().setBody(body))
                try {
                    api.transcribe(Pcm16Wav.encode(byteArrayOf(1, 0)))
                    fail("expected invalid ASR response for body: $body")
                } catch (error: ApiFailure) {
                    assertEquals(200, error.status)
                    assertNull(error.detail)
                    assertTrue(error.message!!.contains("格式" ) || error.message!!.contains("文本"))
                    assertTrue("server body must not enter the message", !error.message!!.contains("not json"))
                }
            }
            assertEquals(bodies.size, server.requestCount)
        }
    }

    @Test fun retryAfterZeroCannotReplayAsrPost() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                .setBody("""{"detail":"unavailable"}"""))
            server.enqueue(MockResponse().setBody("""{"text":"unexpected replay"}"""))
            val api = MediaApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))

            try {
                api.transcribe(Pcm16Wav.encode(byteArrayOf(1, 0)))
                fail("expected original 503 response")
            } catch (error: ApiFailure) {
                assertEquals(503, error.status)
                assertEquals("unavailable", error.detail!!.asString)
            }

            assertEquals(1, server.requestCount)
            assertEquals("/v1/asr", server.takeRequest().path)
        }
    }

    @Test fun emptyWavIsRejectedBeforeAnyRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = MediaApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))

            try {
                api.transcribe(byteArrayOf())
                fail("expected empty WAV rejection")
            } catch (_: IllegalArgumentException) { }

            assertEquals(0, server.requestCount)
        }
    }

    @Test fun wavAboveThirtySecondByteCeilingIsRejectedBeforeAnyRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = MediaApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))

            try {
                api.transcribe(ByteArray(Pcm16Wav.MAX_WAV_BYTES + 1))
                fail("expected oversized WAV rejection")
            } catch (_: IllegalArgumentException) { }

            assertEquals(0, server.requestCount)
        }
    }

    @Test fun connectionFailureAfterUploadDoesNotRetryAsrPost() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val api = MediaApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))

            try {
                api.transcribe(Pcm16Wav.encode(byteArrayOf(1, 0)))
                fail("expected connection failure")
            } catch (_: IOException) { }

            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectCannotSendAudioToAnotherRoute() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/replayed"))
            server.enqueue(MockResponse().setBody("""{"text":"unexpected replay"}"""))
            val api = MediaApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))

            try {
                api.transcribe(Pcm16Wav.encode(byteArrayOf(1, 0)))
                fail("expected redirect response")
            } catch (error: ApiFailure) {
                assertEquals(307, error.status)
            }

            assertEquals(1, server.requestCount)
            assertEquals("/v1/asr", server.takeRequest().path)
        }
    }

    @Test fun cancellationCancelsTheUnderlyingMediaCall() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val started = CountDownLatch(1)
            val failed = CountDownLatch(1)
            val activeCall = AtomicReference<Call>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    activeCall.set(call)
                    started.countDown()
                }

                override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
            }).build()
            val api = MediaApi(ServerConfig(server.url("/").toString(), server.url("/").toString()), client)
            val pending = async { api.transcribe(Pcm16Wav.encode(byteArrayOf(1, 0))) }
            withTimeout(5_000) { while (started.count > 0) yield() }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

            pending.cancelAndJoin()

            assertTrue(activeCall.get().isCanceled())
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun closesSuccessfulAndErrorResponseBodies() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val closeCount = AtomicInteger()
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = requireNotNull(response.body)
                response.newBuilder().body(TrackingResponseBody(body) { closeCount.incrementAndGet() }).build()
            }.build()
            val api = MediaApi(ServerConfig(server.url("/").toString(), server.url("/").toString()), client)
            val bodies = listOf("""{"text":"ok"}""", """{"detail":"bad"}""")
            val statuses = listOf(200, 422)

            for (index in bodies.indices) {
                server.enqueue(MockResponse().setResponseCode(statuses[index]).setBody(bodies[index]))
                val closedBefore = closeCount.get()
                try {
                    api.transcribe(Pcm16Wav.encode(byteArrayOf(1, 0)))
                } catch (_: ApiFailure) { }
                assertTrue("response body ${statuses[index]} must be closed", closeCount.get() > closedBefore)
            }
        }
    }

    private class TrackingResponseBody(
        private val delegate: ResponseBody,
        private val onClosed: () -> Unit,
    ) : ResponseBody() {
        private val didClose = AtomicBoolean(false)
        private val trackedSource: BufferedSource = object : ForwardingSource(delegate.source()) {
            override fun close() {
                if (didClose.compareAndSet(false, true)) onClosed()
                super.close()
            }
        }.buffer()

        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source(): BufferedSource = trackedSource
    }
}
