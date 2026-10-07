package com.charactermemory.android.data

import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class RssQuery(val period:String="today",val q:String="",val category:String="",val sourceId:String="") {
    init { require(period in setOf("today","all") && q.length<=200 && (sourceId.isBlank() || sourceId.toLongOrNull()?.let {it>0}==true)) }
    fun parameters(cursor:String?=null):Map<String,String> = buildMap {
        put("period",period); put("limit","30")
        if(q.trim().isNotEmpty()) put("q",q.trim())
        if(category.isNotBlank()) put("category",category)
        if(sourceId.isNotBlank()) put("source_id",sourceId)
        if(cursor!=null) { require(cursor.toLongOrNull()?.let { it>0 }==true); put("before_id",cursor) }
    }
}

/** Core owns subscriptions, fetching, rich content and history. No client facts database. */
class RssRepository(private val api:CoreApi) {
    val origin:String get()=api.config.coreUrl
    fun imageBytes(url:String)=api.rssImage(url)
    suspend fun items(query:RssQuery,cursor:String?=null)=api.get("/v1/rss/items",query.parameters(cursor))
    suspend fun sources()=api.get("/v1/rss/sources",mapOf("include_cancelled" to "true"))
    suspend fun categories()=api.get("/v1/rss/categories")
    suspend fun article(id:String)=api.get("/v1/rss/items/${id(id)}")
    suspend fun add(url:String,name:String):JsonObject {
        val parsed=url.trim().toHttpUrlOrNull()
        require(parsed!=null && parsed.username.isEmpty() && parsed.password.isEmpty()) { "请输入有效的 HTTP(S) RSS 地址" }
        require(url.trim().length<=2000 && name.trim().length<=240) { "订阅名称或地址过长" }
        return api.post("/v1/rss/sources",jsonObject("feed_url" to url.trim(),"name" to name.trim()))
    }
    suspend fun refresh(id:String)=api.post("/v1/rss/sources/${id(id)}/refresh",jsonObject())
    suspend fun enabled(id:String,value:Boolean)=api.patch("/v1/rss/sources/${id(id)}",jsonObject("enabled" to value))
    suspend fun cancel(id:String)=api.delete("/v1/rss/sources/${id(id)}")
    suspend fun restore(id:String)=api.post("/v1/rss/sources/${id(id)}/restore",jsonObject())
    fun imageUrl(itemId:String,raw:String):String {
        val target=raw.toHttpUrlOrNull() ?: return ""
        if(target.username.isNotEmpty() || target.password.isNotEmpty() || raw.length>2000) return ""
        if(api.config.coreUrl.isBlank()) return ""
        return api.config.coreUrl.toHttpUrl().newBuilder()
            .encodedPath("/v1/rss/items/${id(itemId)}/image").addQueryParameter("url",raw).build().toString()
    }
    private fun id(value:String):String { require(value.toLongOrNull()?.let { it>0 }==true) { "无效的 RSS ID" };return value }
}
