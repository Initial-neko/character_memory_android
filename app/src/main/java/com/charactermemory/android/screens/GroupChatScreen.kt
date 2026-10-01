package com.charactermemory.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Existing group chat prototype. No group creation or network side effects. */
@Composable
internal fun GroupChatScreen(state: PrototypeUiState, model: PrototypeViewModel) {
    var draft by rememberSaveable(state.selectedGroupName) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().testTag("screen-group-chat").padding(horizontal = 14.dp)) {
        DemoBanner("现有群聊：${state.selectedGroupName} · 本地 Mock 消息，不会联系真实人物")
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.groupChat, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth()) {
                    Text(message.author, color = if (message.outbound) Cyan else Purple, fontSize = 12.sp)
                    Card(
                        modifier = Modifier.fillMaxWidth(0.91f).padding(top = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.outbound) Color(0xFF2858A0) else Color(0xFF273549)
                        ),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Text(message.text, color = Pale, modifier = Modifier.padding(12.dp), fontSize = 15.sp)
                    }
                    if (message.simulated) Text("本地消息 · 未发送", color = Muted, fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                label = { Text("群聊消息（Mock）") },
                maxLines = 3,
                shape = RoundedCornerShape(15.dp),
                modifier = Modifier.weight(1f).testTag("group-chat-input")
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = { model.sendLocalGroup(draft); draft = "" },
                enabled = draft.isNotBlank(), modifier = Modifier.testTag("group-chat-send")) {
                Text("发送")
            }
        }
    }
}
