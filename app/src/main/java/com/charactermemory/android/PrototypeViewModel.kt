package com.charactermemory.android

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PrototypeUiState(
    val screen: Screen = Screen.HOME,
    val selectedCharacterId: String = "rin",
    val selectedGroupName: String = MockContent.groupNames.first(),
    val groupChat: List<ChatBubble> = MockContent.groupChatFor(MockContent.groupNames.first()),
    val chat: List<ChatBubble> = MockContent.initialChat,
    val likedPosts: Set<String> = emptySet(),
    val microphoneDemoOn: Boolean = true,
    val cameraDemoOn: Boolean = false,
    val screenDemoOn: Boolean = false
)

/**
 * P1 state is intentionally in-memory only. No networking, no microphone
 * acquisition, no camera stream, no screen-recording permission.
 */
class PrototypeViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(PrototypeUiState())
    val state: StateFlow<PrototypeUiState> = mutableState.asStateFlow()

    fun show(screen: Screen) {
        mutableState.value = mutableState.value.copy(screen = screen)
    }

    fun openCharacter(id: String) {
        require(PrototypeRules.characterIdExists(id)) { "Unknown mock character" }
        mutableState.value = mutableState.value.copy(
            selectedCharacterId = id,
            chat = MockContent.chatFor(id),
            screen = Screen.CHAT
        )
    }

    fun openGroup(name: String) {
        require(name in MockContent.groupNames) { "Unknown mock group" }
        mutableState.value = mutableState.value.copy(
            selectedGroupName = name,
            groupChat = MockContent.groupChatFor(name),
            screen = Screen.GROUP_CHAT
        )
    }

    fun sendLocalGroup(text: String) {
        mutableState.value = mutableState.value.copy(
            groupChat = PrototypeRules.appendDemoMessage(mutableState.value.groupChat, text)
        )
    }

    fun back() {
        mutableState.value = mutableState.value.copy(
            screen = when (mutableState.value.screen) {
                Screen.CALL -> Screen.CHAT
                Screen.HOME -> Screen.HOME
                else -> Screen.HOME
            }
        )
    }

    fun sendLocal(text: String) {
        mutableState.value = mutableState.value.copy(
            chat = PrototypeRules.appendDemoMessage(mutableState.value.chat, text)
        )
    }

    fun toggleLike(id: String) {
        val current = mutableState.value.likedPosts
        val updated = if (current.contains(id)) current - id else current + id
        mutableState.value = mutableState.value.copy(likedPosts = updated)
    }

    fun toggleMicDemo() {
        mutableState.value = mutableState.value.copy(microphoneDemoOn = !mutableState.value.microphoneDemoOn)
    }

    fun toggleCameraDemo() {
        mutableState.value = mutableState.value.copy(cameraDemoOn = !mutableState.value.cameraDemoOn)
    }

    fun toggleScreenDemo() {
        mutableState.value = mutableState.value.copy(screenDemoOn = !mutableState.value.screenDemoOn)
    }
}
