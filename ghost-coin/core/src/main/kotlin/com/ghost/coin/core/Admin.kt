package com.ghost.coin.core

import kotlinx.serialization.Serializable

/** Commande d'administration appliquée à l'état d'un joueur (par le serveur, ou localement dans le bac à sable). */
@Serializable
data class AdminCmd(val type: String, val i: Int = 0, val n: Long = 0, val s: String = "")

/** Événement créé depuis Ghost Admin : remplace l'événement du calendrier pendant sa période d'activité. */
@Serializable
data class LiveEvent(
    val id: String = "", val name: String = "", val text: String = "",
    val coins: Int = 100, val raid: Int = 100, val attack: Int = 100, val freeCards: Int = 3,
    val start: Long = 0, val end: Long = 0, val enabled: Boolean = true,
) {
    fun active(now: Long) = enabled && now in start until end
    fun toDay() = DayEvent(name, text, coins.coerceIn(10, 1000), raid.coerceIn(10, 1000), attack.coerceIn(10, 1000), freeCards.coerceIn(1, 20))
}

object Admin {
    val RESOURCES = listOf("coins", "spins", "shields", "attacks", "raids", "stars")

    fun apply(s: GameState, c: AdminCmd): GameState {
        fun num(cur: Long) = maxOf(0, if (c.s == "add") cur + c.n else c.n)
        return when (c.type) {
            "coins" -> s.copy(coins = num(s.coins).coerceAtMost(Long.MAX_VALUE / 8))
            "spins" -> s.copy(spins = num(s.spins.toLong()).coerceAtMost(1_000_000).toInt())
            "shields" -> s.copy(shields = num(s.shields.toLong()).coerceAtMost(Rules.MAX_SHIELDS.toLong()).toInt())
            "attacks" -> s.copy(attacks = num(s.attacks.toLong()).coerceAtMost(Rules.MAX_CHARGES.toLong()).toInt())
            "raids" -> s.copy(raids = num(s.raids.toLong()).coerceAtMost(Rules.MAX_CHARGES.toLong()).toInt())
            "stars" -> s.copy(stars = num(s.stars.toLong()).coerceAtMost(1_000_000).toInt())
            // Cartes : i = rareté (-1 = toutes), n = exemplaires ajoutés à chaque carte.
            "cards" -> s.copy(cards = s.cards + CARDS.indices.filter { c.i < 0 || rarityOf(it) == c.i }.associateWith { (s.cards[it] ?: 0) + maxOf(1, c.n.toInt()) })
            "clearCards" -> s.copy(cards = emptyMap(), albums = emptySet())
            "country" -> s.copy(village = c.i.coerceAtLeast(0), levels = List(8) { 0 }, damaged = List(8) { false }, target = null, targetKind = "")
            "levels" -> s.copy(levels = List(8) { c.i.coerceIn(0, Rules.MAX_LEVEL) }, damaged = List(8) { false })
            "level" -> s.copy(levels = s.levels.mapIndexed { k, l -> if (k == c.i) c.n.toInt().coerceIn(0, Rules.MAX_LEVEL) else l })
            "damage" -> s.copy(damaged = s.damaged.mapIndexed { k, d -> if (k == c.i) s.levels[k] > 0 && !d else d })
            "voucher" -> s.copy(voucher = !s.voucher)
            "timers" -> s.copy(freeChestAt = 0, wheelDay = -1, dailyDay = -1)
            "quests" -> s.copy(daily = QUESTS.associate { it.key to 100_000 }, weekly = QUESTS.associate { it.key to 100_000 }, stats = s.stats + QUESTS.associate { it.key to 100_000L }, claimed = emptySet())
            "unlock" -> s.copy(village = COUNTRIES.size - 1, levels = List(8) { 2 }, damaged = List(8) { false }, stars = (COUNTRIES.size - 1) * 24 + 16, cards = CARDS.indices.associateWith { maxOf(1, s.cards[it] ?: 0) })
            "reset" -> GameState(id = s.id, name = s.name, avatar = s.avatar, test = s.test)
            else -> throw Refused("Commande inconnue")
        }
    }
}
