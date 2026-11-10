package com.ghost.coin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghost.coin.core.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

fun lerp(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)
fun levelOf(s: GameState) = 1 + s.stars / 6
private val Glass = Color(0x8C1B0B4A)
private val TextShadow = TextStyle(shadow = Shadow(Color(0xAA000000), Offset(0f, 2f), 6f))

/** Texte du jeu. Aucune taille sous 12 sp. */
@Composable
fun T(text: String, size: Int = 14, color: Color = Ink, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier, align: TextAlign? = null, lines: Int = Int.MAX_VALUE, shadow: Boolean = false) {
    val sz = maxOf(12, size)
    Text(text, modifier, color, sz.sp, fontWeight = weight, textAlign = align, maxLines = lines, overflow = TextOverflow.Ellipsis, lineHeight = (sz * 1.25f).sp, style = if (shadow) TextShadow else TextStyle.Default)
}

/** Bouton en volume : une face brillante posée sur un socle plus sombre, qui s'enfonce au toucher. */
@Composable
fun GButton(game: Game, text: String, modifier: Modifier = Modifier, color: Color = Gold, enabled: Boolean = true, size: Int = 15, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val down by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = Spring.StiffnessHigh), label = "press")
    val c = if (enabled) color else Color(0xFF7D6BB5)
    val light = c == Gold || c.luminanceApprox() > 0.6f
    Box(
        modifier.heightIn(min = 46.dp)
            .drawBehind {
                val r = CornerRadius(minOf(this.size.height * 0.5f, 26.dp.toPx()))
                val lift = 5.dp.toPx()
                drawRoundRect(lerp(c, Color(0xFF14082E), 0.5f), Offset(0f, lift), Size(this.size.width, this.size.height - lift), r)
                val y = lift * down
                drawRoundRect(Brush.verticalGradient(listOf(lerp(c, Color.White, 0.4f), c, lerp(c, Color.Black, 0.12f)), y, this.size.height - lift + y), Offset(0f, y), Size(this.size.width, this.size.height - lift), r)
                drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(this.size.width * 0.06f, y + this.size.height * 0.08f), Size(this.size.width * 0.88f, this.size.height * 0.26f), CornerRadius(this.size.height * 0.2f))
            }
            .clip(RoundedCornerShape(26.dp)).clickable(src, null, enabled) { game.sfx(Sfx.CLICK); onClick() }
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 13.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, size, if (!enabled) Color(0xFFD9CCFF) else if (light) Color(0xFF3A1A00) else Color.White, FontWeight.Black, align = TextAlign.Center) }
}

private fun Color.luminanceApprox() = 0.3f * red + 0.59f * green + 0.11f * blue

/** Panneau de contenu : dégradé doux, reflet en haut, ombre portée. `border` ne sert qu'à signaler un état. */
@Composable
fun Card(modifier: Modifier = Modifier, border: Color = Color.Transparent, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.fillMaxWidth().shadow(10.dp, shape, ambientColor = Color(0xFF12052E), spotColor = Color(0xFF12052E)).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF7A55EC), Color(0xFF5330C4))))
            .drawBehind { drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), 0f, size.height * 0.35f)) }
            .then(if (border.alpha > 0.3f) Modifier.border(2.5.dp, border, shape) else Modifier).padding(16.dp),
        content = content,
    )
}

@Composable
fun Pill(icon: Int, text: String, modifier: Modifier = Modifier) = Row(
    modifier.clip(CircleShape).background(Glass).padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(icon, Modifier.size(28.dp)); Spacer(Modifier.width(6.dp)); T(text, 16, Ink, FontWeight.Black)
}

@Composable
fun Bar(progress: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "bar")
    Box(modifier.height(height).clip(CircleShape).background(Color(0x66140830))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(CircleShape).background(Brush.verticalGradient(listOf(lerp(color, Color.White, 0.55f), color))))
    }
}

