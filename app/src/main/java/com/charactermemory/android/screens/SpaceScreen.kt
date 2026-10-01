package com.charactermemory.android

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
internal fun SpaceScreen(state: PrototypeUiState, model: PrototypeViewModel) {
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
