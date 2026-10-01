package com.charactermemory.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeRulesTest {
    @Test fun refusesWhitespaceOnlyMessages() {
        val previous = MockContent.initialChat
        assertEquals(previous, PrototypeRules.appendDemoMessage(previous, "   \n "))
    }

    @Test fun appendsLocalMessageWithoutPretendingItWasSent() {
        val base = MockContent.initialChat
        val updated = PrototypeRules.appendDemoMessage(base, "  帮我看看这个  ")
        assertEquals(base.size + 1, updated.size)
        assertEquals("帮我看看这个", updated.last().text)
        assertEquals(base.maxOf { it.id } + 1, updated.last().id)
        assertTrue(updated.last().outbound)
        assertTrue(updated.last().simulated)
        assertEquals(base.size, MockContent.initialChat.size)
    }

    @Test fun onlyValidPromptsEnableMockPreview() {
        assertFalse(PrototypeRules.validCharacterDescription(""))
        assertFalse(PrototypeRules.validCharacterDescription("  "))
        assertFalse(PrototypeRules.validGroupPrompt("a"))
        assertTrue(PrototypeRules.validCharacterDescription("在自己的世界里生活"))
        assertTrue(PrototypeRules.validGroupPrompt("二次元旅行小队"))
    }

    @Test fun mockCharacterRosterHasStableUniqueIds() {
        val chars = MockContent.characters
        assertTrue(chars.size >= 4)
        assertEquals(chars.size, chars.map { it.id }.distinct().size)
        assertTrue(PrototypeRules.characterIdExists("rin"))
        assertFalse(PrototypeRules.characterIdExists("unknown"))
    }

    @Test fun spaceFeedIdentifiersRemainUnique() {
        assertEquals(MockContent.posts.size, MockContent.posts.map { it.id }.distinct().size)
        assertTrue(MockContent.posts.all { it.likes >= 0 && it.comments >= 0 })
    }
}
