package com.charactermemory.android.live

/** Stage selection has no access to VoiceCallCoordinator or capture/session factories. */
enum class CallStageMode { AVATAR, LIVE2D, VIDEO, SCREEN_SHARE }
data class CallStageState(val characterMode: CallStageMode = CallStageMode.AVATAR) {
    fun mode(sharing: Boolean, camera: Boolean): CallStageMode = when {
        sharing -> CallStageMode.SCREEN_SHARE
        camera -> CallStageMode.VIDEO
        else -> characterMode
    }
    fun hasCharacterOverlay(sharing: Boolean, camera: Boolean) = sharing || camera
}
data class CallCharacterState(val phase: String, val replyText: String)
interface CallCharacterRenderer {
    fun setCharacterState(state: CallCharacterState)
    fun playAction(action: String)
    fun setExpression(expression: String)
}
