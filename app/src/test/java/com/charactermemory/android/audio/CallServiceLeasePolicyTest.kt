package com.charactermemory.android.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallServiceLeasePolicyTest {
    @Test fun previousDestroyCannotReleaseSameCoordinatorAfterRedial() {
        val policy = CallServiceLeasePolicy()
        val oldLease = start(policy, startedAtMs = 100, startId = 7)
        val newLease = start(policy, startedAtMs = 200, startId = 8)

        assertFalse(policy.claim(oldLease, coordinatorStartedAtMs = 200))
        assertTrue(policy.isCurrent(newLease, coordinatorStartedAtMs = 200))
    }

    @Test fun previousNotificationCannotHangUpRedialedSession() {
        val policy = CallServiceLeasePolicy()
        val oldNotificationLease = start(policy, startedAtMs = 100, startId = 7)
        val currentLease = start(policy, startedAtMs = 200, startId = 8)

        assertFalse(policy.claim(oldNotificationLease, coordinatorStartedAtMs = 200))
        assertTrue(policy.isCurrent(currentLease, coordinatorStartedAtMs = 200))
    }

    @Test fun previousObserverCannotStopCurrentServiceStart() {
        val policy = CallServiceLeasePolicy()
        val opened = policy.issue(startedAtMs = 100)
        val previousObserverLease = policy.recordStart(opened, startId = 7)!!
        val currentServiceLease = policy.recordStart(previousObserverLease, startId = 8)!!

        assertFalse(policy.claim(previousObserverLease, coordinatorStartedAtMs = 100))
        assertTrue(policy.isCurrent(currentServiceLease, coordinatorStartedAtMs = 100))
    }

    @Test fun currentSessionHangupClaimsOnlyItsOwnLease() {
        val policy = CallServiceLeasePolicy()
        val currentLease = start(policy, startedAtMs = 100, startId = 7)

        assertTrue(policy.claim(currentLease, coordinatorStartedAtMs = 100))
        assertFalse(policy.isCurrent(currentLease, coordinatorStartedAtMs = 100))
        assertFalse(policy.claim(currentLease, coordinatorStartedAtMs = 100))
    }

    @Test fun pendingLeaseOwnsOnlyItsActiveCoordinatorAndCannotBeClaimed() {
        val policy = CallServiceLeasePolicy()
        val pendingLease = policy.issue(startedAtMs = 100)

        assertTrue(policy.owns(pendingLease, coordinatorStartedAtMs = 100))
        assertFalse(policy.owns(pendingLease, coordinatorStartedAtMs = 101))
        assertFalse(policy.claim(pendingLease, coordinatorStartedAtMs = 100))

        val redialedLease = policy.issue(startedAtMs = 100)
        assertFalse(policy.owns(pendingLease, coordinatorStartedAtMs = 100))
        assertTrue(policy.owns(redialedLease, coordinatorStartedAtMs = 100))
    }

    @Test fun notificationIdentityIsSessionSpecificButStableWhenStartIdChanges() {
        val policy = CallServiceLeasePolicy()
        val opened = policy.issue(startedAtMs = 100)
        val firstStart = policy.recordStart(opened, startId = 7)!!
        val refreshedStart = policy.recordStart(firstStart, startId = 8)!!
        val nextSession = start(policy, startedAtMs = 200, startId = 9)

        val endIdentity = policy.notificationIdentity(firstStart, kind = "end")
        assertEquals(endIdentity, policy.notificationIdentity(refreshedStart, kind = "end"))
        assertNotEquals(endIdentity, policy.notificationIdentity(nextSession, kind = "end"))
        assertNotEquals(endIdentity, policy.notificationIdentity(firstStart, kind = "return"))
    }

    private fun start(policy: CallServiceLeasePolicy, startedAtMs: Long, startId: Int) =
        policy.recordStart(policy.issue(startedAtMs), startId)!!
}
