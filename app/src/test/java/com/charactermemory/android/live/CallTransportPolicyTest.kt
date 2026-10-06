package com.charactermemory.android.live

import org.junit.Assert.*
import org.junit.Test

class CallTransportPolicyTest {
    @Test fun recoverableCallStreamFailureRetainsCursorAndSchedulesHistoryReconcile() {
        val plan = StreamFailurePolicy.onFailure(
            lastEventId = "event-27",
            sessionAlive = true,
            callActive = true,
            terminal = false
        )

        assertEquals("event-27", plan.lastEventId)
        assertTrue(plan.reconnect)
        assertTrue(plan.reconcileHistoryOnOpen)
        assertTrue(plan.pauseCallInput)
    }

    @Test fun terminalFailureRetainsCursorWithoutReconnecting() {
        val plan = StreamFailurePolicy.onFailure(
            lastEventId = "event-27",
            sessionAlive = true,
            callActive = true,
            terminal = true
        )

        assertEquals("event-27", plan.lastEventId)
        assertFalse(plan.reconnect)
        assertFalse(plan.reconcileHistoryOnOpen)
        assertTrue(plan.pauseCallInput)
    }

    @Test fun visualSourceEpochFencesLateFramesAndStaleStops() {
        val fence = CallVisualSourceFence()
        val screenEpoch = fence.begin(CallVisualSource.SCREEN)
        val cameraEpoch = fence.begin(CallVisualSource.CAMERA)

        assertFalse(fence.accepts(CallVisualSource.SCREEN, screenEpoch))
        assertTrue(fence.accepts(CallVisualSource.CAMERA, cameraEpoch))
        assertFalse(fence.end(CallVisualSource.SCREEN, screenEpoch))
        assertTrue(fence.accepts(CallVisualSource.CAMERA, cameraEpoch))
        assertTrue(fence.end(CallVisualSource.CAMERA, cameraEpoch))
        assertFalse(fence.accepts(CallVisualSource.CAMERA, cameraEpoch))
    }

    @Test fun historyRecoveryOnlyAcceptsAssistantRowsOwnedByCurrentReceiptAndTarget() {
        assertTrue(CallReceiptRecoveryPolicy.matches(
            receiptKey = "41", group = false, sourceEventId = "41", turnId = null,
            role = "assistant", action = "MESSAGE", characterId = "rin", targetCharacterId = "rin",
            groupMemberIds = emptySet()
        ))
        assertFalse(CallReceiptRecoveryPolicy.matches(
            receiptKey = "41", group = false, sourceEventId = "40", turnId = null,
            role = "assistant", action = "MESSAGE", characterId = "rin", targetCharacterId = "rin",
            groupMemberIds = emptySet()
        ))
        assertFalse(CallReceiptRecoveryPolicy.matches(
            receiptKey = "41", group = false, sourceEventId = "41", turnId = null,
            role = "assistant", action = "MESSAGE", characterId = "other", targetCharacterId = "rin",
            groupMemberIds = emptySet()
        ))
        assertTrue(CallReceiptRecoveryPolicy.matches(
            receiptKey = "turn-9", group = true, sourceEventId = null, turnId = "turn-9",
            role = "assistant", action = "VOICE_MESSAGE", characterId = "alice", targetCharacterId = "g",
            groupMemberIds = setOf("alice", "bob")
        ))
        assertFalse(CallReceiptRecoveryPolicy.matches(
            receiptKey = "turn-9", group = true, sourceEventId = null, turnId = "turn-9",
            role = "user", action = "MESSAGE", characterId = "alice", targetCharacterId = "g",
            groupMemberIds = setOf("alice", "bob")
        ))
    }
}
