package com.charactermemory.android.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The original P1 visual palette; these components have no model or API ownership.
internal val LiveNavy = Color(0xFF091120)
internal val LivePanel = Color(0xFF151F31)
internal val LiveAccent = Color(0xFF79A9FF)
internal val LiveMuted = Color(0xFF9BAFCB)
internal val LivePale = Color(0xFFEAF1FF)
internal val LivePurple = Color(0xFFB09CFF)
internal val LiveCyan = Color(0xFF76D4E9)
internal val LiveBorder = Color(0xFF263956)

internal enum class LiveSymbol { BACK, CHAT, SPACE, SETTINGS, PERSON, GROUP, SPARKLE, REFRESH, MORE, SEND, STICKER, TOOLS, MIC, PHONE, KEYBOARD, PLAY, STOP, CAMERA, DISPLAY, MIC_OFF, SPEAKER }

/** Small native vector drawings avoid font-dependent symbol sizing and extra dependencies. */
@Composable
internal fun LiveGlyph(symbol: LiveSymbol, modifier: Modifier = Modifier, tint: Color = LiveAccent) {
    Canvas(modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        val stroke = Stroke(1.7f * unit)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, point(x, y), point(x2, y2), strokeWidth = 1.7f * unit)
        fun circle(x: Float, y: Float, radius: Float) = drawCircle(tint, radius * unit, point(x, y), style = stroke)
        when (symbol) {
            LiveSymbol.SPEAKER -> { val path = Path().apply { moveTo(3f * unit, 9f * unit); lineTo(8f * unit, 9f * unit); lineTo(13f * unit, 4f * unit); lineTo(13f * unit, 20f * unit); lineTo(8f * unit, 15f * unit); lineTo(3f * unit, 15f * unit); close() }; drawPath(path, tint, style = stroke); drawArc(tint, -60f, 120f, false, point(10f, 5f), Size(12f * unit, 14f * unit), style = stroke) }
            LiveSymbol.CAMERA -> { drawRoundRect(tint, point(2f, 6f), Size(13f * unit, 12f * unit), androidx.compose.ui.geometry.CornerRadius(2f * unit), style = stroke); val path = Path().apply { moveTo(15f * unit, 10f * unit); lineTo(22f * unit, 6f * unit); lineTo(22f * unit, 18f * unit); lineTo(15f * unit, 14f * unit) }; drawPath(path, tint, style = stroke) }
            LiveSymbol.DISPLAY -> { drawRoundRect(tint, point(2f, 4f), Size(20f * unit, 14f * unit), androidx.compose.ui.geometry.CornerRadius(2f * unit), style = stroke); line(12f, 18f, 12f, 22f); line(7f, 22f, 17f, 22f) }
            LiveSymbol.MIC_OFF -> { drawRoundRect(tint, point(9f, 3f), Size(6f * unit, 12f * unit), androidx.compose.ui.geometry.CornerRadius(3f * unit), style = stroke); drawArc(tint, 0f, 180f, false, point(6f, 8f), Size(12f * unit, 10f * unit), style = stroke); line(12f, 18f, 12f, 22f); line(3f, 3f, 22f, 22f) }
            LiveSymbol.MIC -> { drawRoundRect(tint, point(9f, 3f), Size(6f * unit, 12f * unit), androidx.compose.ui.geometry.CornerRadius(3f * unit), style = stroke); drawArc(tint, 0f, 180f, false, point(6f, 8f), Size(12f * unit, 10f * unit), style = stroke); line(12f, 18f, 12f, 22f); line(8f, 22f, 16f, 22f) }
            LiveSymbol.PHONE -> { val path = Path().apply { moveTo(5f * unit, 3f * unit); lineTo(9f * unit, 7f * unit); lineTo(7f * unit, 10f * unit); quadraticBezierTo(10f * unit, 15f * unit, 15f * unit, 17f * unit); lineTo(18f * unit, 15f * unit); lineTo(22f * unit, 19f * unit); quadraticBezierTo(19f * unit, 25f * unit, 10f * unit, 18f * unit); quadraticBezierTo(0f * unit, 10f * unit, 5f * unit, 3f * unit) }; drawPath(path, tint, style = stroke) }
            LiveSymbol.KEYBOARD -> { drawRoundRect(tint, point(2f, 5f), Size(20f * unit, 14f * unit), androidx.compose.ui.geometry.CornerRadius(2f * unit), style = stroke); listOf(8f, 12f).forEach { y -> listOf(6f, 10f, 14f, 18f).forEach { x -> drawCircle(tint, unit, point(x, y)) } }; line(7f, 16f, 17f, 16f) }
            LiveSymbol.PLAY -> { val path = Path().apply { moveTo(8f * unit, 4f * unit); lineTo(20f * unit, 12f * unit); lineTo(8f * unit, 20f * unit); close() }; drawPath(path, tint) }
            LiveSymbol.STOP -> drawRoundRect(tint, point(6f, 6f), Size(12f * unit, 12f * unit), androidx.compose.ui.geometry.CornerRadius(2f * unit))
            LiveSymbol.BACK -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
            LiveSymbol.MORE -> listOf(5f, 12f, 19f).forEach { drawCircle(tint, 1.6f * unit, point(it, 12f)) }
            LiveSymbol.TOOLS -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            LiveSymbol.PERSON -> { circle(9f, 7f, 3f); drawArc(tint, 180f, 180f, false, point(3f, 12f), Size(12f * unit, 12f * unit), style = stroke); line(18f, 10f, 18f, 18f); line(14f, 14f, 22f, 14f) }
            LiveSymbol.GROUP -> { circle(8f, 7f, 3f); circle(17f, 9f, 2.5f); drawArc(tint, 180f, 180f, false, point(2f, 12f), Size(12f * unit, 12f * unit), style = stroke); drawArc(tint, 180f, 180f, false, point(13f, 14f), Size(9f * unit, 8f * unit), style = stroke) }
            LiveSymbol.STICKER -> { circle(12f, 12f, 9f); drawCircle(tint, unit, point(8f, 10f)); drawCircle(tint, unit, point(16f, 10f)); drawArc(tint, 10f, 160f, false, point(7f, 10f), Size(10f * unit, 8f * unit), style = stroke) }
            LiveSymbol.SEND -> {
                val path = Path().apply { moveTo(3f * unit, 3f * unit); lineTo(22f * unit, 12f * unit); lineTo(3f * unit, 21f * unit); lineTo(7f * unit, 12f * unit); close() }
                drawPath(path, tint, style = stroke); line(7f, 12f, 21f, 12f)
            }
            LiveSymbol.SPARKLE -> {
                val path = Path().apply { moveTo(12f * unit, 2f * unit); lineTo(15f * unit, 9f * unit); lineTo(22f * unit, 12f * unit); lineTo(15f * unit, 15f * unit); lineTo(12f * unit, 22f * unit); lineTo(9f * unit, 15f * unit); lineTo(2f * unit, 12f * unit); lineTo(9f * unit, 9f * unit); close() }
                drawPath(path, tint, style = stroke)
            }
            LiveSymbol.REFRESH -> { drawArc(tint, 45f, 285f, false, point(4f, 4f), Size(16f * unit, 16f * unit), style = stroke); line(20f, 5f, 20f, 10f); line(15f, 10f, 20f, 10f) }
            LiveSymbol.CHAT -> {
                drawRoundRect(tint, point(3f, 4f), Size(18f * unit, 14f * unit), androidx.compose.ui.geometry.CornerRadius(3f * unit), style = stroke)
                line(5f, 18f, 5f, 22f); line(5f, 22f, 10f, 18f); line(7f, 9f, 17f, 9f); line(7f, 13f, 14f, 13f)
            }
            LiveSymbol.SPACE -> { circle(12f, 12f, 9f); circle(12f, 12f, 4f); line(12f, 1f, 12f, 3f); line(12f, 21f, 12f, 23f) }
            LiveSymbol.SETTINGS -> { circle(12f, 12f, 7f); circle(12f, 12f, 2.5f); listOf(0f, 90f, 180f, 270f).forEach { angle ->
                val r = Math.toRadians(angle.toDouble()); line(12f + 8f * kotlin.math.cos(r).toFloat(), 12f + 8f * kotlin.math.sin(r).toFloat(), 12f + 11f * kotlin.math.cos(r).toFloat(), 12f + 11f * kotlin.math.sin(r).toFloat())
            } }
        }
    }
}

