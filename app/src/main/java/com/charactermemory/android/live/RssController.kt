package com.charactermemory.android.live

import com.charactermemory.android.data.*
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable

enum class RssPage { FEED, SOURCES, ADD, ARTICLE }
data class RssState(
    val page:RssPage=RssPage.FEED, val query:RssQuery=RssQuery(), val search:String="",
    val items:List<JsonObject> = emptyList(),val categories:List<JsonObject> = emptyList(),
    val sources:List<JsonObject> = emptyList(),val cursor:String?=null,val date:String?=null,
    val article:JsonObject?=null,val articleId:String?=null,
    val busy:Set<String> = emptySet(),val error:String?=null,val feedError:String?=null,
    val notice:String?=null,val cancelCandidate:JsonObject?=null,
    val name:String="",val url:String=""
)

/** Feature-local UI state. Every response belongs to this server and request version. */
class RssController(val repository:RssRepository,private val scope:CoroutineScope):Closeable {
    private val mutable=MutableStateFlow(RssState())
    val state=mutable.asStateFlow()
    private val jobs=mutableMapOf<String,Job>()
    private var closed=false
    private var started=false
    private var feedVersion=0L
    private var articleVersion=0L
    private var navigationVersion=0L
    private fun update(block:(RssState)->RssState) { if(!closed) mutable.update(block) }
    fun start() { if(started || closed) return;started=true;loadFeed();loadSources();loadCategories() }
    fun show(page:RssPage) { navigationVersion++;update { it.copy(page=page,error=null,cancelCandidate=null) };if(page==RssPage.SOURCES) loadSources() }
    fun back() { articleVersion++;jobs["article"]?.cancel();update {it.copy(busy=it.busy-"article")};show(if(state.value.page==RssPage.ADD) RssPage.SOURCES else RssPage.FEED) }
    fun search(value:String)=update { it.copy(search=value.take(200)) }
    fun submitSearch()=filter(state.value.query.copy(q=state.value.search.trim()))
    fun filter(query:RssQuery) { update { it.copy(query=query) };loadFeed() }
    fun name(value:String)=update { it.copy(name=value.take(240)) }
    fun url(value:String)=update { it.copy(url=value.take(2000)) }
    fun requestCancel(source:JsonObject?)=update { it.copy(cancelCandidate=source) }
    private fun launch(key:String,replace:Boolean=false,block:suspend ()->Unit) {
        if(closed || (!replace && key in state.value.busy)) return
        if(replace) jobs[key]?.cancel()
        update { it.copy(busy=it.busy+key) }
        jobs[key]=scope.launch {
            try { block() }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) { update { it.copy(error=message(error)) } }
            finally { if(currentCoroutineContext().isActive) update { it.copy(busy=it.busy-key) } }
        }
    }
    fun loadCategories()=launch("categories",replace=true) { val data=repository.categories();ensureActive();update { it.copy(categories=data.items("categories")) } }
    fun loadSources()=launch("sources",replace=true) { val data=repository.sources();ensureActive();update { it.copy(sources=data.items("sources")) } }
    fun loadFeed(append:Boolean=false) {
        if(append && ("feed" in state.value.busy || state.value.cursor==null)) return
        val version=++feedVersion
        val query=state.value.query
        val cursor=if(append) state.value.cursor else null
        update { it.copy(feedError=null,items=if(append) it.items else emptyList(),cursor=if(append) it.cursor else null) }
        launch("feed",replace=true) {
            try {
                val data=repository.items(query,cursor);ensureActive()
                if(version!=feedVersion) return@launch
                val date=data.obj("query").text("date").ifBlank { null }
                if(append && query.period=="today" && date!=state.value.date) {loadFeed();return@launch}
                update { it.copy(items=LiveRules.mergeRecords(if(append) it.items else emptyList(),data.items("items")),
                    cursor=LiveRules.nextCursor(data),date=date,feedError=null) }
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(error:Exception) {if(version==feedVersion) update { it.copy(feedError=message(error)) }}
        }
    }
    fun openArticle(item:JsonObject) { show(RssPage.ARTICLE);update {it.copy(articleId=item.text("id"),article=null)};loadArticle() }
    fun loadArticle() {
        val id=state.value.articleId ?: return
        val version=++articleVersion
        update {it.copy(error=null)}
        launch("article",replace=true) {val data=repository.article(id);ensureActive();if(version==articleVersion) update {it.copy(article=data.obj("item"))}}
    }
    fun add() {
        val name=state.value.name;val url=state.value.url;val navigation=navigationVersion
        launch("add") {
            val result=repository.add(url,name);ensureActive()
            receipt(result,"订阅已保存")
            if(navigation==navigationVersion && state.value.page==RssPage.ADD) {show(RssPage.SOURCES);update {it.copy(name="",url="")}}
            loadSources();loadFeed()
        }
    }
    fun refresh(id:String)=launch("source-$id") {
        val result=repository.refresh(id);ensureActive()
        update {it.copy(notice=if(result.flag("ok")) "抓取完成" else "订阅保留，抓取失败：${result.text("error")}")}
        loadSources();loadFeed()
    }
    fun enabled(id:String,value:Boolean)=launch("source-$id") {repository.enabled(id,value);ensureActive();loadSources()}
    fun cancel() {
        val source=state.value.cancelCandidate ?: return
        val id=source.text("id")
        launch("source-$id") {repository.cancel(id);ensureActive();update {it.copy(cancelCandidate=it.cancelCandidate.takeUnless {candidate->candidate?.text("id")==id},notice="已取消订阅，历史文章保留")};loadSources()}
    }
    fun restore(id:String)=launch("source-$id") {val result=repository.restore(id);ensureActive();receipt(result,"订阅已恢复");loadSources();loadFeed()}
    private fun receipt(result:JsonObject,label:String) = update {
        it.copy(notice=if(result.obj("refresh").flag("ok")) "$label，抓取完成" else "$label，抓取失败；可在订阅页重试：${result.obj("refresh").text("error")}",error=null)
    }
    private suspend fun ensureActive()=currentCoroutineContext().ensureActive()
    private fun message(error:Exception):String = if(error is ApiFailure && error.detail?.isJsonPrimitive==true)
        error.detail.asString else error.message ?: "请求失败，请重试；写入结果未确认时请先刷新订阅列表"
    override fun close() {closed=true;feedVersion++;articleVersion++;jobs.values.forEach {it.cancel()};jobs.clear()}
}
