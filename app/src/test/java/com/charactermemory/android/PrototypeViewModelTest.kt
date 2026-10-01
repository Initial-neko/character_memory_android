package com.charactermemory.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeViewModelTest {
    @Test fun navigationReturnsFromCallToChat() {
        val vm = PrototypeViewModel()
        vm.openCharacter("rin")
        vm.show(Screen.CALL)
        vm.back()
        assertEquals(Screen.CHAT, vm.state.value.screen)
        assertEquals("rin", vm.state.value.selectedCharacterId)
    }

    @Test fun directChatBackReturnsHome() {
        val vm = PrototypeViewModel()
        vm.openCharacter("lex")
        vm.back()
        assertEquals(Screen.HOME, vm.state.value.screen)
        assertEquals("Lex", vm.state.value.chat.first().author)
    }

    @Test fun existingGroupOpensGroupChatWithoutCreatingAnotherGroup() {
        val vm = PrototypeViewModel()
        vm.openGroup(MockContent.groupNames.last())
        assertEquals(Screen.GROUP_CHAT, vm.state.value.screen)
        assertEquals(MockContent.groupNames.last(), vm.state.value.selectedGroupName)
        assertEquals("Rin", vm.state.value.groupChat.first().author)
        vm.sendLocalGroup("  Mock 群聊消息  ")
        assertTrue(vm.state.value.groupChat.last().simulated)
        assertEquals("Mock 群聊消息", vm.state.value.groupChat.last().text)
        vm.back()
        assertEquals(Screen.HOME, vm.state.value.screen)
    }

    @Test fun mockDirectMessagesStayWithTheirOwnCharacter() {
        val vm = PrototypeViewModel()
        vm.openCharacter("rin")
        vm.sendLocal("只发给 Rin")
        vm.openCharacter("lex")
        assertFalse(vm.state.value.chat.any { it.text == "只发给 Rin" })
        vm.sendLocal("只发给 Lex")
        vm.openCharacter("rin")
        assertEquals("只发给 Rin", vm.state.value.chat.last().text)
        vm.openCharacter("lex")
        assertEquals("只发给 Lex", vm.state.value.chat.last().text)
    }

    @Test fun mockGroupMessagesSurviveNavigationWithoutCrossTalk() {
        val vm = PrototypeViewModel()
        val first = MockContent.groupNames.first()
        val second = MockContent.groupNames.last()
        vm.openGroup(first)
        vm.sendLocalGroup("第一个群的消息")
        vm.openGroup(second)
        assertFalse(vm.state.value.groupChat.any { it.text == "第一个群的消息" })
        vm.openGroup(first)
        assertEquals("第一个群的消息", vm.state.value.groupChat.last().text)
        assertTrue(vm.state.value.groupChat.last().simulated)
    }

    @Test fun localMessagesAreNotBackendMessages() {
        val vm = PrototypeViewModel()
        val before = vm.state.value.chat.size
        vm.sendLocal("  本地测试 ")
        assertEquals(before + 1, vm.state.value.chat.size)
        assertEquals("本地测试", vm.state.value.chat.last().text)
        assertTrue(vm.state.value.chat.last().simulated)
    }

    @Test fun likeToggleIsIdempotentAfterTwoToggles() {
        val vm = PrototypeViewModel()
        vm.toggleLike("p1")
        assertTrue(vm.state.value.likedPosts.contains("p1"))
        vm.toggleLike("p1")
        assertFalse(vm.state.value.likedPosts.contains("p1"))
    }

    @Test fun callTogglesNeverStartCapture() {
        val vm = PrototypeViewModel()
        assertFalse(vm.state.value.cameraDemoOn)
        assertFalse(vm.state.value.screenDemoOn)
        vm.toggleCameraDemo()
        vm.toggleScreenDemo()
        vm.toggleMicDemo()
        assertTrue(vm.state.value.cameraDemoOn)
        assertTrue(vm.state.value.screenDemoOn)
        assertFalse(vm.state.value.microphoneDemoOn)
        assertEquals(Screen.HOME, vm.state.value.screen)
    }
}
