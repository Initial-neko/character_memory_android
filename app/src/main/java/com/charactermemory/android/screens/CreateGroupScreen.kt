package com.charactermemory.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
internal fun CreateGroupScreen() {
    var prompt by rememberSaveable { mutableStateOf("") }
    var preview by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen-group")
        .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DemoBanner("群聊成员为固定 Mock；不会新增真实人物或群聊")
        SectionHeading("创建一个属于人物们的群聊", "AI 一键建群完整流程将在 P2 接入")
        OutlinedTextField(value = prompt, onValueChange = { prompt = it; preview = false },
            label = { Text("群聊主题 / 描述 *") },
            placeholder = { Text("例如：喜欢二次元和旅行的四个朋友…") },
            modifier = Modifier.fillMaxWidth().height(142.dp).testTag("group-description"), maxLines = 5)
        if (preview) {
            SectionHeading("成员预览", "演示固定成员，没有调用后端")
            MockContent.suggestedMembers.forEachIndexed { index, member ->
                PanelCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar((index + 1).toString(), if (index % 2 == 0) Purple else Cyan)
                        Spacer(Modifier.width(12.dp))
                        Text(member, color = Pale, fontSize = 14.sp)
                    }
                }
            }
            DemoBanner("预览已生成 · 此阶段不提供真实确认提交")
        }
        Button(
            onClick = { preview = true },
            enabled = PrototypeRules.validGroupPrompt(prompt),
            modifier = Modifier.fillMaxWidth().height(51.dp).testTag("group-preview"),
            shape = RoundedCornerShape(15.dp)
        ) { Text("✦ 一键生成群聊预览（Mock）", fontWeight = FontWeight.Bold) }
    }
}
