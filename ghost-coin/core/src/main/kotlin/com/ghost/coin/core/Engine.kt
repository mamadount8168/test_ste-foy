package com.ghost.coin.core

import kotlin.random.Random

class Refused(message: String) : Exception(message)

class Result(val state: GameState, val events: List<Event>)

/** Le reste du monde vu par le moteur : adversaires réels côté serveur, robots hors ligne. */
interface World {
    fun target(self: GameState, rng: Random, preferId: String): Target
    /** Applique l'attaque sur la cible. Renvoie true si un bouclier l'a bloquée. */
    fun attack(self: GameState, target: Target, building: Int, now: Long, rng: Random): Boolean
    /** Retire le butin à la cible. */
    fun raid(self: GameState, target: Target, amount: Long, now: Long) {}
    /** Événements subis par le joueur pendant qu'il joue (robots hors ligne). */
    fun ambient(s: GameState, now: Long, rng: Random, ev: MutableList<Event>): GameState = s
}

/**
 * Règles du jeu, sous forme de fonction pure : (état, action, heure, hasard) -> (nouvel état, événements).
 * Le serveur et le mode solo exécutent exactement ce code ; en ligne, seul le serveur fait foi.
 */
object Engine {
    private fun refuse(msg: String): Nothing = throw Refused(msg)

    fun regen(s: GameState, now: Long): GameState {
        if (s.spinsAt == 0L || s.spins >= Rules.MAX_SPINS) return s.copy(spinsAt = now)
        val n = ((now - s.spinsAt) / Rules.REGEN_MS).toInt()
        if (n <= 0) return s
        val spins = minOf(Rules.MAX_SPINS, s.spins + n)
        return s.copy(spins = spins, spinsAt = if (spins >= Rules.MAX_SPINS) now else s.spinsAt + n * Rules.REGEN_MS)
    }

    private fun rollover(s: GameState, now: Long): GameState {
        val day = Rules.day(now)
        val week = (day + 3) / 7
        var r = s
        if (r.questDay != day) r = r.copy(questDay = day, daily = emptyMap(), claimed = r.claimed.filterNot { it.startsWith("d:") }.toSet())
        if (r.questWeek != week) r = r.copy(questWeek = week, weekly = emptyMap(), claimed = r.claimed.filterNot { it.startsWith("w:") }.toSet())
        return r
    }

    private fun GameState.count(key: String, n: Int = 1) = copy(
        daily = daily + (key to (daily[key] ?: 0) + n),
        weekly = weekly + (key to (weekly[key] ?: 0) + n),
        stats = stats + (key to (stats[key] ?: 0) + n),
    )

    private fun GameState.gain(amount: Long, ev: MutableList<Event>): GameState {
        ev += Event("coins", amount)
        return copy(coins = coins + amount, stats = stats + ("coins" to (stats["coins"] ?: 0) + amount))
    }

    private fun GameState.pay(amount: Long): GameState {
        if (coins < amount) refuse("Pas assez de pièces")
        return copy(coins = coins - amount)
    }

    private fun GameState.draw(n: Int, odds: List<Int>, rng: Random, ev: MutableList<Event>): GameState {
        val got = List(n) {
            var roll = rng.nextInt(1000)
            var rarity = 0
            while (rarity < 4 && roll >= odds[rarity]) { roll -= odds[rarity]; rarity++ }
            CARDS.indices.filter { rarityOf(it) == rarity }.random(rng)
        }
        var c = cards
        got.forEach { c = c + (it to (c[it] ?: 0) + 1) }
        ev += Event("cards", ids = got)
        return copy(cards = c)
    }

    private fun GameState.reward(spins: Int, units: Int, chest: Int, rng: Random, ev: MutableList<Event>): GameState {
        var s = this
        if (spins > 0) { s = s.copy(spins = s.spins + spins); ev += Event("spins", spins.toLong()) }
        if (units > 0) s = s.gain(Rules.unit(s.village) * units, ev)
        if (chest >= 0) s = s.draw(CHESTS[chest].cards, CHESTS[chest].odds, rng, ev)
        return s
    }

    /** Le joueur `s` subit une attaque. Renvoie le nouvel état et true si un bouclier a bloqué. */
    fun sufferAttack(s: GameState, by: String, byId: String, building: Int, now: Long): Pair<GameState, Boolean> {
        val blocked = s.shields > 0
        val hit = !blocked && s.levels.getOrElse(building) { 0 } > 0
        val entry = LogEntry(by, byId, "attack", building.toLong(), blocked, now)
        return s.copy(
            shields = if (blocked) s.shields - 1 else s.shields,
            damaged = if (hit) s.damaged.mapIndexed { i, d -> d || i == building } else s.damaged,
            log = (listOf(entry) + s.log).take(30),
        ) to blocked
    }

