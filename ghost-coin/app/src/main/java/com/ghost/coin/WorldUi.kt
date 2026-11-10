package com.ghost.coin

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghost.coin.core.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// ---------------------------------------------------------------- Carte du monde

/** Contours simplifiés des terres (latitude, longitude), pour donner à la carte sa vraie forme. */
private val LAND = listOf(
    floatArrayOf(37f, 10f, 33f, -8f, 21f, -17f, 15f, -17f, 10f, -15f, 5f, -8f, 4f, 7f, -5f, 12f, -17f, 12f, -29f, 17f, -34f, 19f, -34f, 26f, -26f, 33f, -16f, 40f, -5f, 39f, 2f, 45f, 12f, 51f, 11f, 43f, 15f, 39f, 22f, 37f, 30f, 32f, 31f, 25f, 33f, 12f),
    floatArrayOf(-13f, 49f, -17f, 50f, -25f, 47f, -24f, 44f, -16f, 44f),
    floatArrayOf(36f, -6f, 43f, -9f, 48f, -5f, 51f, 2f, 54f, 8f, 57f, 8f, 58f, 6f, 62f, 5f, 70f, 20f, 71f, 28f, 68f, 44f, 68f, 60f, 52f, 60f, 46f, 48f, 46f, 38f, 46f, 30f, 41f, 29f, 37f, 24f, 40f, 19f, 44f, 13f, 38f, 16f, 42f, 12f, 44f, 8f, 43f, 3f, 37f, -2f),
    floatArrayOf(50f, -5f, 54f, -3f, 58f, -6f, 58f, -2f, 53f, 1f, 51f, 1f),
    floatArrayOf(52f, -10f, 55f, -8f, 54f, -6f, 52f, -6f),
    floatArrayOf(64f, -24f, 66f, -18f, 65f, -14f, 63f, -19f),
    floatArrayOf(70f, -160f, 71f, -125f, 68f, -95f, 60f, -94f, 55f, -80f, 60f, -65f, 47f, -53f, 44f, -66f, 35f, -76f, 25f, -80f, 30f, -90f, 26f, -97f, 18f, -95f, 21f, -87f, 15f, -83f, 9f, -79f, 8f, -83f, 15f, -93f, 22f, -106f, 32f, -117f, 48f, -125f, 58f, -136f, 60f, -147f, 55f, -163f),
    floatArrayOf(12f, -72f, 10f, -62f, 5f, -52f, -5f, -35f, -13f, -38f, -23f, -42f, -35f, -56f, -41f, -63f, -52f, -69f, -55f, -70f, -46f, -75f, -30f, -72f, -18f, -71f, -5f, -81f, 1f, -79f, 8f, -77f),
    floatArrayOf(23f, -84f, 22f, -78f, 20f, -74f, 20f, -77f, 22f, -82f),
    floatArrayOf(20f, -74f, 20f, -69f, 18f, -68f, 18f, -74f),
    floatArrayOf(41f, 29f, 46f, 38f, 46f, 48f, 52f, 60f, 68f, 60f, 73f, 80f, 77f, 105f, 72f, 130f, 70f, 160f, 66f, 180f, 60f, 165f, 52f, 157f, 60f, 143f, 53f, 141f, 43f, 132f, 39f, 128f, 35f, 129f, 37f, 122f, 30f, 122f, 22f, 114f, 21f, 107f, 9f, 105f, 13f, 100f, 1f, 104f, 7f, 99f, 16f, 95f, 22f, 91f, 16f, 81f, 8f, 77f, 20f, 72f, 25f, 66f, 25f, 57f, 30f, 49f, 24f, 52f, 17f, 55f, 13f, 45f, 21f, 39f, 28f, 35f, 31f, 34f, 36f, 36f, 37f, 28f),
    floatArrayOf(31f, 130f, 34f, 135f, 36f, 141f, 41f, 141f, 45f, 142f, 43f, 140f, 36f, 137f, 34f, 131f),
    floatArrayOf(5f, 95f, -6f, 105f, -8f, 115f, -9f, 125f, -3f, 135f, -9f, 141f, -2f, 141f, 1f, 125f, 4f, 118f, 7f, 117f, 1f, 110f),
    floatArrayOf(18f, 120f, 14f, 124f, 6f, 126f, 9f, 122f, 14f, 120f),
    floatArrayOf(10f, 80f, 6f, 82f, 7f, 80f),
    floatArrayOf(-3f, 141f, -6f, 147f, -10f, 150f, -9f, 143f),
    floatArrayOf(-11f, 132f, -12f, 137f, -11f, 142f, -19f, 147f, -28f, 153f, -37f, 150f, -39f, 146f, -35f, 138f, -32f, 133f, -34f, 123f, -34f, 115f, -22f, 114f, -17f, 123f, -14f, 129f),
    floatArrayOf(-35f, 173f, -38f, 178f, -41f, 175f, -46f, 170f, -44f, 168f, -41f, 172f),
)

