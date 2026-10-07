package com.charactermemory.android.live

import com.charactermemory.android.data.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class RssArticleHtmlTest {
    @Test fun paragraphsSurviveAndImagesOnlyUseCoreProxy() {
        val repo=RssRepository(CoreApi(ServerConfig.normalize("https://node.example.ts.net")))
        val item=jsonObject("id" to 3,"content_html" to "<p>第一段</p><p>第二段<br>换行</p><img src=\"https://img.example/a?x=1&amp;y=2\" alt=\"图\">")
        val html=RssArticleHtml.render(item,repo)
        assertTrue(html.contains("<p>第一段</p><p>第二段<br>换行</p>"))
        val src=Regex("<img src=\"([^\"]+)\"").find(html)!!.groupValues[1].replace("&amp;","&").toHttpUrl()
        assertEquals("node.example.ts.net",src.host)
        assertEquals("https://img.example/a?x=1&y=2",src.queryParameter("url"))
        assertTrue(html.contains("default-src 'none'"))
    }
    @Test fun plainTextEscapesMarkupAndPreservesLineBreaks() {
        val repo=RssRepository(CoreApi(ServerConfig.normalize("https://node.example.ts.net")))
        val html=RssArticleHtml.render(jsonObject("id" to 3,"content_text" to "<script>bad</script>\n第二段"),repo)
        assertTrue(html.contains("&lt;script&gt;bad&lt;/script&gt;"));assertTrue(html.contains("<br>第二段"))
    }
}
