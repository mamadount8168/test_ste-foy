package com.ghost.coin.server

import com.ghost.coin.core.*
import com.ghost.coin.core.Target
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.random.asKotlinRandom

@Serializable
data class Account(
    val id: String, val tokenHash: String, val recoveryHash: String, val state: GameState,
    val friends: Set<String> = emptySet(),
    val giftDay: Map<String, Long> = emptyMap(),
    val cardsDay: Long = -1, val cardsSent: Int = 0,
    val lastHitAt: Long = 0,
)

class Http(val code: Int, message: String) : Exception(message)

/**
 * Serveur autoritaire de Ghost Coin. L'état de chaque joueur vit ici ; le téléphone n'envoie que des
 * intentions (« je lance », « je construis le bâtiment 3 ») et reçoit le résultat calculé par le moteur.
 */
class GameServer(private val dir: File, port: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private val secure = SecureRandom()
    private val rng: Random = secure.asKotlinRandom()
    private val accounts = HashMap<String, Account>()
    private val byToken = HashMap<String, String>()
    private val hits = HashMap<String, ArrayDeque<Long>>()
    private val http = HttpServer.create(InetSocketAddress(port), 0)
    val port: Int get() = http.address.port

    init {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            runCatching { JSON.decodeFromString<Account>(f.readText()) }.onSuccess { accounts[it.id] = it; byToken[it.tokenHash] = it.id }
        }
        http.createContext("/") { ex -> handle(ex) }
        http.executor = Executors.newFixedThreadPool(8)
    }

    fun start() = http.start()
    fun stop() = http.stop(0)

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun secret(bytes: Int) = ByteArray(bytes).also { secure.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun newId(): String {
        val abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        while (true) { val id = String(CharArray(8) { abc[secure.nextInt(abc.length)] }); if (id !in accounts) return id }
    }

    private fun save(a: Account) {
        accounts[a.id] = a
        val tmp = File(dir, "${a.id}.tmp")
        tmp.writeText(JSON.encodeToString(Account.serializer(), a))
        Files.move(tmp.toPath(), File(dir, "${a.id}.json").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Limitation des requêtes : `max` appels par fenêtre glissante de `windowMs`. */
    private fun limit(key: String, max: Int, windowMs: Long) {
        val now = clock()
        val q = hits.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
        if (q.size >= max) throw Http(429, "Trop de requêtes, réessayez dans un instant")
        q.addLast(now)
    }

    private fun row(a: Account) = Row(a.id, a.state.name, a.state.avatar, a.state.village, a.state.stars)
    private fun friendsOf(a: Account) = (a.friends.mapNotNull { accounts[it] } + a).map(::row).sortedByDescending { it.stars }

    private inner class ServerWorld(val selfId: String) : World {
        private fun real(id: String) = accounts[id]?.takeIf { it.id != selfId }

        override fun target(self: GameState, rng: Random, preferId: String): Target {
            val now = clock()
            // Anti-acharnement : un joueur touché récemment n'est plus proposé pendant 10 minutes.
            val a = real(preferId) ?: if (preferId.isNotEmpty()) null else accounts.values
                .filter { it.id != selfId && it.state.levels.any { l -> l > 0 } && now - it.lastHitAt > 600_000 }
                .randomOrNull(rng)
            return a?.let { Target(it.id, it.state.name, it.state.avatar, it.state.village, it.state.levels, it.state.coins) }
                ?: BotWorld.target(self, rng, preferId)
        }

        override fun attack(self: GameState, target: Target, building: Int, now: Long, rng: Random): Boolean {
            val a = real(target.id) ?: return BotWorld.attack(self, target, building, now, rng)
            val (s, blocked) = Engine.sufferAttack(a.state, self.name, selfId, building, now)
            save(a.copy(state = s, lastHitAt = now))
            return blocked
        }

        override fun raid(self: GameState, target: Target, amount: Long, now: Long) {
            val a = real(target.id) ?: return
            save(a.copy(state = Engine.sufferRaid(a.state, self.name, selfId, amount, now).first, lastHitAt = now))
        }
    }

    @Synchronized
    private fun route(path: String, token: String, ip: String, req: Req): Reply {
        val now = clock()
        if (path == "/register") {
            limit("reg:$ip", 10, 3_600_000)
            val t = secret(32); val rec = secret(10); val id = newId()
            val named = Engine.apply(GameState(id = id, spinsAt = now), Action("profile", 0, req.name.ifBlank { "Fantôme" }), now, rng, BotWorld).state
            save(Account(id, sha(t), sha(rec), named)); byToken[sha(t)] = id
            return Reply(state = named, token = t, recovery = rec)
        }
        if (path == "/recover") {
            limit("rec:$ip", 10, 3_600_000)
            val a = accounts.values.firstOrNull { it.recoveryHash == sha(req.recovery.trim()) } ?: throw Http(403, "Code de récupération invalide")
            val t = secret(32)
            byToken.remove(a.tokenHash) // l'ancienne session est révoquée
            save(a.copy(tokenHash = sha(t))); byToken[sha(t)] = a.id
            return Reply(state = a.state, token = t)
        }
        val me = byToken[sha(token)]?.let { accounts[it] } ?: throw Http(401, "Session invalide")
        limit("act:${me.id}", 12, 1_000)
        limit("min:${me.id}", 300, 60_000)
        when (path) {
            "/act" -> {
                if (req.seq >= 0 && req.seq != me.state.seq) return Reply(false, "desync", me.state)
                val r = try { Engine.apply(me.state, Action(req.type, req.i, req.s), now, rng, ServerWorld(me.id)) }
                catch (e: Refused) { return Reply(false, e.message ?: "Refusé", me.state) }
                save(accounts[me.id]!!.copy(state = r.state))
                return Reply(state = r.state, events = r.events)
            }
            "/board" -> return Reply(
                state = me.state,
                board = accounts.values.sortedByDescending { it.state.stars }.take(50).map(::row),
                friends = friendsOf(me),
            )
            "/friend" -> {
                val f = accounts[req.to.trim().uppercase()] ?: throw Http(404, "Code ami introuvable")
                if (f.id == me.id) throw Http(400, "C'est votre propre code")
                if (me.friends.size >= 100) throw Http(400, "Liste d'amis pleine")
                save(me.copy(friends = me.friends + f.id)); save(accounts[f.id]!!.copy(friends = f.friends + me.id))
                return Reply(state = me.state, friends = friendsOf(accounts[me.id]!!))
            }
            "/gift" -> {
                val f = accounts[req.to]?.takeIf { it.id in me.friends } ?: throw Http(404, "Ami introuvable")
                val day = Rules.day(now)
                if (me.giftDay[f.id] == day) throw Http(400, "Cadeau déjà envoyé à cet ami aujourd'hui")
                save(me.copy(giftDay = me.giftDay + (f.id to day)))
                val entry = LogEntry(me.state.name, me.id, "gift", 3, false, now)
                save(f.copy(state = f.state.copy(spins = f.state.spins + 3, log = (listOf(entry) + f.state.log).take(30))))
                return Reply(state = me.state)
            }
            "/card" -> {
                val f = accounts[req.to]?.takeIf { it.id in me.friends } ?: throw Http(404, "Ami introuvable")
                val day = Rules.day(now)
                val sent = if (me.cardsDay == day) me.cardsSent else 0
                if (sent >= 5) throw Http(400, "Limite de 5 cartes envoyées par jour atteinte")
                // Seuls les doubles s'échangent : on ne peut ni créer une carte ni vider son album par erreur.
                if ((me.state.cards[req.card] ?: 0) < 2) throw Http(400, "Vous n'avez pas cette carte en double")
                val mine = me.state.copy(cards = me.state.cards + (req.card to me.state.cards[req.card]!! - 1), seq = me.state.seq + 1)
                save(me.copy(state = mine, cardsDay = day, cardsSent = sent + 1))
                val entry = LogEntry(me.state.name, me.id, "card", req.card.toLong(), false, now)
                val fs = f.state
                save(f.copy(state = fs.copy(cards = fs.cards + (req.card to (fs.cards[req.card] ?: 0) + 1), log = (listOf(entry) + fs.log).take(30), seq = fs.seq + 1)))
                return Reply(state = mine)
            }
        }
        throw Http(404, "Route inconnue")
    }

    private fun handle(ex: HttpExchange) {
        var code = 200
        val reply = try {
            if (ex.requestURI.path == "/health") Reply()
            else {
                if (ex.requestMethod != "POST") throw Http(405, "POST attendu")
                val body = ex.requestBody.readNBytes(4097)
                if (body.size > 4096) throw Http(413, "Requête trop volumineuse")
                val req = if (body.isEmpty()) Req() else JSON.decodeFromString<Req>(String(body))
                val ip = ex.requestHeaders.getFirst("X-Forwarded-For")?.substringBefore(',')?.trim() ?: ex.remoteAddress.address.hostAddress
                val token = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
                route(ex.requestURI.path, token, ip, req)
            }
        } catch (e: Http) { code = e.code; Reply(false, e.message ?: "Erreur") }
        catch (e: Refused) { code = 400; Reply(false, e.message ?: "Refusé") }
        catch (e: Exception) { code = 400; Reply(false, "Requête invalide") }
        val out = JSON.encodeToString(Reply.serializer(), reply).toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, out.size.toLong())
        ex.responseBody.use { it.write(out) }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val server = GameServer(File(System.getenv("DATA_DIR") ?: "data"), port)
    server.start()
    println("Ghost Coin : serveur démarré sur le port ${server.port}")
}
