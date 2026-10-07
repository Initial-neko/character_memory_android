package com.charactermemory.android.live

import com.charactermemory.android.data.*
import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Input is Core-sanitized HTML. Rewrite every image; CSP supplies a second boundary. */
object RssArticleHtml {
    fun escape(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
    private fun unescape(value:String):String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt);").replace(value) {
        when(val entity=it.groupValues[1]) {
            "amp"->"&";"quot"->"\"";"apos"->"'";"lt"->"<";"gt"->">"
            else -> runCatching {String(Character.toChars(if(entity.startsWith("#x")) entity.drop(2).toInt(16) else entity.drop(1).toInt()))}.getOrDefault("")
        }
    }
    fun render(item:JsonObject,repository:RssRepository):String {
        val raw=item.text("content_html")
        val body=if(raw.isBlank()) "<p>${escape(item.text("content_text")).replace("\n","<br>")}</p>" else
            Regex("<img\\b[^>]*>",RegexOption.IGNORE_CASE).replace(raw) { image ->
                val src=Regex("\\bsrc\\s*=\\s*([\"'])(.*?)\\1",RegexOption.IGNORE_CASE).find(image.value)?.groupValues?.get(2)
                val proxy=src?.let {repository.imageUrl(item.text("id"),unescape(it))}.orEmpty()
                if(proxy.isBlank()) "" else "<img src=\"${escape(proxy)}\" alt=\"文章图片\" loading=\"lazy\">"
            }
        val original=item.text("url").toHttpUrlOrNull()?.takeIf {it.username.isEmpty() && it.password.isEmpty()}
        val link=if(original==null) "原文链接不可用" else "<a href=\"${escape(item.text("url"))}\">查看原文 ↗</a>"
        val published=item.text("published_at")
        val time=if(published.isBlank()) "暂无发布时间" else runCatching {OffsetDateTime.parse(published).atZoneSameInstant(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}.getOrDefault(published)
        val footer="<footer style=\"border-top:1px solid #263956;margin-top:24px;padding-top:16px;display:flex;justify-content:space-between;gap:12px;font-size:12px\">$link<time>${escape(time)}</time></footer>"
        return """<!doctype html><html lang="zh"><head><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="referrer" content="no-referrer"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><style>html{color-scheme:dark}body{margin:0;padding:12px 16px 32px;background:#091120;color:#eaf1ff;font:16px/1.8 sans-serif;overflow-wrap:anywhere}p,div,ul,ol,blockquote,pre{margin:0 0 18px}img{max-width:100%;height:auto;display:block;border-radius:12px;margin:18px auto}a{color:#79a9ff}pre{white-space:pre-wrap;background:#151f31;padding:12px}table{max-width:100%;display:block;overflow:auto}blockquote{border-left:3px solid #79a9ff;padding-left:12px}</style></head><body>$body$footer</body></html>"""
    }
}
