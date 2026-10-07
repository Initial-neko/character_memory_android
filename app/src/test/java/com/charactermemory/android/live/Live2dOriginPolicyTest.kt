package com.charactermemory.android.live

import org.junit.Assert.*
import org.junit.Test

class Live2dOriginPolicyTest {
    @Test fun permitsOnlyConfiguredHttpsOrigin() {
        val policy = Live2dOriginPolicy("https://core.example:8443/base")
        assertTrue(policy.allows("https://core.example:8443/static/real.moc3"))
        assertFalse(policy.allows("https://core.example/static/real.moc3"))
        assertFalse(policy.allows("https://core.example.evil:8443/a"))
        assertFalse(policy.allows("https://user@core.example:8443/a"))
        assertFalse(policy.allows("http://core.example:8443/a"))
        assertFalse(policy.allows("file:///data/local/tmp/a"))
        assertFalse(policy.allows("javascript:alert(1)"))
    }
    @Test fun explicitDefaultPortMatchesAndInvalidConfigFailsClosed() {
        assertTrue(Live2dOriginPolicy("https://core.example").allows("https://core.example:443/static/a"))
        assertFalse(Live2dOriginPolicy("http://core.example").allows("http://core.example/a"))
        assertFalse(Live2dOriginPolicy("malformed").allows("https://core.example/a"))
    }
}
