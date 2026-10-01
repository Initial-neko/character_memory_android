package com.charactermemory.android

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** P1 deterministic UI screen: offline state, never a Core API request. */
@Composable
internal fun ChatScreen(state: PrototypeUiState, model: PrototypeViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().testTag("screen-chat").padding(horizontal = 14.dp)) {
        DemoBanner("演示对话 · 发送只写入当前设备的内存，不调用 AI")
        Spacer(Modifier.height(9.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            contentPadding = PaddingValues(bottom = 15.dp)) {
            items(state.chat, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outbound) Arrangement.End else Arrangement.Start) {
                    Column(horizontalAlignment = if (message.outbound) Alignment.End else Alignment.Start) {
                        Text(message.author, fontSize = 11.sp, color = Muted)
                        Card(
                            modifier = Modifier.widthIn(max = 310.dp).padding(top = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = if (message.outbound) Color(0xFF2959A0) else Color(0xFF283447)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(message.text, color = Pale, fontSize = 15.sp, lineHeight = 23.sp,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp))
                        }
                        if (message.simulated) Text("本地预览 · 未发送", color = Cyan, fontSize = 10.sp)
                    }
                }
            }
            item {
                PanelCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("✦ AI 图片预览（Mock）", color = Purple, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(105.dp).clip(RoundedCornerShape(11.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF294B79), Color(0xFF58447F)))),
                            contentAlignment = Alignment.Center) {
                            Text("图像消息展示区", color = Pale, fontSize = 17.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { model.show(Screen.CALL) }, modifier = Modifier.testTag("chat-open-call")) { Text("◉ 语音 / 视频") }
            OutlinedButton(onClick = { model.show(Screen.CHARACTER) }) { Text("✦ AI 生图（展示）") }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("输入消息…", fontSize = 14.sp) },
                modifier = Modifier.weight(1f).testTag("chat-input"),
                shape = RoundedCornerShape(17.dp), maxLines = 3
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = { model.sendLocal(input); input = "" },
                enabled = input.isNotBlank(),
                modifier = Modifier.testTag("chat-send"),
                contentPadding = PaddingValues(horizontal = 15.dp)) {
                Text("➤", fontSize = 20.sp)
            }
        }
    }
}