/** Décor commun des écrans secondaires : violet lumineux, halos, titre large et retour bien visible. */
@Composable
fun Backdrop(modifier: Modifier = Modifier) = Canvas(modifier.fillMaxSize()) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF8257F2), Color(0xFF5A2FD0), Color(0xFF34188C))))
    drawCircle(Brush.radialGradient(listOf(Color(0x66FFD86B), Color.Transparent), Offset(size.width * 0.85f, size.height * 0.08f), size.width * 0.7f), size.width * 0.7f, Offset(size.width * 0.85f, size.height * 0.08f))
    drawCircle(Brush.radialGradient(listOf(Color(0x552CC8FF), Color.Transparent), Offset(size.width * 0.05f, size.height * 0.55f), size.width * 0.8f), size.width * 0.8f, Offset(size.width * 0.05f, size.height * 0.55f))
    val rnd = Random(4)
    repeat(26) { star(Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height), 3f + rnd.nextFloat() * 6f, Color.White.copy(alpha = 0.10f + rnd.nextFloat() * 0.14f)) }
}

@Composable
fun Header(game: Game, title: String, modifier: Modifier = Modifier) = Row(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    GButton(game, "‹", Modifier.size(52.dp), Electric, size = 24) { game.screen = Screen.HOME }
    T(title, if (title.length > 10) 20 else 24, Ink, FontWeight.Black, Modifier.weight(1f).padding(horizontal = 12.dp), lines = 1, shadow = true)
    Pill(0, fmt(game.state.coins))
}

@Composable
fun Sheet(game: Game, title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Backdrop()
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Header(game, title)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                content(); Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/**
 * Carte de collection : cadre de rareté en relief, reflet holographique animé à partir d'Épique,
 * rayons tournants pour les Légendaires et Mythiques.
 */
@Composable
fun CardView(id: Int, count: Int, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val rarity = rarityOf(id)
    val rc = RARITY[rarity]
    val owned = count > 0
    val time = rememberTime()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.shadow(if (owned) 8.dp else 2.dp, shape).clip(shape)
            .background(if (owned) Brush.verticalGradient(listOf(lerp(rc, Color.White, 0.55f), rc, lerp(rc, Color(0xFF1A0F33), 0.45f))) else Brush.verticalGradient(listOf(Color(0xFF6E5BA8), Color(0xFF4A3A80))))
            .then(if (selected) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.92f).clip(RoundedCornerShape(11.dp))
                .background(if (owned) Brush.radialGradient(listOf(lerp(rc, Color.White, 0.75f), lerp(rc, Color(0xFF20104A), 0.55f))) else Brush.verticalGradient(listOf(Color(0xFF3A2A70), Color(0xFF2A1C58))))
                .drawWithContent {
                    val t = time.value
                    if (owned && rarity >= 3) for (i in 0 until 10) rotate(t * 22f + i * 36f) { drawRect(Color.White.copy(alpha = 0.16f), Offset(size.width / 2 - size.width * 0.045f, -size.height * 0.4f), Size(size.width * 0.09f, size.height * 0.9f)) }
                    drawContent()
                    if (owned && rarity >= 2) { // reflet holographique qui balaie la carte
                        val x = ((t * 0.35f + id * 0.13f) % 1.6f - 0.3f) * size.width
                        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color(0x55FFFFFF), Color(0x448CF2FF), Color(0x44FF8CF2), Color.Transparent), Offset(x - size.width * 0.35f, 0f), Offset(x + size.width * 0.35f, size.height)))
                    }
                    if (owned && rarity >= 3) for (i in 0 until 4) star(Offset(size.width * (0.15f + i * 0.24f), size.height * (0.14f + 0.7f * abs(sin(t * 1.3f + i * 1.9f)))), size.width * 0.045f * abs(sin(t * 3f + i)), Color.White)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (owned) Canvas(Modifier.fillMaxSize()) { cardArt(id, time.value) } else T("?", 34, Color(0xFF9C8AD6), FontWeight.Black)
            if (count > 1) T("×$count", 12, Color(0xFF3A1A00), FontWeight.Black, Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).background(Gold).padding(horizontal = 7.dp, vertical = 1.dp))
        }
        T(if (owned) CARDS[id] else "À découvrir", 12, if (owned && rarity != 3) Color.White else if (owned) Color(0xFF3A1A00) else Color(0xFFCFC2F5), FontWeight.Black, Modifier.padding(top = 5.dp).height(32.dp), TextAlign.Center, 2)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { repeat(rarity + 1) { Canvas(Modifier.size(9.dp)) { star(Offset(size.width / 2, size.height / 2), size.width / 2, if (owned) Color.White else Color(0x66FFFFFF)) } } }
    }
}

