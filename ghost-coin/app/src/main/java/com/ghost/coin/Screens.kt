package com.ghost.coin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghost.coin.core.*
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Construction

@Composable
fun BuildScreen(game: Game) = Sheet(game, "Construire") {
    val s = game.state
    val t = frameTime()
    Canvas(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp))) { villageScene(s.village, s.levels, s.damaged, t) }
    T("${villageName(s.village)} — terminez les 8 bâtiments pour ouvrir le monde suivant." + if (s.voucher) " Bon −50 % actif." else "", 12, Dim)
    val pal = remember(s.village) { Palette(s.village) }
    for (b in 0 until 8) {
        val lvl = s.levels[b]
        val repair = s.damaged[b]
        val max = lvl >= Rules.MAX_LEVEL && !repair
        val price = if (repair) Rules.cost(s.village, b, lvl - 1) / 2 else if (max) 0 else Rules.cost(s.village, b, lvl) / (if (s.voucher) 2 else 1)
        Card(border = if (repair) Danger else Color.White.copy(alpha = 0.12f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(58.dp)) { building(b, maxOf(lvl, 1), repair, pal, size.width / 2, size.height * 0.96f, size.width * 0.62f, t) }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    T(BUILDINGS[b], 15, Ink, FontWeight.Bold)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(Rules.MAX_LEVEL) { Box(Modifier.size(width = 22.dp, height = 7.dp).clip(CircleShape).background(if (it < lvl) Gold else Color(0x33FFFFFF))) }
                    }
                    if (repair) T("Endommagé par une attaque", 11, Danger)
                }
                if (max) T("MAX", 14, Gold, FontWeight.Black)
                else GButton(game, (if (repair) "Réparer\n" else "Améliorer\n") + fmt(price), Modifier.width(118.dp), if (repair) Danger else Gold, s.coins >= price, 12) { game.act("build", b) }
            }
        }
    }
}

// ---------------------------------------------------------------- Attaque

