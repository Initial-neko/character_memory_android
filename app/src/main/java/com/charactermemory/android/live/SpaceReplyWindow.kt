package com.charactermemory.android.live

/** A receipt-owned monotonic window; navigation must not renew it. */
data class SpaceReplyWindow(val confirmedAtMillis: Long) {
    fun isOpen(nowMillis: Long): Boolean = nowMillis >= confirmedAtMillis && nowMillis - confirmedAtMillis < 90_000L
}