// ---------------------------------------------------------------- Écran principal

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(game: Game) {
    val s = game.view
    val time = rememberTime()
    val c = countryOf(s.village)
    val coins by animateFloatAsState(s.coins.toFloat(), tween(700), label = "coins")
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(Deep)) {
        // Le village occupe tout l'écran derrière l'interface ; le pupitre de la machine le recouvre en bas.
        Canvas(Modifier.fillMaxSize().padding(bottom = 264.dp + nav)) {
            val t = time.value
            villageScene(s.village, s.levels, s.damaged, t)
            ghost(size.width * 0.5f, size.height * 0.84f, size.width * 0.17f, AVATARS[s.avatar], game.mood, t)
            drawRect(Brush.verticalGradient(listOf(Color(0x66140830), Color.Transparent), 0f, size.height * 0.2f), size = Size(size.width, size.height * 0.2f))
        }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Médaillon du joueur : toucher pour le profil, appui long pour Ghost Admin.
                Row(Modifier.clip(CircleShape).combinedClickable(onLongClick = { game.screen = Screen.ADMIN }) { game.screen = Screen.SETTINGS }, verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Canvas(Modifier.size(60.dp)) {
                            val o = Offset(size.width / 2, size.height / 2)
                            drawCircle(Brush.linearGradient(listOf(Color(0xFFFFF2B0), Gold, GoldDark)), size.width / 2, o)
                            drawCircle(Brush.radialGradient(listOf(Color(0xFF9B74FF), Color(0xFF4A22B8)), o, size.width / 2), size.width * 0.43f, o)
                            ghost(o.x, o.y + size.height * 0.03f, size.width * 0.52f, AVATARS[s.avatar], Mood.IDLE, time.value)
                        }
                        T("${levelOf(s)}", 12, Color(0xFF3A1A00), FontWeight.Black, Modifier.clip(CircleShape).background(Gold).padding(horizontal = 7.dp, vertical = 1.dp))
                    }
                    Column(Modifier.padding(start = 10.dp, end = 10.dp)) {
                        T(s.name, 17, Ink, FontWeight.Black, lines = 1, shadow = true)
                        T("Niveau ${levelOf(s)}", 13, Gold, FontWeight.Bold, shadow = true)
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(0, fmt(coins.toLong()))
                    Pill(3, "${s.shields}/${Rules.MAX_SHIELDS}")
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Glass).clickable { game.sfx(Sfx.CLICK); game.screen = Screen.MAP }.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 200.dp)) {
                    T("${c.flag}  ${villageName(s.village)}", 17, Ink, FontWeight.Black, lines = 1)
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Bar(s.levels.sum() / 24f, Gold, Modifier.width(120.dp)); T("  ${s.levels.sum()}/24", 12, Ink, FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(Color(0xFFFFE08A), Gold))).clickable { game.sfx(Sfx.CLICK); game.screen = Screen.QUESTS }.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { T("ÉVÉNEMENT", 12, Color(0xFF7A4A00), FontWeight.Black); T(game.event.name, 13, Color(0xFF3A1A00), FontWeight.Black, lines = 1) }
            }

            // Actions de jeu posées sur le village, à portée de pouce.
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
                Column(Modifier.align(Alignment.CenterEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundAction(game, 5, "Attaque", s.attacks, Color(0xFF2C8BFF)) { if (s.attacks > 0 || s.target != null) game.begin("attack") else game.toast = "Alignez 3 éclairs sur la machine pour gagner une attaque" }
                    RoundAction(game, 6, "Raid", s.raids, Color(0xFFFF8A2C)) { if (s.raids > 0 || s.target != null) game.begin("raid") else game.toast = "Alignez 3 clés sur la machine pour gagner un raid" }
                }
                Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundAction(game, 12, "Monde", 0, Color(0xFF2FB673)) { game.screen = Screen.MAP }
                    if (game.sandbox) T("SANDBOX", 12, Color.White, FontWeight.Black, Modifier.clip(CircleShape).background(Danger).clickable { game.screen = Screen.ADMIN }.padding(horizontal = 10.dp, vertical = 5.dp))
                }
                if (s.voucher) T("Prochaine construction −50 %", 13, Color(0xFF3A1A00), FontWeight.Black, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).clip(CircleShape).background(Gold).padding(horizontal = 14.dp, vertical = 5.dp))
            }

            SlotCabinet(game, s, time)

            val day = Rules.day(game.now)
            val questDot = s.dailyDay != day || s.wheelDay != day || QUESTS.any { it.id !in s.claimed && s.progress(it) >= it.goal }
            Row(Modifier.fillMaxWidth().background(Color(0xFF24105E)).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
                NavItem(game, 2, "Construire", s.damaged.any { it }) { game.screen = Screen.BUILD }
                NavItem(game, 9, "Collection", false) { game.screen = Screen.CARDS }
                NavItem(game, 7, "Missions", questDot) { game.screen = Screen.QUESTS }
                NavItem(game, 10, "Boutique", game.now >= s.freeChestAt) { game.screen = Screen.SHOP }
                NavItem(game, 4, "Profil", s.log.any { !it.revenged && (it.kind == "attack" || it.kind == "raid") }) { game.screen = Screen.SETTINGS }
            }
        }
    }
}