@Composable
fun MapScreen(game: Game) = Sheet(game, "Tour du monde") {
    val s = game.state
    val here = s.village % COUNTRIES.size
    var continent by remember { mutableStateOf(countryOf(s.village).continent) }
    var sel by remember { mutableIntStateOf(here) }
    val t = frameTime()
    val lap = s.village / COUNTRIES.size

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Continent.entries.forEachIndexed { i, c ->
            val open = lap > 0 || here >= CONTINENT_START[i]
            val done = (here - CONTINENT_START[i]).coerceIn(0, CONTINENT_START[i + 1] - CONTINENT_START[i])
            GButton(game, (if (open) "" else "🔒 ") + "${c.label} ${if (lap > 0) CONTINENT_START[i + 1] - CONTINENT_START[i] else done}/${CONTINENT_START[i + 1] - CONTINENT_START[i]}", color = if (c == continent) Gold else if (open) Color(0xFF8C6BF0) else Color(0xFF6E5BA8), size = 14) { continent = c }
        }
    }

    // Cadre de la carte : projection équirectangulaire, zoom animé sur le continent choisi.
    val list = COUNTRIES.withIndex().filter { it.value.continent == continent }
    fun lon(c: Country) = if (continent == Continent.OCEANIE && c.lon < 0) c.lon + 360 else c.lon // l'Océanie enjambe l'antiméridien
    val west by animateFloatAsState(list.minOf { lon(it.value) } - 8f, tween(700), label = "w")
    val east by animateFloatAsState(list.maxOf { lon(it.value) } + 8f, tween(700), label = "e")
    val north by animateFloatAsState(list.maxOf { it.value.lat } + 7f, tween(700), label = "n")
    val south by animateFloatAsState(list.minOf { it.value.lat } - 7f, tween(700), label = "s")
    val shape = RoundedCornerShape(22.dp)
    Canvas(
        Modifier.fillMaxWidth().height(360.dp).shadow(12.dp, shape).clip(shape)
            .pointerInput(continent) {
                detectTapGestures { tap ->
                    val best = list.minByOrNull { (_, c) -> hypot(tap.x - (lon(c) - west) / (east - west) * size.width, tap.y - (north - c.lat) / (north - south) * size.height) }
                    if (best != null) { sel = best.index; game.sfx(Sfx.CLICK) }
                }
            },
    ) {
        clipRect {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF4FC3F7), Color(0xFF2E8FE8), Color(0xFF2468D0))))
            // Terres en relief : une ombre décalée puis la terre, éclaircie vers le nord.
            for (pass in 0..1) LAND.forEach { poly ->
                val path = androidx.compose.ui.graphics.Path()
                for (i in poly.indices step 2) { val x = (poly[i + 1] - west) / (east - west) * size.width; val y = (north - poly[i]) / (north - south) * size.height + (if (pass == 0) 7f else 0f); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                path.close()
                if (pass == 0) drawPath(path, Color(0xFF1A4FA8)) else { drawPath(path, Brush.verticalGradient(listOf(Color(0xFFB8E986), Color(0xFF6CC56A), Color(0xFF4FA85A)))); drawPath(path, Color(0xFFEFFFD6), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f)) }
            }
            for (g in -180..540 step 20) { val x = (g - west) / (east - west) * size.width; drawLine(Color(0x33FFFFFF), Offset(x, 0f), Offset(x, size.height), 1f) }
            for (g in -80..80 step 20) { val y = (north - g) / (north - south) * size.height; drawLine(Color(0x33FFFFFF), Offset(0f, y), Offset(size.width, y), 1f) }
            fun at(c: Country) = Offset((lon(c) - west) / (east - west) * size.width, (north - c.lat) / (north - south) * size.height)
            // Terres : un halo par pays dessine la silhouette du continent.
            if (continent == Continent.OCEANIE || continent == Continent.AMERIQUE || continent == Continent.AFRIQUE) list.forEach { (_, c) -> if (c.biome == 'c') { drawCircle(Color(0xFFFFF2B0), size.width * 0.022f, at(c)); drawCircle(Color(0xFF6CC56A), size.width * 0.015f, at(c)) } }
            // Itinéraire du voyage, dans l'ordre des pays.
            list.zipWithNext().forEach { (a, b) -> if (lap > 0 || b.index <= here) { drawLine(Color(0xFF7A4A00), at(a.value), at(b.value), 6f, androidx.compose.ui.graphics.StrokeCap.Round); drawLine(Gold, at(a.value), at(b.value), 3.5f, androidx.compose.ui.graphics.StrokeCap.Round) } }
            list.forEach { (i, c) ->
                val o = at(c)
                val done = lap > 0 || i < here
                val r = size.width * 0.02f
                if (i == here) drawCircle(Color.White.copy(alpha = 0.5f + 0.4f * abs(sin(t * 3f))), r * (2.4f + 0.8f * abs(sin(t * 3f))), o, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                if (i == sel) drawCircle(Color.White, r * 1.9f, o)
                drawCircle(Color(0x55000000), r * 1.25f, Offset(o.x, o.y + 3f))
                drawCircle(if (done) GoldDark else if (i == here) Color(0xFF1C4FD0) else Color(0xFF5A6480), r * 1.3f, o)
                drawCircle(if (done) Gold else if (i == here) Electric else Color(0xFFB9C2D6), r, o)
                if (done) star(o, r * 0.75f, Color(0xFF7A4A00))
            }
            val c = COUNTRIES[here]
            if (c.continent == continent) ghost(at(c).x, at(c).y - size.width * 0.06f, size.width * 0.06f, AVATARS[s.avatar], Mood.IDLE, t)
        }
    }

    // Présentation du pays choisi.
    val c = COUNTRIES[sel]
    val done = lap > 0 || sel < here
    val current = sel == here
    Card(border = if (done) Gold else if (current) Electric else Color.Transparent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(c.flag, 40)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                T(c.name, 20, Ink, FontWeight.Black)
                T("${c.continent.label} · étape ${sel + 1}/${COUNTRIES.size}", 12, Dim)
            }
            T(if (done) "Terminé" else if (current) "En cours" else "Verrouillé", 12, if (done) Gold else if (current) Electric else Dim, FontWeight.Black)
        }
        val preview = remember(sel, done, current) { if (current) s.levels else List(8) { 3 } }
        Box(Modifier.padding(vertical = 10.dp)) {
            Canvas(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(16.dp))) {
                villageScene(sel, preview, if (current) s.damaged else List(8) { false }, t)
                if (!done && !current) drawRect(Color(0x99060312))
            }
            if (!done && !current) T("Aperçu — à débloquer", 12, Ink, FontWeight.Bold, Modifier.align(Alignment.Center).clip(CircleShape).background(Color(0xCC120A26)).padding(horizontal = 12.dp, vertical = 5.dp))
        }
        T("Lieu emblématique : ${c.landmark}", 13, Ink, FontWeight.Bold)
        T("${BIOMES[c.biome]} · ${STYLES[c.style]}", 12, Dim)
        if (current) { Bar(s.levels.sum() / 24f, Gold, Modifier.fillMaxWidth().padding(top = 8.dp)); T("${s.levels.sum()}/24 améliorations", 11, Dim) }
        if (current) GButton(game, "Construire ici", Modifier.fillMaxWidth().padding(top = 8.dp)) { game.screen = Screen.BUILD }
    }

    T("Pays du continent", 15, Gold, FontWeight.Black)
    list.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { (i, k) ->
                val sh = RoundedCornerShape(12.dp)
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(sh).background(if (i == sel) Color(0xFF8C6BF0) else Color(0xFF5330C4))
                        .clickable { sel = i }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(k.flag, 18); T(k.name, 13, if (lap > 0 || i <= here) Ink else Dim, FontWeight.Bold, Modifier.weight(1f).padding(horizontal = 6.dp), lines = 1)
                    T(if (lap > 0 || i < here) "★" else if (i == here) "●" else "🔒", 11, if (i == here) Electric else Gold)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Cinématique de fin de continent : le globe tourne vers la prochaine destination. */
@Composable
fun Cinematic(game: Game) {
    val n = game.cinematic
    if (n < 0) return
    val a = remember(n) { Animatable(0f) }
    LaunchedEffect(n) { game.sfx(Sfx.WIN); a.animateTo(1f, tween(4500)) }
    val next = Continent.entries[(n + 1) % Continent.entries.size]
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFA4A22B8), Color(0xFA24105E)))).clickable(remember { MutableInteractionSource() }, null) { if (a.value > 0.5f) game.cinematic = -1 }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * 0.4f); val r = size.width * 0.3f * (0.6f + 0.4f * a.value)
            repeat(60) { val ang = it * 2.4f; val d = (it * 37 % 100) / 100f; drawCircle(Color.White.copy(alpha = 0.5f * abs(sin(a.value * 8f + it))), 2.5f, Offset(size.width * ((cos(ang) * d + 1f) / 2f), size.height * ((sin(ang) * d + 1f) / 2f))) }
            drawCircle(Brush.radialGradient(listOf(Electric.copy(alpha = 0.5f), Color.Transparent), c, r * 1.6f), r * 1.6f, c)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF7FD8FF), Color(0xFF123A7A)), Offset(c.x - r * 0.3f, c.y - r * 0.3f), r * 1.5f), r, c)
            // Les pays défilent sur le globe pendant qu'il tourne.
            COUNTRIES.forEachIndexed { i, k ->
                val lon = Math.toRadians(k.lon + a.value * 220.0); val lat = Math.toRadians(k.lat.toDouble())
                if (cos(lon) * cos(lat) > 0) drawCircle(if (i < CONTINENT_START[n + 1]) Gold else Color(0xFF58B45E), r * 0.035f, Offset(c.x + (sin(lon) * cos(lat) * r).toFloat(), c.y - (sin(lat) * r).toFloat()))
            }
            ghost(c.x + cos(a.value * 6.28f) * r * 1.3f, c.y + sin(a.value * 6.28f) * r * 0.5f, r * 0.3f, AVATARS[game.state.avatar], Mood.WIN, a.value * 6f)
        }
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
            T("Continent terminé : ${Continent.entries[n].label}", 30, Gold, FontWeight.Black, align = TextAlign.Center)
            T("Récompense de continent : 100 tours, un Coffre d'or et un trésor de pièces", 14, Ink, align = TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp))
            T(if (n == Continent.entries.size - 1) "Tour du monde accompli ! Un nouveau tour commence au Sénégal." else "Cap sur : ${next.label}", 20, Electric, FontWeight.Black, align = TextAlign.Center)
            GButton(game, "Continuer le voyage", Modifier.padding(top = 18.dp, bottom = 40.dp).width(240.dp)) { game.cinematic = -1 }
        }
    }
}