@Composable
fun AttackScreen(game: Game) {
    val target = remember { game.state.target }
    if (target == null) { LaunchedEffect(Unit) { game.screen = Screen.HOME }; return }
    val scope = rememberCoroutineScope()
    var struck by remember { mutableIntStateOf(-1) }
    var blocked by remember { mutableStateOf(false) }
    var gain by remember { mutableLongStateOf(0L) }
    var done by remember { mutableStateOf(false) }
    val anim = remember { Animatable(0f) }
    val t = frameTime()
    Sheet(game, "Attaque") {
        T("Village de ${target.name} · ${villageName(target.village)}", 14, Ink, FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(20.dp)).border(1.5.dp, Electric, RoundedCornerShape(20.dp))) {
            val broken = List(8) { it == struck && !blocked && anim.value > 0.4f }
            villageScene(target.village, target.levels, broken, t, struck, anim.value)
            if (struck >= 0 && blocked && anim.value > 0.2f) { val (x, base, u) = slot(struck, size); symbol(3, Offset(x, base - u * 0.6f), u * 0.7f) }
        }
        if (done) Card(border = if (blocked) Electric else Gold) {
            T(if (blocked) "Bloqué par un bouclier !" else "${BUILDINGS[struck]} détruit !", 18, if (blocked) Electric else Gold, FontWeight.Black)
            T("Récompense : +${fmt(gain)} pièces", 14)
            GButton(game, "Continuer", Modifier.fillMaxWidth().padding(top = 10.dp)) {
                if (game.state.attacks > 0) { game.screen = Screen.HOME; game.begin("attack") } else game.screen = Screen.HOME
            }
        } else {
            T("Choisissez le bâtiment à viser", 13, Dim)
            for (row in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in listOf(row * 2, row * 2 + 1)) GButton(
                    game, "${BUILDINGS[b]}\nniveau ${target.levels[b]}", Modifier.weight(1f), Electric, target.levels[b] > 0 && struck < 0, 12,
                ) {
                    scope.launch {
                        val r = game.send("hit", b, effects = false)
                        val e = r.events.firstOrNull { it.type == "attackResult" } ?: return@launch
                        blocked = e.m > 0; gain = r.events.firstOrNull { it.type == "coins" }?.n ?: 0; struck = b
                        game.sfx(Sfx.ATTACK)
                        anim.animateTo(1f, tween(1300, easing = LinearEasing))
                        game.play(r.events.filter { it.type == "coins" }); done = true
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Raid

@Composable
fun RaidScreen(game: Game) {
    val target = remember { game.state.target }
    if (target == null) { LaunchedEffect(Unit) { game.screen = Screen.HOME }; return }
    val scope = rememberCoroutineScope()
    val pot = remember { game.state.raidPot }
    // Emplacement -> butin ; -1 = déjà creusé lors d'une session précédente.
    val found = remember { mutableStateMapOf<Int, Long>().apply { game.state.raidPicked.forEach { put(it, -1) } } }
    var done by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    val t = frameTime()
    Sheet(game, "Raid") {
        T("Trésor de ${target.name}", 14, Ink, FontWeight.Bold)
        Card(border = Gold) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(1, Modifier.size(44.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    T("Butin en jeu : ${fmt(pot)}", 17, Gold, FontWeight.Black)
                    T("3 coups de pelle, 4 emplacements : l'un d'eux est vide.", 12, Dim)
                }
            }
        }
        for (row in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (h in listOf(row * 2, row * 2 + 1)) {
                val amount = found[h]
                val shape = RoundedCornerShape(22.dp)
                Box(
                    Modifier.weight(1f).height(150.dp).clip(shape).background(Brush.verticalGradient(listOf(Color(0xFF2A1757), Color(0xFF120A26))))
                        .border(2.dp, if (amount == null) Violet else if (amount > 0) Gold else Dim, shape)
                        .clickable(enabled = amount == null && !done && !waiting) {
                            waiting = true
                            scope.launch {
                                val r = game.send("dig", h, effects = false)
                                val e = r.events.firstOrNull { it.type == "dig" }
                                if (e != null) {
                                    found[h] = e.n
                                    if (e.n > 0) { game.burst++; game.sfx(Sfx.COIN) } else game.sfx(Sfx.LOSE)
                                    done = e.ids.firstOrNull() == 1
                                }
                                waiting = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Offset(size.width / 2, size.height * 0.5f)
                        if (amount == null) {
                            drawOval(Color(0xFF3B2A14), Offset(c.x - size.width * 0.32f, c.y), Size(size.width * 0.64f, size.height * 0.36f))
                            drawOval(Color(0xFF6B4B25), Offset(c.x - size.width * 0.28f, c.y - size.height * 0.06f), Size(size.width * 0.56f, size.height * 0.28f))
                            star(Offset(c.x, c.y - size.height * 0.22f + kotlin.math.sin(t * 3f + h) * 6f), size.width * 0.07f, Gold)
                        } else if (amount > 0) {
                            chest(c, size.width * 0.2f, 2)
                            for (i in 0 until 3) symbol(0, Offset(c.x + (i - 1) * size.width * 0.16f, c.y - size.height * 0.3f - kotlin.math.abs(kotlin.math.sin(t * 4f + i)) * 10f), size.width * 0.07f)
                        } else ghost(c.x, c.y, size.width * 0.22f, Dim, Mood.SAD, t)
                    }
                    T(
                        when { amount == null -> "Creuser"; amount > 0 -> "+${fmt(amount)}"; amount == 0L -> "Vide"; else -> "Déjà creusé" },
                        14, if ((amount ?: 0) > 0) Gold else Ink, FontWeight.Black, Modifier.align(Alignment.BottomCenter).padding(10.dp),
                    )
                }
            }
        }
        if (done) Card(border = Gold) {
            val total = found.values.filter { it > 0 }.sum()
            T(if (found.values.none { it == 0L }) "Raid parfait !" else "Raid terminé", 18, Gold, FontWeight.Black)
            T("Butin emporté : ${fmt(total)} pièces", 14)
            GButton(game, "Continuer", Modifier.fillMaxWidth().padding(top = 10.dp)) {
                if (game.state.raids > 0) { game.screen = Screen.HOME; game.begin("raid") } else game.screen = Screen.HOME
            }
        } else T("Coups restants : ${Rules.RAID_DIGS - found.size}", 13, Dim)
    }
}

// ---------------------------------------------------------------- Cartes

@Composable
fun CardsScreen(game: Game) = Sheet(game, "Collection") {
    val s = game.state
    var album by remember { mutableIntStateOf(0) }
    var sel by remember { mutableIntStateOf(-1) }
    T("${s.cards.size}/${CARDS.size} cartes découvertes · ${s.albums.size}/${ALBUMS.size} albums récompensés", 12, Dim)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ALBUMS.forEachIndexed { i, name ->
            val n = (0 until ALBUM_SIZE).count { (s.cards[i * ALBUM_SIZE + it] ?: 0) > 0 }
            GButton(game, "$name $n/8", color = if (i == album) Gold else Violet, size = 12) { album = i; sel = -1 }
        }
    }
    for (row in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in 0 until 4) {
            val id = album * ALBUM_SIZE + row * 4 + k
            CardView(id, s.cards[id] ?: 0, Modifier.weight(1f), sel == id) { sel = id }
        }
    }
    val complete = (0 until ALBUM_SIZE).all { (s.cards[album * ALBUM_SIZE + it] ?: 0) > 0 }
    Card(border = if (complete && album !in s.albums) Gold else Color.White.copy(alpha = 0.12f)) {
        T("Album ${ALBUMS[album]}", 15, Ink, FontWeight.Bold)
        T("Récompense : ${30 + 10 * album} tours et ${fmt(Rules.unit(s.village) * 100)} pièces", 12, Dim)
        when {
            album in s.albums -> T("Récompense déjà récupérée", 12, Gold)
            complete -> GButton(game, "Récupérer la récompense", Modifier.fillMaxWidth().padding(top = 8.dp)) { game.act("album", album) }
            else -> T("Trouvez les 8 cartes pour la débloquer.", 12, Dim)
        }
    }
    if (sel >= 0) {
        val n = s.cards[sel] ?: 0
        val r = rarityOf(sel)
        Card(border = RARITY[r]) {
            T(if (n > 0) CARDS[sel] else "Carte inconnue", 17, Ink, FontWeight.Black)
            T("${RARITIES[r]} · " + if (n > 0) "possédée ×$n" else "à trouver dans les coffres", 12, RARITY[r], FontWeight.Bold)
            if (n > 1) {
                GButton(game, "Échanger ${n - 1} double(s) contre ${fmt(Rules.unit(s.village) * Rules.SELL[r] * (n - 1))}", Modifier.fillMaxWidth().padding(top = 8.dp), size = 13) { game.act("sell", sel) }
                val pals = game.friends.filter { it.id != s.id }
                if (game.online && pals.isNotEmpty()) {
                    T("Offrir un double à un ami :", 12, Dim, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pals.forEach { f -> GButton(game, f.name, color = Electric, size = 12) { game.sendCard(f.id, sel) } }
                    }
                }
            }
        }
    }
    GButton(game, "Obtenir des cartes : ouvrir des coffres", Modifier.fillMaxWidth(), Violet) { game.screen = Screen.SHOP }
}

// ---------------------------------------------------------------- Boutique

@Composable
fun ShopScreen(game: Game) = Sheet(game, "Boutique") {
    val s = game.state
    val wait = s.freeChestAt - game.now
    Card(border = if (wait <= 0) Gold else Color.White.copy(alpha = 0.12f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(64.dp)) { chest(Offset(size.width / 2, size.height * 0.55f), size.width * 0.42f, 0) }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                T("Coffre gratuit", 16, Ink, FontWeight.Bold)
                T("${Rules.event(game.now).freeCards} cartes, toutes les 4 heures", 12, Dim)
            }
            GButton(game, if (wait <= 0) "Ouvrir" else "%d:%02d:%02d".format(wait / 3_600_000, wait / 60_000 % 60, wait / 1000 % 60), Modifier.width(112.dp), enabled = wait <= 0) { game.act("freeChest") }
        }
    }
    CHESTS.forEachIndexed { i, c ->
        val price = Rules.unit(s.village) * c.price
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(64.dp)) { chest(Offset(size.width / 2, size.height * 0.55f), size.width * 0.42f, i) }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    T(c.name, 16, Ink, FontWeight.Bold)
                    T("${c.cards} cartes · légendaire ${c.odds[3] / 10f} % · mythique ${c.odds[4] / 10f} %", 11, Dim)
                }
                GButton(game, fmt(price), Modifier.width(112.dp), enabled = s.coins >= price) { game.act("chest", i) }
            }
        }
    }
    T("Tout s'achète avec les pièces gagnées en jouant. Cette version ne contient ni achat en argent réel ni publicité.", 12, Dim)
}

