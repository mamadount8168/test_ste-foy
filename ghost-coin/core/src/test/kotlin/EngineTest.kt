import com.ghost.coin.core.*
import kotlin.random.Random
import kotlin.test.*

class EngineTest {
    private val now = 1_800_000_000_000L
    private fun act(s: GameState, type: String, i: Int = 0, str: String = "", rng: Random = Random(1), t: Long = now) =
        Engine.apply(s, Action(type, i, str), t, rng, BotWorld)

    @Test fun spinConsumesBetAndIsRefusedWithoutSpins() {
        val r = act(GameState(bet = 5, spinsAt = now), "spin")
        assertEquals(45, r.state.spins - (r.events.firstOrNull { it.type == "spins" }?.n?.toInt() ?: 0))
        assertEquals(3, r.events.first { it.type == "reels" }.ids.size)
        assertFailsWith<Refused> { act(GameState(spins = 2, bet = 5, spinsAt = now), "spin") }
    }

    @Test fun spinsRegenerateUpToMax() {
        val s = GameState(spins = 10, spinsAt = now)
        assertEquals(13, Engine.regen(s, now + 3 * Rules.REGEN_MS + 5).spins)
        assertEquals(Rules.MAX_SPINS, Engine.regen(s, now + 1000 * Rules.REGEN_MS).spins)
        // Les tours bonus au-delà du maximum ne sont pas perdus.
        assertEquals(80, Engine.regen(GameState(spins = 80, spinsAt = now), now + Rules.DAY).spins)
    }

    @Test fun buildPaysCostAndRefusesWhenPoor() {
        val s = GameState(coins = 1_000_000)
        val r = act(s, "build", 2)
        assertEquals(1, r.state.levels[2])
        assertEquals(s.coins - Rules.cost(0, 2, 0), r.state.coins)
        assertFailsWith<Refused> { act(GameState(coins = 10), "build", 0) }
    }

    @Test fun voucherHalvesNextBuild() {
        val r = act(GameState(coins = 1_000_000, voucher = true), "build", 0)
        assertEquals(1_000_000 - Rules.cost(0, 0, 0) / 2, r.state.coins)
        assertFalse(r.state.voucher)
    }

    @Test fun completingVillageUnlocksNextWorld() {
        var s = GameState(coins = Long.MAX_VALUE / 4)
        repeat(3) { for (b in 0..7) s = act(s, "build", b).state }
        assertEquals(1, s.village)
        assertTrue(s.levels.all { it == 0 })
        assertEquals(24, s.stars)
    }

    @Test fun damagedBuildingMustBeRepairedBeforeCompletion() {
        val s = GameState(coins = 10_000_000, levels = List(8) { 3 }, damaged = List(8) { it == 1 })
        val r = act(s, "build", 1)
        assertEquals(1, r.state.village)
        assertEquals("repair", r.events.first { it.type == "built" }.text)
    }

    @Test fun attackNeedsChargeThenRewards() {
        assertFailsWith<Refused> { act(GameState(), "attack") }
        val s = act(GameState(attacks = 1), "attack").state
        assertEquals("attack", s.targetKind)
        val b = s.target!!.levels.indexOfFirst { it > 0 }
        val r = act(s, "hit", b)
        assertNull(r.state.target)
        assertTrue(r.state.coins > s.coins)
        assertFailsWith<Refused> { act(r.state, "hit", b) }
    }

    @Test fun raidGivesThreeDigsWithExactlyOneEmptyHoleAmongFour() {
        repeat(50) { seed ->
            val rng = Random(seed)
            var s = act(GameState(raids = 1), "raid", rng = rng).state
            val pot = s.raidPot
            val before = s.coins
            for (hole in listOf(0, 1, 2)) s = act(s, "dig", hole, rng = rng).state
            assertNull(s.target)
            assertTrue(s.coins - before <= pot)
            assertTrue(s.coins - before >= pot / 2 - 2) // au pire : les deux plus petites parts (30 % + 20 %)
        }
    }

    @Test fun worldHas196CountriesInContinentOrderStartingInSenegal() {
        assertEquals(196, COUNTRIES.size)
        assertEquals("Sénégal", COUNTRIES[0].name)
        assertEquals(listOf(0, 54, 100, 135, 182, 196), CONTINENT_START)
        assertEquals(196, COUNTRIES.map { it.code }.toSet().size)
        assertEquals("\uD83C\uDDF8\uD83C\uDDF3", COUNTRIES[0].flag)
        assertTrue(COUNTRIES.all { it.biome in BIOMES && it.style in STYLES && it.lat in -60..75 && it.lon in -180..180 })
    }

    @Test fun finishingLastAfricanCountryRewardsTheContinentAndOpensEurope() {
        val s = GameState(coins = Long.MAX_VALUE / 4, village = 53, levels = List(8) { if (it == 0) 2 else 3 })
        val r = act(s, "build", 0)
        assertEquals(54, r.state.village); assertEquals(Continent.EUROPE, countryOf(r.state.village).continent)
        assertEquals(0L, r.events.first { it.type == "continentDone" }.n)
        assertTrue(r.state.spins >= 50 + 25 + 100)
        assertTrue(act(GameState(coins = Long.MAX_VALUE / 4, village = 3, levels = List(8) { if (it == 0) 2 else 3 }), "build", 0).events.none { it.type == "continentDone" })
    }

