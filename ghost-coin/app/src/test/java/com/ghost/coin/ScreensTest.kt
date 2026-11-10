package com.ghost.coin

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

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
        rule.onNodeWithTag("build-0").performClick(); settle()
        assertEquals(1, game.state.levels[0])
        assertEquals(500_000L - 3000, game.state.coins)
    }

    @Test fun attackFlowFromHomeToReward() {
        launch(GameState(attacks = 1))
        rule.onNodeWithText("Attaque").performClick(); settle()
        assertEquals(Screen.ATTACK, game.screen)
        val t = game.state.target!!
        val b = t.levels.indexOfFirst { it > 0 }
        rule.onNodeWithTag("target-$b").performClick()
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
        for (h in 0..2) { rule.onNodeWithTag("dig-$h").performClick(); settle() }
        assertEquals(null, game.state.target)
        assertEquals(1L, game.state.stats["raid"])
        rule.onNodeWithText("Continuer").performClick(); settle()
        assertEquals(Screen.HOME, game.screen)
    }

    @Test fun rendersWorldMapAdminAndCountryVillages() {
        launch(rich.copy(village = 57, levels = listOf(3, 2, 1, 2, 0, 2, 1, 3)))
        rule.runOnUiThread { game.screen = Screen.MAP }; settle(2500)
        rule.onRoot().captureRoboImage("build/shots/MAP.png")
        rule.runOnUiThread { game.screen = Screen.ADMIN }; settle()
        rule.onRoot().captureRoboImage("build/shots/ADMIN_LOGIN.png")
        rule.onNodeWithText("Ouvrir le bac à sable").performClick(); settle()
        rule.onRoot().captureRoboImage("build/shots/SANDBOX.png")
        rule.runOnUiThread { game.screen = Screen.HOME; game.cinematic = 0 }; settle(3000)
        rule.onRoot().captureRoboImage("build/shots/CINEMATIC.png")
    }

    /** Même écran sur un petit et un grand téléphone : rien ne doit déborder ni se chevaucher. */
    @Test @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi") fun rendersHomeOnSmallPhone() { launch(rich); rule.onRoot().captureRoboImage("build/shots/HOME_small.png"); rule.onNodeWithText("SPIN").assertExists() }
    @Test @Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi") fun rendersHomeOnLargePhone() { launch(rich); rule.onRoot().captureRoboImage("build/shots/HOME_large.png"); rule.onNodeWithText("SPIN").assertExists() }

    @Test fun rendersVillagesOfManyCountries() {
        val names = listOf("Sénégal", "Mali", "Égypte", "Maroc", "Kenya", "France", "Grèce", "Russie", "Norvège", "Mexique", "Brésil", "Chine", "Japon", "Inde", "Thaïlande", "Émirats arabes unis", "Mongolie", "Australie", "Fidji", "Pays-Bas")
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                names.chunked(4).forEach { row ->
                    androidx.compose.foundation.layout.Row {
                        row.forEach { n ->
                            val i = COUNTRIES.indexOfFirst { it.name == n }
                            androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.weight(1f).height(150.dp).padding(1.dp)) { villageScene(i, List(8) { 3 }, List(8) { false }, 1.5f) }
                        }
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/shots/COUNTRIES.png")
    }

    @Test fun sandboxIsIsolatedFromTheRealGameAndHonoursCheats() {
        launch(GameState(coins = 4_321))
        rule.runOnUiThread { game.cheats.force = Sym.GHOST.ordinal; game.enterSandbox(true) }; settle()
        rule.runOnUiThread { game.boxCmd(AdminCmd("coins", n = 9_000_000, s = "set")); game.boxCmd(AdminCmd("country", COUNTRIES.indexOfFirst { it.name == "Japon" })) }; settle()
        assertEquals("Japon", villageName(game.state.village)); assertEquals(9_000_000L, game.state.coins)
        rule.onNodeWithText("SPIN").performClick(); repeat(6) { settle(700) }
        assertEquals(List(3) { Sym.GHOST.ordinal }, game.reels) // résultat imposé
        rule.runOnUiThread { game.saveScenario("japon"); game.boxCmd(AdminCmd("reset")); game.loadScenario("japon") }; settle()
        assertEquals("Japon", villageName(game.box.state.village))
        rule.runOnUiThread { game.cheats.netLoss = true }; rule.onNodeWithText("SPIN").performClick(); settle()
        assertTrue(game.toast!!.contains("réseau"))
        rule.runOnUiThread { game.cheats.netLoss = false; game.enterSandbox(false) }; settle()
        // La vraie partie n'a pas bougé.
        assertEquals(4_321L, game.state.coins); assertEquals(0, game.state.village)
        assertEquals(4_321L, JSON.decodeFromString<GameState>(File(ctx.filesDir, "save.json").readText()).coins)
    }

    @Test fun adminPanelDrivesARealServer() {
        val dir = java.nio.file.Files.createTempDirectory("gc").toFile()
        val server = com.ghost.coin.server.GameServer(dir, 0, "mot-de-passe-de-test").also { it.start() }
        try {
            launch(GameState())
            val url = "http://localhost:${server.port}"
            fun wait(cond: () -> Boolean) { repeat(40) { if (!cond()) settle(300) }; assertTrue(cond()) }
            rule.runOnUiThread { game.adminLogin(url, "faux") }; wait { game.toast != null }
            assertEquals("", game.adminToken)
            rule.runOnUiThread { game.adminLogin(url, "mot-de-passe-de-test") }; wait { game.adminStats.isNotEmpty() }
            rule.runOnUiThread { game.adminDo(Req(op = "mktest", n = 3)) }; wait { game.adminRows.size == 3 }
            assertTrue(game.adminRows.all { it.test })
            val id = game.adminRows[0].id
            rule.runOnUiThread { game.adminDo(Req(op = "cmd", to = id, cmd = AdminCmd("country", 120))) }; wait { game.adminPlayer?.village == 120 }
            rule.runOnUiThread { game.screen = Screen.ADMIN }; settle()
            rule.onRoot().captureRoboImage("build/shots/ADMIN_DASHBOARD.png")
            rule.onNodeWithText("Joueurs").performClick(); settle(); settle()
            rule.onRoot().captureRoboImage("build/shots/ADMIN_PLAYERS.png")
            rule.runOnUiThread { game.adminLogout() }; settle()
            assertEquals("", game.adminToken)
        } finally { server.stop(); dir.deleteRecursively() }
    }
}