// ---------------------------------------------------------------- Missions, roue, événement

private fun rewardText(spins: Int, units: Int, chest: Int, village: Int) = listOfNotNull(
    if (spins > 0) "$spins tours" else null, if (units > 0) "${fmt(Rules.unit(village) * units)} pièces" else null, if (chest >= 0) CHESTS[chest].name else null,
).joinToString(" + ")

@Composable
fun QuestsScreen(game: Game) = Sheet(game, "Missions") {
    val s = game.state
    val day = Rules.day(game.now)
    val ev = Rules.event(game.now)
    val scope = rememberCoroutineScope()
    Card(border = Gold) {
        T("ÉVÉNEMENT DU JOUR", 10, Gold, FontWeight.Black)
        T(ev.name, 18, Ink, FontWeight.Black); T(ev.text, 13, Dim)
    }
    Card {
        val next = if (s.dailyDay == day - 1 || s.dailyDay == day) (if (s.dailyDay == day) s.dailyStreak else s.dailyStreak % 7 + 1) else 1
        T("Bonus de connexion · jour $next/7", 15, Ink, FontWeight.Bold)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (d in 1..7) Box(
                Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(8.dp)).background(if (d < next || d == next && s.dailyDay == day) Gold else if (d == next) Violet else Color(0x22FFFFFF)),
                contentAlignment = Alignment.Center,
            ) { T("$d", 12, if (d < next || d == next && s.dailyDay == day) Night else Ink, FontWeight.Black) }
        }
        if (s.dailyDay == day) T("Récupéré. Revenez demain pour continuer la série.", 12, Dim)
        else GButton(game, "Récupérer : " + rewardText(5 + 2 * next, 10 * next, if (next == 7) 1 else -1, s.village), Modifier.fillMaxWidth(), size = 13) { game.act("daily") }
    }
    Card {
        T("Roue des récompenses", 15, Ink, FontWeight.Bold)
        val rot = remember { Animatable(0f) }
        var turning by remember { mutableStateOf(false) }
        val measurer = rememberTextMeasurer()
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(250.dp)) {
                val c = Offset(size.width / 2, size.height / 2); val r = size.width / 2
                drawCircle(Gold, r, c)
                WHEEL.forEachIndexed { i, w ->
                    drawArc(if (i % 2 == 0) Color(0xFF4A1FA8) else Color(0xFF1C6FE0), -90f + i * 45f + rot.value, 45f, true, Offset(r * 0.05f, r * 0.05f), Size(r * 1.9f, r * 1.9f))
                    rotate((i + 0.5f) * 45f + rot.value, c) {
                        val l = measurer.measure(w.label, TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                        drawText(l, topLeft = Offset(c.x - l.size.width / 2f, c.y - r * 0.82f))
                    }
                }
                drawCircle(Gold, r * 0.16f, c); star(c, r * 0.1f, Night)
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(c.x - r * 0.09f, 0f); lineTo(c.x + r * 0.09f, 0f); lineTo(c.x, r * 0.2f); close() }
                drawPath(p, Danger)
            }
        }
        if (s.wheelDay == day && !turning) T("La roue a tourné aujourd'hui. Prochain tour gratuit demain.", 12, Dim)
        else GButton(game, "Tourner la roue (gratuit)", Modifier.fillMaxWidth(), enabled = !turning) {
            turning = true
            scope.launch {
                val r = game.send("wheel", effects = false)
                val i = r.events.firstOrNull { it.type == "wheel" }?.n?.toInt()
                if (i != null) {
                    game.sfx(Sfx.SPIN)
                    rot.snapTo(rot.value % 360f)
                    rot.animateTo(360f * 5 - (i + 0.5f) * 45f, tween(3200, easing = FastOutSlowInEasing))
                    game.play(r.events)
                }
                turning = false
            }
        }
    }
    for ((title, prefix) in listOf("Missions du jour" to "d:", "Missions de la semaine" to "w:", "Succès" to "a:")) {
        T(title, 16, Gold, FontWeight.Black, Modifier.padding(top = 6.dp))
        QUESTS.filter { it.id.startsWith(prefix) }.forEach { q ->
            val p = s.progress(q)
            val shown = if (q.key == "village") p + 1 else p
            val goal = if (q.key == "village") q.goal + 1 else q.goal
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        T(q.label, 14, Ink, FontWeight.Bold)
                        T(rewardText(q.spins, q.units, q.chest, s.village), 11, Gold)
                        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Bar(p.toFloat() / q.goal, Electric, Modifier.weight(1f)); T("  ${minOf(shown, goal)}/$goal", 11, Dim)
                        }
                    }
                    if (q.id in s.claimed) T("Fait", 13, Gold, FontWeight.Black)
                    else GButton(game, "Récupérer", enabled = p >= q.goal, size = 12) { game.act("claim", s = q.id) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Social

@Composable
private fun Field(value: String, label: String, onChange: (String) -> Unit) = OutlinedTextField(
    value, onChange, Modifier.fillMaxWidth(), label = { T(label, 12, Dim) }, singleLine = true,
    textStyle = TextStyle(color = Ink, fontSize = 15.sp), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
)

@Composable
fun SocialScreen(game: Game) = Sheet(game, "Social") {
    val s = game.state
    LaunchedEffect(game.online) { if (game.online) game.refreshBoard() }
    T("Journal", 16, Gold, FontWeight.Black)
    if (s.log.isEmpty()) T("Personne ne vous a encore attaqué.", 12, Dim)
    s.log.take(8).forEach { e ->
        Card(border = if (e.kind == "gift" || e.kind == "card") Electric else Danger.copy(alpha = 0.6f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(
                    when (e.kind) {
                        "attack" -> if (e.blocked) "${e.who} a attaqué : bloqué par un bouclier" else "${e.who} a endommagé : ${BUILDINGS[e.amount.toInt()]}"
                        "raid" -> "${e.who} a pillé ${fmt(e.amount)} pièces"
                        "gift" -> "${e.who} vous a offert ${e.amount} tours"
                        else -> "${e.who} vous a offert la carte ${CARDS[e.amount.toInt()]}"
                    }, 13, Ink, modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                if (e.kind == "attack" || e.kind == "raid") {
                    if (e.revenged) T("Vengé", 12, Dim, FontWeight.Bold)
                    else GButton(game, "Vengeance", color = Danger, enabled = s.attacks > 0, size = 12) { game.begin("attack", e.whoId) }
                }
            }
        }
    }
    if (s.log.any { !it.revenged && (it.kind == "attack" || it.kind == "raid") } && s.attacks == 0) T("La vengeance utilise une attaque gagnée à la machine.", 11, Dim)

    T("Multijoueur", 16, Gold, FontWeight.Black, Modifier.padding(top = 6.dp))
    if (!game.online) {
        var url by remember { mutableStateOf(game.serverUrl) }
        var name by remember { mutableStateOf(s.name) }
        var code by remember { mutableStateOf("") }
        Card {
            T("Vous jouez en solo contre des adversaires simulés. Pour affronter de vrais joueurs, indiquez l'adresse d'un serveur Ghost Coin.", 12, Dim)
            Field(url, "Adresse du serveur (https://…)") { url = it }
            Field(name, "Pseudo") { name = it }
            GButton(game, "Créer un compte en ligne", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = url.startsWith("http") && name.length >= 3) { game.connect(url.trim(), name) }
            T("La progression solo n'est pas transférée : le serveur ne fait confiance qu'à ce qu'il a lui-même calculé.", 11, Dim, modifier = Modifier.padding(top = 6.dp))
            Field(code, "Code de récupération d'un compte existant") { code = it }
            GButton(game, "Récupérer mon compte", Modifier.fillMaxWidth().padding(top = 8.dp), Electric, url.startsWith("http") && code.length >= 8) { game.recover(url.trim(), code) }
        }
    } else {
        var code by remember { mutableStateOf("") }
        var world by remember { mutableStateOf(true) }
        Card(border = Electric) {
            T("Votre code ami", 12, Dim); T(s.id, 24, Electric, FontWeight.Black)
            Field(code, "Code d'un ami") { code = it.uppercase() }
            GButton(game, "Ajouter cet ami", Modifier.fillMaxWidth().padding(top = 8.dp), Electric, code.length == 8) { game.addFriend(code); code = "" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GButton(game, "Classement mondial", Modifier.weight(1f), if (world) Gold else Violet, size = 13) { world = true }
            GButton(game, "Entre amis", Modifier.weight(1f), if (!world) Gold else Violet, size = 13) { world = false }
        }
        val rows = if (world) game.board else game.friends
        if (rows.isEmpty()) T("Classement en cours de chargement…", 12, Dim)
        rows.forEachIndexed { i, r ->
            Card(border = if (r.id == s.id) Gold else Color.White.copy(alpha = 0.12f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("${i + 1}", 16, Gold, FontWeight.Black, Modifier.width(30.dp))
                    Canvas(Modifier.size(34.dp).clip(CircleShape).background(Violet)) { ghost(size.width / 2, size.height * 0.52f, size.width * 0.6f, AVATARS[r.avatar % AVATARS.size], Mood.IDLE, 0f) }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        T(r.name, 14, Ink, FontWeight.Bold, lines = 1); T("Monde ${r.village + 1} · ${r.stars} ★", 11, Dim)
                    }
                    if (!world && r.id != s.id) GButton(game, "Offrir 3 tours", color = Electric, size = 11) { game.gift(r.id) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Réglages

@Composable
fun SettingsScreen(game: Game) = Sheet(game, "Réglages") {
    val s = game.state
    var name by remember { mutableStateOf(s.name) }
    var avatar by remember { mutableIntStateOf(s.avatar) }
    Card {
        T("Profil", 15, Ink, FontWeight.Bold)
        Field(name, "Pseudo (3 à 16 caractères)") { name = it.take(16) }
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AVATARS.forEachIndexed { i, c ->
                Canvas(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(if (i == avatar) Violet else Color(0x22FFFFFF)).border(2.dp, if (i == avatar) Gold else Color.Transparent, CircleShape).clickable { avatar = i }) {
                    ghost(size.width / 2, size.height * 0.52f, size.width * 0.58f, c, Mood.IDLE, 0f)
                }
            }
        }
        GButton(game, "Enregistrer le profil", Modifier.fillMaxWidth(), enabled = name.trim().length >= 3) { game.act("profile", avatar, name) }
    }
    Card {
        T("Son", 15, Ink, FontWeight.Bold)
        var music by remember { mutableFloatStateOf(game.audio?.musicVolume ?: 0f) }
        var sfx by remember { mutableFloatStateOf(game.audio?.sfxVolume ?: 0f) }
        val colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color(0x33FFFFFF))
        T("Musique · ${(music * 100).toInt()} %", 12, Dim)
        Slider(music, { music = it; game.audio?.musicVolume = it }, colors = colors)
        T("Effets sonores · ${(sfx * 100).toInt()} %", 12, Dim)
        Slider(sfx, { sfx = it; game.audio?.sfxVolume = it }, onValueChangeFinished = { game.sfx(Sfx.COIN) }, colors = colors)
    }
    Card(border = if (game.online) Electric else Color.White.copy(alpha = 0.12f)) {
        T("Compte", 15, Ink, FontWeight.Bold)
        if (game.online) {
            T("En ligne sur ${game.serverUrl}", 12, Electric)
            T("Code ami : ${s.id}", 13)
            if (game.recovery.isNotEmpty()) {
                T("Code de récupération (à conserver, il permet de retrouver le compte sur un autre téléphone) :", 12, Dim, modifier = Modifier.padding(top = 6.dp))
                T(game.recovery, 15, Gold, FontWeight.Black)
            }
            GButton(game, "Se déconnecter et revenir au solo", Modifier.fillMaxWidth().padding(top = 10.dp), Danger, size = 13) { game.logout() }
        } else {
            T("Mode solo : la progression est enregistrée automatiquement sur ce téléphone.", 12, Dim)
            GButton(game, "Jouer en ligne", Modifier.fillMaxWidth().padding(top = 10.dp), Electric, size = 13) { game.screen = Screen.SOCIAL }
        }
    }
    T("Ghost Coin 0.1.0", 11, Dim, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
}
