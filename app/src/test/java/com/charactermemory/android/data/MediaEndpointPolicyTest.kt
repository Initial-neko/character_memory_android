package com.charactermemory.android.data

import org.junit.Assert.*
import org.junit.Test

class MediaEndpointPolicyTest {
    @Test fun legacyTailnetCoreAddressIsNotAValidSeparateMediaOrigin() {
        assertTrue(MediaEndpointPolicy.useDefault("https://node.example.ts.net", "https://node.example.ts.net"))
        assertFalse(MediaEndpointPolicy.useDefault("https://node.example.ts.net", "https://node.example.ts.net:8443"))
    }
    @Test fun customGatewayAndExplicitMediaAddressesStayUntouched() {
        assertFalse(MediaEndpointPolicy.useDefault("https://gateway.example", "https://gateway.example"))
        assertFalse(MediaEndpointPolicy.useDefault("https://node.example.ts.net", "https://media.example"))
        assertTrue(MediaEndpointPolicy.useDefault("https://node.example.ts.net", ""))
    }
    @Test fun coreHealthCannotMasqueradeAsAReadyMediaService() {
        val routes = jsonObject("paths" to mapOf("/v1/asr" to mapOf("post" to emptyMap<String,String>()), "/v1/tts" to mapOf("post" to emptyMap<String,String>())))
        assertTrue(ServiceHealth.media(jsonObject("ok" to true, "web" to "ready"), routes).startsWith("不可用"))
        val ready = jsonObject("asr" to mapOf("ready" to true), "tts" to mapOf("ready" to true))
        assertTrue(ServiceHealth.media(ready, jsonObject()).startsWith("不可用"))
        assertEquals("可连接", ServiceHealth.media(ready, routes))
    }
}
