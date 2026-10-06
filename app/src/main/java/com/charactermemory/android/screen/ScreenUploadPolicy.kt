package com.charactermemory.android.screen

/** Transport failures do not revoke the separate OS capture authorization. */
class ScreenUploadPolicy {
    private var attempts = 0
    var authorizationRejected = false; private set
    fun failed(status: Int = 0): Long {
        if (status in setOf(400, 401, 403, 404, 422)) { authorizationRejected = true; return 0 }
        return longArrayOf(2000, 5000, 10000, 30000, 60000)[attempts++.coerceAtMost(4)]
    }
    fun succeeded() { attempts = 0; authorizationRejected = false }
}
