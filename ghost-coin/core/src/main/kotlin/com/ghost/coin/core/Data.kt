package com.ghost.coin.core

import kotlinx.serialization.Serializable
import kotlin.math.pow

/** Symboles de la machine. L'ordre est celui de la bande de chaque rouleau. */
enum class Sym { COIN, BAG, HAMMER, SHIELD, GHOST, ATTACK, RAID, STAR }

/** Les 8 emplacements d'un village ; leur apparence dépend du pays (voir Country). */
val BUILDINGS = listOf(
    "Porte du village", "Maisons", "Marché", "Monument", "Édifice historique", "Jardin", "Atelier d'artisans", "Palais",
)

val RARITIES = listOf("Commune", "Rare", "Épique", "Légendaire", "Mythique")
val ALBUMS = listOf("Esprits", "Créatures", "Bâtisses", "Reliques", "Sorciers", "Trésors")
const val ALBUM_SIZE = 8

/** 6 albums de 8 cartes ; dans chaque album : 3 communes, 2 rares, 1 épique, 1 légendaire, 1 mythique. */
val CARDS = listOf(
    "Follet timide", "Spectre farceur", "Âme vagabonde", "Dame blanche", "Ombre rieuse", "Esprit du phare", "Banshee d'opale", "Roi Fantôme",
    "Chauve-souris dodue", "Araignée tisseuse", "Chat de minuit", "Gargouille", "Loup brumeux", "Hydre des marais", "Dragon d'ambre", "Léviathan astral",
    "Cabane bancale", "Moulin grinçant", "Puits aux vœux", "Manoir oublié", "Phare éteint", "Bibliothèque vivante", "Citadelle flottante", "Palais du Néant",
    "Bougie éternelle", "Clé rouillée", "Lanterne bleue", "Miroir menteur", "Sablier inversé", "Grimoire scellé", "Couronne spectrale", "Cœur de comète",
    "Apprenti distrait", "Herboriste", "Diseuse de sort", "Alchimiste", "Nécromancien poli", "Mage des tempêtes", "Archimage voilé", "Oracle sans visage",
    "Pièce fendue", "Bourse percée", "Coffret de cuivre", "Perle noire", "Calice doré", "Rubis du dragon", "Trésor englouti", "Pièce Originelle",
)

fun rarityOf(card: Int) = when (card % ALBUM_SIZE) { 0, 1, 2 -> 0; 3, 4 -> 1; 5 -> 2; 6 -> 3; else -> 4 }

/** Coffres : nom, nombre de cartes, prix en unités du village, chances par rareté (pour 1000). */
class Chest(val name: String, val cards: Int, val price: Int, val odds: List<Int>)

val CHESTS = listOf(
    Chest("Coffre de bois", 3, 30, listOf(700, 220, 60, 17, 3)),
    Chest("Coffre d'argent", 5, 80, listOf(500, 320, 130, 40, 10)),
    Chest("Coffre d'or", 8, 200, listOf(300, 350, 230, 90, 30)),
)

/** Mission ou succès. `id` : d:… quotidien, w:… hebdomadaire, a:… succès. Récompense : tours, pièces (en unités), coffre. */
class Quest(val id: String, val label: String, val key: String, val goal: Int, val spins: Int = 0, val units: Int = 0, val chest: Int = -1)

val QUESTS = listOf(
    Quest("d:spin", "Lancer 30 tours", "spin", 30, spins = 10),
    Quest("d:build", "Améliorer 3 bâtiments", "build", 3, units = 25),
    Quest("d:attack", "Réussir 2 attaques", "attack", 2, spins = 5),
    Quest("d:raid", "Mener 1 raid", "raid", 1, units = 30),
    Quest("d:chest", "Ouvrir 1 coffre", "chest", 1, spins = 5),
    Quest("w:spin", "Lancer 300 tours", "spin", 300, spins = 40),
    Quest("w:build", "Améliorer 20 bâtiments", "build", 20, units = 300),
    Quest("w:attack", "Réussir 15 attaques", "attack", 15, chest = 2),
    Quest("w:raid", "Mener 8 raids", "raid", 8, spins = 30),
    Quest("a:spin", "Vétéran : 1 000 tours lancés", "spin", 1000, spins = 50),
    Quest("a:attack", "Foudre de guerre : 50 attaques", "attack", 50, spins = 40),
    Quest("a:raid", "Pilleur : 25 raids", "raid", 25, units = 400),
    Quest("a:v10", "Voyageur : terminer 10 pays", "village", 10, spins = 40),
    Quest("a:af", "Terminer l'Afrique", "village", 54, spins = 150),
    Quest("a:eu", "Terminer l'Europe", "village", 100, spins = 200),
    Quest("a:am", "Terminer l'Amérique", "village", 135, spins = 250),
    Quest("a:as", "Terminer l'Asie", "village", 182, spins = 300),
    Quest("a:oc", "Tour du monde : terminer l'Océanie", "village", 196, spins = 500),
    Quest("a:c24", "Collectionneur : 24 cartes différentes", "cards", 24, chest = 2),
    Quest("a:c48", "Archiviste : toutes les cartes", "cards", 48, spins = 300),
)

