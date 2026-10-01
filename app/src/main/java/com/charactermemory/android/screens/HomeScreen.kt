package com.charactermemory.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** P1 deterministic UI screen: offline state, never a Core API request. */
@Composable
internal fun HomeScreen(model: PrototypeViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("screen-home"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item { DemoBanner("P1 原型 · 本地 Mock 数据，不连接 Core") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                QuickAction("✦", "一键生成人物", Modifier.weight(1f).testTag("home-create-character")) {
                    model.show(Screen.CHARACTER)
                }
                QuickAction("♧", "一键生成群聊", Modifier.weight(1f).testTag("home-create-group")) {
                    model.show(Screen.GROUP)
                }
                QuickAction("◎", "空间", Modifier.weight(1f).testTag("home-space")) {
                    model.show(Screen.SPACE)
                }
            }
        }
        item { SectionHeading("全部对话", "角色、群聊与最近消息") }
        items(MockContent.characters, key = { it.id }) { item ->
            PanelCard(Modifier.fillMaxWidth().clickable { model.openCharacter(item.id) }.testTag("character-" + item.id)) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(item.initials, Color(item.tint))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = Pale, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(item.tagline, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(item.time, color = Muted, fontSize = 10.sp)
                        if (item.unread > 0) {
                            Spacer(Modifier.height(9.dp))
                            Text(item.unread.toString(), color = Color.White, fontSize = 11.sp,
                                modifier = Modifier.background(Color(0xFFDE506B), CircleShape).padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
        item { SectionHeading("群聊", "点击后进入 Mock 群聊预览") }
        items(MockContent.groupNames) { name ->
            PanelCard(Modifier.fillMaxWidth().clickable { model.show(Screen.GROUP) }) {
                Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar("群", Cyan)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(name, color = Pale, fontSize = 16.sp)
                        Text("模拟群聊 · 新成员与消息将在 P2 接入", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAction(symbol: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PanelCard(modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().height(100.dp).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(symbol, fontSize = 27.sp, color = Purple)
            Spacer(Modifier.height(10.dp))
            Text(label, fontSize = 11.sp, color = Pale, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}