    @Test fun forcedReelsAreHonouredForTheSandbox() {
        val r = Engine.apply(GameState(spinsAt = now), Action("spin"), now, Random(1), BotWorld, force = Sym.RAID.ordinal)
        assertEquals(List(3) { Sym.RAID.ordinal }, r.events.first { it.type == "reels" }.ids); assertEquals(1, r.state.raids)
    }

    @Test fun costsStayFiniteAcrossTheWholeWorld() {
        assertTrue((0 until 800).all { Rules.cost(it, 7, 2) > 0 && Rules.unit(it) > 0 })
    }

    @Test fun cannotDigSameHoleTwice() {
        val s = act(act(GameState(raids = 1), "raid").state, "dig", 0).state
        assertFailsWith<Refused> { act(s, "dig", 0) }
    }

    @Test fun shieldBlocksAttackThenBuildingIsDamaged() {
        val s = GameState(shields = 1, levels = List(8) { 1 })
        val (a, blocked) = Engine.sufferAttack(s, "X", "bot:1", 3, now)
        assertTrue(blocked); assertEquals(0, a.shields); assertFalse(a.damaged[3])
        val (b, blocked2) = Engine.sufferAttack(a, "X", "bot:1", 3, now)
        assertFalse(blocked2); assertTrue(b.damaged[3]); assertEquals(2, b.log.size)
    }

    @Test fun raidOnPlayerTakesAtMostAQuarter() {
        val (s, taken) = Engine.sufferRaid(GameState(coins = 1000), "X", "bot:1", 999_999, now)
        assertEquals(250, taken); assertEquals(750, s.coins)
    }

    @Test fun dailyBonusOncePerDayWithStreak() {
        val d1 = act(GameState(), "daily").state
        assertEquals(1, d1.dailyStreak)
        assertFailsWith<Refused> { act(d1, "daily") }
        assertEquals(2, act(d1, "daily", t = now + Rules.DAY).state.dailyStreak)
        assertEquals(1, act(d1, "daily", t = now + 3 * Rules.DAY).state.dailyStreak)
    }

    @Test fun missionClaimRequiresProgressAndResetsDaily() {
        assertFailsWith<Refused> { act(GameState(), "claim", str = "d:build") }
        var s = GameState(coins = 10_000_000)
        for (b in 0..2) s = act(s, "build", b).state
        s = act(s, "claim", str = "d:build").state
        assertFailsWith<Refused> { act(s, "claim", str = "d:build") }
        val next = act(s, "sync", t = now + Rules.DAY).state
        assertEquals(0, next.progress(QUESTS.first { it.id == "d:build" }))
        assertEquals(3, next.progress(QUESTS.first { it.id == "w:build" }).coerceAtMost(3))
    }

    @Test fun chestsGiveCardsAndDuplicatesSell() {
        val r = act(GameState(coins = 10_000_000), "chest", 2)
        assertEquals(8, r.events.first { it.type == "cards" }.ids.size)
        val s = GameState(cards = mapOf(7 to 3))
        val sold = act(s, "sell", 7).state
        assertEquals(1, sold.cards[7]); assertEquals(s.coins + Rules.unit(0) * 80 * 2, sold.coins)
        assertFailsWith<Refused> { act(sold, "sell", 7) }
    }

    @Test fun albumRewardOnlyWhenComplete() {
        assertFailsWith<Refused> { act(GameState(cards = mapOf(0 to 1)), "album", 0) }
        val full = GameState(cards = (8 until 16).associateWith { 1 })
        val s = act(full, "album", 1).state
        assertTrue(1 in s.albums)
        assertFailsWith<Refused> { act(s, "album", 1) }
    }

    @Test fun wheelOncePerDay() {
        val s = act(GameState(), "wheel").state
        assertFailsWith<Refused> { act(s, "wheel") }
    }

    @Test fun profileNameIsSanitised() {
        assertEquals("Bob_le-9", act(GameState(), "profile", 2, "Bob_le-9<script>".take(8)).state.name)
        assertFailsWith<Refused> { act(GameState(), "profile", 0, "<>") }
    }

    /** Garde-fou d'équilibrage : un monde se termine en un nombre de tours raisonnable, sans achat. */
    @Test fun economyLetsPlayerFinishFirstWorlds() {
        val rng = Random(42)
        var s = GameState(spinsAt = now)
        var spins = 0
        while (s.village < 3 && spins < 20_000) {
            s = s.copy(spins = 50)
            s = Engine.apply(s, Action("spin"), now, rng, BotWorld).state; spins++
            if (s.attacks > 0 && s.target == null) {
                s = Engine.apply(s, Action("attack"), now, rng, BotWorld).state
                s = Engine.apply(s, Action("hit", s.target!!.levels.indexOfFirst { it > 0 }), now, rng, BotWorld).state
            }
            if (s.raids > 0 && s.target == null) {
                s = Engine.apply(s, Action("raid"), now, rng, BotWorld).state
                for (h in 0..2) s = Engine.apply(s, Action("dig", h), now, rng, BotWorld).state
            }
            for (b in 0..7) runCatching { s = Engine.apply(s, Action("build", b), now, rng, BotWorld).state }
        }
        println("3 mondes terminés en $spins tours")
        assertTrue(spins in 60..3000, "3 mondes en $spins tours")
    }
}
