package com.charactermemory.android.audio

import org.junit.Assert.*
import org.junit.Test

class CallTurnQueueTest {
    @Test fun confirmedVisualWaitsForTextReceiptAndPlaybackBeforeOneWrite() {
        val queue = CallTurnQueue(); queue.start(); queue.transcript("text"); queue.accepted("1")
        val method = queue.javaClass.methods.firstOrNull { it.name == "visual" }
        assertNotNull("Confirmed frames must use the serialized input queue", method)
        @Suppress("UNCHECKED_CAST")
        val queued = method!!.invoke(queue, "look", "{\"source\":\"CAMERA\"}") as List<CallEffect>
        assertTrue(queued.isEmpty()); assertEquals(1, queue.pendingCount)
        val effect = queue.completed("1").single()
        assertEquals("look", (effect as CallEffect.Send).text)
        assertEquals("{\"source\":\"CAMERA\"}", effect.javaClass.getMethod("getVisualFrame").invoke(effect))
        assertTrue(queue.completed("1").isEmpty())
    }
    @Test fun visualObservationReplyUsesOnePlayerWithoutCompletingTextTurn() {
        val queue = CallTurnQueue(); queue.start(); queue.transcript("text"); queue.accepted("text-1")
        assertTrue(queue.reply("screen-r", "screen-1", "screen response", "alice").isEmpty())
        assertEquals(listOf(CallEffect.Speak("screen-r", "screen response", "alice")), external(queue, "screen-1"))
        queue.completed("screen-1")
        assertTrue(queue.awaitingReaction)
        assertTrue(queue.reply("text-r", "text-1", "text response", "alice").isEmpty())
        assertEquals(listOf(CallEffect.Speak("text-r", "text response", "alice")), queue.played("screen-r"))
        queue.completed("text-1"); queue.played("text-r")
        assertFalse(queue.waiting)
        assertTrue(external(queue, "screen-1").isEmpty())
    }
    @Test fun visualReplyBeforeReceiptIsRetainedWhileMicrophoneIsMutedAndIdle() {
        val queue = CallTurnQueue(); queue.start(); queue.discardPendingInput()
        queue.reply("r", "camera-1", "camera response", "alice")
        assertEquals(listOf(CallEffect.Speak("r", "camera response", "alice")), external(queue, "camera-1"))
        queue.end(); assertTrue(external(queue, "late").isEmpty())
    }
    private fun external(queue: CallTurnQueue, key: String): List<CallEffect> {
        val method = queue.javaClass.methods.firstOrNull { it.name == "externalAccepted" }
        assertNotNull("Visual receipt must enter the shared playback queue", method)
        @Suppress("UNCHECKED_CAST")
        return method!!.invoke(queue, key) as List<CallEffect>
    }
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