@Composable
internal fun LiveIconAction(symbol: LiveSymbol, label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label }) {
        LiveGlyph(symbol, tint = if (enabled) LiveAccent else LiveMuted.copy(alpha = 0.4f))
    }
}

@Composable
internal fun LivePlaybackAction(label: String, status: String, tag: String, enabled: Boolean,
    voiceBarLabel: String? = null, onClick: () -> Unit) {
    if (voiceBarLabel != null) {
        LiveVoiceBar(voiceBarLabel, status, tag, enabled, label, onClick)
        return
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag).semantics {
        contentDescription = label
        stateDescription = status
    }) {
        when {
            status.contains("加载") || status.contains("合成") -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            status.contains("失败") || status.contains("重试") -> LiveGlyph(LiveSymbol.REFRESH, tint = MaterialTheme.colorScheme.error)
            else -> LiveGlyph(if (status == "停止") LiveSymbol.STOP else LiveSymbol.PLAY, tint = if (enabled) LiveAccent else LiveMuted)
        }
    }
}

/** The whole row is the playback target. The marks are a voice icon, not a sampled waveform. */
@Composable
internal fun LiveVoiceBar(text: String, status: String, tag: String, enabled: Boolean,
    label: String = "语音消息", onClick: () -> Unit = {}) {
    Surface(onClick = onClick, enabled = enabled,
        modifier = Modifier.width(184.dp).heightIn(min = 48.dp).testTag(tag).semantics {
            contentDescription = label; stateDescription = status
        }, shape = RoundedCornerShape(12.dp), color = LiveAccent.copy(alpha = .12f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                status.contains("加载") || status.contains("合成") || status.contains("生成中") ->
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                status.contains("失败") || status.contains("重试") -> LiveGlyph(LiveSymbol.REFRESH, Modifier.size(20.dp), LiveMuted)
                else -> LiveGlyph(if (status == "停止") LiveSymbol.STOP else LiveSymbol.PLAY, Modifier.size(20.dp), if (enabled) LiveAccent else LiveMuted)
            }
            Canvas(Modifier.weight(1f).height(20.dp)) {
                repeat(7) { index ->
                    val height = size.height * (if (index % 2 == 0) .45f else .85f)
                    val x = size.width * (index + 1) / 8f
                    drawLine(if (enabled) LiveAccent else LiveMuted,
                        Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), 3.dp.toPx())
                }
            }
            Text(text, color = if (enabled) LivePale else LiveMuted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun LivePanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, LiveBorder),
        colors = CardDefaults.cardColors(containerColor = LivePanel)) { content() }
}