@Composable
private fun RoundAction(game: Game, icon: Int, label: String, badge: Int, color: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.9f else 1f, label = "ra")
    Column(Modifier.scale(sc).clickable(src, null) { game.sfx(Sfx.CLICK); onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Canvas(Modifier.size(62.dp)) {
                val o = Offset(size.width / 2, size.height / 2)
                drawCircle(Color(0x66000000), size.width * 0.48f, Offset(o.x, o.y + 5f))
                drawCircle(Color.White, size.width * 0.5f, o)
                drawCircle(Brush.verticalGradient(listOf(lerp(color, Color.White, 0.45f), color, lerp(color, Color.Black, 0.25f))), size.width * 0.45f, o)
                drawArc(Color.White.copy(alpha = 0.45f), 200f, 140f, false, Offset(size.width * 0.14f, size.height * 0.1f), Size(size.width * 0.72f, size.height * 0.5f), style = Stroke(5f, cap = StrokeCap.Round))
                symbol(icon, o, size.width * 0.3f)
            }
            if (badge > 0) T("$badge", 13, Color.White, FontWeight.Black, Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Danger).padding(horizontal = 8.dp, vertical = 1.dp))
        }
        T(label, 13, Ink, FontWeight.Black, shadow = true)
    }
}

@Composable
private fun RowScope.NavItem(game: Game, icon: Int, label: String, dot: Boolean, onClick: () -> Unit) {
    Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { game.sfx(Sfx.CLICK); onClick() }.padding(top = 6.dp, bottom = 6.dp), contentAlignment = Alignment.TopCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, Modifier.size(34.dp)); T(label, 12, Ink, FontWeight.Bold, lines = 1) }
        if (dot) Box(Modifier.padding(start = 30.dp).size(12.dp).clip(CircleShape).background(Danger).border(2.dp, Color.White, CircleShape))
    }
}

