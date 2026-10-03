package com.charactermemory.android.data

import com.google.gson.JsonObject

/** Parsed response for Core's existing GET /v1/llm/usage endpoint. */
data class LlmUsageDto(
    val windowHours: Int,
    val summary: LlmUsageSummary,
    val byFeature: List<LlmUsageBreakdown>,
    val byModel: List<LlmUsageBreakdown>,
    val recent: List<LlmUsageCall>
) {
    companion object {
        fun fromJson(json: JsonObject): LlmUsageDto {
            val summaryJson = json.obj("summary")
            val requests = summaryJson.longValue("requests")
            val tokenKnownRequests = summaryJson.longValue("token_known_requests")
            return LlmUsageDto(
                windowHours = json.longValue("window_hours", 1L).toInt().coerceAtLeast(1),
                summary = LlmUsageSummary(
                    requests = requests,
                    logicalCalls = summaryJson.longValue("logical_calls"),
                    inputTokens = summaryJson.longValue("input_tokens"),
                    outputTokens = summaryJson.longValue("output_tokens"),
                    totalTokens = summaryJson.longValue("total_tokens"),
                    tokenKnownRequests = tokenKnownRequests,
                    retriedLogicalCalls = summaryJson.longValue("retried_logical_calls"),
                    errors = summaryJson.longValue("errors"),
                    avgLatencyMs = summaryJson.doubleValue("avg_latency_ms"),
                    inputChars = summaryJson.longValue("input_chars"),
                    outputChars = summaryJson.longValue("output_chars"),
                    tokenCoverage = summaryJson.doubleValue(
                        "token_coverage",
                        if (requests == 0L) 1.0 else tokenKnownRequests.toDouble() / requests
                    )
                ),
                byFeature = json.items("by_feature").map(::breakdown),
                byModel = json.items("by_model").map(::breakdown),
                recent = json.items("recent").map(::recentCall)
            )
        }

        private fun breakdown(json: JsonObject) = LlmUsageBreakdown(
            feature = json.textOrNull("feature"),
            purpose = json.textOrNull("purpose"),
            model = json.textOrNull("model"),
            requests = json.longValue("requests"),
            logicalCalls = json.longValue("logical_calls"),
            inputTokens = json.longValue("input_tokens"),
            outputTokens = json.longValue("output_tokens"),
            totalTokens = json.longValue("total_tokens"),
            tokenKnownRequests = json.longValue("token_known_requests"),
            retriedLogicalCalls = json.longValue("retried_logical_calls"),
            errors = json.longValue("errors"),
            avgLatencyMs = json.doubleValue("avg_latency_ms"),
            inputChars = json.longValue("input_chars"),
            outputChars = json.longValue("output_chars"),
            inputCharShare = json.doubleValue("input_char_share"),
            requestsPerLogicalCall = json.doubleValue("requests_per_logical_call"),
            tokenCoverage = json.doubleValue("token_coverage")
        )

        private fun recentCall(json: JsonObject) = LlmUsageCall(
            createdAt = json.textOrNull("created_at"),
            provider = json.text("provider"),
            model = json.text("model"),
            feature = json.text("feature"),
            purpose = json.text("purpose"),
            characterId = json.textOrNull("character_id"),
            attempt = json.longValue("attempt", 1L).toInt().coerceAtLeast(1),
            status = json.text("status"),
            inputTokens = json.nullableLongValue("input_tokens"),
            outputTokens = json.nullableLongValue("output_tokens"),
            totalTokens = json.nullableLongValue("total_tokens"),
            durationMs = json.doubleValue("duration_ms"),
            usageSource = json.textOrNull("usage_source")
        )
    }
}

data class LlmUsageSummary(
    val requests: Long,
    val logicalCalls: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val tokenKnownRequests: Long,
    val retriedLogicalCalls: Long,
    val errors: Long,
    val avgLatencyMs: Double,
    val inputChars: Long,
    val outputChars: Long,
    val tokenCoverage: Double
)

data class LlmUsageBreakdown(
    val feature: String?,
    val purpose: String?,
    val model: String?,
    val requests: Long,
    val logicalCalls: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val tokenKnownRequests: Long,
    val retriedLogicalCalls: Long,
    val errors: Long,
    val avgLatencyMs: Double,
    val inputChars: Long,
    val outputChars: Long,
    val inputCharShare: Double,
    val requestsPerLogicalCall: Double,
    val tokenCoverage: Double
)

data class LlmUsageCall(
    val createdAt: String?,
    val provider: String,
    val model: String,
    val feature: String,
    val purpose: String,
    val characterId: String?,
    val attempt: Int,
    val status: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val durationMs: Double,
    val usageSource: String?
)

class LlmUsageRepository(private val api: CoreApi) {
    suspend fun load(): LlmUsageDto = LlmUsageDto.fromJson(
        api.get("/v1/llm/usage", mapOf("hours" to "1", "limit" to "80"))
    )
}

private fun JsonObject.textOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

private fun JsonObject.longValue(key: String, default: Long = 0L): Long =
    get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }
        ?.let { runCatching { it.asString.toLong() }.getOrNull() } ?: default

private fun JsonObject.nullableLongValue(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }
        ?.let { runCatching { it.asString.toLong() }.getOrNull() }

private fun JsonObject.doubleValue(key: String, default: Double = 0.0): Double =
    get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }
        ?.let { runCatching { it.asString.toDouble() }.getOrNull() } ?: default
