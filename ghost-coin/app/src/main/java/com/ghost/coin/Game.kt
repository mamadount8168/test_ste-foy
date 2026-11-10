package com.ghost.coin

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.ghost.coin.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

enum class Screen { HOME, BUILD, ATTACK, RAID, CARDS, QUESTS, SHOP, SOCIAL, SETTINGS }

class Banner(val text: String, val color: Color)

interface Backend { suspend fun act(a: Action): Reply }

/** Mode solo : le moteur tourne sur le téléphone, contre des adversaires simulés. Sauvegarde automatique. */
class LocalBackend(private val file: File) : Backend {
    var state: GameState = runCatching { JSON.decodeFromString<GameState>(file.readText()) }.getOrElse { GameState() }

    override suspend fun act(a: Action): Reply = withContext(Dispatchers.IO) {
        try {
            val r = Engine.apply(state, a, System.currentTimeMillis(), Random.Default, BotWorld)
            state = r.state
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSON.encodeToString(GameState.serializer(), state)); tmp.renameTo(file)
            Reply(state = r.state, events = r.events)
        } catch (e: Refused) { Reply(false, e.message ?: "Refusé", state) }
    }
}

/** Mode en ligne : le téléphone envoie des intentions, le serveur calcule et renvoie l'état officiel. */
class RemoteBackend(private val url: String, var token: String) : Backend {
    var seq = -1L

    suspend fun post(path: String, req: Req): Reply = withContext(Dispatchers.IO) {
        try {
            val c = URL(url.trimEnd('/') + path).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Content-Type", "application/json")
            if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
            c.outputStream.use { it.write(JSON.encodeToString(Req.serializer(), req).toByteArray()) }
            val body = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
            JSON.decodeFromString<Reply>(body).also { r -> r.state?.let { seq = it.seq } }
        } catch (e: Exception) { Reply(false, "Hors ligne : connexion au serveur impossible") }
    }

    override suspend fun act(a: Action): Reply {
        val r = post("/act", Req(type = a.type, i = a.i, s = a.s, seq = seq))
        // Désynchronisation (action rejouée ou état modifié par un autre joueur) : on adopte l'état du serveur et on réessaie une fois.
        return if (r.error == "desync") post("/act", Req(type = a.type, i = a.i, s = a.s, seq = seq)) else r
    }
}

class Game(ctx: Context, private val scope: CoroutineScope, val audio: Audio?) {
    private val prefs = ctx.getSharedPreferences("game", Context.MODE_PRIVATE)
    private val local = LocalBackend(File(ctx.filesDir, "save.json"))
    private var remote: RemoteBackend? = null
    private val backend: Backend get() = remote ?: local

    var state by mutableStateOf(local.state)
    var now by mutableLongStateOf(System.currentTimeMillis())
    var screen by mutableStateOf(Screen.HOME)
    var busy by mutableStateOf(false)
    var reels by mutableStateOf(listOf(0, 4, 7))
    var spinId by mutableIntStateOf(0)
    var burst by mutableIntStateOf(0)
    var mood by mutableStateOf(Mood.IDLE)
    private var moodId = 0
    val banners = mutableStateListOf<Banner>()
    var reveal by mutableStateOf<List<Int>?>(null)
    var toast by mutableStateOf<String?>(null)
    var online by mutableStateOf(false)
    var serverUrl by mutableStateOf(prefs.getString("url", "") ?: "")
    var recovery by mutableStateOf(prefs.getString("recovery", "") ?: "")
    var board by mutableStateOf(listOf<Row>())
    var friends by mutableStateOf(listOf<Row>())

    /** État affiché : les tours se rechargent à l'écran sans attendre le serveur (qui applique la même règle). */
    val view: GameState get() = Engine.regen(state, now)

    init {
        val token = prefs.getString("token", "") ?: ""
        if (serverUrl.isNotEmpty() && token.isNotEmpty()) { remote = RemoteBackend(serverUrl, token); online = true }
        scope.launch { sync() }
        scope.launch { while (true) { delay(1000); now = System.currentTimeMillis() } }
    }

    fun sfx(s: Sfx) { audio?.play(s) }

    fun feel(m: Mood, ms: Long = 2200) {
        mood = m; val id = ++moodId
        scope.launch { delay(ms); if (moodId == id) mood = Mood.IDLE }
    }

    suspend fun sync() {
        val r = backend.act(Action("sync"))
        r.state?.let { state = it }
        if (!r.ok && online) toast = r.error
        if (state.target != null) screen = if (state.targetKind == "raid") Screen.RAID else Screen.ATTACK
    }

    /** Envoie une action ; renvoie la réponse après avoir mis à jour l'état et joué les effets. */
    suspend fun send(type: String, i: Int = 0, s: String = "", effects: Boolean = true): Reply {
        val r = backend.act(Action(type, i, s))
        r.state?.let { state = it }
        if (!r.ok) { toast = r.error; sfx(Sfx.LOSE) } else if (effects) play(r.events)
        return r
    }

    fun act(type: String, i: Int = 0, s: String = "") { scope.launch { send(type, i, s) } }

