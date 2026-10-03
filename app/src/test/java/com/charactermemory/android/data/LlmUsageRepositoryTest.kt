package com.charactermemory.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmUsageRepositoryTest {
    @Test fun parsesCoreUsageSummaryBreakdownsAndUnknownRecentTokens() {
        val usage = LlmUsageDto.fromJson(com.google.gson.JsonParser.parseString(payload).asJsonObject)

        assertEquals(1, usage.windowHours)
        assertEquals(2L, usage.summary.requests)
        assertEquals(1L, usage.summary.logicalCalls)
        assertEquals(120L, usage.summary.totalTokens)
        assertEquals(1L, usage.summary.tokenKnownRequests)
        assertEquals(0.5, usage.summary.tokenCoverage, 0.0001)
        assertEquals("CHAT", usage.byFeature.single().feature)
        assertEquals("DIRECT_REACTION", usage.byFeature.single().purpose)
        assertEquals(20L, usage.byFeature.single().outputChars)
        assertEquals("fixture-model", usage.byModel.single().model)
        assertEquals("rin", usage.recent.first().characterId)
        assertEquals(120L, usage.recent.first().totalTokens)
        assertNull(usage.recent.last().totalTokens)
    }

    @Test fun nullableRecentTokenCountsDistinguishNullMissingZeroAndNumericValues() {
        val usage = LlmUsageDto.fromJson(com.google.gson.JsonParser.parseString(
            """{"summary":{},"recent":[
                {"input_tokens":null,"output_tokens":0,"total_tokens":42},
                {"output_tokens":9},
                {"input_tokens":"7","output_tokens":8,"total_tokens":15}
            ]}"""
        ).asJsonObject)

        assertNull(usage.recent[0].inputTokens)
        assertEquals(0L, usage.recent[0].outputTokens)
        assertEquals(42L, usage.recent[0].totalTokens)
        assertNull(usage.recent[1].inputTokens)
        assertEquals(9L, usage.recent[1].outputTokens)
        assertEquals(7L, usage.recent[2].inputTokens)
        assertEquals(15L, usage.recent[2].totalTokens)
    }

    @Test fun loadsTheOneHourUsageRouteWithTheFixedRecentLimit() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(payload))
            val api = CoreApi(ServerConfig(server.url("/").toString(), server.url("/").toString()))

            val usage = LlmUsageRepository(api).load()

            assertEquals(2L, usage.summary.requests)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/llm/usage?hours=1&limit=80", request.path)
        }
    }

    private companion object {
        val payload = """
            {
              "window_hours": 1,
              "summary": {
                "requests": 2, "logical_calls": 1,
                "input_tokens": 100, "output_tokens": 20, "total_tokens": 120,
                "token_known_requests": 1, "retried_logical_calls": 0,
                "errors": 0, "avg_latency_ms": 12.5,
                "input_chars": 90, "output_chars": 20, "token_coverage": 0.5
              },
              "by_feature": [{
                "feature": "CHAT", "purpose": "DIRECT_REACTION", "requests": 2,
                "logical_calls": 1, "input_tokens": 100, "output_tokens": 20,
                "total_tokens": 120, "token_known_requests": 1,
                "retried_logical_calls": 0, "errors": 0, "avg_latency_ms": 12.5,
                "input_chars": 90, "output_chars": 20, "input_char_share": 1.0,
                "requests_per_logical_call": 2.0, "token_coverage": 0.5
              }],
              "by_model": [{
                "model": "fixture-model", "requests": 2, "logical_calls": 1,
                "input_tokens": 100, "output_tokens": 20, "total_tokens": 120,
                "token_known_requests": 1, "retried_logical_calls": 0, "errors": 0,
                "avg_latency_ms": 12.5, "input_chars": 90, "output_chars": 20,
                "input_char_share": 1.0, "requests_per_logical_call": 2.0,
                "token_coverage": 0.5
              }],
              "recent": [
                {
                  "id": 8, "created_at": "2026-10-02T02:00:00+00:00",
                  "provider": "example.test", "model": "fixture-model",
                  "feature": "CHAT", "purpose": "DIRECT_REACTION", "character_id": "rin",
                  "attempt": 1, "status": "SUCCESS", "input_tokens": 100,
                  "output_tokens": 20, "total_tokens": 120, "duration_ms": 12.5,
                  "usage_source": "PROVIDER", "error_type": "", "request_id": "fixture-8"
                },
                {
                  "id": 7, "created_at": "2026-10-02T01:59:00+00:00",
                  "provider": "example.test", "model": "fixture-model",
                  "feature": "CHAT", "purpose": "DIRECT_REACTION", "character_id": "rin",
                  "attempt": 1, "status": "SUCCESS", "input_tokens": null,
                  "output_tokens": null, "total_tokens": null, "duration_ms": 10.0,
                  "usage_source": "UNAVAILABLE", "error_type": "", "request_id": "fixture-7"
                }
              ]
            }
        """.trimIndent()
    }
}