// ---------------------------------------------------------------- GHOST ADMIN

@Composable
private fun In(value: String, label: String, number: Boolean = false, secret: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) = OutlinedTextField(
    value, onChange, modifier, label = { T(label, 11, Dim) }, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 14.sp),
    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false),
    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
)

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    T(label, 13, Ink, modifier = Modifier.weight(1f)); Switch(on, set)
}

/** Éditeur de partie : les mêmes commandes servent pour un joueur du serveur et pour le bac à sable. */
@Composable
private fun Editor(game: Game, s: GameState, run: (AdminCmd) -> Unit) {
    var res by remember { mutableStateOf("coins") }
    var amount by remember { mutableStateOf("100000") }
    var country by remember { mutableStateOf("") }
    val labels = mapOf("coins" to "Pièces", "spins" to "Tours", "shields" to "Boucliers", "attacks" to "Attaques", "raids" to "Raids", "stars" to "Étoiles")
    T("${countryOf(s.village).flag} ${villageName(s.village)} (pays ${s.village + 1}) · ${fmt(s.coins)} pièces · ${s.spins} tours · ${s.shields} boucliers · ${s.attacks} attaques · ${s.raids} raids · ${s.cards.size} cartes", 12, Dim)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Admin.RESOURCES.forEach { r -> GButton(game, labels.getValue(r), color = if (r == res) Gold else Violet, size = 11) { res = r } }
    }
    In(amount, "Quantité", number = true) { amount = it.filter(Char::isDigit).take(15) }
    val n = amount.toLongOrNull() ?: 0
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GButton(game, "Ajouter", Modifier.weight(1f), size = 12) { run(AdminCmd(res, n = n, s = "add")) }
        GButton(game, "Retirer", Modifier.weight(1f), Danger, size = 12) { run(AdminCmd(res, n = -n, s = "add")) }
        GButton(game, "Définir", Modifier.weight(1f), Electric, size = 12) { run(AdminCmd(res, n = n, s = "set")) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        In(country, "Pays : numéro (1-${COUNTRIES.size}) ou nom", modifier = Modifier.weight(1f)) { country = it }
        val idx = country.toIntOrNull()?.minus(1) ?: COUNTRIES.indexOfFirst { country.length >= 3 && it.name.startsWith(country.trim(), true) }
        GButton(game, "Y aller", color = Electric, enabled = idx in COUNTRIES.indices, size = 12) { run(AdminCmd("country", idx)) }
    }
    T("Niveau de tous les bâtiments", 11, Dim)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (l in 0..3) GButton(game, "Niv. $l", Modifier.weight(1f), Violet, size = 12) { run(AdminCmd("levels", l)) } }
    T("Un bâtiment : toucher pour monter d'un niveau, bouton rouge pour l'endommager ou le réparer", 11, Dim)
    for (row in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in listOf(row * 2, row * 2 + 1)) {
            GButton(game, "${BUILDINGS[b]} · ${s.levels[b]}", Modifier.weight(1f), Violet, size = 10) { run(AdminCmd("level", b, ((s.levels[b] + 1) % 4).toLong())) }
            GButton(game, if (s.damaged[b]) "✚" else "✖", Modifier.width(38.dp), Danger, size = 11) { run(AdminCmd("damage", b)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GButton(game, "Toutes les cartes", Modifier.weight(1f), size = 11) { run(AdminCmd("cards", -1, 1)) }
        GButton(game, "Légendaires", Modifier.weight(1f), size = 11) { run(AdminCmd("cards", 3, 1)) }
        GButton(game, "Mythiques", Modifier.weight(1f), size = 11) { run(AdminCmd("cards", 4, 1)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GButton(game, "Missions finies", Modifier.weight(1f), Electric, size = 11) { run(AdminCmd("quests")) }
        GButton(game, "Délais à zéro", Modifier.weight(1f), Electric, size = 11) { run(AdminCmd("timers")) }
        GButton(game, "Bon −50 %", Modifier.weight(1f), Electric, size = 11) { run(AdminCmd("voucher")) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GButton(game, "Tout débloquer", Modifier.weight(1f), size = 12) { run(AdminCmd("unlock")) }
        GButton(game, "Nouveau joueur", Modifier.weight(1f), Danger, size = 12) { run(AdminCmd("reset")) }
    }
}

@Composable
fun AdminScreen(game: Game) = Sheet(game, "Ghost Admin") {
    val logged = game.adminToken.isNotEmpty()
    // Le bac à sable local sans serveur n'existe que dans l'APK de test (debug) ; l'APK publié exige la session serveur.
    var local by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current

    if (!logged && !local) {
        var url by remember { mutableStateOf(game.adminUrl.ifEmpty { game.serverUrl }) }
        var pw by remember { mutableStateOf("") }
        Card(border = Electric) {
            T("Accès réservé au propriétaire du jeu", 15, Ink, FontWeight.Bold)
            T("Le mot de passe est vérifié par le serveur (variable ADMIN_PASSWORD). Il n'est stocké ni dans l'application ni sur ce téléphone. 5 essais par quart d'heure.", 12, Dim)
            In(url, "Adresse du serveur (https://…)") { url = it }
            In(pw, "Mot de passe administrateur", secret = true) { pw = it }
            GButton(game, "Connexion", Modifier.fillMaxWidth().padding(top = 8.dp), Electric, url.startsWith("http") && pw.isNotEmpty()) { game.adminLogin(url, pw); pw = "" }
        }
        if (BuildConfig.DEBUG) Card(border = Danger) {
            T("GHOST SANDBOX hors serveur", 15, Ink, FontWeight.Bold)
            T("Disponible uniquement dans cet APK de test. La partie d'essai est enregistrée à part : elle ne touche ni votre partie solo ni aucun joueur.", 12, Dim)
            GButton(game, "Ouvrir le bac à sable", Modifier.fillMaxWidth().padding(top = 8.dp), Danger) { local = true; tab = 4 }
        }
        return@Sheet
    }

    val tabs = listOf("Tableau", "Joueurs", "Événements", "Console", "Sandbox")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { i, name -> GButton(game, name, color = if (i == tab) Gold else Violet, size = 12) { tab = i } }
    }
    if (!logged && tab != 4) { T("Cette section demande une session administrateur sur un serveur.", 13, Dim); GButton(game, "Se connecter", color = Electric) { local = false }; return@Sheet }

    when (tab) {
        0 -> {
            LaunchedEffect(Unit) { game.admin(Req(op = "dashboard")) }
            game.adminStats.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (k, v) -> Card(Modifier.weight(1f), Electric.copy(alpha = 0.5f)) { T(k.uppercase(), 9, Electric, FontWeight.Black); T(v, 18, Ink, FontWeight.Black) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GButton(game, "Actualiser", Modifier.weight(1f), Electric) { game.adminDo(Req(op = "dashboard")) }
                GButton(game, "Déconnexion", Modifier.weight(1f), Danger) { game.adminLogout() }
            }
            T("Serveur : ${game.adminUrl}. Toutes les actions sont inscrites au journal d'audit.", 11, Dim)
        }
        1 -> {
            LaunchedEffect(Unit) { game.admin(Req(op = "players")) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GButton(game, "+5 comptes de test", Modifier.weight(1f), size = 11) { game.adminDo(Req(op = "mktest", n = 5), "Comptes de test créés") }
                GButton(game, "Supprimer les tests", Modifier.weight(1f), Danger, size = 11) { game.adminDo(Req(op = "rmtests"), "Comptes de test supprimés") }
                GButton(game, "Annuler", Modifier.weight(1f), Electric, size = 11) { game.adminDo(Req(op = "undo"), "Dernière commande annulée") }
            }
            val p = game.adminPlayer
            if (p != null) Card(border = Gold) {
                T((if (p.test) "[TEST] " else "") + "${p.name} · ${p.id}", 16, Ink, FontWeight.Black)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Editor(game, p) { c -> game.adminDo(Req(op = "cmd", to = p.id, cmd = c)) }
                    val bot = game.adminRows.firstOrNull { it.test && it.id != p.id }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GButton(game, "Attaque simulée", Modifier.weight(1f), Danger, bot != null, 11) { game.adminDo(Req(op = "simAttack", to = p.id, s = bot!!.id, i = p.levels.indexOfFirst { it > 0 }.coerceAtLeast(0)), "Attaque de ${bot.name} appliquée") }
                        GButton(game, "Raid simulé", Modifier.weight(1f), Danger, bot != null, 11) { game.adminDo(Req(op = "simRaid", to = p.id, s = bot!!.id, n = p.coins / 10), "Raid de ${bot.name} appliqué") }
                    }
                    if (bot == null) T("Créez des comptes de test pour simuler attaques et raids.", 11, Dim)
                }
            }
            T("${game.adminRows.size} comptes (les plus récemment actifs d'abord)", 12, Dim)
            game.adminRows.forEach { r ->
                Card(border = if (r.id == p?.id) Gold else if (r.test) Danger.copy(alpha = 0.6f) else Color.Transparent, modifier = Modifier.clickable { game.adminDo(Req(op = "player", to = r.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T((if (r.test) "[TEST] " else "") + r.name, 14, Ink, FontWeight.Bold)
                            T("${r.id} · ${countryOf(r.village).flag} ${villageName(r.village)} · ${fmt(r.coins)} pièces", 11, Dim)
                        }
                        T("${r.stars} ★", 13, Gold, FontWeight.Black)
                    }
                }
            }
        }
        2 -> {
            LaunchedEffect(Unit) { game.admin(Req(op = "dashboard")) }
            var name by remember { mutableStateOf("") }
            var text by remember { mutableStateOf("") }
            var coins by remember { mutableStateOf("200") }
            var raid by remember { mutableStateOf("100") }
            var attack by remember { mutableStateOf("100") }
            var cards by remember { mutableStateOf("3") }
            var delay by remember { mutableStateOf("0") }
            var hours by remember { mutableStateOf("24") }
            var editing by remember { mutableStateOf("") }
            Card(border = Gold) {
                T(if (editing.isEmpty()) "Nouvel événement" else "Modifier l'événement", 15, Ink, FontWeight.Bold)
                In(name, "Nom") { name = it.take(40) }
                In(text, "Description affichée aux joueurs") { text = it.take(120) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    In(coins, "Pièces %", true, modifier = Modifier.weight(1f)) { coins = it.filter(Char::isDigit).take(4) }
                    In(raid, "Raids %", true, modifier = Modifier.weight(1f)) { raid = it.filter(Char::isDigit).take(4) }
                    In(attack, "Attaques %", true, modifier = Modifier.weight(1f)) { attack = it.filter(Char::isDigit).take(4) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    In(cards, "Cartes gratuites", true, modifier = Modifier.weight(1f)) { cards = it.filter(Char::isDigit).take(2) }
                    In(delay, "Début dans (min)", true, modifier = Modifier.weight(1f)) { delay = it.filter(Char::isDigit).take(6) }
                    In(hours, "Durée (h)", true, modifier = Modifier.weight(1f)) { hours = it.filter(Char::isDigit).take(4) }
                }
                GButton(game, if ((delay.toLongOrNull() ?: 0) > 0) "Programmer" else "Démarrer maintenant", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = name.isNotBlank() && (hours.toLongOrNull() ?: 0) > 0) {
                    val start = game.now + (delay.toLongOrNull() ?: 0) * 60_000
                    game.adminDo(Req(op = "eventSave", event = LiveEvent(editing, name, text, coins.toIntOrNull() ?: 100, raid.toIntOrNull() ?: 100, attack.toIntOrNull() ?: 100, cards.toIntOrNull() ?: 3, start, start + (hours.toLongOrNull() ?: 1) * 3_600_000)), "Événement enregistré")
                    editing = ""; name = ""; text = ""
                }
            }
            if (game.adminEvents.isEmpty()) T("Aucun événement administrateur : le calendrier automatique s'applique (${Rules.event(game.now).name} aujourd'hui).", 12, Dim)
            game.adminEvents.forEach { e ->
                val status = when { !e.enabled -> "Arrêté"; e.active(game.now) -> "Actif"; game.now < e.start -> "Programmé dans ${(e.start - game.now) / 60_000} min"; else -> "Terminé" }
                Card(border = if (e.active(game.now)) Gold else Color.Transparent) {
                    T(e.name, 15, Ink, FontWeight.Bold); T(e.text, 12, Dim)
                    T("$status · pièces ${e.coins} % · raids ${e.raid} % · attaques ${e.attack} % · ${e.freeCards} cartes", 11, if (e.active(game.now)) Gold else Dim)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GButton(game, "Modifier", Modifier.weight(1f), Electric, size = 11) { editing = e.id; name = e.name; text = e.text; coins = "${e.coins}"; raid = "${e.raid}"; attack = "${e.attack}"; cards = "${e.freeCards}" }
                        GButton(game, "Arrêter", Modifier.weight(1f), Violet, e.enabled, 11) { game.adminDo(Req(op = "eventStop", s = e.id), "Événement arrêté") }
                        GButton(game, "Supprimer", Modifier.weight(1f), Danger, size = 11) { game.adminDo(Req(op = "eventDelete", s = e.id), "Événement supprimé") }
                    }
                }
            }
        }
        3 -> {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GButton(game, "Requêtes", color = Electric, size = 12) { game.adminDo(Req(op = "logs", s = "requests")) }
                GButton(game, "Erreurs", color = Danger, size = 12) { game.adminDo(Req(op = "logs", s = "errors")) }
                GButton(game, "Audit", size = 12) { game.adminDo(Req(op = "logs", s = "audit")) }
                GButton(game, "Diagnostic complet", color = Violet, size = 12) { game.adminDo(Req(op = "export")) }
            }
            GButton(game, "Exporter ces ${game.adminLines.size} lignes", Modifier.fillMaxWidth(), enabled = game.adminLines.isNotEmpty()) {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Ghost Coin — diagnostic").putExtra(Intent.EXTRA_TEXT, game.adminLines.joinToString("\n")), "Exporter le journal"))
            }
            Card {
                if (game.adminLines.isEmpty()) T("Choisissez un journal ci-dessus.", 12, Dim)
                game.adminLines.take(120).forEach { androidx.compose.material3.Text(it, color = Ink, fontSize = 10.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp) }
            }
        }
        else -> {
            val c = game.cheats
            Card(border = Danger) {
                T("GHOST SANDBOX", 16, Ink, FontWeight.Black)
                T("Partie d'essai isolée (fichier séparé, jamais envoyée au serveur). " + if (game.sandbox) "Vous jouez actuellement dans le bac à sable." else "Vous jouez actuellement votre vraie partie.", 12, Dim)
                GButton(game, if (game.sandbox) "Quitter le bac à sable" else "Jouer dans le bac à sable", Modifier.fillMaxWidth().padding(top = 8.dp), if (game.sandbox) Electric else Danger) { game.enterSandbox(!game.sandbox) }
            }
            Card {
                Toggle("Ressources illimitées", c.unlimited) { c.unlimited = it; if (it) game.boxCmd(AdminCmd("coins", n = 0, s = "add")) }
                Toggle("Aucun temps d'attente (coffre, roue, bonus)", c.noWait) { c.noWait = it; if (it) game.boxCmd(AdminCmd("timers")) }
                Toggle("Animations accélérées ×4", c.fast) { c.fast = it }
                Toggle("Perte de réseau simulée", c.netLoss) { c.netLoss = it }
                T("Résultat imposé à la machine", 12, Dim, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    GButton(game, "Hasard", color = if (c.force < 0) Gold else Violet, size = 11) { c.force = -1 }
                    for (i in 0 until 8) Icon(i, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (c.force == i) Gold else Color(0x33FFFFFF)).clickable { c.force = i }.padding(5.dp))
                }
            }
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    T("Partie du bac à sable", 15, Ink, FontWeight.Bold)
                    val s = if (game.sandbox) game.state else game.box.state
                    var tick by remember { mutableIntStateOf(0) }
                    key(tick) { Editor(game, if (game.sandbox) s else game.box.state) { cmd -> game.boxCmd(cmd); tick++ } }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GButton(game, "Subir une attaque", Modifier.weight(1f), Danger, size = 11) { game.boxSuffer(false); tick++ }
                        GButton(game, "Subir un raid", Modifier.weight(1f), Danger, size = 11) { game.boxSuffer(true); tick++ }
                    }
                    GButton(game, "Voir la cinématique de continent", Modifier.fillMaxWidth(), Violet, size = 12) { game.cinematic = countryOf(s.village).continent.ordinal }
                }
            }
            Card {
                var name by remember { mutableStateOf("") }
                var list by remember { mutableStateOf(game.scenarios()) }
                T("Scénarios de test", 15, Ink, FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    In(name, "Nom du scénario", modifier = Modifier.weight(1f)) { name = it.take(24) }
                    GButton(game, "Enregistrer", enabled = name.length >= 2, size = 12) { game.saveScenario(name); list = game.scenarios(); name = "" }
                }
                list.forEach { n ->
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        T(n, 13, Ink, modifier = Modifier.weight(1f))
                        GButton(game, "Charger", color = Electric, size = 11) { game.loadScenario(n) }
                        GButton(game, "✖", color = Danger, size = 11) { game.deleteScenario(n); list = game.scenarios() }
                    }
                }
            }
        }
    }
}
