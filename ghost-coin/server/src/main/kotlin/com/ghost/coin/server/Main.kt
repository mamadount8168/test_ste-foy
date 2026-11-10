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
    val lastSeen: Long = 0,
)

class Http(val code: Int, message: String) : Exception(message)

/**
 * Serveur autoritaire de Ghost Coin. L'état de chaque joueur vit ici ; le téléphone n'envoie que des
 * intentions (« je lance », « je construis le bâtiment 3 ») et reçoit le résultat calculé par le moteur.
 */
class GameServer(private val dir: File, port: Int, private val adminPassword: String = "", private val clock: () -> Long = System::currentTimeMillis) {
    private val secure = SecureRandom()
    private val rng: Random = secure.asKotlinRandom()
    private val accounts = HashMap<String, Account>()
    private val byToken = HashMap<String, String>()
    private val hits = HashMap<String, ArrayDeque<Long>>()
    private val http = HttpServer.create(InetSocketAddress(port), 0)

    // Ghost Admin : sessions séparées de celles des joueurs, journaux en mémoire, événements programmés.
    private val started = clock()
    private val adminSessions = HashMap<String, Long>()
    private val adminFails = HashMap<String, ArrayDeque<Long>>()
    private val requests = ArrayDeque<String>()
    private val errors = ArrayDeque<String>()
    private val audit = ArrayDeque<String>()
    private val undo = ArrayDeque<Account>()
    private var liveEvents = listOf<LiveEvent>()
    private var requestCount = 0L
    private var errorCount = 0L
    private var granted = 0L
    private var lastSave = 0L
    val port: Int get() = http.address.port

    init {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            runCatching { JSON.decodeFromString<Account>(f.readText()) }.onSuccess { accounts[it.id] = it; byToken[it.tokenHash] = it.id }
        }
        runCatching { liveEvents = JSON.decodeFromString(File(dir, "events.json").readText()) }
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
        accounts[a.id] = a; lastSave = clock()
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

    private fun row(a: Account) = Row(a.id, a.state.name, a.state.avatar, a.state.village, a.state.stars, a.state.coins, a.state.test)
    private fun liveNow(now: Long) = liveEvents.firstOrNull { it.active(now) }
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

        override fun event(now: Long) = liveNow(now)?.toDay() ?: Rules.event(now)

