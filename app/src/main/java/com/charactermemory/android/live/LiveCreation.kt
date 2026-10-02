package com.charactermemory.android.live

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.charactermemory.android.data.*

@Composable
internal fun LiveCharacter(state: LiveState, model: LiveViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("live-character-create"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("先生成草稿，查看后确认创建。生成会调用 Core 的模型。", color = LiveMuted)
        OutlinedTextField(state.characterPrompt, model::editCharacterPrompt, label = { Text("人物描述") }, minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag("live-character-description"))
        LiveAction(if ("character-draft" in state.busy) "正在生成…" else "生成草稿", "live-character-draft",
            state.busy.isEmpty()) { model.generateCharacter() }
        state.draft?.let { draft ->
            Card(Modifier.fillMaxWidth().testTag("live-character-preview")) {
                Column(Modifier.padding(14.dp)) { LiveJsonDetails(draft) }
            }
            LiveAction("确认创建人物", "live-character-confirm", state.busy.isEmpty()) { model.confirmCharacter() }
        }
    }
    CapacityDialog(state, model)
}

@Composable
internal fun CapacityDialog(state: LiveState, model: LiveViewModel) {
    val key = state.capacityConfirmation ?: return
    AlertDialog(onDismissRequest = { model.update { it.copy(capacityConfirmation = null) } },
        title = { Text("继续超过人物提醒阈值？") }, text = { Text(state.error ?: "服务器要求确认人物数量提醒。") },
        confirmButton = { TextButton(onClick = {
            model.update { it.copy(capacityConfirmation = null) }
            if (key == "character-confirm") model.confirmCharacter(true) else if (key == "ensemble-confirm") model.confirmEnsemble(true)
        }, modifier = Modifier.testTag("live-capacity-confirm")) { Text("确认继续") } },
        dismissButton = { TextButton(onClick = { model.update { it.copy(capacityConfirmation = null) } }) { Text("暂不创建") } })
}

@Composable
internal fun LiveEnsemble(state: LiveState, model: LiveViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("live-ensemble"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Core 整理成员资料和草稿，确认后才建立可用群聊。", color = LiveMuted)
        OutlinedTextField(state.ensemblePrompt, model::editEnsemblePrompt, label = { Text("群聊描述") }, minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag("live-group-prompt"))
        LiveAction(if ("ensemble-prepare" in state.busy) "正在整理…" else "准备群聊草稿", "live-group-prepare",
            state.busy.isEmpty()) { model.prepareEnsemble() }
        TextButton(onClick = { model.resumeEnsemble() }, enabled = state.busy.isEmpty(), modifier = Modifier.testTag("live-ensemble-resume")) { Text("恢复最近未完成构建") }
        state.build?.let { build ->
            Column(Modifier.fillMaxWidth().testTag("live-ensemble-preview"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(build.text("group_name", "群聊草稿"), style = MaterialTheme.typography.titleLarge)
                Text("状态：${build.text("status")} · 可用成员 ${build.number("ready_member_count")}", modifier = Modifier.testTag("live-ensemble-status"))
                if (build.text("error").isNotBlank()) Text(build.text("error"), color = MaterialTheme.colorScheme.error)
                if (build.text("overview").isNotBlank()) Text(build.text("overview"))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { model.refreshEnsemble() }, enabled = state.busy.isEmpty(), modifier = Modifier.testTag("live-ensemble-refresh")) { Text("刷新进度") }
                    TextButton(onClick = { model.researchEnsemble() }, enabled = state.busy.isEmpty() && build.text("status") in setOf("FAILED", "BUILDING"),
                        modifier = Modifier.testTag("live-ensemble-research")) { Text("重试资料整理") }
                }
                build.items("drafts").forEach { member ->
                    val index = member.number("index", -1).toInt()
                    val draft = member.objOrNull("draft")
                    val ready = member.text("status", "READY") == "READY" && draft != null
                    Card(Modifier.fillMaxWidth().testTag("live-member-$index")) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(index in state.selectedMembers, { model.selectMember(index, it) }, enabled = ready && state.busy.isEmpty(),
                                    modifier = Modifier.testTag("live-member-select-$index"))
                                Text(member.text("canonical_name", draft?.text("name") ?: "成员 $index"), modifier = Modifier.weight(1f))
                            }
                            if (draft != null) LiveJsonDetails(draft)
                            if (member.text("error").isNotBlank()) Text(member.text("error"), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { model.researchEnsemble(index) }, enabled = state.busy.isEmpty() && index >= 0 && build.text("status") != "ACTIVE",
                                modifier = Modifier.testTag("live-member-retry-$index")) { Text("重试此成员") }
                            if (member.text("existing_character_id").isNotBlank()) Text("使用已有角色：${member.text("existing_character_id")}", color = LiveMuted)
                        }
                    }
                }
                LiveAction("确认所选成员并创建", "live-ensemble-confirm", state.busy.isEmpty() && build.text("status") == "READY" && state.selectedMembers.size in 2..12) { model.confirmEnsemble() }
                OutlinedButton(onClick = { model.cancelEnsemble() }, enabled = state.busy.isEmpty() && build.text("status") != "ACTIVE",
                    modifier = Modifier.testTag("live-ensemble-cancel")) { Text("取消此构建") }
            }
        }
        if (state.build == null && "ensemble-resume" !in state.busy) Text("没有可恢复的构建", color = LiveMuted)
    }
    CapacityDialog(state, model)
}

