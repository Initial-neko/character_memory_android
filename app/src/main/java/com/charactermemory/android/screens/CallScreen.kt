package com.charactermemory.android

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** P1 deterministic UI screen: offline state, never a Core API request. */
@Composable
internal fun CallScreen(state: PrototypeUiState, model: PrototypeViewModel) {
    Column(Modifier.fillMaxSize().testTag("screen-call").padding(horizontal = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        DemoBanner("仅是通话布局演示：没有申请麦克风、摄像头或屏幕共享权限")
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF343353), Color(0xFF182A4A), Navy)))) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar("R", Purple, Modifier.size(115.dp))
                Spacer(Modifier.height(16.dp))
                Text("Rin", color = Pale, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("演示人物画面 · V2 可接虚拟形象", color = Muted, fontSize = 12.sp)
            }
            PanelCard(Modifier.align(Alignment.BottomEnd).padding(14.dp).size(95.dp, 112.dp)) {
                Box(Modifier.fillMaxSize().background(BluePanel), contentAlignment = Alignment.Center) {
                    Text("本机视频\nMock", color = Pale, textAlign = TextAlign.Center, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PanelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("你：我给你看看这个页面。", color = Pale, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("Rin：字幕 / ASR / TTS 接口将于 P3 接入。", color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(11.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedButton(onClick = model::toggleMicDemo, modifier = Modifier.testTag("call-mic-demo")) {
                Text(if (state.microphoneDemoOn) "🎙 麦克风" else "🎙 已关闭")
            }
            OutlinedButton(onClick = model::toggleCameraDemo, modifier = Modifier.testTag("call-camera-demo")) {
                Text(if (state.cameraDemoOn) "📷 模拟开启" else "📷 摄像头")
            }
            OutlinedButton(onClick = model::toggleScreenDemo, modifier = Modifier.testTag("call-screen-demo")) {
                Text(if (state.screenDemoOn) "▣ 模拟共享" else "▣ 共享屏幕")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = model::back, modifier = Modifier.fillMaxWidth().testTag("call-end"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC44061))) {
            Text("结束演示", color = Color.White)
        }
        Spacer(Modifier.height(10.dp))
    }
}
