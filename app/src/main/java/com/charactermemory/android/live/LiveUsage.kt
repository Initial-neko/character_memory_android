package com.charactermemory.android.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import com.charactermemory.android.data.LlmUsageBreakdown
import com.charactermemory.android.data.LlmUsageCall
import java.util.Locale

@Composable
internal fun LiveUsage(state: LiveState, model: LiveViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("live-usage"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("统计窗口：近 ${state.llmUsage?.windowHours ?: 1} 小时", color = LiveMuted)
                Text("按 Core 记录展示，不含金额", style = MaterialTheme.typography.bodySmall, color = LiveMuted)
            }
            LiveAction(
                if ("llm-usage" in state.busy) "刷新中…" else "刷新",
                "live-usage-refresh",
                "llm-usage" !in state.busy,
                model::loadLlmUsage
            )
        }

        val usage = state.llmUsage
        when {
            usage == null && "llm-usage" in state.busy -> Text("正在加载使用情况…", color = LiveMuted,
                modifier = Modifier.testTag("live-usage-loading"))
            usage == null -> Text("尚未收到 Usage 数据，请刷新重试。", color = LiveMuted,
                modifier = Modifier.testTag("live-usage-unavailable"))
            else -> {
                UsagePanel("汇总", "live-usage-summary") {
                    UsageMetricRow("请求数", usage.summary.requests.toString(),
                        "逻辑调用", usage.summary.logicalCalls.toString())
                    UsageMetricRow("输入 Token", tokenText(usage.summary.tokenKnownRequests, usage.summary.inputTokens),
                        "输出 Token", tokenText(usage.summary.tokenKnownRequests, usage.summary.outputTokens))
                    UsageMetricRow("总 Token", tokenText(usage.summary.tokenKnownRequests, usage.summary.totalTokens),
                        "Token 覆盖", if (usage.summary.requests > 0L) percent(usage.summary.tokenCoverage) else "—",
                        rightTag = "live-usage-token-coverage")
                    UsageMetricRow("重试调用", usage.summary.retriedLogicalCalls.toString(),
                        "错误请求", usage.summary.errors.toString())
                    UsageMetricRow("平均延迟", if (usage.summary.requests > 0L) latency(usage.summary.avgLatencyMs) else "—",
                        "输入字符", measuredCount(usage.summary.requests, usage.summary.inputChars),
                        leftTag = "live-usage-average-latency")
                    UsageMetricRow("输出字符", measuredCount(usage.summary.requests, usage.summary.outputChars),
                        "Token 已知请求", if (usage.summary.requests > 0L) "${usage.summary.tokenKnownRequests}/${usage.summary.requests}" else "—")
                }
                if (usage.summary.requests == 0L) {
                    Text("此时间段没有 LLM 请求。", color = LiveMuted,
                        modifier = Modifier.testTag("live-usage-empty"))
                } else {
                    UsagePanel("按功能", "live-usage-by-feature") {
                        if (usage.byFeature.isEmpty()) Text("暂无数据", color = LiveMuted)
                        usage.byFeature.forEach { UsageBreakdown(it, useModel = false) }
                    }
                    UsagePanel("按模型", "live-usage-by-model") {
                        if (usage.byModel.isEmpty()) Text("暂无数据", color = LiveMuted)
                        usage.byModel.forEach { UsageBreakdown(it, useModel = true) }
                    }
                    UsagePanel("最近调用", "live-usage-recent") {
                        if (usage.recent.isEmpty()) Text("暂无数据", color = LiveMuted)
                        usage.recent.forEachIndexed { index, call -> UsageRecentCall(index, call) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsagePanel(title: String, tag: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LivePanel)
            .padding(14.dp).testTag(tag).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(title, color = LivePale, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        content()
    }
}

@Composable
private fun UsageMetricRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
    leftTag: String? = null,
    rightTag: String? = null
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UsageMetric(leftLabel, leftValue, Modifier.weight(1f), leftTag)
        UsageMetric(rightLabel, rightValue, Modifier.weight(1f), rightTag)
    }
}

@Composable
private fun UsageMetric(label: String, value: String, modifier: Modifier = Modifier, valueTag: String? = null) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(LiveNavy).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = LiveMuted, style = MaterialTheme.typography.labelSmall)
        Text(value, color = LivePale, fontWeight = FontWeight.Medium,
            modifier = valueTag?.let { Modifier.testTag(it) } ?: Modifier)
    }
}

@Composable
private fun UsageBreakdown(item: LlmUsageBreakdown, useModel: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LiveNavy).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val title = if (useModel) item.model.orEmpty().ifBlank { "未知模型" }
            else listOfNotNull(item.feature, item.purpose).joinToString(" · ").ifBlank { "未知功能" }
        Text(title, color = LivePale, fontWeight = FontWeight.Medium)
        Text("请求 ${item.requests} · 逻辑调用 ${item.logicalCalls} · 重试 ${item.retriedLogicalCalls} · 错误 ${item.errors}", color = LiveMuted)
        Text("输入 ${tokenText(item.tokenKnownRequests, item.inputTokens)} · 输出 ${tokenText(item.tokenKnownRequests, item.outputTokens)} · 总计 ${tokenText(item.tokenKnownRequests, item.totalTokens)} Token", color = LiveMuted)
        Text("输入字符 ${item.inputChars} · 输出字符 ${item.outputChars} · 输入占比 ${percent(item.inputCharShare)}", color = LiveMuted)
        Text("平均 ${latency(item.avgLatencyMs)} · 每次逻辑调用 ${String.format(Locale.ROOT, "%.2f", item.requestsPerLogicalCall)} 个请求", color = LiveMuted)
    }
}

@Composable
private fun UsageRecentCall(index: Int, call: LlmUsageCall) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LiveNavy).padding(11.dp)
            .testTag("live-usage-call-$index"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(call.createdAt ?: "时间未知", color = LiveMuted, style = MaterialTheme.typography.bodySmall)
        Text("${call.feature} · ${call.purpose}", color = LivePale, fontWeight = FontWeight.Medium)
        Text("${call.provider} · ${call.model.ifBlank { "未知模型" }} · ${call.characterId ?: "无人物"} · 第 ${call.attempt} 次 · ${call.status.ifBlank { "状态未知" }}", color = LiveMuted)
        Text("Token ${call.totalTokens?.toString() ?: "—"} · ${latency(call.durationMs)}${call.usageSource?.let { " · $it" }.orEmpty()}", color = LiveMuted)
    }
}

private fun tokenText(knownRequests: Long, tokens: Long): String =
    if (knownRequests > 0) tokens.toString() else "—"

private fun measuredCount(requests: Long, value: Long): String =
    if (requests > 0L) value.toString() else "—"

private fun percent(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * 100.0)

private fun latency(value: Double): String = String.format(Locale.ROOT, "%.1f ms", value)
