package com.charactermemory.android.audio

data class AudioRequestTicket(val owner: String, val generation: Long)

/** Main-thread audio intent ownership; stale synthesis must not replace a newer selection. */
class AudioRequestFence {
    private var generation = 0L
    private var current: AudioRequestTicket? = null
    private var onCancel: (() -> Unit)? = null
    fun begin(owner: String, cancel: () -> Unit): AudioRequestTicket {
        cancelAll()
        return AudioRequestTicket(owner, generation).also { current = it; onCancel = cancel }
    }
    fun consume(ticket: AudioRequestTicket): Boolean {
        if (current != ticket) return false
        current = null; onCancel = null
        return true
    }
    fun cancelOwner(owner: String) { if (current?.owner == owner) cancelAll() }
    fun cancelAll() {
        val cancel = onCancel
        current = null; onCancel = null; generation++
        cancel?.invoke()
    }
}

fun shouldStopForAudioFocus(change: Int): Boolean = change < 0
