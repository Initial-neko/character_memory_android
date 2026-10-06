package com.charactermemory.android.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenUploadPolicyTest {
    @Test fun repeatedNetworkFailuresDoNotRevokeProjectionAndRetryIsBounded() {
        val type = runCatching { Class.forName("com.charactermemory.android.screen.ScreenUploadPolicy") }.getOrNull()
        assertNotNull("Upload recovery policy is missing", type)
        val policy = type!!.getDeclaredConstructor().newInstance()
        val failed = type.getMethod("failed", Integer.TYPE)
        val delays = (1..12).map { failed.invoke(policy, 0) as Long }
        assertTrue(delays.all { it in 2000L..60000L })
        assertEquals(60000L, delays.last())
        assertEquals(false, type.getMethod("getAuthorizationRejected").invoke(policy))
        type.getMethod("succeeded").invoke(policy)
        assertEquals(2000L, failed.invoke(policy, 0))
    }
    @Test fun accessDeniedPausesUploadsInsteadOfRetryingForever() {
        val type = runCatching { Class.forName("com.charactermemory.android.screen.ScreenUploadPolicy") }.getOrNull()
        assertNotNull("Upload recovery policy is missing", type)
        val policy = type!!.getDeclaredConstructor().newInstance()
        assertEquals(0L, type.getMethod("failed", Integer.TYPE).invoke(policy, 403))
        assertEquals(true, type.getMethod("getAuthorizationRejected").invoke(policy))
    }
}
