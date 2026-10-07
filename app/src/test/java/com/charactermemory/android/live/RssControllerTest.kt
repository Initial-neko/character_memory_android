package com.charactermemory.android.live

import com.charactermemory.android.data.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class RssControllerTest {
    @Test fun leavingDetailDiscardsItsDelayedFailure()=runBlocking {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"old detail failure"}""").setBodyDelay(250,TimeUnit.MILLISECONDS))
            server.start()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val controller=RssController(RssRepository(CoreApi(ServerConfig(server.url("/").toString(),server.url("/").toString()))),scope)
            try {
                controller.openArticle(jsonObject("id" to 1));assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))
                controller.back();delay(400)
                assertEquals(RssPage.FEED,controller.state.value.page);assertNull(controller.state.value.error)
                assertFalse("article" in controller.state.value.busy)
            } finally {controller.close();scope.cancel()}
        }
    }
    private suspend fun await(controller:RssController,predicate:(RssState)->Boolean) {
        withTimeout(4000) { while(!predicate(controller.state.value)) delay(10) }
    }
    @Test fun failedFetchStillSavesAndRepeatedTapDoesNotResubmit()=runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse = when {
                    request.method=="POST" -> MockResponse().setBody("""{"source":{"id":1},"refresh":{"ok":false,"error":"unavailable"}}""").setBodyDelay(200,TimeUnit.MILLISECONDS)
                    request.path!!.startsWith("/v1/rss/sources") -> MockResponse().setBody("""{"sources":[{"id":1}]}""")
                    else -> MockResponse().setBody("""{"items":[],"has_more":false}""")
                }
            }
            server.start()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val controller=RssController(RssRepository(CoreApi(ServerConfig(server.url("/").toString(),server.url("/").toString()))),scope)
            try {
                controller.show(RssPage.ADD);controller.url("https://example.com/rss");controller.add();controller.add()
                await(controller) { "add" !in it.busy && it.notice!=null }
                assertEquals(RssPage.SOURCES,controller.state.value.page)
                assertTrue(controller.state.value.notice!!.contains("抓取失败"))
                val requests=(1..server.requestCount).map { server.takeRequest() }
                assertEquals(1,requests.count {it.method=="POST"})
            } finally {controller.close();scope.cancel()}
        }
    }
    @Test fun latestFilterWinsAndReturningFromArticleKeepsFeed()=runBlocking {
        MockWebServer().use {server ->
            val oldDispatched=CountDownLatch(1)
            // Cancellation may close the first socket before a queued response is consumed.
            // Select responses by request identity, independently of socket scheduling.
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse = when {
                    request.requestUrl!!.encodedPath=="/v1/rss/items/2" ->
                        MockResponse().setBody("""{"item":{"id":2,"content_html":"<p>正文</p>"}}""")
                    request.requestUrl!!.queryParameter("q")=="new" ->
                        MockResponse().setBody("""{"items":[{"id":2,"title":"new"}],"has_more":true,"next_before_id":2}""")
                    else -> {
                        oldDispatched.countDown()
                        MockResponse().setBody("""{"items":[{"id":1,"title":"old"}]}""").setBodyDelay(500,TimeUnit.MILLISECONDS)
                    }
                }
            }
            server.start()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val controller=RssController(RssRepository(CoreApi(ServerConfig(server.url("/").toString(),server.url("/").toString()))),scope)
            try {
                controller.loadFeed();assertTrue("old request must reach its delayed response",oldDispatched.await(2,TimeUnit.SECONDS))
                controller.filter(RssQuery(period="all",q="new"));await(controller) {it.items.size==1}
                val before=controller.state.value
                assertEquals("2",before.items.single().text("id"))
                controller.openArticle(before.items.single());await(controller) {it.article!=null};controller.back()
                assertEquals(before.query,controller.state.value.query);assertEquals(before.items,controller.state.value.items)
                assertEquals(before.cursor,controller.state.value.cursor)
                delay(600);assertEquals("2",controller.state.value.items.single().text("id"))
            } finally {controller.close();scope.cancel()}
        }
    }
}