/** Pupitre de la machine : fronton doré bombé, trois rouleaux, grand bouton SPIN rond. */
@Composable
private fun SlotCabinet(game: Game, s: GameState, time: State<Float>) {
    Box(Modifier.fillMaxWidth().height(246.dp).drawBehind {
        val top = 26.dp.toPx()
        // Fronton : arc doré puis corps violet, avec guirlande d'ampoules.
        val rim = androidx.compose.ui.graphics.Path().apply { moveTo(0f, size.height); lineTo(0f, top); quadraticTo(size.width / 2, -top * 0.9f, size.width, top); lineTo(size.width, size.height); close() }
        drawPath(rim, Brush.verticalGradient(listOf(Color(0xFFFFF2B0), Gold, GoldDark), 0f, top * 2))
        val body = androidx.compose.ui.graphics.Path().apply { val d = 7.dp.toPx(); moveTo(0f, size.height); lineTo(0f, top + d); quadraticTo(size.width / 2, -top * 0.9f + d * 2, size.width, top + d); lineTo(size.width, size.height); close() }
        drawPath(body, Brush.verticalGradient(listOf(Color(0xFF7B4DF0), Color(0xFF4A22B8), Color(0xFF24105E))))
        val t = time.value
        for (i in 0 until 15) {
            val f = (i + 0.5f) / 15f
            val o = Offset(size.width * f, top + 16.dp.toPx() - 4f * f * (1 - f) * top * 0.95f)
            val on = ((t * (if (game.busy) 14f else 2.5f)).toInt() + i) % 3 == 0
            if (on) drawCircle(Color(0x66FFF2B0), 9.dp.toPx(), o)
            drawCircle(if (on) Color(0xFFFFF8D6) else Color(0xFFB8801A), 4.dp.toPx(), o)
        }
    }) {
        Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 8.dp)) {
            // Fenêtre des rouleaux, encadrée d'or.
            Row(
                Modifier.fillMaxWidth().height(92.dp).shadow(8.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFFFFF2B0), Gold, GoldDark))).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) { for (i in 0 until 3) Reel(game, i, Modifier.weight(1f).fillMaxHeight()) }
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    T("TOURS", 12, Dim, FontWeight.Black)
                    T("${s.spins}", 30, if (s.spins >= s.bet) Ink else Danger, FontWeight.Black, shadow = true)
                    Bar(s.spins / Rules.MAX_SPINS.toFloat(), Electric, Modifier.width(84.dp), 8.dp)
                    val left = Rules.REGEN_MS - (game.now - s.spinsAt)
                    T(if (s.spins >= Rules.MAX_SPINS) "complet" else "+1 dans %d:%02d".format(left / 60000, left / 1000 % 60), 12, Dim, align = TextAlign.Center)
                }
                SpinButton(game, time)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    T("MISE", 12, Dim, FontWeight.Black)
                    GButton(game, "×${s.bet}", Modifier.width(84.dp), Electric, !game.busy, 20) { game.act("bet", Rules.BETS[(Rules.BETS.indexOf(s.bet) + 1) % Rules.BETS.size]) }
                    T("gains ×${s.bet}", 12, Dim)
                }
            }
        }
    }
}

