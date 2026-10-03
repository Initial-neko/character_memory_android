package com.charactermemory.android.audio

import org.junit.Assert.*
import org.junit.Test

class CallTurnQueueTest {
    @Test fun speechDuringReplyWaitsForBothCompletionAndPlayback() {
        val queue = CallTurnQueue()
        queue.start()
        assertEquals(listOf(CallEffect.Send("first")), queue.transcript("first"))
        queue.accepted("10")
        assertEquals(listOf(CallEffect.Speak("a", "hello", "alice")), queue.reply("a", "10", "hello", "alice"))
        assertTrue(queue.transcript("second").isEmpty())
        assertTrue(queue.completed("10").isEmpty())
        assertEquals(listOf(CallEffect.Send("second")), queue.played("a"))
    }
    @Test fun earlyReplyBeforeReceiptIsBufferedAndUnrelatedEventsAreIgnored() {
        val queue = CallTurnQueue(); queue.start(); queue.transcript("first")
        assertTrue(queue.reply("old", "9", "old", "alice").isEmpty())
        assertTrue(queue.reply("a", "10", "hello", "alice").isEmpty())
        assertTrue(queue.completed("10").isEmpty())
        assertEquals(listOf(CallEffect.Speak("a", "hello", "alice")), queue.accepted("10"))
        assertTrue(queue.reply("a", "10", "hello", "alice").isEmpty())
        queue.played("a")
        assertFalse(queue.waiting)
    }
    @Test fun groupSpeakersPlayInOrderAndFailureAdvancesTheQueue() {
        val queue = CallTurnQueue(); queue.start(); queue.transcript("hi"); queue.accepted("turn-1")
        assertEquals(listOf(CallEffect.Speak("a", "A", "alice")), queue.reply("a", "turn-1", "A", "alice"))
        assertTrue(queue.reply("b", "turn-1", "B", "bob").isEmpty())
        queue.completed("turn-1")
        assertEquals(listOf(CallEffect.Speak("b", "B", "bob")), queue.played("a"))
        queue.played("b"); assertFalse(queue.waiting)
    }
    @Test fun silentReactionFlushesQueuedInputAndOldCompletionCannotFinishNewTurn() {
        val queue = CallTurnQueue(); queue.start(); queue.transcript("a"); queue.accepted("1")
        queue.transcript("b"); assertEquals(listOf(CallEffect.Send("b")), queue.completed("1"))
        queue.accepted("2"); assertTrue(queue.completed("1").isEmpty()); assertTrue(queue.waiting)
    }
    @Test fun hangupDiscardsQueuedInputAndAllLateCallbacks() {
        val queue = CallTurnQueue(); queue.start(); queue.transcript("a"); queue.accepted("1"); queue.transcript("b")
        queue.end()
        assertTrue(queue.completed("1").isEmpty()); assertTrue(queue.reply("a", "1", "late", "alice").isEmpty())
        assertTrue(queue.transcript("late").isEmpty()); assertEquals(0, queue.pendingCount)
    }
    @Test fun blankTranscriptDoesNotWriteAndQueueIsBoundedWithoutSilentDrops() {
        val queue = CallTurnQueue(); queue.start()
        assertTrue(queue.transcript("  ").isEmpty())
        queue.transcript("first")
        repeat(4) { queue.transcript("pending $it") }
        try { queue.transcript("overflow"); fail("must expose full queue") } catch (_: IllegalStateException) {}
        assertEquals(4, queue.pendingCount)
    }
}
