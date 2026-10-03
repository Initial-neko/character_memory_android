package com.charactermemory.android

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic payloads mirror Core508c6f0 route schemas, not private user data. */
internal class P2FixtureDispatcher : Dispatcher() {
    val writes = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val sent = AtomicBoolean(false)
    val commented = AtomicBoolean(false)
    val externalReply = AtomicBoolean(false)
    val postReads = AtomicInteger(0)
    @Volatile var holdPostRead = false
    @Volatile var commentDelayMs = 0L
    val postReadStarted = CountDownLatch(1)
    val releasePostRead = CountDownLatch(1)
    var coreOffline = false
    var corruptImage = false
    private fun json(body: String, status: Int = 200) = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl?.encodedPath.orEmpty()
        if (coreOffline) return json("{\"detail\":\"fixture offline\"}", 503)
        if (request.method == "POST") {
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            writes.add(path to body)
            return when {
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
                    commented.set(true)
                    json("""{"comment":{"id":5,"content":"fixture comment","actor_type":"USER","author":{"id":"user","name":"我"}},"post":${post()},"thread_replies":[]}""")
                        .setBodyDelay(commentDelayMs, TimeUnit.MILLISECONDS)
                }
                else -> json("{\"detail\":\"unexpected fixture write\"}", 404)
            }
        }
        return when (path) {
            "/health" -> json("{\"status\":\"ok\"}")
            "/v1/characters" -> json("""{"characters":[{"id":"rin","name":"Rin","identity":"摄影师","description":"Fixture character"},{"id":"lex","name":"Lex","identity":"工程师"}],"soft_limit":10,"active_limit":20,"active_total":2,"overflow_count":0}""")
            "/v1/characters/summaries" -> json("""{"characters":[{"id":"rin","latest_message":{"id":1,"role":"assistant","content":"fixture opening","preview":"fixture opening","event_time":"2026-10-02T10:00:00+08:00"},"latest_assistant_message_id":1}]}""")
            "/v1/groups" -> json("""{"groups":[{"id":"g1","name":"Fixture Group","status":"ACTIVE","member_ids":["rin","lex"],"members":[{"id":"rin","name":"Rin"},{"id":"lex","name":"Lex"}]}]}""")
            "/v1/stickers" -> json("""{"stickers":[]}""")
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
                    .setBody("id: 100\nevent: $type\ndata: $raw\n\nid: 101\nevent: reaction_status\ndata: {\"state\":\"idle\"}\n\n")
            }
            "/v1/characters/rin/persona" -> json("""{"id":"rin","name":"Rin","description":"Fixture persona"}""")
            "/v1/space/posts" -> json("""{"posts":[${post()}],"total":1,"has_more":false,"next_before_id":null,"max_feed_items":10}""")
            "/v1/space/posts/1" -> {
                postReads.incrementAndGet()
                val snapshot = post()
                if (holdPostRead) { postReadStarted.countDown(); check(releasePostRead.await(10, TimeUnit.SECONDS)) }
                json("""{"post":$snapshot}""")
            }
            "/v1/ensembles" -> json("{\"build\":null}")
            "/v1/ensembles/e1" -> json(build())
            else -> json("{\"detail\":\"not in fixture\"}", 404)
        }
    }

    private fun post(): String {
        val entries = mutableListOf<String>()
        if (commented.get()) entries += """{"id":5,"actor_type":"USER","author":{"id":"user","name":"我"},"content":"fixture comment","reply_to_comment_id":null}"""
        if (externalReply.get()) {
            val replyTarget = if (commented.get()) "5" else "null"
            entries += """{"id":6,"actor_type":"CHARACTER","author":{"id":"rin","name":"Rin"},"content":"fixture external reply","reply_to_comment_id":$replyTarget}"""
        }
        val comments = entries.joinToString(",", "[", "]")
        return """{"id":1,"character_id":"rin","author":{"id":"rin","name":"Rin"},"content":"fixture space post","created_at":"2026-10-02T10:00:00+08:00","media_items":[],"comments":$comments,"like_count":0,"likes":[]}"""
    }

    private fun build() = """{"build":{"group_id":"e1","name":"Fixture Ensemble","status":"READY","ready_member_count":2,"failed_members":[],"capacity":{"confirmation_required":false},"drafts":[{"index":0,"status":"READY","canonical_name":"Rin","existing_character_id":"rin","draft":{"name":"Rin","description":"Fixture member"}},{"index":1,"status":"READY","canonical_name":"Lex","existing_character_id":"lex","draft":{"name":"Lex","description":"Fixture member"}}]}}"""
}