@Composable
internal fun LiveQuickAction(symbol: LiveSymbol, label: String, modifier: Modifier, tag: String,
    enabled: Boolean = true, action: () -> Unit) {
    LivePanelCard(modifier.clickable(enabled = enabled, onClick = action).testTag(tag)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 94.dp).padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LiveGlyph(symbol, Modifier.size(27.dp), LivePurple)
            Text(label, color = LivePale, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
internal fun LiveSection(title: String, detail: String? = null) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, color = LivePale, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        detail?.let { Text(it, color = LiveMuted, fontSize = 12.sp) }
    }
}

@Composable
internal fun LiveFeedback(text: String, tag: String, error: Boolean = false) {
    var expanded by remember(text) { mutableStateOf(false) }
    Text(text, color = if (error) MaterialTheme.colorScheme.error else LiveCyan, fontSize = 12.sp,
        maxLines = if (error) 2 else 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .background(if (error) Color(0xFF321D2D) else Color(0xFF142A3C), RoundedCornerShape(9.dp))
            .clickable { expanded = true }.testTag(tag).padding(horizontal = 10.dp, vertical = 7.dp))
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(if (error) "操作提示" else "当前状态") },
        text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("知道了") } })
}

/** Only formats a supplied timestamp: missing or malformed dates produce no invented time. */
internal object LiveTime {
    fun short(value: String, zone: ZoneId = ZoneId.systemDefault()): String = format(value, zone, "HH:mm")
    fun dateTime(value: String, zone: ZoneId = ZoneId.systemDefault()): String = format(value, zone, "MM-dd HH:mm")
    private fun format(value: String, zone: ZoneId, pattern: String): String = runCatching {
        OffsetDateTime.parse(value).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern(pattern))
    }.getOrDefault("")
}
