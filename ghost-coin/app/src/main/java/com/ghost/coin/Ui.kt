package com.ghost.coin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghost.coin.core.*
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun T(text: String, size: Int = 14, color: Color = Ink, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier, align: TextAlign? = null, lines: Int = Int.MAX_VALUE) =
    Text(text, modifier, color, size.sp, fontWeight = weight, textAlign = align, maxLines = lines, overflow = TextOverflow.Ellipsis, lineHeight = (size * 1.25f).sp)

/** Bouton en relief ; s'enfonce au toucher et joue le clic. */
@Composable
fun GButton(game: Game, text: String, modifier: Modifier = Modifier, color: Color = Gold, enabled: Boolean = true, size: Int = 15, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")
    val c = if (enabled) color else Color(0xFF4A3F66)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.scale(s).clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(c, Color.White, 0.35f), c, lerp(c, Color.Black, 0.3f))))
            .border(1.5.dp, Color.White.copy(alpha = 0.35f), shape)
            .clickable(src, null, enabled) { game.sfx(Sfx.CLICK); onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, size, if (enabled) Night else Dim, FontWeight.Black, align = TextAlign.Center) }
}

private fun lerp(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)

@Composable
fun Card(modifier: Modifier = Modifier, border: Color = Color.White.copy(alpha = 0.12f), content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Brush.verticalGradient(listOf(Color(0xFF241548), Panel))).border(1.dp, border, shape).padding(14.dp), content = content)
}

@Composable
fun Pill(icon: Int, text: String, modifier: Modifier = Modifier) = Row(
    modifier.clip(CircleShape).background(Color(0xAA120A26)).border(1.dp, Gold.copy(alpha = 0.5f), CircleShape).padding(start = 5.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(icon, Modifier.size(24.dp)); Spacer(Modifier.width(6.dp)); T(text, 15, Ink, FontWeight.Bold)
}

@Composable
fun Bar(progress: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(500), label = "bar")
    Box(modifier.height(height).clip(CircleShape).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(CircleShape).background(Brush.horizontalGradient(listOf(color, lerp(color, Color.White, 0.4f)))))
    }
}

/** Gabarit des écrans secondaires : fond, barre de titre avec retour et solde. */
@Composable
fun Sheet(game: Game, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B0F3A), Night))).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GButton(game, "‹", Modifier.size(44.dp), Violet, size = 20) { game.screen = Screen.HOME }
            T(title, 20, Ink, FontWeight.Black, Modifier.weight(1f).padding(horizontal = 12.dp), lines = 1)
            Pill(0, fmt(game.state.coins))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content(); Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun CardView(id: Int, count: Int, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val rc = RARITY[rarityOf(id)]
    val shape = RoundedCornerShape(12.dp)
    val t = if (count > 0) frameTime() else 0f
    Column(
        modifier.clip(shape).background(Brush.verticalGradient(listOf(lerp(rc, Night, 0.55f), lerp(rc, Night, 0.85f))))
            .border(if (selected) 3.dp else 1.5.dp, if (selected) Color.White else rc, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color(0x55000000)), contentAlignment = Alignment.Center) {
            if (count > 0) Canvas(Modifier.fillMaxSize()) { cardArt(id, t) } else T("?", 30, Dim, FontWeight.Black)
            if (count > 1) T("×$count", 11, Night, FontWeight.Black, Modifier.align(Alignment.TopEnd).padding(3.dp).clip(CircleShape).background(Gold).padding(horizontal = 5.dp))
        }
        T(if (count > 0) CARDS[id] else "Inconnue", 10, if (count > 0) Ink else Dim, FontWeight.Bold, Modifier.padding(top = 4.dp).height(26.dp), TextAlign.Center, 2)
    }
}

// ---------------------------------------------------------------- Accueil

