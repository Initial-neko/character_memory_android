package com.charactermemory.android.data

import com.google.gson.JsonParser
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CoreApiTest {
    @Test fun normalizesHttpsAndDerivesIndependentMediaOrigin() {
        assertEquals("", ServerConfig.DEFAULT_CORE)
        assertEquals("", ServerConfig("", "").coreUrl)
        assertThrows(IllegalArgumentException::class.java) { ServerConfig.normalize("") }
        val config = ServerConfig.normalize(" https://example.test:9443/ ")
        assertEquals("https://example.test:9443", config.coreUrl)
        assertEquals("https://example.test:8443", config.mediaUrl)
        listOf("http://example.test", "https://user:pass@example.test", "https://example.test?q=secret", "https://example.test/base").forEach {
            assertThrows(IllegalArgumentException::class.java) { ServerConfig.normalize(it) }
        }
    }

    @Test fun requestsUseSeparateOriginsEncodeQueryAndPreserveAcceptedPayload() = runBlocking {
        MockWebServer().use { core -> MockWebServer().use { media ->
            core.start(); media.start()
            val api = CoreApi(ServerConfig(core.url("/").toString(), media.url("/").toString()))
            core.enqueue(MockResponse().setBody("{\"messages\":[]}"))
            api.get("/v1/chat/history-page", mapOf("character_id" to "a & b"))
            assertEquals("/v1/chat/history-page?character_id=a%20%26%20b", core.takeRequest().path)
            media.enqueue(MockResponse().setBody("{\"status\":\"ok\"}"))
            assertEquals("ok", api.get("/health", media = true).text("status"))
            assertEquals("/health", media.takeRequest().path)
            core.enqueue(MockResponse().setResponseCode(202).setBody("{\"accepted\":true,\"event_id\":12,\"message\":{\"id\":12,\"role\":\"user\"}}"))
            val accepted = api.post("/v1/chat/messages", jsonObject("text" to "你好", "conversation_id" to "mobile-1"))
            assertTrue(accepted.flag("accepted"))
            assertEquals("user", accepted.obj("message").text("role"))
            val request = core.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("你好", JsonParser.parseString(request.body.readUtf8()).asJsonObject.text("text"))
            core.enqueue(MockResponse().setBody("{}"))
            api.patch("/v1/example", jsonObject("enabled" to true))
            assertEquals("PATCH", core.takeRequest().method)
        } }
    }

    @Test fun errorsPreserveStringAndStructuredDetailsWithoutInvalidSuccessFallback() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = CoreApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))
            for (body in listOf("{\"detail\":\"missing\"}", "{\"detail\":{\"code\":\"limit\",\"count\":10}}")) {
                server.enqueue(MockResponse().setResponseCode(409).setBody(body))
                try { api.post("/v1/characters", jsonObject()); fail("expected HTTP failure") }
                catch (error: ApiFailure) {
                    assertEquals(409, error.status)
                    assertEquals(JsonParser.parseString(body).asJsonObject.get("detail"), error.detail)
                }
            }
            server.enqueue(MockResponse().setBody("not json"))
            try { api.get("/health"); fail("invalid success must fail") }
            catch (error: ApiFailure) { assertNull(error.detail); assertFalse(error.message!!.contains("not json")) }
        }
    }

    @Test fun cancelledSuspensionCancelsUnderlyingCallAndReleasesIt() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val started = CountDownLatch(1)
            val ended = CountDownLatch(1)
            val activeCall = AtomicReference<Call>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { activeCall.set(call); started.countDown() }
                override fun callFailed(call: Call, ioe: java.io.IOException) { ended.countDown() }
            }).build()
            val api = CoreApi(ServerConfig(server.url("/").toString(), server.url("/").toString()), client)
            val pending = async { api.get("/slow") }
            withTimeout(5000) { while (started.count > 0) kotlinx.coroutines.yield() }
            pending.cancelAndJoin()
            assertTrue(activeCall.get().isCanceled())
            assertTrue(ended.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun ambiguousWriteFailureIsNeverAutomaticallyRetried() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val api = CoreApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))
            try { api.post("/v1/chat/messages", jsonObject("text" to "one write")); fail("expected disconnect") }
            catch (_: java.io.IOException) { }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun serviceUnavailableRetryAfterZeroCannotReplayPostOrPatch() = runBlocking {
        for (method in listOf("POST", "PATCH")) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                    .setBody("{\"detail\":\"unavailable\"}"))
                // A replay would consume this success and hide the failed submission.
                server.enqueue(MockResponse().setResponseCode(202).setBody("{\"accepted\":true}"))
                val api = CoreApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))
                try {
                    if (method == "POST") api.post("/v1/chat/messages", jsonObject("text" to "one write"))
                    else api.patch("/v1/test-resource", jsonObject("enabled" to true))
                    fail("expected original 503 response for $method")
                } catch (error: ApiFailure) {
                    assertEquals(503, error.status)
                    assertEquals("unavailable", error.detail!!.asString)
                }
                assertEquals("write must reach server exactly once", 1, server.requestCount)
                assertEquals(method, server.takeRequest().method)
            }
        }
    }

    @Test fun streamPassesCursorParsesEventsAndCloseReleasesCall() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "id: cursor-12\nevent: character_event\ndata: {\"id\":44,\"content\":\"reply\",\"metadata\":null}\n\n"
            ).setSocketPolicy(SocketPolicy.KEEP_OPEN))
            val event = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val activeCall = AtomicReference<Call>()
            val error = AtomicReference<Throwable?>()
            // okhttp-sse disables EventListener; inspect the real call through an interceptor.
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                activeCall.set(chain.call())
                chain.proceed(chain.request())
            }.build()
            val stream = CoreApi(ServerConfig(server.url("/").toString(), server.url("/").toString()), client).stream(
                "direct", "rin", "mobile-1", "cursor-11", {}, { type, id, payload ->
                    if (type != "character_event" || id != "cursor-12" || payload.text("id") != "44") error.set(AssertionError("wrong SSE projection"))
                    event.countDown()
                    releaseCallback.await(5, TimeUnit.SECONDS)
                }, { error.set(it); event.countDown() }
            )
            assertTrue(event.await(5, TimeUnit.SECONDS))
            val request = server.takeRequest()
            assertEquals("/v1/events/stream?scope=direct&conversation_id=mobile-1&character_id=rin", request.path)
            assertEquals("cursor-11", request.getHeader("Last-Event-ID"))
            stream.close(); stream.close()
            releaseCallback.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (client.dispatcher.runningCallsCount() != 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(0, client.dispatcher.runningCallsCount())
            assertTrue(activeCall.get().isCanceled())
            assertNull(error.get())
        }
    }

    @Test fun streamMalformedEventFailsOnceAndStopsTheRequest() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: character_event\ndata: private-invalid-body\n\n"))
            val failed = CountDownLatch(1)
            val failures = java.util.concurrent.atomic.AtomicInteger()
            val error = AtomicReference<Throwable>()
            val api = CoreApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))
            api.stream("group", null, "g", onOpen = {}, onEvent = { _, _, _ -> fail("malformed event emitted") }, onFailure = {
                error.set(it); failures.incrementAndGet(); failed.countDown()
            }).use {
                assertTrue(failed.await(5, TimeUnit.SECONDS))
                assertTrue(error.get() is ApiFailure)
                assertFalse(error.get().message!!.contains("private-invalid-body"))
            }
            assertEquals(1, failures.get())
        }
    }

    @Test fun redirectCannotReplayWriteOrEscapeConfiguredServer() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/replayed"))
            val api = CoreApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))
            try { api.post("/v1/chat/messages", jsonObject("text" to "one")); fail("expected redirect failure") }
            catch (error: ApiFailure) { assertEquals(307, error.status) }
            assertEquals(1, server.requestCount)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.get("//different.test/v1") } }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun assetUrlsResolveAgainstCoreAndRejectUnsafeSchemes() {
        val api = CoreApi(ServerConfig.normalize("https://core.test", "https://media.test:8443"))
        assertEquals("https://core.test/v1/media/a", api.assetUrl("/v1/media/a"))
        assertEquals("https://cdn.test/a", api.assetUrl("https://cdn.test/a"))
        listOf("http://core.test/a", "//elsewhere.test/a", "https://u:p@core.test/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { api.assetUrl(it) }
        }
    }
}
