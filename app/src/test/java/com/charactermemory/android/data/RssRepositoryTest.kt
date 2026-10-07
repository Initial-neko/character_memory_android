package com.charactermemory.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class RssRepositoryTest {
    @Test fun filtersAndCursorUseCoreContract() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repo = RssRepository(CoreApi(ServerConfig(server.url("/").toString(),server.url("/").toString())))
            server.enqueue(MockResponse().setBody("{\"items\":[],\"has_more\":false,\"query\":{\"date\":\"2026-10-07\"}}"))
            repo.items(RssQuery(period="today",q="AI & 中文",category="ai",sourceId="2"),"12")
            val url = server.takeRequest().requestUrl!!
            assertEquals("/v1/rss/items",url.encodedPath)
            assertEquals("today",url.queryParameter("period"))
            assertEquals("AI & 中文",url.queryParameter("q"))
            assertEquals("ai",url.queryParameter("category"))
            assertEquals("12",url.queryParameter("before_id"))
            assertEquals("2",url.queryParameter("source_id"))
        }
    }
    @Test fun failedImmediateFetchKeepsSourceWithoutSecondPost() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repo = RssRepository(CoreApi(ServerConfig(server.url("/").toString(),server.url("/").toString())))
            server.enqueue(MockResponse().setBody("{\"source\":{\"id\":4},\"refresh\":{\"ok\":false,\"error\":\"source unavailable\"}}"))
            val created = repo.add("https://example.com/feed","Example")
            assertEquals("4",created.obj("source").text("id"))
            assertFalse(created.obj("refresh").flag("ok"))
            assertEquals("POST",server.takeRequest().method)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun cancellationAndRestoreAreSeparateFromPause() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repo = RssRepository(CoreApi(ServerConfig(server.url("/").toString(),server.url("/").toString())))
            server.enqueue(MockResponse().setBody("{\"sources\":[]}")); repo.sources()
            assertEquals("true",server.takeRequest().requestUrl!!.queryParameter("include_cancelled"))
            server.enqueue(MockResponse().setBody("{\"history_retained\":true}")); repo.cancel("3")
            val cancelled=server.takeRequest(); assertEquals("DELETE",cancelled.method); assertEquals("/v1/rss/sources/3",cancelled.path)
            server.enqueue(MockResponse().setBody("{\"source\":{\"id\":3},\"refresh\":{\"ok\":true}}")); repo.restore("3")
            val restored=server.takeRequest(); assertEquals("POST",restored.method); assertEquals("/v1/rss/sources/3/restore",restored.path)
        }
    }
    @Test fun imageUsesConfiguredCoreAndPreservesRecordedRawUrl() {
        val repo=RssRepository(CoreApi(ServerConfig.normalize("https://node.example.ts.net")))
        val raw="https://EXAMPLE.COM/中文 photo.png"
        val proxy=repo.imageUrl("67",raw).toHttpUrl()
        assertEquals("node.example.ts.net",proxy.host)
        assertEquals("/v1/rss/items/67/image",proxy.encodedPath)
        assertEquals(raw,proxy.queryParameter("url"))
        assertEquals("",repo.imageUrl("67","javascript:bad"))
    }
}
