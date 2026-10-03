package com.charactermemory.android.live

import com.charactermemory.android.data.text
import com.charactermemory.android.data.ConversationProjection
import com.google.gson.JsonObject

/** Display snapshots only: Core history remains authoritative and is always refreshed. */
internal data class ConversationCacheKey(
    val coreUrl: String,
    val mediaUrl: String,
    val group: Boolean,
    val targetId: String,
    val conversationId: String
)

internal data class ConversationSnapshot(
    val messages: List<JsonObject>,
    val historyCursor: String?,
    val historyPaged: Boolean
)

/** A disconnected latest page cannot continue the cached older-page cursor without a gap. */
internal object ConversationHistoryRefresh {
    fun reconcile(
        current: ConversationSnapshot,
        incoming: List<JsonObject>,
        atRequest: List<JsonObject>,
        pageCursor: String?,
        older: Boolean
    ): ConversationSnapshot {
        val requestedIds = atRequest.map { it.text("id") }.filter { it.isNotBlank() }.toSet()
        val reset = !older && requestedIds.isNotEmpty() && incoming.isNotEmpty() &&
            incoming.none { it.text("id") in requestedIds }
        // Keep newly arriving SSE rows while discarding only the disconnected request snapshot.
        val existing = if (reset) current.messages.filterNot { it.text("id") in requestedIds } else current.messages
        return ConversationSnapshot(
            ConversationProjection.reconcileHistory(existing, incoming, atRequest),
            if (older || reset || !current.historyPaged) pageCursor else current.historyCursor,
            if (older) true else if (reset) false else current.historyPaged
        )
    }
}

/** Main-thread-owned, bounded, detached snapshots; never persisted or replayed as writes. */
internal class ConversationSnapshotCache(
    private val maxConversations: Int = 12,
    private val maxMessages: Int = 200
) {
    init { require(maxConversations > 0 && maxMessages > 0) }
    private val entries = LinkedHashMap<ConversationCacheKey, ConversationSnapshot>(16, 0.75f, true)

    fun put(key: ConversationCacheKey, messages: List<JsonObject>, cursor: String?, paged: Boolean) {
        val trimmed = messages.size > maxMessages
        val retained = messages.takeLast(maxMessages).map { it.deepCopy() }
        entries[key] = ConversationSnapshot(retained,
            if (trimmed) retained.firstOrNull()?.text("id")?.takeIf { it.isNotBlank() } else cursor,
            paged || trimmed)
        while (entries.size > maxConversations) entries.remove(entries.keys.first())
    }

    fun get(key: ConversationCacheKey): ConversationSnapshot? = entries[key]?.let {
        it.copy(messages = it.messages.map { message -> message.deepCopy() })
    }

    fun clear() = entries.clear()
}

/** Success-only monotonic TTL, including valid empty responses, with one request per key. */
internal class RefreshWindow(
    private val ttlMillis: Long,
    private val clock: () -> Long,
    private val maxEntries: Int = 256
) {
    init { require(ttlMillis > 0 && maxEntries > 0) }
    data class Ticket(val key: String, val serial: Long)
    private val completed = LinkedHashMap<String, Long>(16, 0.75f, true)
    private val inFlight = mutableMapOf<String, Ticket>()
    private var serial = 0L

    fun begin(key: String, force: Boolean = false): Ticket? {
        if (key in inFlight) return null
        val last = completed[key]
        if (!force && last != null && clock() - last in 0 until ttlMillis) return null
        return Ticket(key, ++serial).also { inFlight[key] = it }
    }

    fun finish(ticket: Ticket, success: Boolean) {
        if (inFlight[ticket.key] != ticket) return
        inFlight.remove(ticket.key)
        if (success) {
            completed[ticket.key] = clock()
            while (completed.size > maxEntries) completed.remove(completed.keys.first())
        }
    }

    fun cancelInFlight() = inFlight.clear()
    fun clear() { cancelInFlight(); completed.clear() }
}
