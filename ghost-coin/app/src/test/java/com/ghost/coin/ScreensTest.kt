package com.ghost.coin

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.ghost.coin.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Rend chaque écran avec un état de partie avancé, l'enregistre en PNG et vérifie les parcours principaux. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class ScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var game: Game

    private fun launch(state: GameState) {
        File(ctx.filesDir, "save.json").writeText(JSON.encodeToString(GameState.serializer(), state))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val scope = rememberCoroutineScope()
            game = remember { Game(ctx, scope, null) }
            MaterialTheme(darkColorScheme()) { GhostCoinApp(game) }
        }
        settle()
    }

    /** Laisse aboutir les appels au moteur (fil d'E/S) et avance les animations. */
    private fun settle(ms: Long = 1500) { Thread.sleep(250); rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    private val rich = GameState(
        name = "Lueur", avatar = 2, village = 1, coins = 1_234_567, spins = 37, spinsAt = System.currentTimeMillis(), shields = 2, attacks = 1, raids = 2,
        levels = listOf(3, 2, 1, 3, 0, 2, 1, 3), damaged = List(8) { it == 1 }, stars = 39,
        cards = (0 until 48).filter { it % 3 != 1 }.associateWith { 1 + it % 3 },
        daily = mapOf("spin" to 30, "build" to 2), weekly = mapOf("spin" to 120),
        log = listOf(LogEntry("Ossian", "bot:1", "attack", 1, false, 0), LogEntry("Sélène", "bot:7", "raid", 4200, false, 0), LogEntry("Pépin", "bot:6", "attack", 2, true, 0, true)),
    )

    @Test fun rendersEveryScreen() {
        launch(rich.copy(target = BotWorld.bot(rich, kotlin.random.Random(3)), targetKind = "attack", raidPot = 84_000, raidShares = listOf(50, 30, 20)))
        for (sc in Screen.entries) {
            rule.runOnUiThread { game.screen = sc }
            settle()
            rule.onRoot().captureRoboImage("build/shots/${sc.name}.png")
        }
        rule.runOnUiThread { game.screen = Screen.HOME; game.play(listOf(Event("cards", ids = listOf(0, 13, 22, 31, 47)))) }
        settle(2500)
        rule.onRoot().captureRoboImage("build/shots/REVEAL.png")
    }

    @Test fun spinButtonConsumesASpinAndUpdatesTheReels() {
        launch(GameState(spinsAt = System.currentTimeMillis()))
        rule.onNodeWithText("SPIN").performClick()
        repeat(6) { settle(700) }
        assertEquals(Rules.MAX_SPINS - 1, game.state.spins - game.state.let { s -> if (s.spins > Rules.MAX_SPINS - 1) s.spins - (Rules.MAX_SPINS - 1) else 0 })
        assertEquals(1L, game.state.stats["spin"])
        assertTrue(!game.busy)
    }

    @Test fun buildFlowUpgradesABuilding() {
        launch(GameState(coins = 500_000))
        rule.onNodeWithText("Construire").performClick(); settle()
        rule.onNodeWithText("Améliorer\n3000").performClick(); settle()
        assertEquals(1, game.state.levels[0])
        assertEquals(500_000L - 3000, game.state.coins)
    }

    @Test fun attackFlowFromHomeToReward() {
        launch(GameState(attacks = 1))
        rule.onNodeWithText("Attaque").performClick(); settle()
        assertEquals(Screen.ATTACK, game.screen)
        val t = game.state.target!!
        val b = t.levels.indexOfFirst { it > 0 }
        rule.onNodeWithText("${BUILDINGS[b]}\nniveau ${t.levels[b]}").performClick()
        repeat(4) { settle(600) }
        assertEquals(null, game.state.target)
        assertTrue(game.state.coins > 20_000)
        rule.onNodeWithText("Continuer").performClick(); settle()
        assertEquals(Screen.HOME, game.screen)
    }

    @Test fun raidFlowDigsThreeHoles() {
        launch(GameState(raids = 1))
        rule.onNodeWithText("Raid").performClick(); settle()
        assertEquals(Screen.RAID, game.screen)
        repeat(3) { rule.onAllNodes(androidx.compose.ui.test.hasText("Creuser"))[0].performClick(); settle() }
        assertEquals(null, game.state.target)
        assertEquals(1L, game.state.stats["raid"])
        rule.onNodeWithText("Continuer").performClick(); settle()
        assertEquals(Screen.HOME, game.screen)
    }
}
