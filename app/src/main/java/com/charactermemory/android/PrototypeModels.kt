package com.charactermemory.android

/**
 * P1: all content stays on-device; no requests to Character Core or Media Runtime.
 * P2 must replace this provider with API-backed repositories without modifying the
 * user-visible presentation contract.
 */
enum class Screen { HOME, CHAT, CHARACTER, GROUP, CALL, SPACE, SETTINGS }

data class CharacterPreview(
    val id: String,
    val name: String,
    val initials: String,
    val tagline: String,
    val time: String,
    val unread: Int,
    val tint: Long
)

data class ChatBubble(
    val id: Long,
    val author: String,
    val text: String,
    val outbound: Boolean = false,
    val simulated: Boolean = false
)

data class SpaceEntry(
    val id: String,
    val author: String,
    val relativeTime: String,
    val content: String,
    val kind: String,
    val likes: Int,
    val comments: Int
)

object MockContent {
    val characters = listOf(
        CharacterPreview("rin", "Rin", "R", "想和你分享刚刚发生的故事…", "14:20", 1, 0xFF9887DA),
        CharacterPreview("hoshino", "星野遥", "遥", "今天的天空很好看，一起去散步吧", "12:06", 3, 0xFF62B8D6),
        CharacterPreview("lex", "Lex", "L", "已发送图片", "11:34", 0, 0xFF8495B6),
        CharacterPreview("alice", "艾莉丝", "艾", "晚安，做个好梦", "昨天", 0, 0xFFD18BAA)
    )
    val groupNames = listOf("次元旅行小队", "摄影交流会")
    val suggestedMembers = listOf("Rin · 温柔 / 摄影", "星野遥 · 活泼 / 游戏", "Lex · 冷静 / 技术", "艾莉丝 · 艺术 / 音乐")
    val initialChat = listOf(
        ChatBubble(1, "Rin", "今天过得怎么样？有什么有趣的事情吗？"),
        ChatBubble(2, "我", "我刚拍了一张照片，给你看看！", outbound = true),
        ChatBubble(3, "Rin", "这家咖啡店的氛围好棒，下次带我一起去吧 ☕"),
        ChatBubble(4, "Rin", "我还想为你画一张我们一起喝咖啡的画。")
    )
    val posts = listOf(
        SpaceEntry("p1", "Rin", "2 小时前", "今天天气很好！分享一些在咖啡店看到的光影 ☕✨", "照片 · 4 张", 328, 24),
        SpaceEntry("p2", "星野遥", "5 小时前", "新的旅程即将开始！这次也一起出发吧～ 🌟", "照片 · 1 张", 189, 12),
        SpaceEntry("p3", "Lex", "昨天", "把之前整理的工具重新检查了一遍，终于顺手多了。", "文字动态", 47, 6)
    )
}

/** Pure, testable navigation and local-only prototype rules. */
object PrototypeRules {
    fun validCharacterDescription(input: String): Boolean = input.trim().length >= 3
    fun validGroupPrompt(input: String): Boolean = input.trim().length >= 3

    fun appendDemoMessage(existing: List<ChatBubble>, input: String): List<ChatBubble> {
        val clean = input.trim()
        if (clean.isEmpty()) return existing
        val nextId = (existing.maxOfOrNull { it.id } ?: 0L) + 1
        return existing + ChatBubble(nextId, "我", clean, outbound = true, simulated = true)
    }

    fun characterIdExists(id: String): Boolean = MockContent.characters.any { it.id == id }
}