    /** Le joueur `s` subit un raid : au plus un quart de ses pièces. Renvoie le nouvel état et le montant réellement pris. */
    fun sufferRaid(s: GameState, by: String, byId: String, amount: Long, now: Long): Pair<GameState, Long> {
        val taken = minOf(amount, s.coins / 4)
        val entry = LogEntry(by, byId, "raid", taken, false, now)
        return s.copy(coins = s.coins - taken, log = (listOf(entry) + s.log).take(30)) to taken
    }

    fun apply(s0: GameState, a: Action, now: Long, rng: Random, world: World): Result {
        var s = rollover(regen(s0, now), now)
        val ev = mutableListOf<Event>()
        val u = Rules.unit(s.village)
        val event = Rules.event(now)
        when (a.type) {
            "sync" -> {}
            "bet" -> {
                if (a.i !in Rules.BETS) refuse("Mise invalide")
                s = s.copy(bet = a.i)
            }
            "spin" -> {
                val bet = s.bet
                if (s.spins < bet) refuse("Plus assez de tours")
                s = s.copy(spins = s.spins - bet).count("spin", bet)
                val roll = rng.nextInt(1000)
                val triple = when {
                    roll < 90 -> Sym.ATTACK
                    roll < 140 -> Sym.RAID
                    roll < 200 -> Sym.SHIELD
                    roll < 240 -> Sym.GHOST
                    roll < 270 -> Sym.HAMMER
                    roll < 282 -> Sym.STAR
                    roll < 332 -> Sym.COIN
                    roll < 357 -> Sym.BAG
                    else -> null
                }
                val weights = listOf(30, 14, 10, 12, 10, 13, 9, 5)
                fun reel(): Sym {
                    var r = rng.nextInt(weights.sum())
                    var i = 0
                    while (r >= weights[i]) { r -= weights[i]; i++ }
                    return Sym.entries[i]
                }
                var reels = if (triple != null) List(3) { triple } else List(3) { reel() }
                while (triple == null && reels.distinct().size == 1) reels = List(3) { reel() }
                ev += Event("reels", ids = reels.map { it.ordinal })
                fun coins(units: Int) = u * units * bet * event.coins / 100
                when (triple) {
                    Sym.ATTACK -> { s = s.copy(attacks = minOf(Rules.MAX_CHARGES, s.attacks + bet)); ev += Event("attackCharge", bet.toLong()) }
                    Sym.RAID -> { s = s.copy(raids = minOf(Rules.MAX_CHARGES, s.raids + bet)); ev += Event("raidCharge", bet.toLong()) }
                    Sym.SHIELD ->
                        if (s.shields < Rules.MAX_SHIELDS) { s = s.copy(shields = s.shields + 1); ev += Event("shield", 1) }
                        else { ev += Event("shield", 0); s = s.gain(coins(10), ev) }
                    Sym.GHOST -> { s = s.copy(spins = s.spins + 10 * bet); ev += Event("spins", 10L * bet) }
                    Sym.HAMMER ->
                        if (!s.voucher) { s = s.copy(voucher = true); ev += Event("voucher") }
                        else s = s.gain(coins(15), ev)
                    Sym.STAR -> { ev += Event("jackpot"); s = s.gain(coins(150), ev).draw(3, CHESTS[1].odds, rng, ev) }
                    Sym.COIN -> s = s.gain(coins(20), ev)
                    Sym.BAG -> s = s.gain(coins(80), ev)
                    null -> {
                        val units = reels.sumOf { if (it == Sym.COIN) 1 else if (it == Sym.BAG) 3 else 0 as Int }
                        if (units > 0) s = s.gain(coins(units), ev)
                    }
                }
                s = world.ambient(s, now, rng, ev)
            }
            "attack", "raid" -> {
                if (s.target != null) refuse("Une action est déjà en cours")
                if (a.type == "attack") {
                    if (s.attacks <= 0) refuse("Aucune attaque disponible")
                    s = s.copy(attacks = s.attacks - 1)
                } else {
                    if (s.raids <= 0) refuse("Aucun raid disponible")
                    s = s.copy(raids = s.raids - 1)
                }
                val t = world.target(s, rng, a.s)
                s = s.copy(target = t, targetKind = a.type)
                if (a.s.isNotEmpty()) {
                    val i = s.log.indexOfFirst { it.whoId == a.s && !it.revenged }
                    if (i >= 0) s = s.copy(log = s.log.mapIndexed { k, e -> if (k == i) e.copy(revenged = true) else e })
                }
                if (a.type == "raid") {
                    val pot = maxOf(u * 20, minOf(t.coins / 4, u * 200)) * event.raid / 100
                    s = s.copy(raidPot = pot, raidShares = listOf(50, 30, 20), raidPicked = emptyList(), raidEmpty = false, raidStolen = 0)
                }
                ev += Event("target", text = a.type)
            }
            "hit" -> {
                val t = s.target
                if (t == null || s.targetKind != "attack") refuse("Aucune cible")
                if (a.i !in 0..7 || t.levels[a.i] <= 0) refuse("Rien à viser ici")
                val blocked = world.attack(s, t, a.i, now, rng)
                s = s.copy(target = null, targetKind = "").count("attack")
                ev += Event("attackResult", m = if (blocked) 1 else 0, ids = listOf(a.i))
                s = s.gain(u * (if (blocked) 12 else 40) * event.attack / 100, ev)
            }
            "dig" -> {
                val t = s.target
                if (t == null || s.targetKind != "raid") refuse("Aucun raid en cours")
                if (a.i !in 0 until Rules.RAID_HOLES || a.i in s.raidPicked) refuse("Emplacement invalide")
                // L'emplacement vide est tiré au moment de creuser : rien de caché ne transite par le téléphone.
                val remaining = Rules.RAID_HOLES - s.raidPicked.size
                val empty = !s.raidEmpty && rng.nextInt(remaining) == 0
                var amount = 0L
                if (empty) s = s.copy(raidEmpty = true) else {
                    val share = s.raidShares.random(rng)
                    amount = s.raidPot * share / 100
                    s = s.copy(raidShares = s.raidShares - share, raidStolen = s.raidStolen + amount)
                }
                s = s.copy(raidPicked = s.raidPicked + a.i)
                val done = s.raidPicked.size >= Rules.RAID_DIGS
                ev += Event("dig", amount, a.i.toLong(), listOf(if (done) 1 else 0))
                if (amount > 0) s = s.gain(amount, ev)
                if (done) {
                    world.raid(s, t, s.raidStolen, now)
                    s = s.copy(target = null, targetKind = "").count("raid")
                }
            }
            "build" -> {
                val b = a.i
                if (b !in 0..7) refuse("Bâtiment invalide")
                if (s.damaged[b]) {
                    s = s.pay(Rules.cost(s.village, b, s.levels[b] - 1) / 2)
                    s = s.copy(damaged = s.damaged.mapIndexed { i, d -> d && i != b })
                    ev += Event("built", b.toLong(), s.levels[b].toLong(), text = "repair")
                } else {
                    val l = s.levels[b]
                    if (l >= Rules.MAX_LEVEL) refuse("Niveau maximal atteint")
                    val c = Rules.cost(s.village, b, l)
                    s = s.pay(if (s.voucher) c / 2 else c)
                    s = s.copy(voucher = false, stars = s.stars + 1, levels = s.levels.mapIndexed { i, x -> if (i == b) x + 1 else x }).count("build")
                    ev += Event("built", b.toLong(), (l + 1).toLong())
                }
                if (s.levels.all { it >= Rules.MAX_LEVEL } && s.damaged.none { it }) {
                    s = s.copy(village = s.village + 1, levels = List(8) { 0 }, damaged = List(8) { false })
                    ev += Event("villageDone", s.village.toLong())
                    s = s.reward(25, 30, 0, rng, ev)
                }
            }
            "chest" -> {
                val c = CHESTS.getOrNull(a.i) ?: refuse("Coffre invalide")
                s = s.pay(u * c.price).count("chest").draw(c.cards, c.odds, rng, ev)
            }
            "freeChest" -> {
                if (now < s.freeChestAt) refuse("Le coffre gratuit n'est pas encore prêt")
                s = s.copy(freeChestAt = now + Rules.FREE_CHEST_MS).count("chest").draw(event.freeCards, CHESTS[0].odds, rng, ev)
            }
            "daily" -> {
                val day = Rules.day(now)
                if (s.dailyDay == day) refuse("Bonus déjà récupéré aujourd'hui")
                val streak = if (s.dailyDay == day - 1) s.dailyStreak % 7 + 1 else 1
                s = s.copy(dailyDay = day, dailyStreak = streak)
                s = s.reward(5 + 2 * streak, 10 * streak, if (streak == 7) 1 else -1, rng, ev)
            }
            "wheel" -> {
                val day = Rules.day(now)
                if (s.wheelDay == day) refuse("La roue a déjà tourné aujourd'hui")
                var r = rng.nextInt(WHEEL.sumOf { it.weight })
                var i = 0
                while (r >= WHEEL[i].weight) { r -= WHEEL[i].weight; i++ }
                val w = WHEEL[i]
                s = s.copy(wheelDay = day)
                ev += Event("wheel", i.toLong())
                if (w.shield) {
                    if (s.shields < Rules.MAX_SHIELDS) { s = s.copy(shields = s.shields + 1); ev += Event("shield", 1) }
                    else s = s.gain(u * 10, ev)
                }
                s = s.reward(w.spins, w.units, w.chest, rng, ev)
            }
            "claim" -> {
                val q = QUESTS.firstOrNull { it.id == a.s } ?: refuse("Mission inconnue")
                if (q.id in s.claimed) refuse("Récompense déjà récupérée")
                if (s.progress(q) < q.goal) refuse("Mission non terminée")
                s = s.copy(claimed = s.claimed + q.id).reward(q.spins, q.units, q.chest, rng, ev)
            }
            "album" -> {
                if (a.i !in ALBUMS.indices || a.i in s.albums) refuse("Album indisponible")
                if ((0 until ALBUM_SIZE).any { (s.cards[a.i * ALBUM_SIZE + it] ?: 0) < 1 }) refuse("Album incomplet")
                s = s.copy(albums = s.albums + a.i)
                ev += Event("album", a.i.toLong())
                s = s.reward(30 + 10 * a.i, 100, -1, rng, ev)
            }
            "sell" -> {
                val extra = (s.cards[a.i] ?: 0) - 1
                if (extra < 1) refuse("Aucun double à échanger")
                s = s.copy(cards = s.cards + (a.i to 1)).gain(u * Rules.SELL[rarityOf(a.i)] * extra, ev)
            }
            "profile" -> {
                val name = a.s.trim().filter { it.isLetterOrDigit() || it in " _-" }.take(16)
                if (name.length < 3) refuse("Pseudo trop court (3 caractères minimum)")
                s = s.copy(name = name, avatar = a.i.coerceIn(0, 5))
            }
            else -> refuse("Action inconnue")
        }
        return Result(s.copy(seq = s.seq + 1), ev)
    }
}