@Composable
internal fun LiveImage(state: LiveState, model: LiveViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("live-image"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("润色和生成会调用 Core 的模型。图片先作为草稿，查看后单独确认发送。", color = LiveMuted)
        val target = state.target
        if (target?.group == true) {
            Text("选择参考人物", color = LiveMuted)
            target.memberIds.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.imageCharacterId == id, onClick = { model.imageOptions(id, state.imagePurpose, state.imageUseAvatar) })
                    Text(id)
                }
            }
        }
        OutlinedTextField(state.imageInstruction, model::editImageInstruction, label = { Text("图片描述") }, minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag("live-image-instruction"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(state.imagePurpose == "SELFIE", { model.imageOptions(state.imageCharacterId, if (it) "SELFIE" else "SCENE", state.imageUseAvatar) },
                modifier = Modifier.testTag("live-image-selfie"))
            Text("人物自拍（关闭为场景）")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.imageUseAvatar, { model.imageOptions(state.imageCharacterId, state.imagePurpose, it) }, modifier = Modifier.testTag("live-image-avatar"))
            Text("使用当前头像参考")
        }
        LiveAction("润色提示词", "live-image-rewrite", state.busy.isEmpty()) { model.rewriteImage() }
        LiveAction(if ("image-generate" in state.busy) "处理中…" else "生成图片草稿", "live-image-generate", state.busy.isEmpty()) { model.rewriteImage(true) }
        if (state.imagePrompt.isNotBlank()) Text(state.imagePrompt, modifier = Modifier.testTag("live-image-prompt"))
        state.imageDraft?.let { draft ->
            Column(Modifier.fillMaxWidth().testTag("live-image-draft"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("未发送草稿", style = MaterialTheme.typography.titleMedium)
                val bytes = remember(draft.text("data_url")) {
                    runCatching { Base64.decode(draft.text("data_url").substringAfter(','), Base64.DEFAULT) }.getOrNull()
                }
                var previewStatus by remember(draft.text("data_url")) { mutableStateOf("加载中") }
                if (bytes != null) AsyncImage(bytes, "生成图片草稿",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp).testTag("live-image-preview")
                        .semantics { stateDescription = previewStatus },
                    onSuccess = { previewStatus = "已加载" }, onError = { previewStatus = "加载失败" })
                if (previewStatus == "加载失败") Text("图片预览加载失败", color = LiveMuted)
                LiveAction("确认发送到当前聊天", "live-image-confirm-send", "send" !in state.busy) { model.sendImageDraft() }
                OutlinedButton(onClick = model::discardImage, enabled = "send" !in state.busy, modifier = Modifier.testTag("live-image-discard")) { Text("放弃草稿") }
            }
        }
    }
}