    fun spin() {
        if (busy) return
        if (view.spins < state.bet) { toast = "Plus assez de tours : ils se rechargent avec le temps"; sfx(Sfx.LOSE); return }
        busy = true
        scope.launch {
            sfx(Sfx.SPIN)
            val r = backend.act(Action("spin"))
            val ids = r.events.firstOrNull { it.type == "reels" }?.ids
            if (!r.ok || ids == null) { r.state?.let { state = it }; toast = r.error; busy = false; return@launch }
            reels = ids; spinId++
            for (k in 0 until 3) { delay(if (k == 0) 900 else 350); sfx(Sfx.TICK) }
            delay(150)
            state = r.state!!
            play(r.events)
            busy = false
            // Une attaque ou un raid gagné s'enchaîne aussitôt.
            if (r.events.any { it.type == "attackCharge" }) { delay(1100); begin("attack") }
            else if (r.events.any { it.type == "raidCharge" }) { delay(1100); begin("raid") }
        }
    }

    fun begin(kind: String, revengeId: String = "") {
        scope.launch {
            if (state.target == null && !send(kind, s = revengeId, effects = false).ok) return@launch
            screen = if (kind == "raid") Screen.RAID else Screen.ATTACK
        }
    }

    fun play(events: List<Event>) {
        for (e in events) when (e.type) {
            "coins" -> { banners += Banner("+${fmt(e.n)}", Gold); burst++; sfx(Sfx.COIN); if (mood == Mood.IDLE) feel(Mood.JUMP, 1200) }
            "spins" -> { banners += Banner("+${e.n} tours", Electric); sfx(Sfx.WIN); feel(Mood.DANCE) }
            "shield" -> { banners += Banner(if (e.n > 0) "Bouclier !" else "Boucliers au maximum", Electric); sfx(Sfx.SHIELD) }
            "voucher" -> { banners += Banner("Prochaine construction à −50 %", Gold); sfx(Sfx.BUILD) }
            "attackCharge" -> { banners += Banner("ATTAQUE !", Electric); sfx(Sfx.WIN); feel(Mood.WIN) }
            "raidCharge" -> { banners += Banner("RAID !", Gold); sfx(Sfx.WIN); feel(Mood.WIN) }
            "jackpot" -> { banners += Banner("JACKPOT !", Color(0xFFFF4DD8)); sfx(Sfx.WIN); feel(Mood.WIN, 3500) }
            "cards" -> { reveal = (reveal ?: emptyList()) + e.ids; sfx(Sfx.CHEST) }
            "built" -> { sfx(Sfx.BUILD); feel(Mood.JUMP, 1200) }
            "villageDone" -> { banners += Banner("Nouveau monde : ${villageName(e.n.toInt())}", Violet); sfx(Sfx.WIN); feel(Mood.DANCE, 4000) }
            "album" -> { banners += Banner("Album ${ALBUMS[e.n.toInt()]} terminé !", Gold); sfx(Sfx.WIN) }
            "hit" -> { banners += Banner(if (e.m > 0) "${e.text} attaque : bouclier utilisé" else "${e.text} a endommagé : ${BUILDINGS[e.n.toInt()]}", Danger); sfx(Sfx.ATTACK); feel(if (e.m > 0) Mood.HIT else Mood.SAD) }
            "robbed" -> { banners += Banner("${e.text} vous a pris ${fmt(e.n)}", Danger); sfx(Sfx.LOSE); feel(Mood.SAD) }
        }
    }

    // ----- Mode en ligne -----

    private fun adopt(url: String, r: Reply, rb: RemoteBackend): Boolean {
        if (!r.ok || r.state == null) { toast = r.error; return false }
        rb.token = r.token; remote = rb; online = true; serverUrl = url; state = r.state!!
        if (r.recovery.isNotEmpty()) recovery = r.recovery
        prefs.edit().putString("url", url).putString("token", r.token).putString("recovery", recovery).apply()
        return true
    }

    fun connect(url: String, name: String) = scope.launch {
        val rb = RemoteBackend(url, "")
        if (adopt(url, rb.post("/register", Req(name = name)), rb)) { toast = "Compte créé. Notez votre code de récupération dans les réglages."; refreshBoard() }
    }

    fun recover(url: String, code: String) = scope.launch {
        val rb = RemoteBackend(url, "")
        recovery = code.trim()
        if (adopt(url, rb.post("/recover", Req(recovery = code.trim())), rb)) { toast = "Compte récupéré"; refreshBoard() }
    }

    fun logout() {
        remote = null; online = false; state = local.state; board = emptyList(); friends = emptyList()
        prefs.edit().remove("token").apply()
    }

    private suspend fun social(path: String, req: Req = Req()): Reply? {
        val r = remote?.post(path, req) ?: return null
        r.state?.let { state = it }
        if (!r.ok) toast = r.error
        if (r.board.isNotEmpty()) board = r.board
        if (r.friends.isNotEmpty()) friends = r.friends
        return r
    }

    fun refreshBoard() = scope.launch { social("/board") }
    fun addFriend(code: String) = scope.launch { if (social("/friend", Req(to = code))?.ok == true) toast = "Ami ajouté" }
    fun gift(id: String) = scope.launch { if (social("/gift", Req(to = id))?.ok == true) { toast = "3 tours offerts"; sfx(Sfx.COIN) } }
    fun sendCard(id: String, card: Int) = scope.launch { if (social("/card", Req(to = id, card = card))?.ok == true) { toast = "Carte envoyée"; sfx(Sfx.COIN) } }
}

fun fmt(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1f Md".format(n / 1e9)
    n >= 1_000_000 -> "%.1f M".format(n / 1e6)
    n >= 10_000 -> "%.1f k".format(n / 1e3)
    else -> n.toString()
}