@Composable
private fun SpinButton(game: Game, time: State<Float>) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed || game.busy) 0.92f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "spin")
    Box(
        Modifier.size(112.dp).scale(sc).drawBehind {
            val t = time.value
            val o = Offset(size.width / 2, size.height / 2); val r = size.width / 2
            if (!game.busy) { val p = (t * 0.8f) % 1f; drawCircle(Gold.copy(alpha = 0.5f * (1f - p)), r * (0.9f + p * 0.35f), o, style = Stroke(6f)) }
            drawCircle(Color(0xFF14082E).copy(alpha = 0.5f), r * 0.9f, Offset(o.x, o.y + r * 0.1f))
            drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFF8D6), Gold, Color(0xFF9A6410))), r * 0.9f, o)
            drawCircle(Brush.radialGradient(if (game.busy) listOf(Color(0xFFE0C98A), Color(0xFFB08A3A)) else listOf(Color(0xFFFFF2B0), Color(0xFFFFB81F), Color(0xFFE08A00)), Offset(o.x, o.y - r * 0.25f), r), r * 0.76f, o)
            drawArc(Color.White.copy(alpha = 0.6f), 205f, 130f, false, Offset(o.x - r * 0.6f, o.y - r * 0.62f), Size(r * 1.2f, r * 0.9f), style = Stroke(7f, cap = StrokeCap.Round))
            if (game.busy) drawArc(Color.White, t * 500f, 90f, false, Offset(o.x - r * 0.83f, o.y - r * 0.83f), Size(r * 1.66f, r * 1.66f), style = Stroke(8f, cap = StrokeCap.Round))
            else for (i in 0 until 3) star(Offset(o.x + cos(t * 2f + i * 2.1f) * r * 0.95f, o.y + sin(t * 2f + i * 2.1f) * r * 0.95f), r * 0.09f, Color.White)
        }.clip(CircleShape).clickable(src, null, !game.busy) { game.spin() },
        contentAlignment = Alignment.Center,
    ) { Text("SPIN", color = Color(0xFF5A2E00), fontSize = 26.sp, fontWeight = FontWeight.Black, style = TextStyle(shadow = Shadow(Color(0x88FFFFFF), Offset(0f, 2f), 1f))) }
}

@Composable
private fun Reel(game: Game, index: Int, modifier: Modifier) {
    val pos = remember { Animatable(game.reels[index].toFloat()) }
    LaunchedEffect(game.spinId) {
        if (game.spinId > 0) {
            val base = ceil(pos.value).toInt()
            val delta = ((game.reels[index] - base) % 8 + 8) % 8
            pos.animateTo((base + 8 * (2 + index) + delta).toFloat(), tween((900 + 350 * index) / game.speed, easing = LinearOutSlowInEasing))
        }
    }
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF3A1FA0), Color(0xFF8E6BFF), Color(0xFF3A1FA0))))) {
        clipRect {
            val cell = size.height * 0.82f
            val p = pos.value
            val j0 = floor(p).toInt()
            for (j in j0 - 1..j0 + 2) symbol(((j % 8) + 8) % 8, Offset(size.width / 2, size.height / 2 + (p - j) * cell), minOf(size.width, size.height) * 0.37f)
            // Courbure du rouleau : ombres en haut et en bas, reflet au centre.
            drawRect(Brush.verticalGradient(listOf(Color(0xE61B0B4A), Color.Transparent, Color(0x22FFFFFF), Color.Transparent, Color(0xE61B0B4A))))
        }
    }
}

// ---------------------------------------------------------------- Calques communs