@Composable
fun HomeScreen(game: Game) {
    val s = game.view
    val t = frameTime()
    val coins by animateFloatAsState(s.coins.toFloat(), tween(700), label = "coins")
    Column(Modifier.fillMaxSize().background(Night).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(CircleShape).clickable { game.screen = Screen.SETTINGS }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(40.dp).clip(CircleShape).background(Violet)) { ghost(size.width / 2, size.height * 0.52f, size.width * 0.6f, AVATARS[s.avatar], Mood.IDLE, 0f) }
                Column(Modifier.padding(start = 8.dp)) {
                    T(s.name, 14, Ink, FontWeight.Bold, lines = 1)
                    T("Monde ${s.village + 1} · ${s.stars} ★", 11, Dim)
                }
            }
            Spacer(Modifier.weight(1f))
            Pill(3, "${s.shields}/${Rules.MAX_SHIELDS}"); Spacer(Modifier.width(8.dp)); Pill(0, fmt(coins.toLong()))
        }

        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp)).border(1.5.dp, Violet.copy(alpha = 0.6f), RoundedCornerShape(24.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                villageScene(s.village, s.levels, s.damaged, t)
                ghost(size.width * 0.5f, size.height * 0.8f, size.width * 0.15f, AVATARS[s.avatar], game.mood, t)
            }
            Column(Modifier.padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x99120A26)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                T(villageName(s.village), 16, Ink, FontWeight.Black)
                val built = s.levels.sum()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Bar(built / 24f, Gold, Modifier.width(110.dp)); T("  $built/24", 11, Dim)
                }
            }
            val ev = Rules.event(game.now)
            Column(
                Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x99120A26))
                    .border(1.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).clickable { game.screen = Screen.QUESTS }.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.End,
            ) { T("ÉVÉNEMENT", 9, Gold, FontWeight.Black); T(ev.name, 12, Ink, FontWeight.Bold) }
            if (s.voucher) T("Construction −50 %", 11, Night, FontWeight.Black, Modifier.align(Alignment.BottomStart).padding(12.dp).clip(CircleShape).background(Gold).padding(horizontal = 10.dp, vertical = 4.dp))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(game, 2, "Construire", 0, Modifier.weight(1f)) { game.screen = Screen.BUILD }
            Tile(game, 5, "Attaque", s.attacks, Modifier.weight(1f)) { if (s.attacks > 0 || s.target != null) game.begin("attack") else game.toast = "Alignez 3 éclairs sur la machine pour gagner une attaque" }
            Tile(game, 6, "Raid", s.raids, Modifier.weight(1f)) { if (s.raids > 0 || s.target != null) game.begin("raid") else game.toast = "Alignez 3 clés sur la machine pour gagner un raid" }
            Tile(game, 9, "Cartes", 0, Modifier.weight(1f)) { game.screen = Screen.CARDS }
        }

        SlotMachine(game, s, t)

        val day = Rules.day(game.now)
        val questDot = s.dailyDay != day || s.wheelDay != day || QUESTS.any { it.id !in s.claimed && s.progress(it) >= it.goal }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(game, 7, "Missions", if (questDot) -1 else 0, Modifier.weight(1f)) { game.screen = Screen.QUESTS }
            Tile(game, 10, "Boutique", if (game.now >= s.freeChestAt) -1 else 0, Modifier.weight(1f)) { game.screen = Screen.SHOP }
            Tile(game, 11, "Social", if (s.log.any { !it.revenged && it.kind != "gift" && it.kind != "card" }) -1 else 0, Modifier.weight(1f)) { game.screen = Screen.SOCIAL }
            Tile(game, 8, "Réglages", 0, Modifier.weight(1f)) { game.screen = Screen.SETTINGS }
        }
    }
}

/** Tuile de menu. `badge` > 0 : compteur ; -1 : pastille « à voir ». */
@Composable
private fun Tile(game: Game, icon: Int, label: String, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Brush.verticalGradient(listOf(Color(0xFF2B1A57), Panel)))
                .border(1.dp, Violet.copy(alpha = 0.5f), shape).clickable { game.sfx(Sfx.CLICK); onClick() }.padding(vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { Icon(icon, Modifier.size(30.dp)); T(label, 11, Ink, FontWeight.Bold, lines = 1) }
        if (badge != 0) T(
            if (badge > 0) "$badge" else "!", 11, Color.White, FontWeight.Black,
            Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Danger).padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun SlotMachine(game: Game, s: GameState, t: Float) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF4A1FA8), Color(0xFF1C0C45)))).border(2.5.dp, Gold, shape).padding(12.dp),
    ) {
        // Guirlande lumineuse : s'emballe pendant la rotation.
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val n = 13
            for (i in 0 until n) {
                val on = ((t * (if (game.busy) 14f else 2.5f)).toInt() + i) % 3 == 0
                val c = Offset(size.width * (i + 0.5f) / n, size.height / 2)
                if (on) drawCircle(Gold.copy(alpha = 0.35f), size.height * 0.75f, c)
                drawCircle(if (on) Color(0xFFFFF2B0) else GoldDark, size.height * 0.32f, c)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until 3) Reel(game, i, Modifier.weight(1f).height(104.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                T("${s.spins}", 26, if (s.spins >= s.bet) Ink else Danger, FontWeight.Black)
                val left = Rules.REGEN_MS - (game.now - s.spinsAt)
                T(if (s.spins >= Rules.MAX_SPINS) "tours · complet" else "tours · +1 dans %d:%02d".format(left / 60000, left / 1000 % 60), 10, Dim, align = TextAlign.Center)
            }
            val pulse by rememberInfiniteTransition(label = "spin").animateFloat(1f, 1.05f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
            GButton(game, "SPIN", Modifier.weight(1.5f).height(58.dp).scale(if (game.busy) 1f else pulse), Gold, !game.busy, 24) { game.spin() }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                GButton(game, "Mise ×${s.bet}", Modifier.fillMaxWidth(), Electric, !game.busy, 13) {
                    game.act("bet", Rules.BETS[(Rules.BETS.indexOf(s.bet) + 1) % Rules.BETS.size])
                }
                T("gains ×${s.bet}", 10, Dim)
            }
        }
    }
}