        override fun raid(self: GameState, target: Target, amount: Long, now: Long) {
            val a = real(target.id) ?: return
            save(a.copy(state = Engine.sufferRaid(a.state, self.name, selfId, amount, now).first, lastHitAt = now))
        }
    }

    private fun keep(q: ArrayDeque<String>, line: String) { q.addLast(line); while (q.size > 300) q.removeFirst() }

    private fun audit(now: Long, what: String) {
        val line = "${java.time.Instant.ofEpochMilli(now)} $what"
        keep(audit, line)
        runCatching { File(dir, "audit.log").appendText(line + "\n") }
    }

    /**
     * Ghost Admin. Le mot de passe vient de la variable d'environnement ADMIN_PASSWORD du serveur : il n'existe
     * ni dans le dépôt ni dans l'application. Chaque commande exige une session administrateur valide,
     * distincte des sessions de joueurs, et laisse une trace dans le journal d'audit.
     */
    private fun admin(token: String, ip: String, req: Req, now: Long): Reply {
        if (adminPassword.length < 12) throw Http(403, "Administration désactivée : définissez ADMIN_PASSWORD (12 caractères minimum) sur le serveur")
        if (req.op == "login") {
            val fails = adminFails.getOrPut(ip) { ArrayDeque() }
            while (fails.isNotEmpty() && now - fails.first() > 900_000) fails.removeFirst()
            if (fails.size >= 5) throw Http(429, "Trop de tentatives : réessayez dans 15 minutes")
            if (!MessageDigest.isEqual(sha(req.password).toByteArray(), sha(adminPassword).toByteArray())) {
                fails.addLast(now); audit(now, "ÉCHEC de connexion administrateur depuis $ip")
                throw Http(403, "Mot de passe administrateur incorrect")
            }
            val t = secret(32)
            adminSessions[sha(t)] = now; audit(now, "connexion administrateur depuis $ip")
            return Reply(token = t)
        }
        val key = sha(token)
        val last = adminSessions[key] ?: throw Http(401, "Session administrateur invalide")
        if (now - last > 900_000) { adminSessions.remove(key); throw Http(401, "Session administrateur expirée après 15 minutes d'inactivité") }
        adminSessions[key] = now
        fun player(id: String = req.to) = accounts[id.trim().uppercase()] ?: throw Http(404, "Joueur introuvable")
        fun saveEvents() = File(dir, "events.json").writeText(JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(LiveEvent.serializer()), liveEvents))
        fun dashboard(): Map<String, String> {
            val real = accounts.values.filter { !it.state.test }
            val rt = Runtime.getRuntime()
            return linkedMapOf(
                "Joueurs" to "${real.size}",
                "Comptes de test" to "${accounts.size - real.size}",
                "Connectés (5 min)" to "${real.count { now - it.lastSeen < 300_000 }}",
                "Pays débloqués" to "${real.sumOf { it.state.village + 1 }}",
                "Villages terminés" to "${real.sumOf { it.state.village }}",
                "Pièces distribuées (admin)" to "$granted",
                "Requêtes servies" to "$requestCount",
                "Erreurs" to "$errorCount",
                "Serveur en ligne depuis" to "${(now - started) / 60_000} min",
                "Mémoire utilisée" to "${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} Mo",
                "Dernière sauvegarde" to if (lastSave == 0L) "aucune depuis le démarrage" else "il y a ${(now - lastSave) / 1000} s",
                "Événement actif" to (liveNow(now)?.name ?: "${Rules.event(now).name} (calendrier)"),
            )
        }
        when (req.op) {
            "logout" -> { adminSessions.remove(key); audit(now, "déconnexion administrateur"); return Reply() }
            "dashboard" -> return Reply(stats = dashboard(), liveEvents = liveEvents)
            "players" -> return Reply(board = accounts.values.sortedByDescending { it.lastSeen }.take(200).map(::row))
            "player" -> return Reply(state = player().state)
            "cmd" -> {
                val a = player()
                val c = req.cmd ?: throw Http(400, "Commande manquante")
                val s = Admin.apply(a.state, c).let { it.copy(seq = it.seq + 1) }
                undo.addLast(a); while (undo.size > 50) undo.removeFirst()
                if (c.type == "coins") granted += maxOf(0, s.coins - a.state.coins)
                save(a.copy(state = s)); audit(now, "commande ${c.type} i=${c.i} n=${c.n} ${c.s} sur ${a.id} (${a.state.name})")
                return Reply(state = s)
            }
            "undo" -> {
                val prev = undo.removeLastOrNull() ?: throw Http(400, "Rien à annuler")
                val seq = (accounts[prev.id]?.state?.seq ?: prev.state.seq) + 1
                save(prev.copy(state = prev.state.copy(seq = seq))); audit(now, "annulation de la dernière commande sur ${prev.id}")
                return Reply(state = accounts[prev.id]!!.state)
            }
            "mktest" -> {
                repeat(req.n.toInt().coerceIn(1, 20)) {
                    val id = newId()
                    val v = rng.nextInt(0, 60)
                    // Jeton aléatoire jamais communiqué : personne ne peut se connecter à un compte de test.
                    save(Account(id, sha(secret(32)), sha(secret(10)), GameState(
                        id = id, name = "TEST-${id.take(4)}", avatar = rng.nextInt(6), test = true, village = v, stars = v * 24,
                        levels = List(8) { rng.nextInt(1, Rules.MAX_LEVEL + 1) }, coins = Rules.unit(v) * rng.nextInt(50, 800),
                        shields = rng.nextInt(0, Rules.MAX_SHIELDS + 1), cards = CARDS.indices.filter { rng.nextInt(3) == 0 }.associateWith { rng.nextInt(1, 4) },
                    )))
                }
                audit(now, "création de ${req.n.toInt().coerceIn(1, 20)} compte(s) de test")
            }
            "rmtests" -> {
                val gone = accounts.values.filter { it.state.test }
                gone.forEach { accounts.remove(it.id); byToken.remove(it.tokenHash); File(dir, "${it.id}.json").delete() }
                audit(now, "suppression de ${gone.size} compte(s) de test")
            }
            "simAttack", "simRaid" -> {
                val from = player(req.s).takeIf { it.state.test } ?: throw Http(400, "L'attaquant doit être un compte de test")
                val a = player()
                undo.addLast(a)
                val s = if (req.op == "simAttack") Engine.sufferAttack(a.state, from.state.name, from.id, req.i.coerceIn(0, 7), now).first
                else Engine.sufferRaid(a.state, from.state.name, from.id, maxOf(0, req.n), now).first
                save(a.copy(state = s.copy(seq = s.seq + 1))); audit(now, "${req.op} de ${from.id} sur ${a.id}")
                return Reply(state = s)
            }
            "eventSave" -> {
                val e = req.event ?: throw Http(400, "Événement manquant")
                if (e.name.isBlank() || e.end <= e.start) throw Http(400, "Nom et période valides requis")
                val id = e.id.ifEmpty { secret(4) }
                liveEvents = liveEvents.filter { it.id != id } + e.copy(id = id, name = e.name.take(40), text = e.text.take(120))
                saveEvents(); audit(now, "événement enregistré : ${e.name} ($id)")
            }
            "eventStop" -> { liveEvents = liveEvents.map { if (it.id == req.s) it.copy(enabled = false) else it }; saveEvents(); audit(now, "événement arrêté : ${req.s}") }
            "eventDelete" -> { liveEvents = liveEvents.filter { it.id != req.s }; saveEvents(); audit(now, "événement supprimé : ${req.s}") }
            "logs" -> return Reply(lines = (when (req.s) { "errors" -> errors; "audit" -> audit; else -> requests }).reversed())
            "export" -> return Reply(lines = listOf("# Ghost Coin — diagnostic du ${java.time.Instant.ofEpochMilli(now)}") + dashboard().map { "${it.key} : ${it.value}" } +
                listOf("", "# Événements") + liveEvents.map { it.toString() } + listOf("", "# Audit") + audit + listOf("", "# Erreurs") + errors + listOf("", "# Requêtes") + requests)
            else -> throw Http(400, "Opération inconnue")
        }
        return Reply(stats = dashboard(), liveEvents = liveEvents, board = accounts.values.sortedByDescending { it.lastSeen }.take(200).map(::row))
    }

    @Synchronized
    private fun route(path: String, token: String, ip: String, req: Req): Reply {
        val now = clock()
        if (path == "/admin") return admin(token, ip, req, now)
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
        val me = (byToken[sha(token)]?.let { accounts[it] } ?: throw Http(401, "Session invalide")).copy(lastSeen = now).also { accounts[it.id] = it }
        limit("act:${me.id}", 12, 1_000)
        limit("min:${me.id}", 300, 60_000)
        when (path) {
            "/act" -> {
                if (req.seq >= 0 && req.seq != me.state.seq) return Reply(false, "desync", me.state)
                val r = try { Engine.apply(me.state, Action(req.type, req.i, req.s), now, rng, ServerWorld(me.id)) }
                catch (e: Refused) { return Reply(false, e.message ?: "Refusé", me.state) }
                save(accounts[me.id]!!.copy(state = r.state))
                return Reply(state = r.state, events = r.events, live = liveNow(now))
            }
            "/board" -> return Reply(
                state = me.state,
                board = accounts.values.sortedByDescending { it.state.stars }.take(50).map(::row),
                friends = friendsOf(me), live = liveNow(now),
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
        val t0 = System.nanoTime()
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
        synchronized(this) {
            requestCount++
            val line = "${java.time.Instant.ofEpochMilli(clock())} ${ex.requestURI.path} $code ${(System.nanoTime() - t0) / 1_000_000} ms"
            if (ex.requestURI.path != "/health") keep(requests, line)
            if (code >= 400) { errorCount++; keep(errors, "$line — ${reply.error}") }
        }
        val out = JSON.encodeToString(Reply.serializer(), reply).toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, out.size.toLong())
        ex.responseBody.use { it.write(out) }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val server = GameServer(File(System.getenv("DATA_DIR") ?: "data"), port, System.getenv("ADMIN_PASSWORD") ?: "")
    server.start()
    println("Ghost Coin : serveur démarré sur le port ${server.port}")
}
