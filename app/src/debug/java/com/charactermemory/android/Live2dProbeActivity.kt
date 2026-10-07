package com.charactermemory.android

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.charactermemory.android.live.CallCharacterState
import com.charactermemory.android.live.Live2dCallCharacter

/** Debug-only presentation probe. No LiveViewModel, call session or capture owner. */
class Live2dProbeActivity : ComponentActivity() {
    private var inset by mutableStateOf(false)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        inset = intent.getBooleanExtra("inset", false)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = intent.getStringExtra("core_url").orEmpty()
        val character = intent.getStringExtra("character_id").orEmpty()
        inset = intent.getBooleanExtra("inset", false)
        setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF102030))) {
                if (core.isNotBlank() && character.isNotBlank())
                    Live2dCallCharacter(core, character, CallCharacterState("listening", ""), inset,
                        if (inset) Modifier.align(Alignment.BottomEnd).padding(16.dp).size(150.dp,200.dp) else Modifier.fillMaxSize())
            }
        }
    }
}
