package com.charactermemory.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private val Navy = Color(0xFF091120)
private val Panel = Color(0xFF151F31)
private val BluePanel = Color(0xFF1D2B47)
private val Accent = Color(0xFF79A9FF)
private val Muted = Color(0xFF9BAFCB)
private val Pale = Color(0xFFEAF1FF)
private val Purple = Color(0xFFB09CFF)
private val Cyan = Color(0xFF76D4E9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CharacterMemoryPrototype() }
    }
}

@Composable
fun CharacterMemoryPrototype(model: PrototypeViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val colors = darkColorScheme(
        primary = Accent, onPrimary = Navy,
        secondary = Purple, background = Navy,
        surface = Panel, onSurface = Pale
    )
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            containerColor = Navy,
            topBar = {
                Header(
                    title = when (state.screen) {
                        Screen.HOME -> "Character Memory"
                        Screen.CHAT -> MockContent.characters.firstOrNull { it.id == state.selectedCharacterId }?.name ?: "聊天"
                        Screen.CHARACTER -> "创建新人物"
                        Screen.GROUP -> "创建群聊"
                        Screen.CALL -> "语音 / 视频通话"
                        Screen.SPACE -> "空间"
                        Screen.SETTINGS -> "基础设置"
                    },
                    isRoot = state.screen in setOf(Screen.HOME, Screen.SPACE, Screen.SETTINGS),
                    back = model::back
                )
            },
            bottomBar = {
                if (state.screen in setOf(Screen.HOME, Screen.SPACE, Screen.SETTINGS)) {
                    NavigationBar(containerColor = Color(0xFF111B2C)) {
                        listOf(
                            Triple(Screen.HOME, "聊天", "◉"),
                            Triple(Screen.SPACE, "空间", "◎"),
                            Triple(Screen.SETTINGS, "设置", "⚙")
                        ).forEach { (destination, label, symbol) ->
                            NavigationBarItem(
                                selected = state.screen == destination,
                                onClick = { model.show(destination) },
                                icon = { Text(symbol, fontSize = 22.sp) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("tab-" + destination.name.lowercase())
                            )
                        }
                    }
                }
            }
        ) { inner ->
            Box(
                Modifier.fillMaxSize().padding(inner).background(
                    Brush.verticalGradient(listOf(Navy, Color(0xFF0C1428), Navy))
                )
            ) {
                when (state.screen) {
                    Screen.HOME -> HomeScreen(model)
                    Screen.CHAT -> ChatScreen(state, model)
                    Screen.CHARACTER -> CreateCharacterScreen()
                    Screen.GROUP -> CreateGroupScreen()
                    Screen.CALL -> CallScreen(state, model)
                    Screen.SPACE -> SpaceScreen(state, model)
                    Screen.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, isRoot: Boolean, back: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Navy).height(62.dp).padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isRoot) {
            TextButton(onClick = back, modifier = Modifier.testTag("nav-back")) {
                Text("‹", fontSize = 29.sp, color = Pale)
            }
        }
        Text(title, color = Pale, fontSize = if (isRoot) 22.sp else 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("MOCK", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(BluePanel, RoundedCornerShape(7.dp)).padding(7.dp))
    }
}

@Composable
private fun Avatar(label: String, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(tint, Color(0xFF293B65)))),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, Color(0xFF263956))
    ) { content() }
}

@Composable
private fun SectionHeading(title: String, detail: String? = null) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(title, color = Pale, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        if (detail != null) Text(detail, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun DemoBanner(text: String) {
    Text(text, fontSize = 12.sp, color = Cyan, modifier = Modifier.fillMaxWidth()
        .background(Color(0xFF142A3C), RoundedCornerShape(9.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun HomeScreen(model: PrototypeViewModel) {
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

@Composable
private fun ChatScreen(state: PrototypeUiState, model: PrototypeViewModel) {
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

@Composable
private fun CreateCharacterScreen() {
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

@Composable
private fun CreateGroupScreen() {
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

@Composable
private fun CallScreen(state: PrototypeUiState, model: PrototypeViewModel) {
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

@Composable
private fun SpaceScreen(state: PrototypeUiState, model: PrototypeViewModel) {
    LazyColumn(Modifier.fillMaxSize().testTag("screen-space"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { DemoBanner("空间动态是 Mock 样例 · 赞、评论数量仅供布局验收") }
        items(MockContent.posts, key = { it.id }) { post ->
            PanelCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(post.author.take(1), if (post.author == "Rin") Purple else Cyan)
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text(post.author, color = Pale, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(post.relativeTime, color = Muted, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                    Text(post.content, color = Pale, fontSize = 14.sp, lineHeight = 23.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(13.dp))
                        .background(Brush.horizontalGradient(listOf(BluePanel, Color(0xFF343652)))),
                        contentAlignment = Alignment.Center) {
                        Text(post.kind, color = Color(0xFFD2DCFF), fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { model.toggleLike(post.id) }, modifier = Modifier.testTag("space-like-" + post.id)) {
                            Text((if (state.likedPosts.contains(post.id)) "♥ " else "♡ ") +
                                (post.likes + if (state.likedPosts.contains(post.id)) 1 else 0), color = Purple)
                        }
                        Text("◌ " + post.comments + " 条评论", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("本地展示", color = Cyan, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
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