/** Roue quotidienne : libellé, poids, tours, pièces (unités), bouclier, coffre. */
class Slice(val label: String, val weight: Int, val spins: Int = 0, val units: Int = 0, val shield: Boolean = false, val chest: Int = -1)

val WHEEL = listOf(
    Slice("Pièces", 26, units = 10), Slice("5 tours", 20, spins = 5), Slice("Pièces +", 16, units = 30),
    Slice("Bouclier", 12, shield = true), Slice("Pièces ++", 9, units = 60), Slice("15 tours", 8, spins = 15),
    Slice("Coffre d'argent", 6, chest = 1), Slice("Jackpot", 3, units = 150),
)

/** Événement du jour, déterminé par le jour de la semaine (identique sur le serveur et hors ligne). */
class DayEvent(val name: String, val text: String, val coins: Int = 100, val raid: Int = 100, val attack: Int = 100, val freeCards: Int = 3)

val EVENTS = listOf(
    DayEvent("Ruée vers l'or", "Les pièces de la machine rapportent 50 % de plus", coins = 150),
    DayEvent("Nuit des raids", "Les butins de raid sont augmentés de 50 %", raid = 150),
    DayEvent("Tempête d'éclairs", "Les récompenses d'attaque sont doublées", attack = 200),
    DayEvent("Ruée vers l'or", "Les pièces de la machine rapportent 50 % de plus", coins = 150),
    DayEvent("Nuit des raids", "Les butins de raid sont augmentés de 50 %", raid = 150),
    DayEvent("Tempête d'éclairs", "Les récompenses d'attaque sont doublées", attack = 200),
    DayEvent("Pluie de cartes", "Le coffre gratuit contient 5 cartes au lieu de 3", freeCards = 5),
)

object Rules {
    const val MAX_LEVEL = 3
    const val MAX_SPINS = 50
    const val REGEN_MS = 6 * 60_000L
    const val MAX_SHIELDS = 3
    const val MAX_CHARGES = 10
    const val DAY = 86_400_000L
    const val FREE_CHEST_MS = 4 * 3_600_000L
    const val RAID_DIGS = 3
    const val RAID_HOLES = 4
    val BETS = listOf(1, 2, 3, 5, 10)
    val SELL = listOf(1, 3, 8, 25, 80)

    /** Valeur de référence d'un monde : toutes les récompenses et tous les prix en découlent. */
    fun unit(v: Int): Long = (500.0 * 1.05.pow(minOf(v, 400))).toLong() / 10 * 10

    /** Prix pour passer le bâtiment `b` du niveau `l` au niveau `l + 1`. */
    fun cost(v: Int, b: Int, l: Int): Long = unit(v) * (6 + 3 * b) * (l + 1) * (100 + 4 * v) / 100

    fun day(now: Long) = now / DAY
    fun event(now: Long) = EVENTS[((day(now) + 3) % 7).toInt()] // le jour 0 (1er janvier 1970) était un jeudi
}

@Serializable
data class LogEntry(val who: String, val whoId: String, val kind: String, val amount: Long, val blocked: Boolean, val at: Long, val revenged: Boolean = false)

@Serializable
data class Target(val id: String, val name: String, val avatar: Int, val village: Int, val levels: List<Int>, val coins: Long)

@Serializable
data class Action(val type: String, val i: Int = 0, val s: String = "")

@Serializable
data class Event(val type: String, val n: Long = 0, val m: Long = 0, val ids: List<Int> = emptyList(), val text: String = "")

@Serializable
data class GameState(
    val id: String = "",
    val name: String = "Fantôme",
    val avatar: Int = 0,
    val coins: Long = 20_000,
    val spins: Int = Rules.MAX_SPINS,
    val spinsAt: Long = 0,
    val shields: Int = 1,
    val attacks: Int = 0,
    val raids: Int = 0,
    val voucher: Boolean = false,
    val village: Int = 0,
    val levels: List<Int> = List(8) { 0 },
    val damaged: List<Boolean> = List(8) { false },
    val stars: Int = 0,
    val bet: Int = 1,
    val cards: Map<Int, Int> = emptyMap(),
    val albums: Set<Int> = emptySet(),
    val freeChestAt: Long = 0,
    val wheelDay: Long = -1,
    val dailyDay: Long = -1,
    val dailyStreak: Int = 0,
    val questDay: Long = -1,
    val questWeek: Long = -1,
    val daily: Map<String, Int> = emptyMap(),
    val weekly: Map<String, Int> = emptyMap(),
    val stats: Map<String, Long> = emptyMap(),
    val claimed: Set<String> = emptySet(),
    val target: Target? = null,
    val targetKind: String = "",
    val raidPot: Long = 0,
    val raidShares: List<Int> = emptyList(),
    val raidPicked: List<Int> = emptyList(),
    val raidEmpty: Boolean = false,
    val raidStolen: Long = 0,
    val log: List<LogEntry> = emptyList(),
    val seq: Long = 0,
    /** Compte de test créé par Ghost Admin (toujours affiché comme tel). */
    val test: Boolean = false,
) {
    fun progress(q: Quest): Int = when {
        q.key == "village" -> village
        q.key == "cards" -> cards.size
        q.id.startsWith("d:") -> daily[q.key] ?: 0
        q.id.startsWith("w:") -> weekly[q.key] ?: 0
        else -> (stats[q.key] ?: 0).toInt()
    }
}
