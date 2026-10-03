package com.charactermemory.android.data

import java.net.URI

/** The documented ts.net deployment uses Core :443 and Media :8443. */
object MediaEndpointPolicy {
    fun useDefault(core: String, media: String): Boolean {
        if (media.isBlank()) return true
        val origin = URI(core)
        return core == media && origin.scheme == "https" &&
            origin.host?.endsWith(".ts.net") == true && origin.port in setOf(-1, 443)
    }
}
