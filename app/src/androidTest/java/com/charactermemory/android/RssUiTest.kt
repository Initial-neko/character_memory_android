package com.charactermemory.android

import android.content.Intent
import android.app.Instrumentation
import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import com.charactermemory.android.data.*
import com.charactermemory.android.live.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RssLiveCore

/** Mock HTTPS and optional actual Core tests are distinct evidence. No SSL bypass. */
@RunWith(AndroidJUnit4::class)
class RssUiTest {
    @get:Rule val compose=AndroidComposeTestRule(ActivityScenarioRule<MainActivity>(
        Intent(ApplicationProvider.getApplicationContext(),MainActivity::class.java).putExtra("p1_mock",true)),
        {rule -> var activity:MainActivity?=null;rule.scenario.onActivity {activity=it};requireNotNull(activity)})
    private lateinit var model:LiveViewModel
    private fun launch(config:ServerConfig,client:OkHttpClient=OkHttpClient()) {
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(compose.activity).okHttpClient(client).logger(coil.util.DebugLogger()).build())
        compose.runOnUiThread {
            model=LiveViewModel(compose.activity,{CoreApi(it,client)},config,"rss-ui-${UUID.randomUUID()}")
            compose.activity.setContent {LiveApp(model)}
        }
        compose.onNodeWithTag("live-tab-rss").assertIsDisplayed().performClick()
    }
    private fun screen(name:String) {
        compose.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1000)
        val directory=File(compose.activity.getExternalFilesDir(null),"rss-acceptance").apply {mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory,"$name.png")))
    }
    @Test fun subscriptionsSourceFiltersDetailsAndRecovery() {
        val requests=CopyOnWriteArrayList<String>()
        val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTrust=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTrust=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client=OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(),clientTrust.trustManager).build()
        MockWebServer().use {server ->
            server.useHttps(serverTrust.sslSocketFactory(),false)
            var cancelled=false
            val fallback=P2FixtureDispatcher()
            val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.BLUE)}
            val png=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    requests.add("${request.method} ${request.path}")
                    val path=request.requestUrl!!.encodedPath
                    val body=when {
                        path=="/v1/rss/sources" && request.method=="GET" -> """{"sources":[{"id":2,"name":"AIHOT 日报","feed_url":"https://example.com/feed","enabled":${!cancelled},"cancelled_at":${if(cancelled) "\"2026-10-07T00:00:00Z\"" else "null"},"item_count":3}]}"""
                        path=="/v1/rss/sources/2" && request.method=="DELETE" -> {cancelled=true;"""{"history_retained":true,"unsubscribed":true}"""}
                        path=="/v1/rss/sources/2/restore" -> {cancelled=false;"""{"source":{"id":2},"refresh":{"ok":true}}"""}
                        path=="/v1/rss/sources" && request.method=="POST" -> """{"source":{"id":3},"refresh":{"ok":false,"error":"upstream unavailable"}}"""
                        path=="/v1/rss/categories" -> """{"categories":[{"id":"ai","label":"AI"}]}"""
                        path=="/v1/rss/items" -> """{"items":[{"id":10,"source_id":2,"source_name":"AIHOT 日报","title":"AI 产品今天的变化","summary":"第一段简短摘要。第二段信息。","image_url":"https://image.example/test.png","published_at":"2026-10-07T01:00:00Z"},{"id":9,"source_id":2,"source_name":"AIHOT 日报","title":"没有图片的技术文章","summary":"纯文字卡片，保持自然高度。","published_at":"2026-10-07T00:00:00Z"}],"has_more":true,"next_before_id":9,"query":{"date":"2026-10-07"}}"""
                        path=="/v1/rss/items/10" -> """{"item":{"id":10,"title":"AI 产品今天的变化","source_name":"AIHOT 日报","content_html":"<p>这是第一段。</p><p>这是第二段，保留换行。</p><img src=\"https://image.example/test.png\">","url":"https://example.com/article/10","published_at":"2026-10-07T01:00:00Z"}}"""
                        path.endsWith("/image") -> return MockResponse().setHeader("Content-Type","image/png").setBody(okio.Buffer().write(png))
                        else -> return fallback.dispatch(request)
                    }
                    return MockResponse().setHeader("Content-Type","application/json").setBody(body)
                }
            }
            server.start()
            launch(ServerConfig(server.url("/").toString(),server.url("/").toString()),client)
            compose.waitUntil(15000) {compose.onAllNodesWithTag("rss-card-10").fetchSemanticsNodes().isNotEmpty()}
            screen("feed")
            compose.onNodeWithTag("rss-source-filter-2").performClick()
            compose.waitUntil(5000) {requests.any {it.contains("source_id=2") && it.contains("period=all")}}
            compose.onNodeWithTag("rss-search").performTextInput("AI")
            compose.onNodeWithTag("rss-search-submit").performClick()
            compose.onNodeWithTag("rss-category-ai").performClick()
            compose.waitUntil(5000) {requests.any {it.contains("q=AI") && it.contains("category=ai") && it.contains("source_id=2")}}
            compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("rss-more").assertIsEnabled();true}.getOrDefault(false)}
            compose.onNodeWithTag("rss-more").performScrollTo().performClick()
            compose.waitUntil(5000) {requests.any {it.contains("before_id=9") && it.contains("source_id=2") && it.contains("category=ai") && it.contains("q=AI")}}
            compose.onNodeWithTag("rss-card-10").performScrollTo().performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithTag("rss-article-title").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(10000) {requests.any {it.contains("/v1/rss/items/10/image")}}
            compose.waitUntil(10000) {UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).hasObject(By.textContains("这是第一段"))}
            screen("detail")
            compose.onNodeWithTag("rss-back").performClick()
            compose.onNodeWithTag("rss-search").assertTextContains("AI")
            compose.onNodeWithTag("rss-subscriptions").performClick()
            compose.waitUntil(5000) {compose.onAllNodesWithTag("rss-cancel-2").fetchSemanticsNodes().isNotEmpty()}
            screen("subscriptions")
            compose.onNodeWithTag("rss-cancel-2").performClick();screen("cancel-dialog")
            compose.onNodeWithTag("rss-cancel-confirm").performClick()
            compose.waitUntil(5000) {compose.onAllNodesWithTag("rss-restore-2").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("rss-restore-2").performClick()
            compose.waitUntil(5000) {compose.onAllNodesWithTag("rss-cancel-2").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("rss-add").performClick();screen("add")
            compose.onNodeWithTag("rss-add-url").performTextInput("https://example.com/rss")
            compose.onNodeWithTag("rss-add-save").performClick()
            compose.waitUntil(5000) {compose.onAllNodesWithTag("rss-notice").fetchSemanticsNodes().any {it.config.toString().contains("抓取失败")}}
            screen("fetch-failure")
            Assert.assertEquals(1,requests.count {it=="DELETE /v1/rss/sources/2"})
            Assert.assertEquals(1,requests.count {it=="POST /v1/rss/sources/2/restore"})
            Assert.assertEquals(1,requests.count {it=="POST /v1/rss/sources"})
            File(compose.activity.getExternalFilesDir(null),"rss-acceptance/mock-requests.json").writeText(com.google.gson.Gson().toJson(requests))
        }
    }
    @Test @RssLiveCore fun actualCoreFeedAndRichArticle() {
        val origin=InstrumentationRegistry.getArguments().getString("rss_real_core") ?: ""
        require(origin.isNotBlank()) {"Actual Core origin required; CI excludes RssLiveCore tests"}
        val ca=InstrumentationRegistry.getArguments().getString("rss_real_ca") ?: error("Acceptance CA required")
        val certificate=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(
            java.io.ByteArrayInputStream(android.util.Base64.decode(ca,android.util.Base64.DEFAULT))) as java.security.cert.X509Certificate
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
        launch(ServerConfig(origin,origin),client)
        compose.waitUntil(20000) {compose.onAllNodesWithTag("rss-all").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("rss-all").performClick()
        try {
            compose.waitUntil(20000) {compose.onAllNodes(hasTestTag("rss-feed-grid")).fetchSemanticsNodes().isNotEmpty()}
        } catch(error:Throwable) {
            screen("real-core-failure")
            File(compose.activity.getExternalFilesDir(null),"rss-acceptance/real-core-failure.txt").writeText(compose.onRoot().printToString())
            throw error
        }
        screen("real-core-feed")
        compose.onAllNodes(hasTestTag("rss-feed-grid")).onFirst().assertIsDisplayed()
        val id=InstrumentationRegistry.getArguments().getString("rss_real_item") ?: error("Real article ID required")
        val source=InstrumentationRegistry.getArguments().getString("rss_real_source") ?: error("Real source ID required")
        compose.waitUntil(10000) {compose.onAllNodesWithTag("rss-source-filter-$source").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("rss-source-filter-$source").performScrollTo().performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("rss-card-$id").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(30000) {compose.onAllNodes(hasTestTag("rss-cover-$id") and hasStateDescription("配图已加载"),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        screen("real-core-source-filter")
        compose.onNodeWithTag("rss-card-$id").performScrollTo().performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("rss-article-content").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(30000) {compose.onAllNodes(hasTestTag("rss-article-content") and hasStateDescription("正文已显示")).fetchSemanticsNodes().isNotEmpty()}
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.waitUntil(10000) {device.hasObject(By.textContains("本周关键词"))}
        screen("real-core-detail")
        fun web(view:android.view.View):android.webkit.WebView? {
            if(view is android.webkit.WebView) return view
            if(view is android.view.ViewGroup) for(index in 0 until view.childCount) web(view.getChildAt(index))?.let {return it}
            return null
        }
        compose.runOnUiThread {requireNotNull(web(compose.activity.window.decorView)).pageDown(true)}
        compose.waitUntil(10000) {device.hasObject(By.textContains("查看原文"))}
        screen("real-core-footer")
        val expected=runBlocking {CoreApi(ServerConfig(origin,origin),client).get("/v1/rss/items/$id").obj("item").text("url")}
        var original:String?=null
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action!=Intent.ACTION_VIEW) return null
                original=intent.dataString
                return Instrumentation.ActivityResult(Activity.RESULT_OK,null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {device.findObject(By.textContains("查看原文")).click();compose.waitUntil(5000) {original!=null};Assert.assertEquals(expected,original)}
        finally {instrumentation.removeMonitor(monitor)}
        compose.onNodeWithTag("rss-back").performClick()
        compose.onNodeWithTag("rss-all").assertIsDisplayed()
        compose.onNodeWithTag("rss-source-filter-$source").assertIsSelected()
        compose.onNodeWithTag("rss-subscriptions").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("rss-source-list").fetchSemanticsNodes().isNotEmpty()}
        screen("real-core-sources")
        File(compose.activity.getExternalFilesDir(null),"rss-acceptance/real-ui-result.json").writeText(com.google.gson.Gson().toJson(mapOf(
            "real_core" to true,"source_id" to source,"article_id" to id,"cover_loaded" to true,"body_visible" to true,"source_retained" to true,"original_intent_url" to original)))
    }
    @After fun cleanup() {if(::model.isInitialized) compose.runOnUiThread {model.deactivate()};coil.Coil.reset()}
}
