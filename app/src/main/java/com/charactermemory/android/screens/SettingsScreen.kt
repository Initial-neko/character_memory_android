package com.charactermemory.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** P1 deterministic UI screen: offline state, never a Core API request. */
@Composable
internal fun SettingsScreen() {
    var speech by rememberSaveable { mutableStateOf(true) }
    var visual by rememberSaveable { mutableStateOf(false) }
    var notifications by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen-settings")
        .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DemoBanner("无 Core 连接；本页开关只控制原型显示，不会修改服务器配置")
        SectionHeading("连接状态")
        PanelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp)) {
                Text("○ 未连接（Mock 模式）", color = Cyan, fontWeight = FontWeight.SemiBold)
                Text("Core 配对、Tailscale 状态和设备身份将在 P2 接入", color = Muted, fontSize = 12.sp)
            }
        }
        SectionHeading("本地偏好")
        SettingToggle("TTS 语音播报", speech, { speech = it })
        SettingToggle("视觉观察", visual, { visual = it })
        SettingToggle("消息通知", notifications, { notifications = it })
        SectionHeading("设备检查")
        DemoBanner("麦克风 / 摄像头 / 屏幕授权：未请求；CI 与真机验收计划详见 TESTING.md")
        Text("Character Memory Android · 0.1.0-p1 · UI Prototype", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingToggle(label: String, enabled: Boolean, toggle: (Boolean) -> Unit) {
    PanelCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Pale, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = toggle)
        }
    }
}