@Composable
fun Overlays(game: Game) {
    // Gerbe de pièces.
    val a = remember { Animatable(1f) }
    LaunchedEffect(game.burst) { if (game.burst > 0) { a.snapTo(0f); a.animateTo(1f, tween(1300, easing = LinearEasing)) } }
    if (a.value < 1f) Canvas(Modifier.fillMaxSize()) {
        val rnd = Random(game.burst)
        val k = a.value
        repeat(34) {
            val ang = rnd.nextFloat() * 6.283f; val sp = size.width * (0.25f + rnd.nextFloat() * 0.7f)
            val o = Offset(size.width / 2 + cos(ang) * sp * k, size.height * 0.55f + sin(ang) * sp * k - size.height * 0.28f * k + size.height * 0.65f * k * k)
            if (k < 0.95f) symbol(0, o, size.width * (0.03f + rnd.nextFloat() * 0.02f) * (1f - k * 0.3f))
        }
    }

    // Annonces successives (gains, attaques subies…) sur un ruban.
    val b = game.banners.firstOrNull()
    if (b != null) key(b) {
        val sc = remember { Animatable(0.3f) }
        LaunchedEffect(Unit) { sc.animateTo(1f, spring(Spring.DampingRatioMediumBouncy)); delay(950); game.banners.remove(b) }
        Box(Modifier.fillMaxSize().padding(bottom = 180.dp), contentAlignment = Alignment.Center) {
            Text(
                b.text, Modifier.padding(horizontal = 20.dp).scale(sc.value).shadow(16.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xF24A22B8), Color(0xF224105E)))).border(3.dp, b.color, RoundedCornerShape(26.dp)).padding(horizontal = 26.dp, vertical = 14.dp),
                b.color, 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 36.sp, style = TextShadow,
            )
        }
    }

    // Cartes obtenues : elles jaillissent une à une devant des rayons.
    val cards = game.reveal
    if (cards != null) {
        val time = rememberTime()
        Box(Modifier.fillMaxSize().background(Color(0xF2301580)).drawBehind {
            val o = Offset(size.width / 2, size.height * 0.45f)
            for (i in 0 until 14) rotate(time.value * 14f + i * 25.7f, o) { drawRect(Brush.verticalGradient(listOf(Color(0x33FFE08A), Color.Transparent), o.y - size.height, o.y), Offset(o.x - size.width * 0.05f, o.y - size.height), Size(size.width * 0.1f, size.height)) }
        }.clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                T("Cartes obtenues", 28, Gold, FontWeight.Black, shadow = true)
                cards.take(12).chunked(3).forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { i, id ->
                            val sc = remember(cards) { Animatable(0f) }
                            LaunchedEffect(cards) { delay((r * 3 + i) * 160L); sc.animateTo(1f, spring(Spring.DampingRatioMediumBouncy)) }
                            Column(Modifier.width(100.dp).graphicsLayer { scaleX = sc.value; scaleY = sc.value; rotationZ = (1f - sc.value) * -25f }, horizontalAlignment = Alignment.CenterHorizontally) {
                                CardView(id, 1)
                                T(RARITIES[rarityOf(id)], 13, Color.White, FontWeight.Black, shadow = true)
                            }
                        }
                    }
                }
                GButton(game, "Super !", Modifier.padding(top = 8.dp).width(220.dp), size = 18) { game.reveal = null }
            }
        }
    }

    Cinematic(game)

    // Messages brefs.
    val toast = game.toast
    LaunchedEffect(toast) { if (toast != null) { delay(3200); if (game.toast == toast) game.toast = null } }
    AnimatedVisibility(toast != null, Modifier.fillMaxSize(), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(20.dp), contentAlignment = Alignment.BottomCenter) {
            T(toast.orEmpty(), 14, Ink, FontWeight.Bold, Modifier.shadow(10.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Color(0xF224105E)).padding(horizontal = 18.dp, vertical = 14.dp), TextAlign.Center)
        }
    }
}

@Composable
fun GhostCoinApp(game: Game) {
    Box(Modifier.fillMaxSize().background(Deep)) {
        // Les écrans secondaires montent depuis le bas ; le retour au village se fait en fondu.
        AnimatedContent(game.screen, transitionSpec = {
            if (targetState == Screen.HOME) fadeIn(tween(250)) togetherWith (slideOutVertically(tween(280)) { it / 3 } + fadeOut(tween(220)))
            else (slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(240))) togetherWith fadeOut(tween(200))
        }, label = "screen") { sc ->
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
                Screen.MAP -> MapScreen(game)
                Screen.ADMIN -> AdminScreen(game)
            }
        }
        Overlays(game)
    }
}
