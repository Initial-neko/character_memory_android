package com.charactermemory.android

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic payloads mirror Core508c6f0 route schemas, not private user data. */
internal class P2FixtureDispatcher : Dispatcher() {
    @Volatile var brokenSticker = false
    val writes = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val reads = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<String>()
    val sent = AtomicBoolean(false)
    val commented = AtomicBoolean(false)
    val notificationRead = AtomicBoolean(false)
    val externalReply = AtomicBoolean(false)
    val postReads = AtomicInteger(0)
    @Volatile private var lastComment: JsonObject? = null
    @Volatile var holdPostRead = false
    @Volatile var commentDelayMs = 0L
    @Volatile var postReadStatus = 200
    @Volatile var notificationReadStatus = 200
    @Volatile var spaceFeedEmpty = false
    @Volatile var holdSpaceFeedRead = false
    @Volatile var usageStatus = 200
    @Volatile var keepCallStreamOpen = false
    @Volatile var usageResponseBody: String? = null
    val postReadStarted = CountDownLatch(1)
    val releasePostRead = CountDownLatch(1)
    val spaceFeedReadStarted = CountDownLatch(1)
    val releaseSpaceFeedRead = CountDownLatch(1)
    var coreOffline = false
    var corruptImage = false
    private fun json(body: String, status: Int = 200) = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        requests.add("${request.method} ${request.path}")
        if (coreOffline) return json("{\"detail\":\"fixture offline\"}", 503)
        if (request.method == "POST") {
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            writes.add(path to body)
            return when {
                path == "/v1/visual/direct/messages" || path == "/v1/visual/groups/g1/messages" -> json("""{"accepted":true,"event_id":11}""", 202)
                path == "/v1/space/notifications/7/read" -> {
                    if (notificationReadStatus != 200) json("""{"detail":"提醒标记失败"}""", notificationReadStatus)
                    else {
                        notificationRead.set(true)
                        json("""{"notification":{"id":7,"post_id":1,"comment_id":59,"reasons":["MENTION"],"created_at":"2026-10-02T09:00:00+00:00","read_at":"2026-10-02T10:00:00+00:00","comment":${notificationComment()},"post":${notificationPost()}} ,"unread_count":0}""")
                    }
                }
                path == "/v1/chat/messages" || path == "/v1/groups/g1/messages" -> {
                    sent.set(true)
                    val message = body.get("message")?.asString.orEmpty()
                    json("""{"accepted":true,"event_id":10,"turn_id":"t1","message":{"id":10,"role":"user","actor_type":"USER","actor_name":"我","character_id":"rin","conversation_id":"g1","content":"$message","event_time":"2026-10-02T10:01:00+08:00"}}""", 202)
                }
                path == "/v1/characters/draft" -> json("""{"draft":{"name":"Fixture Friend","description":"Deterministic character draft","age":22,"identity":"摄影师","personality":"温柔","background":"测试人物","speaking_style":"简洁","tags":[]}}""")
                path == "/v1/characters" -> json("""{"character":{"id":"fixture-new","name":"Fixture Friend"},"description":"Created fixture"}""")
                path == "/v1/ensembles/prepare" || path == "/v1/ensembles/e1/research" || path.contains("/members/") -> json(build())
                path == "/v1/ensembles/e1/confirm" -> json("""{"build":{"group_id":"g1","status":"ACTIVE","group":{"id":"g1","name":"Fixture Group","member_ids":["rin","lex"],"status":"ACTIVE"},"drafts":[]}}""")
                path == "/v1/ensembles/e1/cancel" -> json("""{"build":{"group_id":"e1","status":"CANCELLED","drafts":[]}}""")
                path == "/v1/characters/rin/images/rewrite" -> json("""{"ok":true,"character_id":"rin","purpose":"SCENE","prompt":"A quiet coffee shop","instruction":"coffee","used_avatar_reference":false}""")
                path == "/v1/characters/rin/images/generate" && corruptImage -> json("""{"ok":true,"image":{"data_url":"data:image/jpeg;base64,YWJj","mime_type":"image/jpeg"}}""")
                path == "/v1/characters/rin/images/generate" -> json("""{"ok":true,"character_id":"rin","purpose":"SCENE","prompt":"A quiet coffee shop","image":{"filename":"fixture.png","data_url":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAGUlEQVR4nGMwqrj0nxLMMGrAqAGjBgwXAwDHCHsfIPP/yQAAAABJRU5ErkJggg==","mime_type":"image/png","size_bytes":82,"media_id":null,"url":"","source":"AI_GENERATED_DRAFT"}}""")
                path == "/v1/space/posts/1/comments" -> {
                    val comment = JsonObject().apply {
                        addProperty("id", 5); addProperty("post_id", "1"); addProperty("character_id", "user")
                        addProperty("actor_type", "USER"); addProperty("content", body.get("content")?.asString.orEmpty())
                        addProperty("mentions_user", false)
                        add("mentions", body.get("mentions")?.deepCopy() ?: com.google.gson.JsonArray())
                        add("author", JsonParser.parseString("""{"id":"user","name":"我"}""").asJsonObject)
                    }
                    lastComment = comment
                    commented.set(true)
                    val response = JsonObject().apply {
                        add("comment", comment); add("post", JsonParser.parseString(post()).asJsonObject)
                        add("thread_replies", com.google.gson.JsonArray())
                    }
                    json(response.toString()).setBodyDelay(commentDelayMs, TimeUnit.MILLISECONDS)
                }
                else -> json("{\"detail\":\"unexpected fixture write\"}", 404)
            }
        }
        reads.add(path + request.requestUrl?.encodedQuery?.let { "?$it" }.orEmpty())
        return when (path) {
            "/health" -> json("{\"status\":\"ok\"}")
            "/v1/characters" -> if (request.requestUrl?.queryParameter("include_deferred") == "true")
                json("""{"characters":[{"id":"rin","name":"Rin","identity":"摄影师","description":"Fixture character"},{"id":"lex","name":"Lex","identity":"工程师"},{"id":"nova","name":"Nova","deferred":true}],"soft_limit":10,"active_limit":20,"active_total":3,"overflow_count":0}""")
                else json("""{"characters":[{"id":"rin","name":"Rin","identity":"摄影师","description":"Fixture character"},{"id":"lex","name":"Lex","identity":"工程师"}],"soft_limit":10,"active_limit":20,"active_total":2,"overflow_count":0}""")
            "/v1/characters/summaries" -> json("""{"characters":[{"id":"rin","latest_message":{"id":1,"role":"assistant","content":"fixture opening","preview":"fixture opening","event_time":"2026-10-02T10:00:00+08:00"},"latest_assistant_message_id":1}]}""")
            "/v1/groups" -> json("""{"groups":[{"id":"g1","name":"Fixture Group","status":"ACTIVE","member_ids":["rin","lex"],"members":[{"id":"rin","name":"Rin"},{"id":"lex","name":"Lex"}]}]}""")
            "/v1/stickers" -> json("""{"scope":"global","source":"fixture","stickers":[{"id":"wave","label":"挥手","pack_id":"default","pack_name":"内置","url":"/v1/stickers/wave/asset"},{"id":"sparkle","label":"星光","pack_id":"custom","pack_name":"自定义","url":"/v1/stickers/sparkle/asset"}]}""")
            "/v1/stickers/sparkle/asset" -> MockResponse().setHeader("Content-Type", "image/svg+xml")
                .setBody("""<svg xmlns="http://www.w3.org/2000/svg" width="160" height="160" viewBox="0 0 160 160"><circle cx="80" cy="80" r="64" fill="#ffc7cb"/><path d="M40 80 Q80 130 120 80" fill="none" stroke="#5b4b47" stroke-width="6"/></svg>""")
            "/v1/stickers/wave/asset" -> if (brokenSticker) MockResponse().setHeader("Content-Type", "image/png").setBody("invalid PNG") else MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody(Buffer().write(android.util.Base64.decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZUAAAAASUVORK5CYII=",
                    android.util.Base64.DEFAULT)))
            "/v1/llm/usage" -> json(usageResponseBody ?: """{"window_hours":1,"summary":{"requests":2,"logical_calls":1,"input_tokens":320,"output_tokens":90,"total_tokens":410,"token_known_requests":2,"retried_logical_calls":0,"errors":0,"avg_latency_ms":12.5,"input_chars":80,"output_chars":50,"token_coverage":1.0},"by_feature":[{"feature":"CHAT","purpose":"DIRECT_REACTION","requests":2,"logical_calls":1,"input_tokens":320,"output_tokens":90,"total_tokens":410,"token_known_requests":2,"retried_logical_calls":0,"errors":0,"avg_latency_ms":12.5,"input_chars":80,"output_chars":50,"input_char_share":1.0,"requests_per_logical_call":2.0,"token_coverage":1.0}],"by_model":[{"model":"fixture-model","requests":2,"logical_calls":1,"input_tokens":320,"output_tokens":90,"total_tokens":410,"token_known_requests":2,"retried_logical_calls":0,"errors":0,"avg_latency_ms":12.5,"input_chars":80,"output_chars":50,"input_char_share":1.0,"requests_per_logical_call":2.0,"token_coverage":1.0}],"recent":[{"id":8,"created_at":"2026-10-02T02:00:00+00:00","provider":"example.test","model":"fixture-model","feature":"CHAT","purpose":"DIRECT_REACTION","character_id":"rin","attempt":1,"status":"SUCCESS","input_tokens":320,"output_tokens":90,"total_tokens":410,"duration_ms":12.5,"usage_source":"PROVIDER","error_type":"","request_id":"fixture-8"}]}""", usageStatus)
            "/v1/space/notifications" -> if (notificationRead.get()) json("""{"notifications":[],"unread_count":0,"max_items":100,"background_push":false}""")
                else json("""{"notifications":[${notification()}],"unread_count":1,"max_items":100,"background_push":false}""")
            "/v1/characters/rin/avatar", "/v1/characters/lex/avatar" -> json("""{"avatar_url":""}""")
            "/v1/chat/history-page", "/v1/groups/g1/history" -> {
                val accepted = if (sent.get()) """,{"id":10,"role":"user","actor_type":"USER","actor_name":"我","content":"fixture send","event_time":"2026-10-02T10:01:00+08:00"}""" else ""
                json("""{"character_id":"rin","group":{"id":"g1","name":"Fixture Group","member_ids":["rin","lex"]},"messages":[{"id":1,"role":"assistant","actor_type":"CHARACTER","actor_id":"rin","actor_name":"Rin","content":"fixture opening","event_time":"2026-10-02T10:00:00+08:00"}$accepted],"has_more":false,"next_before_id":null}""")
            }
            "/v1/events/stream" -> {
                val group = request.requestUrl?.queryParameter("scope") == "group"
                val type = if (group) "group_character_event" else "character_event"
                val raw = """{"id":1,"character_id":"rin","conversation_id":"g1","actor_type":"CHARACTER","actor_id":"rin","content":"fixture opening","event_time":"2026-10-02T10:00:00+08:00","metadata":{}}"""
                MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("id: 100\nevent: $type\ndata: $raw\n\nid: 101\nevent: reaction_status\ndata: {\"state\":\"idle\"}\n\n" +
                        if (keepCallStreamOpen) ": keepalive\n\n".repeat(10000) else "")
                    .apply { if (keepCallStreamOpen) throttleBody(256, 100, TimeUnit.MILLISECONDS) }
            }
            "/v1/characters/rin/persona" -> json("""{"id":"rin","name":"Rin","description":"Fixture persona"}""")
            "/v1/space/posts" -> {
                val response = if (spaceFeedEmpty) {
                    json("""{"posts":[],"total":0,"has_more":false,"next_before_id":null,"max_feed_items":10}""")
                } else {
                    json("""{"posts":[${post()}],"total":1,"has_more":false,"next_before_id":null,"max_feed_items":10}""")
                }
                if (holdSpaceFeedRead) {
                    spaceFeedReadStarted.countDown()
                    check(releaseSpaceFeedRead.await(10, TimeUnit.SECONDS))
                }
                response
            }
            "/v1/space/posts/1" -> {
                postReads.incrementAndGet()
                val snapshot = post()
                if (holdPostRead) { postReadStarted.countDown(); check(releasePostRead.await(10, TimeUnit.SECONDS)) }
                json("""{"post":$snapshot}""", postReadStatus)
            }
            "/v1/ensembles" -> json("{\"build\":null}")
            "/v1/ensembles/e1" -> json(build())
            else -> json("{\"detail\":\"not in fixture\"}", 404)
        }
    }

    private fun post(): String {
        val entries = mutableListOf("""{"id":51,"post_id":1,"actor_type":"USER","author":{"id":"user","name":"我"},"content":"保留的已有评论"}""")
        if (commented.get()) entries += lastComment?.toString().orEmpty()
        if (externalReply.get()) {
            entries += """{"id":4,"post_id":1,"actor_type":"USER","author":{"id":"user","name":"我"},"content":"你觉得呢？"}"""
            val replyTarget = if (commented.get()) "5" else "4"
            entries += """{"id":6,"post_id":1,"actor_type":"CHARACTER","author":{"id":"rin","name":"Rin"},"content":"fixture external reply","reply_to_comment_id":$replyTarget}"""
        }
        val comments = entries.joinToString(",", "[", "]")
        return """{"id":1,"character_id":"rin","author":{"id":"rin","name":"Rin"},"content":"fixture space post","created_at":"2026-10-02T10:00:00+08:00","media_items":[{"media_id":"media-1","media_type":"IMAGE","label":"fixture media","url":"","available":false}],"comments":$comments,"like_count":1,"likes":[{"character_id":"rin","character":{"id":"rin","name":"Rin"},"created_at":"2026-10-02T09:30:00+08:00"}]}"""
    }

    private fun notification() = """{"id":7,"post_id":1,"comment_id":59,"reasons":["MENTION"],"created_at":"2026-10-02T09:00:00+00:00","read_at":null,"comment":${notificationComment()},"post":${notificationPost()}}"""
    private fun notificationComment() = """{"id":59,"post_id":1,"character_id":"rin","actor_type":"CHARACTER","content":"我看到你喊我啦。","mentions":[],"mentions_user":true,"author":{"id":"rin","name":"Rin"}}"""
    private fun notificationPost() = """{"id":1,"character_id":"rin","author":{"id":"rin","name":"Rin"},"content":"fixture space post","created_at":"2026-10-02T10:00:00+08:00"}"""

    private fun build() = """{"build":{"group_id":"e1","name":"Fixture Ensemble","status":"READY","ready_member_count":2,"failed_members":[],"capacity":{"confirmation_required":false},"drafts":[{"index":0,"status":"READY","canonical_name":"Rin","existing_character_id":"rin","draft":{"name":"Rin","description":"Fixture member"}},{"index":1,"status":"READY","canonical_name":"Lex","existing_character_id":"lex","draft":{"name":"Lex","description":"Fixture member"}}]}}"""
}
