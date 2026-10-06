package com.charactermemory.android.audio

/** Identifies one coordinator session and the service start that currently owns it. */
internal data class CallServiceLease(
    val startedAtMs: Long,
    val token: Long,
    val startId: Int = 0
)

/** Pure lifecycle policy shared by service callbacks and its JVM tests. */
internal class CallServiceLeasePolicy {
    private var nextToken = 1L
    private var current: CallServiceLease? = null

    @Synchronized
    fun issue(startedAtMs: Long): CallServiceLease {
        require(startedAtMs > 0) { "A call lease needs a started session" }
        return CallServiceLease(startedAtMs, nextToken++).also { current = it }
    }

    /** Records the newest Android service start for this lease, rejecting an older session. */
    @Synchronized
    fun recordStart(lease: CallServiceLease, startId: Int): CallServiceLease? {
        if (startId <= 0) return null
        val active = current ?: return null
        if (active.startedAtMs != lease.startedAtMs || active.token != lease.token) return null
        return active.copy(startId = startId).also { current = it }
    }

    @Synchronized
    fun currentLease(): CallServiceLease? = current

    fun notificationIdentity(lease: CallServiceLease, kind: String): String =
        "character-memory-call://notification/$kind/${lease.startedAtMs}/${lease.token}"

    /** Includes a newly issued lease while its service start command is still pending. */
    @Synchronized
    fun owns(lease: CallServiceLease, coordinatorStartedAtMs: Long): Boolean =
        current == lease && lease.startedAtMs == coordinatorStartedAtMs

    @Synchronized
    fun isCurrent(lease: CallServiceLease, coordinatorStartedAtMs: Long): Boolean =
        lease.startId > 0 && owns(lease, coordinatorStartedAtMs)

    /** Atomically claims the current lease so only one termination path can finish it. */
    @Synchronized
    fun claim(lease: CallServiceLease, coordinatorStartedAtMs: Long): Boolean {
        if (!isCurrent(lease, coordinatorStartedAtMs)) return false
        current = null
        return true
    }

    @Synchronized
    fun releasePending(lease: CallServiceLease): Boolean {
        if (current != lease || lease.startId != 0) return false
        current = null
        return true
    }
}
