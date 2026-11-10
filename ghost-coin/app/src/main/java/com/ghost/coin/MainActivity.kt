package com.ghost.coin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

class MainActivity : ComponentActivity() {
    private lateinit var audio: Audio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        audio = Audio(applicationContext)
        setContent {
            val scope = rememberCoroutineScope()
            val game = remember { Game(applicationContext, scope, audio) }
            BackHandler(game.screen != Screen.HOME && game.state.target == null) { game.screen = Screen.HOME }
            MaterialTheme(darkColorScheme(primary = Gold, surface = Panel, background = Night)) { GhostCoinApp(game) }
        }
    }

    override fun onStart() { super.onStart(); audio.start() }
    override fun onStop() { audio.stop(); super.onStop() }
}
