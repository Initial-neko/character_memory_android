package com.charactermemory.android.live

import android.content.Intent
import android.net.Uri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.charactermemory.android.data.*
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
internal fun LiveRss(controller:RssController,grid:LazyStaggeredGridState,configure:()->Unit) {
    val state by controller.state.collectAsState()
    BackHandler(state.page!=RssPage.FEED) {controller.back()}
    Column(Modifier.fillMaxSize().testTag("rss-root")) {
        Row(Modifier.fillMaxWidth().heightIn(min=60.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(state.page!=RssPage.FEED) LiveIconAction(LiveSymbol.BACK,"返回信息流","rss-back",onClick=controller::back)
            Text(when(state.page) {RssPage.FEED->"信息流";RssPage.SOURCES->"RSS 订阅";RssPage.ADD->"添加订阅";RssPage.ARTICLE->"文章详情"},
                fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            if(state.page==RssPage.FEED) TextButton(onClick={controller.show(RssPage.SOURCES)},modifier=Modifier.testTag("rss-subscriptions")) {Text("管理订阅")}
            if(state.page==RssPage.SOURCES) TextButton(onClick={controller.show(RssPage.ADD)},modifier=Modifier.testTag("rss-add")) {Text("＋ 添加")}
        }
        if(controller.repository.origin.isBlank()) {
            RssEmpty("连接电脑上的 Core 后，即可查看 RSS 信息流。","连接设置",configure)
            return@Column
        }
        state.error?.let {LiveFeedback(it,"rss-error",true)}
        state.notice?.let {LiveFeedback(it,"rss-notice")}
        when(state.page) {
            RssPage.FEED -> RssFeed(state,controller,grid)
            RssPage.SOURCES -> RssSources(state,controller)
            RssPage.ADD -> RssAdd(state,controller)
            RssPage.ARTICLE -> RssArticle(state,controller)
        }
    }
    state.cancelCandidate?.let {source ->
        val id=source.text("id")
        AlertDialog(onDismissRequest={controller.requestCancel(null)},title={Text("取消订阅？")},
            text={Text("${source.text("name")} 将停止抓取，历史文章会保留。以后可以恢复订阅，重新开始抓取。")},
            confirmButton={TextButton(onClick=controller::cancel,enabled="source-$id" !in state.busy,modifier=Modifier.testTag("rss-cancel-confirm")) {Text("取消订阅")}},
            dismissButton={TextButton(onClick={controller.requestCancel(null)}) {Text("保留订阅")}})
    }
}

@Composable
private fun RssFeed(state:RssState,controller:RssController,grid:LazyStaggeredGridState) {
    OutlinedTextField(value=state.search,onValueChange=controller::search,placeholder={Text("按文章标题搜索")},singleLine=true,
        modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp).testTag("rss-search"),shape=RoundedCornerShape(20.dp),
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={controller.submitSearch()}),
        trailingIcon={TextButton(onClick=controller::submitSearch,modifier=Modifier.testTag("rss-search-submit")) {Text("搜索")}})
    Row(Modifier.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        FilterChip(selected=state.query.period=="today",onClick={controller.filter(state.query.copy(period="today"))},label={Text("今天最新")},modifier=Modifier.testTag("rss-today"))
        FilterChip(selected=state.query.period=="all",onClick={controller.filter(state.query.copy(period="all"))},label={Text("全部历史")},modifier=Modifier.testTag("rss-all"))
        Spacer(Modifier.weight(1f));LiveIconAction(LiveSymbol.REFRESH,"刷新文章列表","rss-feed-refresh","feed" !in state.busy) {controller.loadFeed()}
    }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        item {FilterChip(selected=state.query.sourceId.isBlank(),onClick={controller.filter(state.query.copy(sourceId="",period="today"))},label={Text("全部订阅")},modifier=Modifier.testTag("rss-source-filter-all"))}
        items(state.sources,key={it.text("id")}) {source -> FilterChip(selected=state.query.sourceId==source.text("id"),onClick={controller.filter(state.query.copy(sourceId=source.text("id"),period="all"))},
            label={Text("${source.text("name")} · ${source.text("item_count")}")},modifier=Modifier.testTag("rss-source-filter-${source.text("id")}"))}
    }
    LazyRow(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        item {FilterChip(selected=state.query.category.isBlank(),onClick={controller.filter(state.query.copy(category=""))},label={Text("全部类型")},modifier=Modifier.testTag("rss-category-all"))}
        items(state.categories) {category -> FilterChip(selected=state.query.category==category.text("id"),onClick={controller.filter(state.query.copy(category=category.text("id")))},
            label={Text(category.text("label"))},modifier=Modifier.testTag("rss-category-${category.text("id")}"))}
    }
    state.feedError?.let {error -> LiveFeedback(error,"rss-feed-error",true);TextButton(onClick={controller.loadFeed()},modifier=Modifier.testTag("rss-feed-retry")) {Text("重试加载")}}
    if(state.items.isEmpty()) {
        if("feed" in state.busy) RssLoading() else RssEmpty(
            if(state.query.q.isNotBlank() || state.query.category.isNotBlank()) "没有符合标题筛选的文章。" else if(state.query.period=="today") "今天还没有文章，可查看全部历史或管理订阅。" else "还没有文章，请添加订阅并抓取。",
            "管理订阅",{controller.show(RssPage.SOURCES)})
    } else LazyVerticalStaggeredGrid(columns=StaggeredGridCells.Fixed(2),state=grid,
        modifier=Modifier.fillMaxSize().testTag("rss-feed-grid"),contentPadding=PaddingValues(12.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp),verticalItemSpacing=10.dp) {
        items(state.items,key={it.text("id")}) {item -> RssCard(item,controller)}
        item(span=StaggeredGridItemSpan.FullLine) {
            if(state.cursor!=null) TextButton(onClick={controller.loadFeed(true)},enabled="feed" !in state.busy,modifier=Modifier.fillMaxWidth().testTag("rss-more")) {Text(if("feed" in state.busy) "加载中…" else "加载更多")}
            else Text("已显示全部结果",color=LiveMuted,modifier=Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun RssCard(item:JsonObject,controller:RssController) {
    val context=LocalContext.current
    val id=item.text("id")
    var imageFailed by remember(id,item.text("image_url")) {mutableStateOf(false)}
    var imageLoaded by remember(id,item.text("image_url")) {mutableStateOf(false)}
    var imageRetry by remember(id) {mutableIntStateOf(0)}
    Card(onClick={controller.openArticle(item)},shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=LivePanel),modifier=Modifier.fillMaxWidth().testTag("rss-card-$id")) {
        val image=controller.repository.imageUrl(id,item.text("image_url"))
        val request=remember(context,image,imageRetry) {ImageRequest.Builder(context).data(if(imageRetry==0) image else "$image&retry=$imageRetry").allowHardware(false).build()}
        if(image.isNotBlank()) {
            if(imageFailed) TextButton(onClick={imageFailed=false;imageRetry++},modifier=Modifier.fillMaxWidth().height(100.dp).testTag("rss-image-retry-$id")) {Text("图片加载失败 · 重试")}
            else AsyncImage(model=request,contentDescription="文章配图",contentScale=ContentScale.Crop,
                onError={imageFailed=true},onSuccess={imageLoaded=true},modifier=Modifier.fillMaxWidth().height(152.dp).testTag("rss-cover-$id").semantics {stateDescription=if(imageLoaded) "配图已加载" else "配图加载中"})
        }
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(item.text("title"),fontWeight=FontWeight.Bold,maxLines=3,overflow=TextOverflow.Ellipsis,fontSize=15.sp)
            if(item.text("summary").isNotBlank()) Text(item.text("summary"),color=LiveMuted,maxLines=3,overflow=TextOverflow.Ellipsis,fontSize=12.sp)
            Text(item.text("source_name"),color=LiveAccent,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=11.sp)
            Text(rssTime(item.text("published_at")),color=LiveMuted,fontSize=11.sp)
        }
    }
}

@Composable
private fun RssSources(state:RssState,controller:RssController) {
    if("sources" in state.busy && state.sources.isEmpty()) {RssLoading();return}
    if(state.sources.isEmpty()) {RssEmpty("还没有 RSS 订阅。","添加订阅",{controller.show(RssPage.ADD)});return}
    LazyColumn(Modifier.fillMaxSize().testTag("rss-source-list"),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        items(state.sources,key={it.text("id")}) {source ->
            val id=source.text("id");val busy="source-$id" in state.busy;val cancelled=source.text("cancelled_at").isNotBlank()
            Card(colors=CardDefaults.cardColors(containerColor=LivePanel),modifier=Modifier.fillMaxWidth().testTag("rss-source-$id")) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(source.text("name"),fontWeight=FontWeight.Bold,fontSize=18.sp,modifier=Modifier.weight(1f))
                        if(!cancelled) Switch(checked=source.flag("enabled"),onCheckedChange={controller.enabled(id,it)},enabled=!busy,modifier=Modifier.testTag("rss-source-enabled-$id"))
                    }
                    Text(source.text("feed_url"),color=LiveMuted,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(if(cancelled) "已取消 · 历史文章保留" else if(source.flag("enabled")) "订阅中 · 自动抓取" else "已暂停抓取",color=LiveAccent,fontSize=12.sp)
                    Text("最近成功：${rssTime(source.text("last_success_at"))} · ${source.text("item_count")} 篇文章",color=LiveMuted,fontSize=12.sp)
                    if(source.text("last_error").isNotBlank()) Text("抓取失败：${source.text("last_error")}",color=Color(0xFFFFA8A8),fontSize=12.sp,modifier=Modifier.testTag("rss-source-error-$id"))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(cancelled) TextButton(onClick={controller.restore(id)},enabled=!busy,modifier=Modifier.testTag("rss-restore-$id")) {Text(if(busy) "恢复中…" else "恢复订阅")}
                        else {
                            TextButton(onClick={controller.refresh(id)},enabled=!busy,modifier=Modifier.testTag("rss-refresh-$id")) {Text(if(busy) "抓取中…" else "立即抓取")}
                            TextButton(onClick={controller.requestCancel(source)},enabled=!busy,modifier=Modifier.testTag("rss-cancel-$id")) {Text("取消订阅",color=Color(0xFFFFA8A8))}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RssAdd(state:RssState,controller:RssController) {
    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("粘贴 RSS / Atom 地址。保存后电脑会立即抓取一次。",color=LiveMuted)
        OutlinedTextField(value=state.name,onValueChange=controller::name,label={Text("订阅名称（可选）")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("rss-add-name"))
        OutlinedTextField(value=state.url,onValueChange=controller::url,label={Text("RSS 地址")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),singleLine=true,modifier=Modifier.fillMaxWidth().testTag("rss-add-url"))
        Button(onClick=controller::add,enabled=state.url.isNotBlank() && "add" !in state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("rss-add-save")) {Text(if("add" in state.busy) "保存并抓取中…" else "保存并抓取")}
        Text("抓取失败时订阅仍会保留，可以在订阅管理中重试。",color=LiveMuted,fontSize=12.sp)
    }
}

@Composable
private fun RssArticle(state:RssState,controller:RssController) {
    val item=state.article
    if(item==null) {if("article" in state.busy) RssLoading() else RssEmpty("文章加载失败。","重试",controller::loadArticle);return}
    Column(Modifier.fillMaxSize()) {
        Text(item.text("title"),fontSize=23.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp).testTag("rss-article-title"))
        Text("${item.text("source_name")} · ${rssTime(item.text("published_at"))}",color=LiveMuted,fontSize=12.sp,modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp))
        val html=remember(item) {RssArticleHtml.render(item,controller.repository)}
        var rendered by remember(html) {mutableStateOf(false)}
        AndroidView(factory={ctx -> WebView(ctx).apply {
            settings.javaScriptEnabled=false;settings.allowFileAccess=false;settings.allowContentAccess=false
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.domStorageEnabled=false;setBackgroundColor(0xFF091120.toInt())
            webViewClient=object:WebViewClient() {
                override fun onPageFinished(view:WebView,url:String) {rendered=true}
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {if(request.isForMainFrame) openRssLink(ctx,request.url.toString());return true}
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                    if(request.isForMainFrame && request.url.scheme=="data") return null
                    return try {
                        val (bytes,mime)=controller.repository.imageBytes(request.url.toString())
                        WebResourceResponse(mime,null,ByteArrayInputStream(bytes))
                    } catch(_:Exception) {WebResourceResponse("text/plain","utf-8",403,"Blocked",emptyMap(),ByteArrayInputStream(byteArrayOf()))}
                }
            }
        }},update={view -> if(view.tag!=html) {view.tag=html;view.loadDataWithBaseURL(controller.repository.origin+"/",html,"text/html","utf-8",null)}},
            onRelease={it.stopLoading();it.destroy()},modifier=Modifier.fillMaxWidth().weight(1f).testTag("rss-article-content").semantics {stateDescription=if(rendered) "正文已显示" else "正文加载中"})
    }
}

private fun validRssLink(value:String)=runCatching {val uri=Uri.parse(value);uri.scheme in setOf("https","http") && !uri.host.isNullOrBlank() && uri.userInfo==null}.getOrDefault(false)
private fun openRssLink(context:android.content.Context,value:String) {if(validRssLink(value)) runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(value)))} }
private fun rssTime(value:String):String = if(value.isBlank()) "暂无时间" else runCatching {OffsetDateTime.parse(value).atZoneSameInstant(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}.getOrDefault(value.take(16))
@Composable private fun RssLoading() {Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {CircularProgressIndicator(modifier=Modifier.testTag("rss-loading"))}}
@Composable private fun RssEmpty(message:String,action:String,onClick:()->Unit) {Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {Text(message,color=LiveMuted);TextButton(onClick=onClick) {Text(action)}}}
