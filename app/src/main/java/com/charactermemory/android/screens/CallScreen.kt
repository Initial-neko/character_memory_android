package com.charactermemory.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * P1 controls only mutate demo state; no OS recording permission is requested.
 * A vertically scrollable layout keeps every control reachable on small and
 * landscape devices without a horizontal scrolling button strip.
 */
@Composable
internal fun CallScreen(state: PrototypeUiState, model: PrototypeViewModel) {
    val selected = MockContent.characters.firstOrNull { it.id == state.selectedCharacterId }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("screen-call")) {
        val compact = maxHeight < 580.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DemoBanner("仅是通话布局演示：没有申请麦克风、摄像头或屏幕共享权限")
            Box(
                Modifier.fillMaxWidth()
                    .height(if (compact) 160.dp else 315.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF343353), Color(0xFF182A4A), Navy)
                        )
                    )
            ) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Avatar(
                        selected?.initials ?: "?",
                        Color(selected?.tint ?: 0xFF9887DA),
                        Modifier.size(if (compact) 76.dp else 115.dp)
                    )
                    Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                    Text(
                        selected?.name ?: "人物",
                        color = Pale,
                        fontSize = if (compact) 22.sp else 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "演示人物画面 · V2 可接虚拟形象",
                        color = Muted, fontSize = 11.sp,
                        modifier = Modifier.testTag("call-person-caption")
                    )
                }
                PanelCard(
                    Modifier.align(if (compact) Alignment.TopEnd else Alignment.BottomEnd)
                        .padding(if (compact) 8.dp else 14.dp)
                        .testTag("call-local-preview")
                        .size(if (compact) 68.dp else 95.dp, if (compact) 75.dp else 112.dp)
                ) {
                    Box(
                        Modifier.fillMaxSize().background(BluePanel),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "本机视频\nMock",
                            color = Pale,
                            textAlign = TextAlign.Center,
                            fontSize = if (compact) 11.sp else 14.sp
                        )
                    }
                }
            }
            PanelCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("你：我给你看看这个页面。", color = Pale, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (selected?.name ?: "人物") + "：字幕 / ASR / TTS 接口将于 P3 接入。",
                        color = Muted, fontSize = 12.sp
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = model::toggleMicDemo,
                    modifier = Modifier.weight(1f).testTag("call-mic-demo"),
                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 7.dp)
                ) {
                    Text(
                        if (state.microphoneDemoOn) "🎙 麦克风" else "🎙 已关闭",
                        fontSize = 12.sp, maxLines = 1, softWrap = false
                    )
                }
                OutlinedButton(
                    onClick = model::toggleCameraDemo,
                    modifier = Modifier.weight(1f).testTag("call-camera-demo"),
                    contentPadding = PaddingValues(horizontal = 3.dp, vertical = 7.dp)
                ) {
                    Text(
                        if (state.cameraDemoOn) "📷 模拟开启" else "📷 摄像头",
                        fontSize = 12.sp, maxLines = 1, softWrap = false
                    )
                }
            }
            OutlinedButton(
                onClick = model::toggleScreenDemo,
                modifier = Modifier.fillMaxWidth().testTag("call-screen-demo")
            ) {
                Text(if (state.screenDemoOn) "▣ 模拟共享中" else "▣ 共享屏幕（模拟）")
            }
            Button(
                onClick = model::back,
                modifier = Modifier.fillMaxWidth().testTag("call-end"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC44061))
            ) {
                Text("结束演示", color = Color.White)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
