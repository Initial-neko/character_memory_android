package com.charactermemory.android.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Explicit construction permits loopback HTTP in deterministic test harnesses only. */
data class ServerConfig(val coreUrl: String, val mediaUrl: String) {
    init {
        // Both blank is the explicit, disconnected first-launch state.
        if (coreUrl.isNotBlank() || mediaUrl.isNotBlank()) { origin(coreUrl, false); origin(mediaUrl, false) }
    }

    companion object {
        const val DEFAULT_CORE = ""

        fun normalize(core: String, media: String = ""): ServerConfig {
            val coreOrigin = origin(core, true)
            val explicitMedia = if (media.isBlank()) "" else origin(media, true).toString().trimEnd('/')
            val mediaOrigin = if (MediaEndpointPolicy.useDefault(coreOrigin.toString().trimEnd('/'), explicitMedia))
                coreOrigin.newBuilder().port(8443).build() else origin(media, true)
            return ServerConfig(coreOrigin.toString().trimEnd('/'), mediaOrigin.toString().trimEnd('/'))
        }

        private fun origin(value: String, requireHttps: Boolean): HttpUrl {
            val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("请输入有效的 HTTPS 地址")
            require(!requireHttps || url.isHttps) { "服务器地址必须使用 HTTPS" }
            require(url.username.isEmpty() && url.password.isEmpty()) { "服务器地址不能包含用户名或密码" }
            require(url.encodedPath == "/" && url.query == null && url.fragment == null) { "请输入服务器根地址，不包含路径、查询或片段" }
            return url
        }
    }
}
