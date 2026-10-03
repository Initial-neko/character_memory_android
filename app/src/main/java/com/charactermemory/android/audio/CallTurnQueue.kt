package com.charactermemory.android.audio

sealed class CallEffect {
    data class Send(val text: String) : CallEffect()
    data class Speak(val id: String, val text: String, val characterId: String) : CallEffect()
}

/** Main-thread call reducer. Receipt identity fences SSE; playback and reaction are separate gates. */
class CallTurnQueue {
    var active = false; private set
    var waiting = false; private set
    val pendingCount get() = pending.size
    private var receipt: String? = null
    private var complete = false
    private var playing: String? = null
    private val pending = ArrayDeque<String>()
    private val replies = ArrayDeque<CallEffect.Speak>()
    private val seen = mutableSetOf<String>()
    private val early = mutableListOf<Pair<String, CallEffect.Speak>>()
    private val earlyCompletions = mutableSetOf<String>()

    fun start() { end(); active = true }
    fun end() {
        active = false; waiting = false; receipt = null; complete = false; playing = null
        pending.clear(); replies.clear(); seen.clear(); early.clear(); earlyCompletions.clear()
    }
    fun transcript(value: String): List<CallEffect> {
        val text = value.trim()
        if (!active || text.isEmpty()) return emptyList()
        require(text.length <= 12000) { "通话转写超过长度上限" }
        if (waiting) {
            check(pending.size < 4 && pending.sumOf { it.length } + text.length <= 12000) { "待发送语音已满，请等待回应" }
            pending.addLast(text)
            return emptyList()
        }
        return dispatch(text)
    }
    private fun dispatch(text: String): List<CallEffect> {
        waiting = true; receipt = null; complete = false
        early.clear(); earlyCompletions.clear(); replies.clear(); seen.clear()
        return listOf(CallEffect.Send(text))
    }
    fun accepted(key: String): List<CallEffect> {
        if (!active || !waiting || receipt != null) return emptyList()
        require(key.isNotBlank())
        receipt = key
        early.filter { it.first == key }.forEach { (_, reply) -> if (seen.add(reply.id)) replies.addLast(reply) }
        complete = key in earlyCompletions
        early.clear(); earlyCompletions.clear()
        return advance()
    }
    fun reply(id: String, key: String, text: String, characterId: String): List<CallEffect> {
        if (!active || !waiting || id.isBlank() || key.isBlank() || text.isBlank() || characterId.isBlank()) return emptyList()
        val item = CallEffect.Speak(id, text, characterId)
        if (receipt == null) {
            if (early.size < 64) early.add(key to item)
            return emptyList()
        }
        if (key != receipt || !seen.add(id)) return emptyList()
        if (replies.size >= 64) return emptyList()
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
        if (!complete) return emptyList()
        waiting = false; receipt = null; complete = false
        return if (pending.isEmpty()) emptyList() else dispatch(pending.removeFirst())
    }
}
