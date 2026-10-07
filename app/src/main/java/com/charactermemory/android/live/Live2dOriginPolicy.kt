package com.charactermemory.android.live

import java.net.URI

/** Fail closed for every WebView request, including textures and model redirects. */
internal class Live2dOriginPolicy(coreUrl: String) {
    private val origin = parse(coreUrl)?.takeIf { it.scheme == "https" && it.host != null && it.userInfo == null }
    val pageUrl: String? = origin?.let { URI("https", null, it.host, it.port, "/__android_live2d__/stage.html", null, null).toASCIIString() }
    fun allows(url: String): Boolean {
        val base = origin ?: return false
        val request = parse(url) ?: return false
        return request.scheme == "https" && request.userInfo == null &&
            request.host?.equals(base.host, ignoreCase = true) == true && port(request) == port(base)
    }
    private fun port(uri: URI) = if (uri.port == -1) 443 else uri.port
    private fun parse(value: String) = runCatching { URI(value) }.getOrNull()
}
