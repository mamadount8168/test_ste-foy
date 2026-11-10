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
    private var server = GameServer(dir, 0) { now }.also { it.start() }
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
        server = GameServer(dir, 0) { now }.also { it.start() }
        bs = post("/board", token = b.token).second.state!!
        assertEquals(0, bs.shields); assertEquals("Alice", bs.log.first().who); assertEquals("attack", bs.log.first().kind)
    }
}
