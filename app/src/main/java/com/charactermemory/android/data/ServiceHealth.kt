package com.charactermemory.android.data

import com.google.gson.JsonObject

object ServiceHealth {
    fun media(health: JsonObject, schema: JsonObject): String {
        val asr = health.objOrNull("asr")
        val tts = health.objOrNull("tts")
        if (asr == null || tts == null) return "不可用：此地址不是 Media 服务，请检查 Media HTTPS 地址和 8443 端口"
        val paths = schema.obj("paths")
        if (paths.obj("/v1/asr").objOrNull("post") == null || paths.obj("/v1/tts").objOrNull("post") == null)
            return "不可用：Media 缺少 POST /v1/asr 或 /v1/tts 接口"
        if (!asr.flag("ready") || !tts.flag("ready")) return "媒体可连接，${if (!asr.flag("ready")) "ASR" else "TTS"} 尚未就绪"
        return "可连接"
    }
}