/** Adversaires simulés : utilisés en solo, et par le serveur quand aucun joueur réel n'est disponible. */
object BotWorld : World {
    private val NAMES = listOf(
        "Brumeline", "Ossian", "Lueur", "Mirabelle", "Tibère", "Nocturne", "Pépin", "Sélène", "Grimaud", "Farfadet",
        "Ondine", "Balthazar", "Cendrine", "Hector", "Violine", "Morphée", "Capucine", "Lazare", "Églantine", "Zéphyr",
    )

    fun bot(self: GameState, rng: Random): Target {
        val n = rng.nextInt(NAMES.size)
        val v = maxOf(0, self.village + rng.nextInt(-2, 3))
        val levels = List(8) { rng.nextInt(0, Rules.MAX_LEVEL + 1) }.toMutableList()
        if (levels.all { it == 0 }) levels[0] = 1
        return Target("bot:$n", NAMES[n], n % 6, v, levels, Rules.unit(v) * rng.nextInt(120, 640))
    }

    override fun target(self: GameState, rng: Random, preferId: String): Target {
        val t = bot(self, rng)
        val n = preferId.removePrefix("bot:").toIntOrNull() ?: return t
        return t.copy(id = preferId, name = NAMES[n % NAMES.size], avatar = n % 6)
    }

    override fun attack(self: GameState, target: Target, building: Int, now: Long, rng: Random) = rng.nextInt(100) < 25

    override fun ambient(s: GameState, now: Long, rng: Random, ev: MutableList<Event>): GameState {
        val roll = rng.nextInt(1000)
        if (roll >= 60 || s.village == 0 && s.stars < 4) return s // on laisse le débutant tranquille
        val by = bot(s, rng)
        if (roll < 40) {
            val built = s.levels.indices.filter { s.levels[it] > 0 && !s.damaged[it] }
            if (built.isEmpty()) return s
            val b = built.random(rng)
            val (r, blocked) = Engine.sufferAttack(s, by.name, by.id, b, now)
            ev += Event("hit", b.toLong(), if (blocked) 1 else 0, text = by.name)
            return r
        }
        val (r, taken) = Engine.sufferRaid(s, by.name, by.id, Rules.unit(s.village) * rng.nextInt(10, 60), now)
        if (taken > 0) ev += Event("robbed", taken, text = by.name)
        return r
    }
}
