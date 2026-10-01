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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
internal fun CreateCharacterScreen() {
    var description by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var preview by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("screen-character")
        .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DemoBanner("P1 仅展示创建流程；预览不会真的生成或保存人物")
        SectionHeading("用自然语言创造一个新人物", "描述人物的性格、经历、关系和日常")
        OutlinedTextField(value = description, onValueChange = { description = it; preview = false },
            label = { Text("人物设定描述 *") }, placeholder = { Text("例如：温柔的摄影爱好者，有独立的生活…") },
            modifier = Modifier.fillMaxWidth().height(145.dp).testTag("character-description"), maxLines = 5)
        OutlinedTextField(value = name, onValueChange = { name = it },
            label = { Text("名字（可选）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = age, onValueChange = { age = it },
            label = { Text("年龄（可选）") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tags, onValueChange = { tags = it },
            label = { Text("标签（可选，逗号分隔）") }, modifier = Modifier.fillMaxWidth())
        if (preview) {
            PanelCard(Modifier.fillMaxWidth().testTag("character-preview-card")) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar((name.trim().take(1)).ifBlank { "新" }, Purple)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(name.ifBlank { "待命名人物" }, color = Pale, fontWeight = FontWeight.SemiBold)
                        Text(description.trim(), color = Muted, fontSize = 12.sp, maxLines = 3)
                        Text("静态预览 · 未调用 LLM", color = Cyan, fontSize = 11.sp)
                    }
                }
            }
        }
        Button(
            onClick = { preview = true },
            enabled = PrototypeRules.validCharacterDescription(description),
            modifier = Modifier.fillMaxWidth().height(51.dp).testTag("character-preview"),
            shape = RoundedCornerShape(15.dp)
        ) { Text("✦ 生成预览（Mock）", fontWeight = FontWeight.Bold) }
    }
}
