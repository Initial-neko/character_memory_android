package com.charactermemory.android.audio

sealed class CallEffect {
    data class Send(val text: String, val visualFrame: String? = null) : CallEffect()
    data class Speak(val id: String, val text: String, val characterId: String) : CallEffect()
}

/** Main-thread call reducer. Receipt identity fences SSE; playback and reaction are separate gates. */
class CallTurnQueue {
    var active = false; private set
    var waiting = false; private set
    val awaitingReaction get() = waiting && !complete
    val pendingCount get() = pending.size
    private var receipt: String? = null
    private var complete = false
    private var playing: String? = null
    private val pending = ArrayDeque<CallEffect.Send>()
    private val replies = ArrayDeque<CallEffect.Speak>()
    private val seen = mutableSetOf<String>()
    private val early = mutableListOf<Pair<String, CallEffect.Speak>>()
    private val earlyCompletions = mutableSetOf<String>()
    private val externalReceipts = linkedSetOf<String>()

    fun start() { end(); active = true }
    /** User mute discards input not yet submitted; accepted facts and replies remain. */
    fun discardPendingInput() { pending.removeAll { it.visualFrame == null } }
    fun end() {
        active = false; waiting = false; receipt = null; complete = false; playing = null
        pending.clear(); replies.clear(); seen.clear(); early.clear(); earlyCompletions.clear()
        externalReceipts.clear()
    }
    fun transcript(value: String): List<CallEffect> {
        return enqueue(value, null)
    }
    fun visual(value: String, frame: String): List<CallEffect> {
        require(frame.isNotBlank() && frame.length <= 2_900_000) { "画面超过长度上限" }
        return enqueue(value, frame)
    }
    private fun enqueue(value: String, frame: String?): List<CallEffect> {
        val text = value.trim()
        if (!active || text.isEmpty()) return emptyList()
        require(text.length <= 12000) { "通话转写超过长度上限" }
        if (waiting || playing != null || replies.isNotEmpty()) {
            check(pending.size < 4 && pending.sumOf { it.text.length } + text.length <= 12000) { "待发送内容已满，请等待回应" }
            pending.addLast(CallEffect.Send(text, frame))
            return emptyList()
        }
        return dispatch(CallEffect.Send(text, frame))
    }
    private fun dispatch(input: CallEffect.Send): List<CallEffect> {
        waiting = true; receipt = null; complete = false
        return listOf(input)
    }
    fun accepted(key: String): List<CallEffect> {
        if (!active || !waiting || receipt != null) return emptyList()
        require(key.isNotBlank())
        receipt = key
        early.filter { it.first == key }.forEach { (_, reply) -> if (seen.add(reply.id)) replies.addLast(reply) }
        complete = key in earlyCompletions
        early.removeAll { it.first == key }; earlyCompletions.remove(key)
        return advance()
    }
    /** Visual observation receipts share playback but never acknowledge the user's text turn. */
    fun externalAccepted(key: String): List<CallEffect> {
        if (!active || key.isBlank() || key == receipt || !externalReceipts.add(key)) return emptyList()
        if (externalReceipts.size > 64) externalReceipts.remove(externalReceipts.first())
        early.filter { it.first == key }.forEach { (_, reply) ->
            if (replies.size < 64 && rememberReply(reply.id)) replies.addLast(reply)
        }
        early.removeAll { it.first == key }; earlyCompletions.remove(key)
        return advance()
    }
    private fun rememberReply(id: String): Boolean {
        if (!seen.add(id)) return false
        if (seen.size > 256) seen.remove(seen.first())
        return true
    }
    fun reply(id: String, key: String, text: String, characterId: String): List<CallEffect> {
        if (!active || id.isBlank() || key.isBlank() || text.isBlank() || characterId.isBlank()) return emptyList()
        val item = CallEffect.Speak(id, text, characterId)
        if (key != receipt && key !in externalReceipts) {
            if (early.size == 64) early.removeAt(0)
            early.add(key to item)
            return emptyList()
        }
        if (replies.size >= 64) return emptyList()
        if (!rememberReply(id)) return emptyList()
        replies.addLast(item)
        return advance()
    }
    fun completed(key: String): List<CallEffect> {
        if (!active || !waiting || key.isBlank()) return emptyList()
        if (receipt == null) { if (earlyCompletions.size < 64) earlyCompletions.add(key); return emptyList() }
        if (key != receipt) return emptyList()
        complete = true
        return advance()
    }
    /** Failed synthesis/playback also completes that item; the caller exposes the failure. */
    fun played(id: String): List<CallEffect> {
        if (!active || playing != id) return emptyList()
        playing = null
        return advance()
    }
    private fun advance(): List<CallEffect> {
        if (playing != null) return emptyList()
        if (replies.isNotEmpty()) {
            val item = replies.removeFirst(); playing = item.id
            return listOf(item)
        }
        if (waiting && !complete) return emptyList()
        waiting = false; receipt = null; complete = false
        return if (pending.isEmpty()) emptyList() else dispatch(pending.removeFirst())
    }
}
