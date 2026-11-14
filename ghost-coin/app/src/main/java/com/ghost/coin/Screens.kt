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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.verticalScroll
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
fun BuildScreen(game: Game) {
    val s = game.state
    val time = rememberTime()
    var sel by remember { mutableIntStateOf(-1) }
    val pop = remember { Animatable(1f) }
    val built = s.stars
    var seen by remember { mutableIntStateOf(built) }
    LaunchedEffect(built) { if (built != seen) { seen = built; pop.snapTo(0f); pop.animateTo(1f, tween(900)) } }
    val look = remember(s.village) { Look(s.village) }
    Box(Modifier.fillMaxSize()) {
        Backdrop()
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Header(game, "Construire")
            // Grande vue du village : le bâtiment choisi est entouré, une gerbe d'étoiles salue chaque amélioration.
            Box(Modifier.fillMaxWidth().weight(0.4f).padding(horizontal = 16.dp).shadow(12.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))) {
                Canvas(Modifier.fillMaxSize()) {
                    val t = time.value
                    villageScene(s.village, s.levels, s.damaged, t)
                    if (sel >= 0) {
                        val (x, base, u) = slot(sel, size)
                        drawOval(Gold.copy(alpha = 0.55f + 0.3f * kotlin.math.abs(kotlin.math.sin(t * 4f))), Offset(x - u * 0.8f, base - u * 0.16f), Size(u * 1.6f, u * 0.34f), style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                        val k = pop.value
                        if (k < 1f) for (i in 0 until 12) { val a = i * 0.524f; star(Offset(x + kotlin.math.cos(a) * u * 1.3f * k, base - u * 0.6f + kotlin.math.sin(a) * u * 1.3f * k), u * 0.14f * (1f - k), if (i % 2 == 0) Gold else Color.White) }
                    }
                }
                Column(Modifier.padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x8C1B0B4A)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    T("${look.c.flag}  ${villageName(s.village)}", 17, Ink, FontWeight.Black)
                    Row(verticalAlignment = Alignment.CenterVertically) { Bar(s.levels.sum() / 24f, Gold, Modifier.width(130.dp)); T("  ${s.levels.sum()}/24", 12, Ink, FontWeight.Bold) }
                }
                if (s.voucher) T("Bon −50 % actif", 13, Color(0xFF3A1A00), FontWeight.Black, Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(CircleShape).background(Gold).padding(horizontal = 12.dp, vertical = 5.dp))
            }
            Column(Modifier.fillMaxWidth().weight(0.6f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (b in 0 until 8) {
                    val lvl = s.levels[b]
                    val repair = s.damaged[b]
                    val max = lvl >= Rules.MAX_LEVEL && !repair
                    val price = if (repair) Rules.cost(s.village, b, lvl - 1) / 2 else if (max) 0 else Rules.cost(s.village, b, lvl) / (if (s.voucher) 2 else 1)
                    Card(Modifier.clickable { sel = b }, if (repair) Danger else if (sel == b) Gold else Color.Transparent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Illustration du bâtiment à son niveau actuel, sur le ciel du pays.
                            Canvas(Modifier.size(92.dp).clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(look.skyTop, look.skyBottom, look.ground)))) {
                                val bounce = if (sel == b && pop.value < 1f) 1f + 0.25f * kotlin.math.sin(pop.value * 3.14f) else 1f
                                drawRect(look.ground, Offset(0f, size.height * 0.78f), Size(size.width, size.height * 0.22f))
                                building(b, maxOf(lvl, 1), repair, look, size.width * 0.46f, size.height * 0.9f, size.width * 0.5f * bounce, time.value)
                                if (lvl == 0) drawRect(Color(0x99301580))
                            }
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                T(BUILDINGS[b], 17, Ink, FontWeight.Black, lines = 1)
                                T(if (repair) "Endommagé par une attaque" else if (max) "Terminé" else if (lvl == 0) "Terrain à bâtir" else "Niveau $lvl sur ${Rules.MAX_LEVEL}", 13, if (repair) Color(0xFFFFC2CC) else Dim, FontWeight.Bold, lines = 1)
                                Bar(lvl / Rules.MAX_LEVEL.toFloat(), if (repair) Danger else Gold, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                                if (max) Row(verticalAlignment = Alignment.CenterVertically) { Canvas(Modifier.size(22.dp)) { star(Offset(size.width / 2, size.height / 2), size.width / 2, Gold) }; T("  Niveau maximal", 14, Gold, FontWeight.Black) }
                                else Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(0, Modifier.size(22.dp)); T(" ${fmt(price)}", 15, if (s.coins >= price) Ink else Color(0xFFFFC2CC), FontWeight.Black, Modifier.weight(1f), lines = 1)
                                    GButton(game, if (repair) "Réparer" else if (lvl == 0) "Bâtir" else "Améliorer", Modifier.testTag("build-$b").width(112.dp), if (repair) Danger else Gold, s.coins >= price, 15) { sel = b; game.act("build", b) }
                                }
                            }
                        }
                    }
                }
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
    val time = rememberTime()
    fun hit(b: Int) {
        if (struck >= 0) return
        scope.launch {
            val r = game.send("hit", b, effects = false)
            val e = r.events.firstOrNull { it.type == "attackResult" } ?: return@launch
            blocked = e.m > 0; gain = r.events.firstOrNull { it.type == "coins" }?.n ?: 0; struck = b
            kotlinx.coroutines.delay(280); game.sfx(Sfx.ATTACK)
            anim.animateTo(1f, tween(1300, easing = LinearEasing))
            game.play(r.events.filter { it.type == "coins" || it.type == "cards" }); done = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Le village adverse en plein écran : on vise directement les bâtiments.
        Canvas(Modifier.fillMaxSize()) {
            val t = time.value
            val broken = List(8) { it == struck && !blocked && anim.value > 0.4f }
            villageScene(target.village, target.levels, broken, t, struck, anim.value)
            ghost(size.width * 0.5f, size.height * 0.82f, size.width * 0.13f, AVATARS[target.avatar % AVATARS.size], if (!done) (if (struck >= 0) Mood.HIT else Mood.IDLE) else if (blocked) Mood.WIN else Mood.SAD, t)
            if (struck < 0) for (b in 0 until 8) if (target.levels[b] > 0) { // viseurs
                val (x, base, u) = slot(b, size)
                val o = Offset(x, base - u * 0.5f); val r = u * (0.42f + 0.05f * kotlin.math.sin(t * 5f + b))
                drawCircle(Color.White, r, o, style = androidx.compose.ui.graphics.drawscope.Stroke(7f)); drawCircle(Danger, r, o, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
                rotate(t * 60f, o) { for (i in 0 until 4) rotate(i * 90f, o) { drawLine(Danger, Offset(o.x, o.y - r * 1.3f), Offset(o.x, o.y - r * 0.7f), 6f, androidx.compose.ui.graphics.StrokeCap.Round) } }
                drawCircle(Danger, r * 0.14f, o)
            }
            if (struck >= 0 && blocked && anim.value > 0.2f) { val (x, base, u) = slot(struck, size); drawCircle(Electric.copy(alpha = 0.35f), u * 0.9f, Offset(x, base - u * 0.5f)); symbol(3, Offset(x, base - u * 0.5f), u * 0.7f) }
            drawRect(Brush.verticalGradient(listOf(Color(0x99140830), Color.Transparent), 0f, size.height * 0.22f), size = Size(size.width, size.height * 0.22f))
        }
        if (struck < 0) for (b in 0 until 8) if (target.levels[b] > 0) {
            val (x, base, u) = slot(b, Size(maxWidth.value, maxHeight.value))
            Box(Modifier.offset((x - 38f).dp, (base - u * 0.5f - 38f).dp).size(76.dp).clip(CircleShape).testTag("target-$b").clickable { hit(b) })
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            T("ATTAQUE", 28, Ink, FontWeight.Black, shadow = true)
            T("Village de ${target.name} · ${countryOf(target.village).flag} ${villageName(target.village)}", 15, Ink, FontWeight.Bold, shadow = true)
            Spacer(Modifier.weight(1f))
            if (done) Card(border = if (blocked) Electric else Gold) {
                T(if (blocked) "Bloqué par un bouclier !" else "${BUILDINGS[struck]} détruit !", 22, if (blocked) Color(0xFFBFF0FF) else Gold, FontWeight.Black)
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(0, Modifier.size(34.dp)); T("  +${fmt(gain)}", 24, Ink, FontWeight.Black) }
                GButton(game, "Continuer", Modifier.fillMaxWidth(), size = 18) { if (game.state.attacks > 0) { game.screen = Screen.HOME; game.begin("attack") } else game.screen = Screen.HOME }
            } else if (struck < 0) T("Touchez le bâtiment à frapper", 17, Ink, FontWeight.Black, Modifier.fillMaxWidth().clip(CircleShape).background(Color(0xB324105E)).padding(vertical = 12.dp), TextAlign.Center)
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
    val shownAt = remember { mutableStateMapOf<Int, Float>() }
    var done by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    val time = rememberTime()
    val look = remember { Look(target.village) }
    val spots = listOf(0.27f to 0.47f, 0.73f to 0.47f, 0.27f to 0.7f, 0.73f to 0.7f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val t = time.value
            drawRect(Brush.verticalGradient(listOf(look.skyTop, look.skyBottom), 0f, size.height * 0.32f))
            drawRect(Brush.verticalGradient(listOf(look.ground, look.groundDark), size.height * 0.3f, size.height), Offset(0f, size.height * 0.3f))
            for (i in 0 until 6) tree(look.c.biome, size.width * (0.05f + i * 0.18f), size.height * 0.33f, size.height * 0.1f)
            spots.forEachIndexed { h, (fx, fy) ->
                val o = Offset(size.width * fx, size.height * fy); val u = size.width * 0.17f
                val amount = found[h]
                val k = shownAt[h]?.let { ((t - it) / 0.5f).coerceIn(0f, 1f) } ?: 1f
                drawOval(Color.Black.copy(alpha = 0.25f), Offset(o.x - u * 1.1f, o.y + u * 0.25f), Size(u * 2.2f, u * 0.6f))
                if (amount == null) { // monticule de terre fraîche, étincelle qui invite à creuser
                    drawArc(Brush.verticalGradient(listOf(Color(0xFFB98A52), Color(0xFF6B4520)), o.y - u * 0.7f, o.y + u * 0.5f), 180f, 180f, true, Offset(o.x - u, o.y - u * 0.7f), Size(u * 2f, u * 2.2f))
                    drawOval(Color(0xFFD2A46A), Offset(o.x - u * 0.55f, o.y - u * 0.55f), Size(u * 0.8f, u * 0.3f))
                    for (i in 0 until 5) drawCircle(Color(0xFF55381A), u * 0.07f, Offset(o.x - u * 0.6f + i * u * 0.3f, o.y + u * 0.2f - (i % 2) * u * 0.15f))
                    star(Offset(o.x + u * 0.3f, o.y - u * (0.95f + 0.12f * kotlin.math.sin(t * 3f + h))), u * 0.2f, Gold); star(Offset(o.x - u * 0.45f, o.y - u * 0.75f), u * 0.1f * kotlin.math.abs(kotlin.math.sin(t * 2f + h)), Color.White)
                } else {
                    drawOval(Color(0xFF2A1A0C), Offset(o.x - u * 0.9f, o.y), Size(u * 1.8f, u * 0.6f))
                    if (amount > 0 || amount == -1L) {
                        if (amount > 0) for (i in 0 until 12) rotate(t * 30f + i * 30f, o) { drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x55FFE08A)), o.y - u * 2.2f, o.y), Offset(o.x - u * 0.12f, o.y - u * 2.2f * k), Size(u * 0.24f, u * 2.2f * k)) }
                        chest(Offset(o.x, o.y - u * 0.25f * k), u * 0.75f * (0.4f + 0.6f * k), 2)
                        if (amount > 0) for (i in 0 until 5) symbol(0, Offset(o.x + (i - 2) * u * 0.34f, o.y - u * (1.0f + 0.25f * kotlin.math.abs(kotlin.math.sin(t * 4f + i))) * k), u * 0.17f)
                    } else ghost(o.x, o.y - u * 0.5f * k, u * 0.9f, Color(0xFFE8E0FF), Mood.SAD, t)
                }
            }
            drawRect(Brush.verticalGradient(listOf(Color(0x99140830), Color.Transparent), 0f, size.height * 0.2f), size = Size(size.width, size.height * 0.2f))
        }
        spots.forEachIndexed { h, (fx, fy) ->
            if (found[h] == null && !done) Box(
                Modifier.offset((maxWidth.value * fx - 62f).dp, (maxHeight.value * fy - 62f).dp).size(124.dp).clip(CircleShape).testTag("dig-$h").clickable(enabled = !waiting) {
                    waiting = true
                    scope.launch {
                        val r = game.send("dig", h, effects = false)
                        val e = r.events.firstOrNull { it.type == "dig" }
                        if (e != null) {
                            found[h] = e.n; shownAt[h] = time.value
                            if (e.n > 0) { game.burst++; game.sfx(Sfx.CHEST); game.feel(Mood.WIN) } else game.sfx(Sfx.LOSE)
                            done = e.ids.firstOrNull() == 1
                            game.play(r.events.filter { it.type == "cards" })
                        }
                        waiting = false
                    }
                },
            )
            val amount = found[h]
            if (amount != null) T(if (amount > 0) "+${fmt(amount)}" else if (amount == 0L) "Vide" else "Déjà ouvert", 18, if (amount > 0) Gold else Ink, FontWeight.Black, Modifier.offset((maxWidth.value * fx - 60f).dp, (maxHeight.value * fy + 34f).dp).width(120.dp), TextAlign.Center, shadow = true)
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            T("RAID", 28, Ink, FontWeight.Black, shadow = true)
            T("Trésor de ${target.name} · ${look.c.flag} ${villageName(target.village)}", 15, Ink, FontWeight.Bold, shadow = true)
            Row(Modifier.padding(top = 10.dp).clip(CircleShape).background(Color(0xB324105E)).padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(1, Modifier.size(40.dp)); T("  ${fmt(pot)}", 24, Gold, FontWeight.Black); T("  en jeu", 14, Ink, FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            if (done) Card(border = Gold) {
                val total = found.values.filter { it > 0 }.sum()
                T(if (found.values.none { it == 0L }) "Raid parfait !" else "Raid terminé", 22, Gold, FontWeight.Black)
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(0, Modifier.size(34.dp)); T("  +${fmt(total)}", 24, Ink, FontWeight.Black) }
                GButton(game, "Continuer", Modifier.fillMaxWidth(), size = 18) { if (game.state.raids > 0) { game.screen = Screen.HOME; game.begin("raid") } else game.screen = Screen.HOME }
            } else T("Creusez 3 des 4 monticules : l'un d'eux est vide · reste ${Rules.RAID_DIGS - found.size}", 15, Ink, FontWeight.Black, Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xB324105E)).padding(horizontal = 14.dp, vertical = 12.dp), TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------- Cartes

@Composable
fun CardsScreen(game: Game) {
    val s = game.state
    var album by remember { mutableIntStateOf(0) }
    var sel by remember { mutableIntStateOf(-1) }
    Box(Modifier.fillMaxSize()) {
        Sheet(game, "Collection") {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(9, Modifier.size(44.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        T("${s.cards.size} cartes sur ${CARDS.size}", 18, Ink, FontWeight.Black)
                        Bar(s.cards.size / CARDS.size.toFloat(), Gold, Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            }
            // Familiers : un seul accompagne le fantôme et donne son bonus.
            val time = rememberTime()
            T("Familiers", 20, Ink, FontWeight.Black, shadow = true)
            PETS.forEachIndexed { i, p ->
                val open = s.petUnlocked(i)
                Card(Modifier.testTag("pet-$i").clickable(enabled = open) { game.act("pet", if (s.pet == i) -1 else i) }, if (s.pet == i) Gold else Color.Transparent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(76.dp).clip(RoundedCornerShape(20.dp)).background(Brush.radialGradient(listOf(Color(0xFFB9A2FF), Color(0xFF4A22B8))))) {
                            pet(i, size.width / 2, size.height * 0.5f, size.width * 0.3f, time.value)
                            if (!open) drawRect(Color(0xCC301580))
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            T(p.name, 17, Ink, FontWeight.Black)
                            T(p.text, 14, if (open) Gold else Dim, FontWeight.Bold)
                            if (!open) T(p.unlock, 13, Dim)
                        }
                        T(if (s.pet == i) "ACTIF" else if (open) "Choisir" else "🔒", 14, if (s.pet == i) Gold else Ink, FontWeight.Black)
                    }
                }
            }
            T("Albums", 20, Ink, FontWeight.Black, shadow = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ALBUMS.forEachIndexed { i, name ->
                    val n = (0 until ALBUM_SIZE).count { (s.cards[i * ALBUM_SIZE + it] ?: 0) > 0 }
                    GButton(game, "$name  $n/8", color = if (i == album) Gold else Color(0xFF8C6BF0), size = 14) { album = i }
                }
            }
            for (row in listOf(0..2, 3..5, 6..7)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (k in row) { val id = album * ALBUM_SIZE + k; CardView(id, s.cards[id] ?: 0, Modifier.weight(1f).testTag("card-$id")) { game.sfx(Sfx.CLICK); sel = id } }
                if (row.count() == 2) Spacer(Modifier.weight(1f))
            }
            val have = (0 until ALBUM_SIZE).count { (s.cards[album * ALBUM_SIZE + it] ?: 0) > 0 }
            Card(border = if (have == ALBUM_SIZE && album !in s.albums) Gold else Color.Transparent) {
                T("Album ${ALBUMS[album]}", 18, Ink, FontWeight.Black)
                Bar(have / ALBUM_SIZE.toFloat(), Gold, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                T("Récompense : ${30 + 10 * album} tours et ${fmt(Rules.unit(s.village) * 100)} pièces", 14, Dim, FontWeight.Bold)
                when {
                    album in s.albums -> T("Récompense déjà récupérée", 14, Gold, FontWeight.Black)
                    have == ALBUM_SIZE -> GButton(game, "Récupérer la récompense", Modifier.fillMaxWidth().padding(top = 8.dp), size = 16) { game.act("album", album) }
                    else -> T("Encore ${ALBUM_SIZE - have} carte(s) à trouver.", 14, Dim)
                }
            }
            GButton(game, "Ouvrir des coffres", Modifier.fillMaxWidth(), Electric, size = 16) { game.screen = Screen.SHOP }
        }
        // Carte en grand : présentation de vitrine, avec ses actions.
        if (sel >= 0) {
            val n = s.cards[sel] ?: 0
            val r = rarityOf(sel)
            val time = rememberTime()
            Box(Modifier.fillMaxSize().background(Color(0xF2301580)).drawBehind {
                if (n > 0 && r >= 3) { val o = Offset(size.width / 2, size.height * 0.36f); for (i in 0 until 16) rotate(time.value * 16f + i * 22.5f, o) { drawRect(Brush.verticalGradient(listOf(RARITY[r].copy(alpha = 0.4f), Color.Transparent), o.y - size.height * 0.8f, o.y), Offset(o.x - size.width * 0.05f, o.y - size.height * 0.8f), Size(size.width * 0.1f, size.height * 0.8f)) } }
            }.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { sel = -1 }, contentAlignment = Alignment.Center) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardView(sel, n, Modifier.width(230.dp))
                    T(RARITIES[r], 20, if (r == 0) Ink else RARITY[r], FontWeight.Black, shadow = true)
                    T(if (n > 0) "Possédée ×$n" else "À trouver dans les coffres", 15, Ink, FontWeight.Bold)
                    if (n > 1) {
                        GButton(game, "Échanger ${n - 1} double(s) contre ${fmt(Rules.unit(s.village) * Rules.SELL[r] * (n - 1))}", Modifier.fillMaxWidth(), size = 15) { game.act("sell", sel) }
                        val pals = game.friends.filter { it.id != s.id }
                        if (game.online && pals.isNotEmpty()) {
                            T("Offrir un double à un ami", 14, Dim, FontWeight.Bold)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { pals.forEach { f -> GButton(game, f.name, color = Electric, size = 14) { game.sendCard(f.id, sel) } } }
                        }
                    }
                    GButton(game, "Fermer", Modifier.width(180.dp), Color(0xFF8C6BF0), size = 16) { sel = -1 }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Boutique

@Composable
fun ShopScreen(game: Game) = Sheet(game, "Boutique") {
    val s = game.state
    val wait = s.freeChestAt - game.now
    Card(border = if (wait <= 0) Gold else Color.Transparent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(58.dp)) { chest(Offset(size.width / 2, size.height * 0.55f), size.width * 0.42f, 0) }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                T("Coffre gratuit", 16, Ink, FontWeight.Bold)
                T("${game.event.freeCards} cartes, toutes les 4 heures", 12, Dim)
            }
            GButton(game, if (wait <= 0) "Ouvrir" else "%d:%02d:%02d".format(wait / 3_600_000, wait / 60_000 % 60, wait / 1000 % 60), Modifier.width(104.dp), enabled = wait <= 0) { game.act("freeChest") }
        }
    }
    CHESTS.forEachIndexed { i, c ->
        val price = Rules.unit(s.village) * c.price
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(58.dp)) { chest(Offset(size.width / 2, size.height * 0.55f), size.width * 0.42f, i) }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    T(c.name, 16, Ink, FontWeight.Bold)
                    T("${c.cards} cartes · légendaire ${c.odds[3] / 10f} % · mythique ${c.odds[4] / 10f} %", 11, Dim)
                }
                GButton(game, fmt(price), Modifier.width(104.dp), enabled = s.coins >= price) { game.act("chest", i) }
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
    val ev = game.event
    val scope = rememberCoroutineScope()
    Card(border = Gold) {
        T("ÉVÉNEMENT DU JOUR", 10, Gold, FontWeight.Black)
        T(ev.name, 18, Ink, FontWeight.Black); T(ev.text, 13, Dim)
    }
    Card(border = Electric) {
        val c = Rules.challenge(game.now)
        val p = if (s.challengeId == Rules.challengeId(game.now)) s.chal[c.key] ?: 0 else 0
        val claimed = s.challengeClaimed && s.challengeId == Rules.challengeId(game.now)
        val left = Rules.challengeEnd(game.now) - game.now
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("DÉFI ÉCLAIR", 13, Color(0xFFBFF0FF), FontWeight.Black, Modifier.weight(1f))
            T("encore %d h %02d".format(left / 3_600_000, left / 60_000 % 60), 13, Ink, FontWeight.Bold)
        }
        T(c.label, 20, Ink, FontWeight.Black)
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Bar(p.toFloat() / c.goal, Electric, Modifier.weight(1f)); T("  ${minOf(p, c.goal)}/${c.goal}", 14, Ink, FontWeight.Bold) }
        T("Récompense : 60 tours, ${fmt(Rules.unit(s.village) * 150)} pièces et un Coffre d'or", 14, Gold, FontWeight.Bold)
        if (claimed) T("Récompense récupérée. Prochain défi bientôt.", 14, Dim)
        else GButton(game, "Récupérer la récompense", Modifier.testTag("challenge").fillMaxWidth().padding(top = 8.dp), Electric, p >= c.goal, 16) { game.act("challenge") }
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
            val shown = p
            val goal = q.goal
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
            Card(border = if (r.id == s.id) Gold else Color.Transparent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("${i + 1}", 16, Gold, FontWeight.Black, Modifier.width(30.dp))
                    Canvas(Modifier.size(34.dp).clip(CircleShape).background(Violet)) { ghost(size.width / 2, size.height * 0.52f, size.width * 0.6f, AVATARS[r.avatar % AVATARS.size], Mood.IDLE, 0f) }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        T(r.name, 14, Ink, FontWeight.Bold, lines = 1); T("${countryOf(r.village).flag} ${villageName(r.village)} · ${r.stars} ★", 11, Dim)
                    }
                    if (!world && r.id != s.id) GButton(game, "Offrir 3 tours", color = Electric, size = 11) { game.gift(r.id) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Réglages

@Composable
fun SettingsScreen(game: Game) = Sheet(game, "Profil") {
    val s = game.state
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GButton(game, "Amis et classement", Modifier.weight(1f), Electric, size = 14) { game.screen = Screen.SOCIAL }
        GButton(game, "Tour du monde", Modifier.weight(1f), Color(0xFF2FB673), size = 14) { game.screen = Screen.MAP }
    }
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
    Card(border = if (game.online) Electric else Color.Transparent) {
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
    T("Ghost Coin 0.4.0", 11, Dim, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
}