@Composable
private fun Reel(game: Game, index: Int, modifier: Modifier) {
    val pos = remember { Animatable(game.reels[index].toFloat()) }
    LaunchedEffect(game.spinId) {
        if (game.spinId > 0) {
            val base = ceil(pos.value).toInt()
            val delta = ((game.reels[index] - base) % 8 + 8) % 8
            pos.animateTo((base + 8 * (2 + index) + delta).toFloat(), tween(900 + 350 * index, easing = LinearOutSlowInEasing))
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Canvas(modifier.clip(shape).background(Brush.verticalGradient(listOf(Color(0xFF0D0620), Color(0xFF2A1757), Color(0xFF0D0620)))).border(1.5.dp, Gold.copy(alpha = 0.7f), shape)) {
        clipRect {
            val cell = size.height * 0.78f
            val p = pos.value
            val j0 = floor(p).toInt()
            for (j in j0 - 1..j0 + 2) symbol(((j % 8) + 8) % 8, Offset(size.width / 2, size.height / 2 + (p - j) * cell), minOf(size.width, size.height) * 0.36f)
            drawRect(Brush.verticalGradient(listOf(Color(0xCC0D0620), Color.Transparent, Color.Transparent, Color(0xCC0D0620))))
        }
    }
}

// ---------------------------------------------------------------- Calques communs

@Composable
fun Overlays(game: Game) {
    // Gerbe de pièces.
    val a = remember { Animatable(1f) }
    LaunchedEffect(game.burst) { if (game.burst > 0) { a.snapTo(0f); a.animateTo(1f, tween(1200, easing = LinearEasing)) } }
    if (a.value < 1f) Canvas(Modifier.fillMaxSize()) {
        val rnd = Random(game.burst)
        val k = a.value
        repeat(26) {
            val ang = rnd.nextFloat() * 6.283f; val sp = size.width * (0.25f + rnd.nextFloat() * 0.6f)
            val o = Offset(size.width / 2 + cos(ang) * sp * k, size.height * 0.6f + sin(ang) * sp * k - size.height * 0.25f * k + size.height * 0.6f * k * k)
            if (k < 0.95f) symbol(0, o, size.width * 0.028f * (1f - k * 0.3f))
        }
    }

    // Annonces successives (gains, attaques subies…).
    val b = game.banners.firstOrNull()
    if (b != null) key(b) {
        val sc = remember { Animatable(0.3f) }
        LaunchedEffect(Unit) { sc.animateTo(1f, spring(Spring.DampingRatioMediumBouncy)); delay(950); game.banners.remove(b) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                b.text, Modifier.padding(24.dp).scale(sc.value), b.color, 34.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 40.sp,
                style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 6f), 18f)),
            )
        }
    }

    // Cartes obtenues.
    val cards = game.reveal
    if (cards != null) Box(Modifier.fillMaxSize().background(Color(0xE60A0616)).clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T("Cartes obtenues", 24, Gold, FontWeight.Black)
            cards.take(16).chunked(4).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { i, id ->
                        val sc = remember(cards) { Animatable(0f) }
                        LaunchedEffect(cards) { delay((r * 4 + i) * 110L); sc.animateTo(1f, spring(Spring.DampingRatioMediumBouncy)) }
                        Column(Modifier.width(76.dp).scale(sc.value), horizontalAlignment = Alignment.CenterHorizontally) {
                            CardView(id, 1)
                            T(RARITIES[rarityOf(id)], 9, RARITY[rarityOf(id)], FontWeight.Bold)
                        }
                    }
                }
            }
            GButton(game, "Super !", Modifier.padding(top = 8.dp).width(180.dp)) { game.reveal = null }
        }
    }

    // Messages brefs.
    val toast = game.toast
    LaunchedEffect(toast) { if (toast != null) { delay(3200); if (game.toast == toast) game.toast = null } }
    AnimatedVisibility(toast != null, Modifier.fillMaxSize(), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(20.dp), contentAlignment = Alignment.BottomCenter) {
            T(toast.orEmpty(), 13, Ink, FontWeight.Bold, Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xF02B1A57)).border(1.dp, Violet, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp), TextAlign.Center)
        }
    }
}

@Composable
fun GhostCoinApp(game: Game) {
    Box(Modifier.fillMaxSize().background(Night)) {
        AnimatedContent(game.screen, transitionSpec = { (fadeIn(tween(260)) + scaleIn(tween(260), 0.94f)) togetherWith fadeOut(tween(160)) }, label = "screen") { sc ->
            when (sc) {
                Screen.HOME -> HomeScreen(game)
                Screen.BUILD -> BuildScreen(game)
                Screen.ATTACK -> AttackScreen(game)
                Screen.RAID -> RaidScreen(game)
                Screen.CARDS -> CardsScreen(game)
                Screen.QUESTS -> QuestsScreen(game)
                Screen.SHOP -> ShopScreen(game)
                Screen.SOCIAL -> SocialScreen(game)
                Screen.SETTINGS -> SettingsScreen(game)
            }
        }
        Overlays(game)
    }
}
