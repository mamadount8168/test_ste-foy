import com.ghost.coin.core.*
import com.ghost.coin.server.GameServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

class ServerTest {
    private val dir = Files.createTempDirectory("ghostcoin").toFile()
    private var now = 1_800_000_000_000L
    private val pw = "mot-de-passe-de-test"
    private var server = GameServer(dir, 0, pw) { now }.also { it.start() }
    private val http = HttpClient.newHttpClient()

    @AfterTest fun stop() { server.stop(); dir.deleteRecursively() }

    private fun post(path: String, req: Req = Req(), token: String = ""): Pair<Int, Reply> {
        val r = http.send(
            HttpRequest.newBuilder(URI("http://localhost:${server.port}$path")).header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.encodeToString(Req.serializer(), req))).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return r.statusCode() to JSON.decodeFromString<Reply>(r.body())
    }
    private fun tick() { now += 1000 }
    private fun register(name: String) = post("/register", Req(name = name)).second

    @Test fun registerThenSpinIsComputedByServer() {
        val me = register("Alice")
        assertEquals("Alice", me.state!!.name); assertEquals(64, me.token.length)
        val (code, r) = post("/act", Req(type = "spin", seq = me.state!!.seq), me.token)
        assertEquals(200, code); assertTrue(r.ok)
        assertEquals(3, r.events.first { it.type == "reels" }.ids.size)
    }

    @Test fun rejectsMissingOrForgedToken() {
        register("Alice")
        assertEquals(401, post("/act", Req(type = "spin")).first)
        assertEquals(401, post("/act", Req(type = "spin"), "a".repeat(64)).first)
    }

    @Test fun clientCannotInventCoinsOrSkipCost() {
        val me = register("Alice")
        // Le client n'envoie que des intentions : il n'existe aucun champ pour fixer ses pièces.
        val paid = post("/act", Req(type = "build", i = 7), me.token).second.state!!
        assertEquals(me.state!!.coins - Rules.cost(0, 7, 0), paid.coins)
        val r = post("/act", Req(type = "build", i = 7), me.token).second // trop cher : refusé, rien ne change
        assertFalse(r.ok); assertEquals(paid.coins, r.state!!.coins); assertEquals(1, r.state!!.levels[7])
        assertFalse(post("/act", Req(type = "givecoins", i = 999999), me.token).second.ok)
    }

    @Test fun replayedRequestIsDetectedBySequenceNumber() {
        val me = register("Alice")
        val first = post("/act", Req(type = "spin", seq = 1), me.token).second
        assertTrue(first.ok)
        val replay = post("/act", Req(type = "spin", seq = 1), me.token).second
        assertFalse(replay.ok); assertEquals("desync", replay.error); assertEquals(first.state!!.spins, replay.state!!.spins)
    }

    @Test fun rateLimitBlocksBots() {
        val me = register("Alice")
        val codes = (1..20).map { post("/act", Req(type = "sync"), me.token).first }
        assertTrue(429 in codes); assertEquals(200, codes.first())
        tick(); tick()
        assertEquals(200, post("/act", Req(type = "sync"), me.token).first)
    }

    @Test fun recoveryIssuesNewTokenAndRevokesOldOne() {
        val me = register("Alice")
        assertEquals(403, post("/recover", Req(recovery = "nope")).first)
        val again = post("/recover", Req(recovery = me.recovery)).second
        assertEquals(me.state!!.id, again.state!!.id)
        assertEquals(401, post("/act", Req(type = "sync"), me.token).first)
        assertEquals(200, post("/act", Req(type = "sync"), again.token).first)
    }

    @Test fun friendsGiftsAndLeaderboard() {
        val a = register("Alice"); val b = register("Bob")
        assertEquals(404, post("/friend", Req(to = "ZZZZZZZZ"), a.token).first)
        val f = post("/friend", Req(to = b.state!!.id.lowercase()), a.token).second
        assertEquals(setOf("Alice", "Bob"), f.friends.map { it.name }.toSet())
        assertEquals(200, post("/gift", Req(to = b.state!!.id), a.token).first)
        assertEquals(400, post("/gift", Req(to = b.state!!.id), a.token).first)
        val bob = post("/board", token = b.token).second
        assertEquals(Rules.MAX_SPINS + 3, bob.state!!.spins)
        assertEquals("gift", bob.state!!.log.first().kind)
        assertEquals(2, bob.board.size); assertEquals(2, bob.friends.size)
    }

    @Test fun cardTradeOnlyMovesDuplicatesBetweenFriends() {
        val a = register("Alice"); val b = register("Bob"); val c = register("Carl")
        post("/friend", Req(to = b.state!!.id), a.token)
        assertEquals(400, post("/card", Req(to = b.state!!.id, card = 3), a.token).first) // pas de double
        assertEquals(404, post("/card", Req(to = c.state!!.id, card = 3), a.token).first) // pas ami
    }

    @Test fun realPlayerIsAttackedAndStatePersistsAcrossRestart() {
        val a = register("Alice"); val b = register("Bob")
        tick()
        var bs = post("/act", Req(type = "build", i = 0), b.token).second.state!!
        assertEquals(1, bs.levels[0])
        // On relance la machine d'Alice jusqu'à obtenir une attaque (résultat décidé par le serveur).
        var s = a.state!!
        var guard = 0
        while (s.attacks == 0 && guard++ < 3000) {
            tick()
            s = post("/act", Req(type = if (s.spins > 0) "spin" else "sync"), a.token).second.state!!
            if (s.spins == 0) now += Rules.REGEN_MS * 50
        }
        assertTrue(s.attacks > 0, "aucune attaque obtenue")
        tick()
        s = post("/act", Req(type = "attack"), a.token).second.state!!
        assertEquals("Bob", s.target!!.name) // seul joueur réel disponible
        tick()
        val hit = post("/act", Req(type = "hit", i = 0), a.token).second
        assertEquals(1L, hit.events.first { it.type == "attackResult" }.m) // Bob avait 1 bouclier de départ
        server.stop()
        server = GameServer(dir, 0, pw) { now }.also { it.start() }
        bs = post("/board", token = b.token).second.state!!
        assertEquals(0, bs.shields); assertEquals("Alice", bs.log.first().who); assertEquals("attack", bs.log.first().kind)
    }

    private fun adminLogin() = post("/admin", Req(op = "login", password = pw)).second.token

    @Test fun adminIsDisabledWithoutServerSidePassword() {
        server.stop(); server = GameServer(dir, 0) { now }.also { it.start() }
        assertEquals(403, post("/admin", Req(op = "login", password = "")).first)
        assertEquals(403, post("/admin", Req(op = "dashboard")).first)
    }

    @Test fun playersCannotUseAdminAndWrongPasswordLocksOut() {
        val me = register("Alice")
        assertEquals(401, post("/admin", Req(op = "dashboard"), me.token).first)
        assertEquals(401, post("/admin", Req(op = "cmd", to = me.state!!.id, cmd = AdminCmd("coins", n = 9_000_000)), me.token).first)
        repeat(5) { assertEquals(403, post("/admin", Req(op = "login", password = "faux")).first) }
        assertEquals(429, post("/admin", Req(op = "login", password = pw)).first) // même le bon mot de passe attend
        now += 16 * 60_000
        assertEquals(200, post("/admin", Req(op = "login", password = pw)).first)
    }

    @Test fun adminSessionExpiresAfterInactivity() {
        val t = adminLogin()
        assertEquals(200, post("/admin", Req(op = "dashboard"), t).first)
        now += 16 * 60_000
        assertEquals(401, post("/admin", Req(op = "dashboard"), t).first)
    }

    @Test fun adminEditsPlayerWithAuditAndUndo() {
        val me = register("Alice"); val t = adminLogin()
        val r = post("/admin", Req(op = "cmd", to = me.state!!.id, cmd = AdminCmd("coins", n = 5_000_000, s = "add")), t).second
        assertEquals(me.state!!.coins + 5_000_000, r.state!!.coins)
        assertEquals(74, post("/admin", Req(op = "cmd", to = me.state!!.id, cmd = AdminCmd("country", 74)), t).second.state!!.village)
        assertEquals(0, post("/admin", Req(op = "undo"), t).second.state!!.village)
        assertEquals(me.state!!.coins, post("/admin", Req(op = "undo"), t).second.state!!.coins)
        val log = post("/admin", Req(op = "logs", s = "audit"), t).second.lines
        assertTrue(log.any { "commande coins" in it } && log.any { "annulation" in it } && log.any { "connexion administrateur" in it })
        assertTrue(dir.resolve("audit.log").readText().contains("commande country"))
        assertTrue(post("/admin", Req(op = "export"), t).second.lines.size > 10)
    }

    @Test fun testPlayersAreFlaggedCanSimulateAttacksAndAreRemovable() {
        val me = register("Alice"); val t = adminLogin()
        val made = post("/admin", Req(op = "mktest", n = 3), t).second
        val tests = made.board.filter { it.test }
        assertEquals(3, tests.size); assertTrue(tests.all { it.name.startsWith("TEST-") }); assertEquals("3", made.stats["Comptes de test"]); assertEquals("1", made.stats["Joueurs"])
        assertEquals(400, post("/admin", Req(op = "simAttack", to = tests[0].id, s = me.state!!.id), t).first) // un vrai joueur ne sert pas d'attaquant simulé
        val hit = post("/admin", Req(op = "simAttack", to = me.state!!.id, s = tests[0].id, i = 0), t).second.state!!
        assertEquals(0, hit.shields); assertEquals(tests[0].name, hit.log.first().who)
        assertEquals(0, post("/admin", Req(op = "rmtests"), t).second.board.count { it.test })
    }

    @Test fun liveEventOverridesCalendarWhileActive() {
        val me = register("Alice"); val t = adminLogin()
        assertNull(post("/act", Req(type = "sync"), me.token).second.live)
        assertEquals(400, post("/admin", Req(op = "eventSave", event = LiveEvent(name = "", start = now, end = now + 10)), t).first)
        val ev = post("/admin", Req(op = "eventSave", event = LiveEvent(name = "Fête de Dakar", text = "Pièces triplées", coins = 300, start = now - 1, end = now + 3_600_000)), t).second.liveEvents.single()
        tick()
        assertEquals("Fête de Dakar", post("/act", Req(type = "sync"), me.token).second.live!!.name)
        post("/admin", Req(op = "eventStop", s = ev.id), t)
        tick()
        assertNull(post("/act", Req(type = "sync"), me.token).second.live)
    }
}
