package com.ghost.coin

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.ghost.coin.core.Rules
import com.ghost.coin.core.countryOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

val Night = Color(0xFF0A0616)
val Panel = Color(0xFF4A28B8)
val Deep = Color(0xFF2A1466)
val Royal = Color(0xFF6A3DE8)
val Violet = Color(0xFF8B3DFF)
val Gold = Color(0xFFFFC83D)
val GoldDark = Color(0xFFB8801A)
val Electric = Color(0xFF2CC8FF)
val Ink = Color(0xFFF6F1FF)
val Dim = Color(0xFFDCD0FF)
val Danger = Color(0xFFFF4D6D)
val RARITY = listOf(Color(0xFF8FA3C8), Electric, Violet, Gold, Color(0xFFFF4DD8))
val AVATARS = listOf(Ink, Color(0xFFBFF6FF), Color(0xFFFFE08A), Color(0xFFE2B8FF), Color(0xFFB8FFC9), Color(0xFFFFC0CB))

fun hsv(h: Int, s: Float, v: Float) = Color.hsv((((h % 360) + 360) % 360).toFloat(), s, v)

/** Apparence d'un pays : paysage selon son biome, couleurs de bâtiments selon son style d'architecture. */
class Look(val v: Int, private val shade: Float = 0f) {
    /** Version ombrée, utilisée pour la face latérale des volumes. */
    val side: Look by lazy { Look(v, 0.4f) }
    private fun sh(c: Color) = androidx.compose.ui.graphics.lerp(c, Color(0xFF1A0F33), shade)
    val c = countryOf(v)
    private val b = when (c.biome) {
        's' -> longArrayOf(0xFFF08A3C, 0xFFFFD98A, 0xFFB5793A, 0xFFD9A441, 0xFF9C6B1E)
        'd' -> longArrayOf(0xFF7EC8F2, 0xFFFCE7B8, 0xFFE3B66B, 0xFFEFCB85, 0xFFC99A4E)
        'j' -> longArrayOf(0xFF4FA7D8, 0xFFCDEFD0, 0xFF2E7D4F, 0xFF3F9A55, 0xFF226B3A)
        'c' -> longArrayOf(0xFF38A8E8, 0xFFBDEBFF, 0xFF1FA6C9, 0xFFF3DFA2, 0xFFD4B66F)
        'm' -> longArrayOf(0xFF4E8FD6, 0xFFD7E9FA, 0xFF7A8798, 0xFF6FA35A, 0xFF4A7A3C)
        'n' -> longArrayOf(0xFF2B3F73, 0xFFBFD9F2, 0xFF8FA9C4, 0xFFF2F6FB, 0xFFC6D4E3)
        't' -> longArrayOf(0xFF6BB3E8, 0xFFF6EFC9, 0xFFB9B26A, 0xFFCFC36E, 0xFF9C9147)
        else -> longArrayOf(0xFF5AA9E6, 0xFFDFF3FF, 0xFF6DB56A, 0xFF7CC466, 0xFF4F9645)
    }
    val skyTop = Color(b[0]); val skyBottom = Color(b[1]); val far = Color(b[2]); val ground = Color(b[3]); val groundDark = Color(b[4])
    val accent = hsv(v * 47 + 10, 0.7f, 0.95f)
    private val s = when (c.style) {
        'a' -> longArrayOf(0xFFC8935A, 0xFF9A6A3A); 'b' -> longArrayOf(0xFFB9794A, 0xFFD9B45A); 'c' -> longArrayOf(0xFFF4EAD6, 0xFF2E9E8F)
        'e' -> longArrayOf(0xFFF3E6C8, 0xFF8A3B2E); 'f' -> longArrayOf(0xFFFFFFFF, 0xFF2F6FD6)
        'g' -> longArrayOf(listOf(0xFFB23A32, 0xFFE8B84A, 0xFF3D6FA8)[v % 3], 0xFF3A3F4A); 'h' -> longArrayOf(0xFFF6F1E7, 0xFFE0B23A)
        'i' -> longArrayOf(0xFFB8322A, 0xFF2F4F4F); 'j' -> longArrayOf(0xFF8A5A33, 0xFFD9602B); 'k' -> longArrayOf(0xFFE2C290, 0xFF2BA6B8)
        'l' -> longArrayOf(0xFF6FB6E8, 0xFF2C4A6E); 'm' -> longArrayOf(0xFFA9753F, 0xFFC9A45A); 'o' -> longArrayOf(0xFFF7F4EC, 0xFFB8322A)
        else -> longArrayOf(0, 0xFFB5452E)
    }
    val wall = sh(if (c.style == 'd') hsv(v * 47, 0.5f, 0.95f) else Color(s[0]))
    val roof = sh(Color(s[1]))
    val wallDark = androidx.compose.ui.graphics.lerp(wall, Color.Black, 0.28f)
}

enum class Mood { IDLE, JUMP, DANCE, WIN, SAD, HIT }

/** Temps continu en secondes, à lire dans le bloc de dessin : l'écran est redessiné sans être recomposé. */
@Composable
fun rememberTime(): androidx.compose.runtime.State<Float> =
    produceState(0f) { val start = withFrameNanos { it }; while (true) withFrameNanos { value = (it - start) / 1e9f } }

/** Temps continu en secondes, pour les animations dessinées. */
@Composable
fun frameTime(): Float {
    val t by produceState(0f) { val start = withFrameNanos { it }; while (true) withFrameNanos { value = (it - start) / 1e9f } }
    return t
}

private fun poly(vararg p: Float) = Path().apply {
    moveTo(p[0], p[1]); var i = 2
    while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
    close()
}

/** Le fantôme mascotte. `w` = largeur du corps. */
fun DrawScope.ghost(cx: Float, cy: Float, w: Float, tint: Color, mood: Mood, t: Float) {
    var dx = 0f; var dy = sin(t * 2f) * w * 0.06f; var rot = 0f
    when (mood) {
        Mood.JUMP -> dy = -abs(sin(t * 6f)) * w * 0.5f
        Mood.DANCE -> { dx = sin(t * 8f) * w * 0.18f; rot = sin(t * 8f) * 14f }
        Mood.WIN -> dy = -abs(sin(t * 7f)) * w * 0.32f
        Mood.SAD -> dy = w * 0.08f
        Mood.HIT -> dx = sin(t * 40f) * w * 0.07f
        Mood.IDLE -> {}
    }
    val x = cx + dx; val y = cy + dy
    val h = w * 1.15f; val l = x - w / 2; val top = y - h / 2; val r = x + w / 2; val b = y + h / 2
    // Ombre au sol.
    drawOval(Color.Black.copy(alpha = 0.28f), Offset(cx - w * 0.38f, cy + h * 0.62f), Size(w * 0.76f, w * 0.14f))
    rotate(rot, Offset(x, y)) {
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.35f), Color.Transparent), Offset(x, y), w * 1.1f), w * 1.1f, Offset(x, y))
        val up = mood == Mood.WIN || mood == Mood.DANCE
        for (s in listOf(-1f, 1f)) rotate(if (up) s * -50f else s * 15f, Offset(x + s * w * 0.42f, y + w * 0.05f)) {
            drawOval(tint, Offset(x + s * w * 0.42f - w * 0.11f, y - w * 0.02f), Size(w * 0.22f, w * 0.36f))
        }
        val wave = sin(t * 5f) * w * 0.04f
        val body = Path().apply {
            moveTo(l, b); lineTo(l, top + w / 2)
            arcTo(Rect(l, top, r, top + w), 180f, 180f, false)
            lineTo(r, b)
            val seg = w / 3
            for (i in 0 until 3) quadraticTo(r - seg * i - seg / 2, b - w * 0.24f + wave * (if (i % 2 == 0) 1 else -1), r - seg * (i + 1), b)
            close()
        }
        drawPath(body, Brush.verticalGradient(listOf(Color.White, tint, tint.copy(alpha = 0.72f)), top, b))
        val ey = top + w * 0.46f
        val dark = Color(0xFF1B0F33)
        for (s in listOf(-1f, 1f)) {
            val ex = x + s * w * 0.17f
            when (mood) {
                Mood.WIN, Mood.DANCE -> drawArc(dark, 180f, 180f, false, Offset(ex - w * 0.08f, ey - w * 0.07f), Size(w * 0.16f, w * 0.16f), style = Stroke(w * 0.05f, cap = StrokeCap.Round))
                Mood.HIT -> {
                    drawLine(dark, Offset(ex - w * 0.07f, ey - w * 0.07f), Offset(ex + w * 0.07f, ey + w * 0.07f), w * 0.05f, StrokeCap.Round)
                    drawLine(dark, Offset(ex + w * 0.07f, ey - w * 0.07f), Offset(ex - w * 0.07f, ey + w * 0.07f), w * 0.05f, StrokeCap.Round)
                }
                else -> {
                    val blink = if ((t % 4f) > 3.85f) 0.15f else 1f
                    val eh = w * (if (mood == Mood.SAD) 0.14f else 0.2f) * blink
                    drawOval(dark, Offset(ex - w * 0.065f, ey - eh / 2), Size(w * 0.13f, eh))
                    drawCircle(Color.White, w * 0.025f * blink, Offset(ex + w * 0.02f, ey - w * 0.04f))
                }
            }
            drawCircle(Color(0x55FF6FA5), w * 0.07f, Offset(x + s * w * 0.3f, ey + w * 0.16f))
        }
        val my = ey + w * 0.2f
        when (mood) {
            Mood.WIN, Mood.JUMP -> drawOval(dark, Offset(x - w * 0.07f, my - w * 0.03f), Size(w * 0.14f, w * 0.16f))
            Mood.SAD, Mood.HIT -> drawArc(dark, 200f, 140f, false, Offset(x - w * 0.09f, my), Size(w * 0.18f, w * 0.14f), style = Stroke(w * 0.04f, cap = StrokeCap.Round))
            else -> drawArc(dark, 20f, 140f, false, Offset(x - w * 0.09f, my - w * 0.08f), Size(w * 0.18f, w * 0.14f), style = Stroke(w * 0.04f, cap = StrokeCap.Round))
        }
        if (mood == Mood.SAD) drawOval(Electric, Offset(x + w * 0.2f, ey + w * 0.1f + (t % 1f) * w * 0.2f), Size(w * 0.06f, w * 0.1f))
        if (mood == Mood.WIN) for (i in 0 until 5) {
            val a = t * 3f + i * 1.26f
            star(Offset(x + cos(a) * w * 0.85f, y + sin(a) * w * 0.85f), w * 0.08f, Gold)
        }
    }
}

fun DrawScope.star(c: Offset, r: Float, color: Color) {
    val p = Path()
    for (i in 0 until 10) {
        val a = -PI / 2 + i * PI / 5
        val rr = if (i % 2 == 0) r else r * 0.45f
        val x = c.x + (cos(a) * rr).toFloat(); val y = c.y + (sin(a) * rr).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close(); drawPath(p, color)
}

/** Symboles de la machine (0..7, voir Sym) et pictogrammes de menus (8 = engrenage, 9 = cartes, 10 = coffre, 11 = coupe). */
fun DrawScope.symbol(id: Int, c: Offset, r: Float) {
    val goldBrush = Brush.linearGradient(listOf(Color(0xFFFFF2B0), Gold, GoldDark), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r))
    when (id) {
        0 -> { // pièce avec tranche
            drawCircle(Color(0xFF8A5A0E), r * 0.92f, Offset(c.x, c.y + r * 0.14f)); drawCircle(GoldDark, r * 0.92f, Offset(c.x, c.y + r * 0.07f))
            drawCircle(GoldDark, r * 0.92f, c); drawCircle(goldBrush, r * 0.84f, c)
            drawArc(Color.White.copy(alpha = 0.55f), 200f, 70f, false, Offset(c.x - r * 0.72f, c.y - r * 0.72f), Size(r * 1.44f, r * 1.44f), style = Stroke(r * 0.09f, cap = StrokeCap.Round))
            drawCircle(GoldDark, r * 0.6f, c, style = Stroke(r * 0.07f))
            drawCircle(GoldDark, r * 0.08f, Offset(c.x - r * 0.2f, c.y - r * 0.05f)); drawCircle(GoldDark, r * 0.08f, Offset(c.x + r * 0.2f, c.y - r * 0.05f))
            drawArc(GoldDark, 20f, 140f, false, Offset(c.x - r * 0.2f, c.y - r * 0.05f), Size(r * 0.4f, r * 0.3f), style = Stroke(r * 0.07f, cap = StrokeCap.Round))
            drawCircle(Color.White.copy(alpha = 0.7f), r * 0.1f, Offset(c.x - r * 0.45f, c.y - r * 0.5f))
        }
        1 -> { // sac de trésors
            val bag = Brush.verticalGradient(listOf(Color(0xFFB07BFF), Color(0xFF5A1FB8)), c.y - r, c.y + r)
            drawOval(bag, Offset(c.x - r * 0.78f, c.y - r * 0.35f), Size(r * 1.56f, r * 1.3f))
            drawPath(poly(c.x - r * 0.28f, c.y - r * 0.3f, c.x + r * 0.28f, c.y - r * 0.3f, c.x + r * 0.45f, c.y - r * 0.9f, c.x - r * 0.45f, c.y - r * 0.9f), bag)
            drawRoundRect(Gold, Offset(c.x - r * 0.36f, c.y - r * 0.42f), Size(r * 0.72f, r * 0.16f), CornerRadius(r * 0.08f))
            drawCircle(goldBrush, r * 0.3f, Offset(c.x, c.y + r * 0.3f)); drawCircle(GoldDark, r * 0.3f, Offset(c.x, c.y + r * 0.3f), style = Stroke(r * 0.06f))
        }
        2 -> rotate(-40f, c) { // marteau
            drawRoundRect(Color(0xFF9B6B3A), Offset(c.x - r * 0.1f, c.y - r * 0.5f), Size(r * 0.2f, r * 1.4f), CornerRadius(r * 0.1f))
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFE6ECFF), Color(0xFF7C88B0)), c.y - r, c.y), Offset(c.x - r * 0.6f, c.y - r * 0.9f), Size(r * 1.2f, r * 0.55f), CornerRadius(r * 0.12f))
            drawRoundRect(Gold, Offset(c.x - r * 0.16f, c.y - r * 0.9f), Size(r * 0.32f, r * 0.55f))
        }
        3 -> { // bouclier
            val p = Path().apply {
                moveTo(c.x, c.y - r * 0.9f); lineTo(c.x + r * 0.75f, c.y - r * 0.6f)
                quadraticTo(c.x + r * 0.8f, c.y + r * 0.45f, c.x, c.y + r * 0.95f)
                quadraticTo(c.x - r * 0.8f, c.y + r * 0.45f, c.x - r * 0.75f, c.y - r * 0.6f); close()
            }
            drawPath(p, Brush.verticalGradient(listOf(Color(0xFF8FE9FF), Color(0xFF1C6FE0)), c.y - r, c.y + r))
            drawPath(p, Color.White.copy(alpha = 0.8f), style = Stroke(r * 0.09f))
            star(c, r * 0.36f, Color.White)
        }
        4 -> ghost(c.x, c.y - r * 0.05f, r * 1.25f, Ink, Mood.IDLE, 0f)
        5 -> { // éclair d'attaque
            val p = poly(c.x + r * 0.2f, c.y - r, c.x - r * 0.6f, c.y + r * 0.12f, c.x - r * 0.05f, c.y + r * 0.12f, c.x - r * 0.25f, c.y + r, c.x + r * 0.6f, c.y - r * 0.22f, c.x + r * 0.05f, c.y - r * 0.22f)
            drawPath(p, Brush.verticalGradient(listOf(Color.White, Electric, Color(0xFF2460FF)), c.y - r, c.y + r))
            drawPath(p, Color.White.copy(alpha = 0.6f), style = Stroke(r * 0.05f))
        }
        6 -> rotate(-35f, c) { // clé de raid
            drawCircle(goldBrush, r * 0.42f, Offset(c.x, c.y - r * 0.5f)); drawCircle(Night, r * 0.17f, Offset(c.x, c.y - r * 0.5f))
            drawRect(goldBrush, Offset(c.x - r * 0.09f, c.y - r * 0.15f), Size(r * 0.18f, r * 1.1f))
            drawRect(Gold, Offset(c.x, c.y + r * 0.5f), Size(r * 0.36f, r * 0.15f)); drawRect(Gold, Offset(c.x, c.y + r * 0.78f), Size(r * 0.28f, r * 0.15f))
        }
        7 -> { // étoile bonus
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFF4DD8), Color.Transparent), c, r), r, c)
            star(c, r * 0.95f, GoldDark); star(c, r * 0.8f, Gold); star(Offset(c.x - r * 0.1f, c.y - r * 0.1f), r * 0.35f, Color(0xFFFFF6C8))
        }
        8 -> { // engrenage
            for (i in 0 until 8) rotate(i * 45f, c) { drawRoundRect(Dim, Offset(c.x - r * 0.14f, c.y - r * 0.95f), Size(r * 0.28f, r * 0.5f), CornerRadius(r * 0.08f)) }
            drawCircle(Dim, r * 0.65f, c); drawCircle(Night, r * 0.28f, c)
        }
        9 -> { // cartes
            rotate(-12f, c) { drawRoundRect(Electric, Offset(c.x - r * 0.7f, c.y - r * 0.8f), Size(r * 1.0f, r * 1.5f), CornerRadius(r * 0.15f)) }
            rotate(10f, c) {
                drawRoundRect(Violet, Offset(c.x - r * 0.35f, c.y - r * 0.75f), Size(r * 1.0f, r * 1.5f), CornerRadius(r * 0.15f))
                star(Offset(c.x + r * 0.15f, c.y), r * 0.32f, Gold)
            }
        }
        10 -> chest(c, r, 1)
        12 -> { // globe
            drawCircle(Brush.radialGradient(listOf(Color(0xFF7FD8FF), Color(0xFF1C6FE0)), Offset(c.x - r * 0.3f, c.y - r * 0.3f), r * 1.4f), r * 0.9f, c)
            drawOval(Color(0xFF58B45E), Offset(c.x - r * 0.6f, c.y - r * 0.5f), Size(r * 0.55f, r * 0.7f)); drawOval(Color(0xFF58B45E), Offset(c.x + r * 0.05f, c.y - r * 0.1f), Size(r * 0.6f, r * 0.5f))
            drawCircle(Color.White.copy(alpha = 0.6f), r * 0.9f, c, style = Stroke(r * 0.07f))
        }
        else -> { // coupe
            drawPath(poly(c.x - r * 0.55f, c.y - r * 0.8f, c.x + r * 0.55f, c.y - r * 0.8f, c.x + r * 0.3f, c.y + r * 0.15f, c.x - r * 0.3f, c.y + r * 0.15f), goldBrush)
            drawRect(Gold, Offset(c.x - r * 0.08f, c.y + r * 0.15f), Size(r * 0.16f, r * 0.4f))
            drawRoundRect(GoldDark, Offset(c.x - r * 0.45f, c.y + r * 0.55f), Size(r * 0.9f, r * 0.25f), CornerRadius(r * 0.08f))
        }
    }
}

/** Coffre : 0 bois, 1 argent, 2 or. */
fun DrawScope.chest(c: Offset, r: Float, kind: Int) {
    val body = listOf(Color(0xFF9B6B3A), Color(0xFFB9C6E0), Gold)[kind]
    val dark = listOf(Color(0xFF5E3D1E), Color(0xFF6B7896), GoldDark)[kind]
    drawRoundRect(dark, Offset(c.x - r * 0.9f, c.y - r * 0.15f), Size(r * 1.8f, r * 0.95f), CornerRadius(r * 0.12f))
    drawRoundRect(body, Offset(c.x - r * 0.82f, c.y - r * 0.08f), Size(r * 1.64f, r * 0.8f), CornerRadius(r * 0.1f))
    drawArc(dark, 180f, 180f, true, Offset(c.x - r * 0.9f, c.y - r * 0.85f), Size(r * 1.8f, r * 1.4f))
    drawArc(body, 180f, 180f, true, Offset(c.x - r * 0.8f, c.y - r * 0.75f), Size(r * 1.6f, r * 1.2f))
    drawRect(dark, Offset(c.x - r * 0.9f, c.y - r * 0.2f), Size(r * 1.8f, r * 0.12f))
    drawRoundRect(Gold, Offset(c.x - r * 0.16f, c.y - r * 0.3f), Size(r * 0.32f, r * 0.42f), CornerRadius(r * 0.06f))
    drawCircle(Night, r * 0.07f, Offset(c.x, c.y - r * 0.1f))
}

private val Stone = Color(0xFFCDBB95)
private val StoneDark = Color(0xFF8E7F63)
private val Wood = Color(0xFF7A4E2A)

/** Une construction dans le style d'architecture du pays. `w`, `h` : emprise du corps du bâtiment. */
fun DrawScope.house(k: Look, x: Float, base: Float, w: Float, h: Float, damaged: Boolean = false, depth: Boolean = true) {
    // Relief 2,5D : le même volume, décalé et ombré, forme la face latérale et l'épaisseur du toit.
    if (depth) { translate(w * 0.15f, -w * 0.075f) { house(k.side, x, base, w, h, damaged, false) }
        drawPath(poly(x + w / 2, base, x + w / 2 + w * 0.15f, base - w * 0.075f, x + w / 2 + w * 0.15f, base - h * 0.5f, x + w / 2, base - h * 0.5f), k.side.wall) }
    val wall = if (damaged) k.wallDark else k.wall
    val body = Brush.horizontalGradient(listOf(wall, k.wallDark), x - w / 2, x + w / 2)
    fun box(y: Float = base, hh: Float = h, ww: Float = w) = drawRect(body, Offset(x - ww / 2, y - hh), Size(ww, hh))
    fun door(c: Color = Wood) = drawRoundRect(c, Offset(x - w * 0.1f, base - h * 0.42f), Size(w * 0.2f, h * 0.42f), CornerRadius(w * 0.1f, w * 0.1f))
    fun win(dx: Float, dy: Float, c: Color = Color(0xFF2A3350)) = drawRect(c, Offset(x + dx * w - w * 0.06f, base - dy * h - w * 0.07f), Size(w * 0.12f, w * 0.14f))
    when (k.c.style) {
        'a' -> { // terre crue : toit plat, pinacles, bois de soutien apparents
            box(); for (i in 0 until 3) drawPath(poly(x - w * 0.5f + i * w * 0.38f, base - h, x - w * 0.26f + i * w * 0.38f, base - h, x - w * 0.38f + i * w * 0.38f, base - h * 1.22f), wall)
            for (r in 0 until 2) for (i in 0 until 4) drawRect(Wood, Offset(x - w * 0.4f + i * w * 0.25f, base - h * (0.62f + r * 0.2f)), Size(w * 0.07f, h * 0.035f))
            door()
        }
        'b' -> { // case ronde, toit conique en chaume
            box(ww = w * 0.74f, hh = h * 0.55f); drawPath(poly(x - w * 0.6f, base - h * 0.5f, x + w * 0.6f, base - h * 0.5f, x, base - h * 1.15f), k.roof)
            for (i in 0 until 4) drawLine(k.wallDark, Offset(x, base - h * 1.15f), Offset(x - w * 0.45f + i * w * 0.3f, base - h * 0.5f), 1.5f)
            door()
        }
        'c' -> { // médina : murs clairs, coupole, porte en arc outrepassé
            box(); drawArc(k.roof, 180f, 180f, true, Offset(x - w * 0.24f, base - h - w * 0.22f), Size(w * 0.48f, w * 0.44f))
            drawRect(wall, Offset(x - w * 0.5f, base - h * 1.07f), Size(w, h * 0.07f))
            drawCircle(Color(0xFF3A2A1E), w * 0.13f, Offset(x, base - h * 0.36f)); drawRect(Color(0xFF3A2A1E), Offset(x - w * 0.09f, base - h * 0.34f), Size(w * 0.18f, h * 0.34f))
            win(-0.3f, 0.65f); win(0.3f, 0.65f)
        }
        'e' -> { // pignon et colombages
            box(); drawPath(poly(x - w * 0.58f, base - h, x + w * 0.58f, base - h, x, base - h * 1.6f), k.roof)
            for (d in listOf(-1f, 1f)) { drawLine(Wood, Offset(x + d * w * 0.48f, base), Offset(x + d * w * 0.48f, base - h), w * 0.05f); drawLine(Wood, Offset(x + d * w * 0.48f, base - h), Offset(x, base - h * 0.5f), w * 0.04f) }
            drawLine(Wood, Offset(x - w / 2, base - h * 0.5f), Offset(x + w / 2, base - h * 0.5f), w * 0.05f); door(); win(-0.28f, 0.72f); win(0.28f, 0.72f)
        }
        'f' -> { // maison blanche, coupole bleue ou tuiles
            box(); if (h > w) drawArc(k.roof, 180f, 180f, true, Offset(x - w * 0.36f, base - h - w * 0.32f), Size(w * 0.72f, w * 0.64f))
            else drawPath(poly(x - w * 0.58f, base - h, x + w * 0.58f, base - h, x + w * 0.4f, base - h * 1.25f, x - w * 0.4f, base - h * 1.25f), Color(0xFFC8643A))
            door(k.roof); win(-0.3f, 0.65f, k.roof); win(0.3f, 0.65f, k.roof)
        }
        'g' -> { // bois peint, encadrements blancs
            box(); drawPath(poly(x - w * 0.58f, base - h, x + w * 0.58f, base - h, x, base - h * 1.55f), k.roof)
            for (d in listOf(-0.28f, 0.28f)) { drawRect(Color.White, Offset(x + d * w - w * 0.09f, base - h * 0.75f), Size(w * 0.18f, w * 0.2f)); win(d, 0.66f) }
            door(Color.White)
        }
        'h' -> { // bulbe doré sur tambour
            box(); drawRect(wall, Offset(x - w * 0.14f, base - h * 1.2f), Size(w * 0.28f, h * 0.2f))
            drawCircle(k.roof, w * 0.24f, Offset(x, base - h * 1.2f - w * 0.16f)); drawPath(poly(x - w * 0.12f, base - h * 1.2f - w * 0.32f, x + w * 0.12f, base - h * 1.2f - w * 0.32f, x, base - h * 1.2f - w * 0.62f), k.roof)
            door(); win(-0.3f, 0.6f); win(0.3f, 0.6f)
        }
        'i' -> { // toits courbes à avant-toits relevés
            box(hh = h * 0.8f)
            val tiers = if (h > w) 2 else 1
            for (i in 0 until tiers) {
                val y = base - h * (0.8f + i * 0.45f); val ww = w * (1.35f - i * 0.35f)
                if (i > 0) drawRect(body, Offset(x - w * 0.3f, y), Size(w * 0.6f, h * 0.3f))
                drawPath(Path().apply { moveTo(x - ww / 2, y - h * 0.04f); quadraticTo(x - ww * 0.3f, y, x - ww * 0.2f, y - h * 0.3f); lineTo(x + ww * 0.2f, y - h * 0.3f); quadraticTo(x + ww * 0.3f, y, x + ww / 2, y - h * 0.04f); lineTo(x + ww / 2, y + h * 0.04f); lineTo(x - ww / 2, y + h * 0.04f); close() }, k.roof)
            }
            door(Color(0xFF3A1A12)); for (d in listOf(-1f, 1f)) drawRect(Gold, Offset(x + d * w * 0.4f - w * 0.03f, base - h * 0.8f), Size(w * 0.06f, h * 0.8f))
        }
        'j', 'm' -> { // maison sur pilotis ; toit effilé (Asie du Sud-Est) ou large toit végétal (Pacifique)
            for (d in listOf(-1f, 1f)) drawRect(Wood, Offset(x + d * w * 0.36f - w * 0.03f, base - h * 0.3f), Size(w * 0.06f, h * 0.3f))
            box(base - h * 0.25f, h * 0.45f, w * 0.9f)
            if (k.c.style == 'j') {
                drawPath(Path().apply { moveTo(x - w * 0.62f, base - h * 0.68f); quadraticTo(x - w * 0.15f, base - h * 0.85f, x, base - h * 1.55f); quadraticTo(x + w * 0.15f, base - h * 0.85f, x + w * 0.62f, base - h * 0.68f); close() }, k.roof)
                drawLine(Gold, Offset(x, base - h * 1.5f), Offset(x, base - h * 1.75f), w * 0.04f)
            } else drawPath(poly(x - w * 0.7f, base - h * 0.62f, x + w * 0.7f, base - h * 0.62f, x + w * 0.2f, base - h * 1.3f, x - w * 0.2f, base - h * 1.3f), k.roof)
            drawRect(Color(0xFF2A1A10), Offset(x - w * 0.1f, base - h * 0.6f), Size(w * 0.2f, h * 0.35f))
        }
        'k' -> { // iwan en arc brisé et dôme turquoise
            box(); drawArc(k.roof, 180f, 180f, true, Offset(x - w * 0.3f, base - h - w * 0.3f), Size(w * 0.6f, w * 0.6f)); drawLine(Gold, Offset(x, base - h - w * 0.3f), Offset(x, base - h - w * 0.45f), w * 0.04f)
            drawPath(poly(x - w * 0.22f, base, x - w * 0.22f, base - h * 0.5f, x, base - h * 0.8f, x + w * 0.22f, base - h * 0.5f, x + w * 0.22f, base), Color(0xFF3A2A4A))
            drawRect(k.roof, Offset(x - w * 0.5f, base - h), Size(w, h * 0.07f))
        }
        'l' -> { // tour de verre
            drawRect(Brush.verticalGradient(listOf(Color(0xFFCFEFFF), wall, k.roof), base - h * 1.4f, base), Offset(x - w * 0.36f, base - h * 1.4f), Size(w * 0.72f, h * 1.4f))
            for (r in 1 until 7) drawLine(Color.White.copy(alpha = 0.5f), Offset(x - w * 0.36f, base - r * h * 0.2f), Offset(x + w * 0.36f, base - r * h * 0.2f), 1.5f)
            for (cx in listOf(-0.12f, 0.12f)) drawLine(Color.White.copy(alpha = 0.4f), Offset(x + cx * w, base), Offset(x + cx * w, base - h * 1.4f), 1.5f)
            drawLine(k.roof, Offset(x, base - h * 1.4f), Offset(x, base - h * 1.65f), w * 0.04f)
        }
        'o' -> { // yourte
            drawRoundRect(body, Offset(x - w * 0.55f, base - h * 0.5f), Size(w * 1.1f, h * 0.5f), CornerRadius(w * 0.06f))
            drawArc(wall, 180f, 180f, true, Offset(x - w * 0.55f, base - h * 0.9f), Size(w * 1.1f, h * 0.8f))
            drawRect(k.roof, Offset(x - w * 0.55f, base - h * 0.52f), Size(w * 1.1f, h * 0.06f)); door(k.roof); drawCircle(k.roof, w * 0.07f, Offset(x, base - h * 0.88f))
        }
        else -> { // maison coloniale colorée : toit de tuiles, volets, balcon
            box(); drawPath(poly(x - w * 0.58f, base - h, x + w * 0.58f, base - h, x + w * 0.36f, base - h * 1.32f, x - w * 0.36f, base - h * 1.32f), k.roof)
            drawRect(Color.White, Offset(x - w * 0.5f, base - h * 0.52f), Size(w, h * 0.05f)); door(Color.White)
            for (d in listOf(-0.3f, 0.3f)) { drawRect(Color.White, Offset(x + d * w - w * 0.1f, base - h * 0.86f), Size(w * 0.2f, w * 0.2f)); win(d, 0.76f, Color(0xFF2E6F5A)) }
        }
    }
}

/** Arbre typique du paysage. */
fun DrawScope.tree(biome: Char, x: Float, base: Float, s: Float) {
    val leaf = if (biome == 'n' || biome == 'm') Color(0xFF2F6B4A) else Color(0xFF3F9A4B)
    when (biome) {
        's' -> { drawRect(Wood, Offset(x - s * 0.04f, base - s * 0.6f), Size(s * 0.08f, s * 0.6f)); drawOval(Color(0xFF5E8F3A), Offset(x - s * 0.5f, base - s * 0.8f), Size(s, s * 0.28f)) }
        'd', 'c' -> { // palmier
            drawPath(Path().apply { moveTo(x, base); quadraticTo(x + s * 0.12f, base - s * 0.5f, x + s * 0.05f, base - s) }, Wood, style = Stroke(s * 0.08f, cap = StrokeCap.Round))
            for (i in 0 until 6) { val a = -PI + i * PI / 5; drawLine(leaf, Offset(x + s * 0.05f, base - s), Offset(x + s * 0.05f + (cos(a) * s * 0.42f).toFloat(), base - s + (sin(a) * s * 0.2f).toFloat() + s * 0.12f * abs(cos(a)).toFloat()), s * 0.07f, StrokeCap.Round) }
        }
        'm', 'n' -> { drawRect(Wood, Offset(x - s * 0.04f, base - s * 0.2f), Size(s * 0.08f, s * 0.2f)); for (i in 0 until 3) drawPath(poly(x - s * (0.34f - i * 0.08f), base - s * (0.15f + i * 0.26f), x + s * (0.34f - i * 0.08f), base - s * (0.15f + i * 0.26f), x, base - s * (0.6f + i * 0.22f)), if (biome == 'n' && i == 2) Color.White else leaf) }
        else -> { drawRect(Wood, Offset(x - s * 0.05f, base - s * 0.5f), Size(s * 0.1f, s * 0.5f)); drawCircle(leaf, s * 0.3f, Offset(x, base - s * 0.68f)); drawCircle(Color(0xFF58B45E), s * 0.2f, Offset(x - s * 0.12f, base - s * 0.78f)) }
    }
}

/** Monument emblématique : une silhouette par type, aux couleurs de la pierre ou du pays. */
fun DrawScope.monument(k: Look, x: Float, base: Float, s: Float, t: Float) {
    fun r(dx: Float, dy: Float, w: Float, h: Float, c: Color = Stone) = drawRect(c, Offset(x + dx * s - w * s / 2, base - dy * s - h * s), Size(w * s, h * s))
    fun tri(dx: Float, dy: Float, w: Float, h: Float, c: Color) = drawPath(poly(x + (dx - w / 2) * s, base - dy * s, x + (dx + w / 2) * s, base - dy * s, x + dx * s, base - (dy + h) * s), c)
    fun dome(dx: Float, dy: Float, w: Float, c: Color) = drawArc(c, 180f, 180f, true, Offset(x + (dx - w / 2) * s, base - dy * s - w * s / 2), Size(w * s, w * s))
    val earth = Color(0xFFC8935A)
    when (k.c.monument) {
        'P' -> { tri(0.25f, 0f, 0.7f, 0.5f, Color(0xFFD9B36A)); tri(-0.1f, 0f, 1.1f, 0.85f, Color(0xFFE8C67A)); drawPath(poly(x - 0.1f * s, base - 0.85f * s, x + 0.45f * s, base, x - 0.1f * s, base), Color(0xFFC49A4E)) }
        'Q' -> { r(0f, 0f, 1.2f, 0.45f, earth); for (i in -1..1) { r(i * 0.4f, 0f, 0.2f, 0.75f + (1 - abs(i)) * 0.15f, earth); tri(i * 0.4f, 0.75f + (1 - abs(i)) * 0.15f, 0.2f, 0.18f, earth); for (j in 0 until 3) r(i * 0.4f + 0.12f, 0.3f + j * 0.15f, 0.06f, 0.02f, Wood) } }
        'M' -> { r(0.25f, 0f, 0.8f, 0.35f, k.wall); dome(0.25f, 0.35f, 0.4f, k.roof); r(-0.3f, 0f, 0.22f, 1.0f, k.wall); r(-0.3f, 1.0f, 0.28f, 0.05f, k.wallDark); r(-0.3f, 1.05f, 0.14f, 0.2f, k.wall); dome(-0.3f, 1.25f, 0.16f, k.roof); drawCircle(Gold, s * 0.03f, Offset(x - 0.3f * s, base - 1.38f * s)) }
        'S' -> { r(0f, 0f, 0.5f, 0.35f); r(0f, 0.35f, 0.36f, 0.08f, StoneDark); drawOval(Color(0xFF8FB5A0), Offset(x - 0.13f * s, base - 1.0f * s), Size(0.26f * s, 0.6f * s)); drawCircle(Color(0xFF8FB5A0), 0.09f * s, Offset(x, base - 1.08f * s)); drawLine(Color(0xFF8FB5A0), Offset(x + 0.08f * s, base - 0.9f * s), Offset(x + 0.24f * s, base - 1.3f * s), 0.07f * s, StrokeCap.Round) }
        'T' -> { val c = Color(0xFF6B5A4A); val st = Stroke(0.05f * s, cap = StrokeCap.Round)
            drawPath(Path().apply { moveTo(x - 0.4f * s, base); quadraticTo(x - 0.1f * s, base - 0.5f * s, x - 0.03f * s, base - 1.4f * s); moveTo(x + 0.4f * s, base); quadraticTo(x + 0.1f * s, base - 0.5f * s, x + 0.03f * s, base - 1.4f * s) }, c, style = st)
            r(0f, 0.32f, 0.5f, 0.05f, c); r(0f, 0.7f, 0.26f, 0.04f, c); drawLine(c, Offset(x, base - 1.4f * s), Offset(x, base - 1.6f * s), 0.03f * s) }
        'H' -> { r(0f, 0f, 0.3f, 1.1f, Color(0xFFD8C08A)); tri(0f, 1.1f, 0.36f, 0.3f, StoneDark); drawCircle(Color.White, 0.1f * s, Offset(x, base - 0.9f * s)); drawLine(Night, Offset(x, base - 0.9f * s), Offset(x + 0.06f * s, base - 0.95f * s), 2f); r(0.35f, 0f, 0.5f, 0.4f, Color(0xFFD8C08A)) }
        'W' -> { drawPath(poly(x - 0.22f * s, base, x + 0.22f * s, base, x + 0.12f * s, base - 0.8f * s, x - 0.12f * s, base - 0.8f * s), Color(0xFF8A5A3A)); tri(0f, 0.8f, 0.3f, 0.15f, StoneDark)
            for (i in 0 until 4) rotate(t * 40f + i * 90f, Offset(x, base - 0.8f * s)) { drawRect(Color(0xFFF2E6C8), Offset(x - 0.04f * s, base - 1.35f * s), Size(0.1f * s, 0.5f * s)) } }
        'C' -> { r(0f, 0f, 1.0f, 0.45f); for (i in 0 until 5) r(-0.4f + i * 0.2f, 0.45f, 0.1f, 0.08f); for (d in listOf(-0.5f, 0.5f)) { r(d, 0f, 0.24f, 0.8f); tri(d, 0.8f, 0.3f, 0.3f, k.roof) }; r(0f, 0f, 0.3f, 0.95f); tri(0f, 0.95f, 0.36f, 0.3f, k.roof); r(0f, 0f, 0.14f, 0.25f, Night) }
        'K' -> { r(0f, 0f, 0.7f, 0.6f); tri(0f, 0.6f, 0.7f, 0.25f, StoneDark); for (d in listOf(-0.3f, 0.3f)) { r(d, 0f, 0.2f, 1.0f); tri(d, 1.0f, 0.2f, 0.45f, StoneDark) }; drawCircle(k.accent, 0.1f * s, Offset(x, base - 0.45f * s)); r(0f, 0f, 0.16f, 0.25f, Night) }
        'B' -> { r(0f, 0f, 0.9f, 0.5f, Color(0xFFF2EDE2)); val cs = listOf(Color(0xFF3D8FD6), Gold, Color(0xFF3FAE6A)); for (i in -1..1) { val h = 0.5f + (1 - abs(i)) * 0.25f; r(i * 0.3f, 0.5f, 0.12f, h - 0.5f + 0.1f, Color(0xFFF2EDE2)); drawCircle(cs[i + 1], 0.12f * s, Offset(x + i * 0.3f * s, base - (h + 0.2f) * s)); tri(i * 0.3f, h + 0.28f, 0.12f, 0.16f, cs[i + 1]) } }
        'G' -> { r(0f, 0f, 1.1f, 0.08f); r(0f, 0.08f, 1.0f, 0.06f); for (i in 0 until 6) r(-0.42f + i * 0.168f, 0.14f, 0.08f, 0.5f, Color(0xFFEDE3CC)); r(0f, 0.64f, 1.0f, 0.07f); tri(0f, 0.71f, 1.0f, 0.22f, Color(0xFFEDE3CC)) }
        'A' -> { for (d in listOf(-0.32f, 0.32f)) r(d, 0f, 0.24f, 0.8f); r(0f, 0.6f, 0.9f, 0.3f); drawArc(k.skyBottom, 180f, 180f, true, Offset(x - 0.2f * s, base - 0.8f * s), Size(0.4f * s, 0.4f * s)); r(0f, 0.9f, 1.0f, 0.06f, StoneDark) }
        'O' -> { r(0f, 0f, 0.4f, 0.12f, StoneDark); drawPath(poly(x - 0.1f * s, base - 0.12f * s, x + 0.1f * s, base - 0.12f * s, x + 0.05f * s, base - 1.25f * s, x - 0.05f * s, base - 1.25f * s), Stone); tri(0f, 1.25f, 0.1f, 0.12f, Gold) }
        'D' -> { r(0f, 0f, 1.0f, 0.45f, Color(0xFFF4EFE4)); dome(0f, 0.45f, 0.56f, if (k.c.style == 'k') Color(0xFFF4EFE4) else k.roof); drawLine(Gold, Offset(x, base - 0.73f * s), Offset(x, base - 0.9f * s), 0.03f * s); for (d in listOf(-0.56f, 0.56f)) { r(d, 0f, 0.09f, 0.75f, Color(0xFFF4EFE4)); dome(d, 0.75f, 0.12f, Color(0xFFF4EFE4)) }; drawPath(poly(x - 0.1f * s, base, x - 0.1f * s, base - 0.22f * s, x, base - 0.34f * s, x + 0.1f * s, base - 0.22f * s, x + 0.1f * s, base), Night) }
        'I' -> for (i in 0 until 4) { val w = 0.8f - i * 0.16f; r(0f, i * 0.3f, w * 0.6f, 0.22f, Color(0xFFB8322A)); drawPath(Path().apply { val y = base - (i * 0.3f + 0.22f) * s; moveTo(x - w * s / 2, y); quadraticTo(x - w * s * 0.3f, y, x - w * s * 0.25f, y - 0.1f * s); lineTo(x + w * s * 0.25f, y - 0.1f * s); quadraticTo(x + w * s * 0.3f, y, x + w * s / 2, y); close() }, Color(0xFF2F4F4F)); if (i == 3) drawLine(Gold, Offset(x, base - 1.24f * s), Offset(x, base - 1.45f * s), 0.03f * s) }
        'R' -> { val c = Color(0xFFD9412B); for (d in listOf(-0.3f, 0.3f)) r(d, 0f, 0.09f, 0.95f, c); r(0f, 0.7f, 0.8f, 0.07f, c); drawPath(Path().apply { moveTo(x - 0.55f * s, base - 1.02f * s); quadraticTo(x, base - 0.9f * s, x + 0.55f * s, base - 1.02f * s); lineTo(x + 0.52f * s, base - 0.9f * s); lineTo(x - 0.52f * s, base - 0.9f * s); close() }, Color(0xFF2A2A2A)); drawRect(Color(0xFF1FA6C9).copy(alpha = 0.5f), Offset(x - 0.6f * s, base - 0.05f * s), Size(1.2f * s, 0.05f * s)) }
        'U' -> { for (i in 0 until 3) r(0f, i * 0.12f, 1.1f - i * 0.22f, 0.12f, if (i % 2 == 0) Stone else StoneDark); dome(0f, 0.36f, 0.5f, Gold); r(0f, 0.6f, 0.14f, 0.1f, Gold); tri(0f, 0.7f, 0.12f, 0.45f, Gold); for (d in listOf(-0.42f, 0.42f)) { dome(d, 0.24f, 0.16f, Gold); tri(d, 0.3f, 0.06f, 0.2f, Gold) } }
        'Y' -> for (d in listOf(-0.2f, 0.2f)) { val h = if (d < 0) 1.3f else 1.1f; drawRect(Brush.verticalGradient(listOf(Color(0xFFDFF4FF), Color(0xFF4F9BD6), Color(0xFF244A78)), base - h * s, base), Offset(x + d * s - 0.14f * s, base - h * s), Size(0.28f * s, h * s)); for (j in 1 until 8) drawLine(Color.White.copy(alpha = 0.45f), Offset(x + d * s - 0.14f * s, base - j * h * s / 8), Offset(x + d * s + 0.14f * s, base - j * h * s / 8), 1.5f); tri(d, h, 0.1f, 0.2f, Color(0xFF244A78)) }
        'V' -> { r(0f, 0f, 1.2f, 0.12f, Color(0xFFB9A58A)); for (i in 0 until 3) drawPath(Path().apply { val bx = x + (-0.4f + i * 0.34f) * s; moveTo(bx, base - 0.12f * s); quadraticTo(bx + 0.05f * s, base - (0.8f - i * 0.12f) * s, bx + 0.42f * s, base - (0.85f - i * 0.15f) * s); quadraticTo(bx + 0.34f * s, base - 0.4f * s, bx + 0.4f * s, base - 0.12f * s); close() }, if (i == 1) Color(0xFFF2F2EA) else Color.White) }
        'F' -> { r(0f, 0f, 1.3f, 0.35f); for (i in 0 until 7) r(-0.54f + i * 0.18f, 0.35f, 0.09f, 0.07f); r(0f, 0f, 0.4f, 0.7f, if (k.c.style == 'a') earth else Stone); for (i in 0 until 3) r(-0.13f + i * 0.13f, 0.7f, 0.07f, 0.07f, if (k.c.style == 'a') earth else Stone); r(0f, 0f, 0.14f, 0.22f, Night); drawPath(poly(x + 0.02f * s, base - 0.77f * s, x + 0.02f * s, base - 1.0f * s, x + 0.2f * s, base - 0.93f * s), k.accent) }
        'L' -> { drawPath(poly(x - 0.16f * s, base, x + 0.16f * s, base, x + 0.09f * s, base - 1.0f * s, x - 0.09f * s, base - 1.0f * s), Color.White); for (i in 0 until 3) r(0f, 0.15f + i * 0.3f, 0.26f - i * 0.04f, 0.12f, Danger); r(0f, 1.0f, 0.2f, 0.14f, Color(0xFFFFF2B0)); tri(0f, 1.14f, 0.24f, 0.12f, StoneDark)
            drawCircle(Color(0xFFFFF2B0).copy(alpha = 0.3f + 0.3f * abs(sin(t * 2f))), 0.3f * s, Offset(x, base - 1.07f * s)) }
        'X' -> { for (i in 0 until 4) r(0f, i * 0.17f, 1.1f - i * 0.24f, 0.17f, if (i % 2 == 0) Stone else Color(0xFFBFAE88)); r(0f, 0.68f, 0.26f, 0.16f); r(0f, 0.7f, 0.1f, 0.12f, Night); drawPath(poly(x - 0.09f * s, base, x + 0.09f * s, base, x + 0.05f * s, base - 0.68f * s, x - 0.05f * s, base - 0.68f * s), StoneDark) }
        'Z' -> for (d in listOf(-0.25f, 0.25f)) { val h = if (d < 0) 0.95f else 0.75f; drawRoundRect(Color(0xFF7D766A), Offset(x + d * s - 0.16f * s, base - h * s), Size(0.32f * s, h * s), CornerRadius(0.08f * s)); r(d, h - 0.26f, 0.34f, 0.06f, Color(0xFF55504A)); r(d, h - 0.55f, 0.08f, 0.28f, Color(0xFF6A6459)); r(d, h - 0.7f, 0.16f, 0.04f, Color(0xFF55504A)) }
        'E' -> { drawPath(poly(x - 0.2f * s, base, x + 0.2f * s, base, x + 0.14f * s, base - 0.85f * s, x - 0.14f * s, base - 0.85f * s), Color(0xFF9A7B5A)); for (i in 0 until 5) { val a = -PI + (i + 0.5) * PI / 5; val e = Offset(x + (cos(a) * 0.4f * s).toFloat(), base - 0.85f * s + (sin(a) * 0.3f * s).toFloat()); drawLine(Color(0xFF9A7B5A), Offset(x, base - 0.8f * s), e, 0.05f * s, StrokeCap.Round); drawCircle(Color(0xFF5E8F3A), 0.1f * s, e) } }
        'J' -> { drawRoundRect(Stone, Offset(x - 0.6f * s, base - 0.7f * s), Size(1.2f * s, 0.7f * s), CornerRadius(0.2f * s)); for (row in 0 until 3) for (i in 0 until 7) drawArc(Night.copy(alpha = 0.75f), 180f, 180f, true, Offset(x - 0.52f * s + i * 0.15f * s, base - (0.2f + row * 0.22f) * s), Size(0.1f * s, 0.16f * s)) }
        else -> { // merveille naturelle : sommet enneigé et cascade
            drawPath(poly(x - 0.75f * s, base, x + 0.75f * s, base, x + 0.2f * s, base - 0.9f * s, x - 0.05f * s, base - 1.1f * s, x - 0.3f * s, base - 0.75f * s), k.far)
            drawPath(poly(x + 0.2f * s, base - 0.9f * s, x - 0.05f * s, base - 1.1f * s, x - 0.2f * s, base - 0.88f * s, x - 0.02f * s, base - 0.95f * s, x + 0.08f * s, base - 0.85f * s), if (k.c.biome == 'd') Color(0xFFF2D9A0) else Color.White)
            val f = (t * 0.8f) % 1f
            drawLine(Color(0xFFBFEFFF), Offset(x + 0.25f * s, base - 0.55f * s), Offset(x + 0.25f * s, base), 0.07f * s)
            for (i in 0 until 3) drawCircle(Color.White, 0.025f * s, Offset(x + 0.25f * s, base - 0.55f * s + ((f + i / 3f) % 1f) * 0.55f * s))
        }
    }
}

/** Un des 8 emplacements du village : `x` = centre, `base` = ligne de sol, `u` = taille de référence. */
fun DrawScope.building(type: Int, level: Int, damaged: Boolean, k: Look, x: Float, base: Float, u: Float, t: Float) {
    if (level <= 0) { // terrain à bâtir
        drawOval(Color.Black.copy(alpha = 0.18f), Offset(x - u * 0.45f, base - u * 0.08f), Size(u * 0.9f, u * 0.16f))
        drawRect(Wood, Offset(x - u * 0.03f, base - u * 0.42f), Size(u * 0.06f, u * 0.42f))
        drawRoundRect(Color(0xFFE9D8B0), Offset(x - u * 0.22f, base - u * 0.52f), Size(u * 0.44f, u * 0.2f), CornerRadius(u * 0.03f))
        return
    }
    val s = u * (0.62f + 0.19f * level)
    drawOval(Color.Black.copy(alpha = 0.2f), Offset(x - s * 0.6f, base - s * 0.07f), Size(s * 1.2f, s * 0.14f))
    when (type) {
        0 -> { // porte du village
            for (d in listOf(-1f, 1f)) drawRect(Brush.horizontalGradient(listOf(k.wall, k.wallDark), x + d * s * 0.36f - s * 0.1f, x + d * s * 0.36f + s * 0.1f), Offset(x + d * s * 0.36f - s * 0.1f, base - s * 0.7f), Size(s * 0.2f, s * 0.7f))
            drawArc(k.roof, 180f, 180f, false, Offset(x - s * 0.36f, base - s * 1.0f), Size(s * 0.72f, s * 0.66f), style = Stroke(s * 0.14f))
            if (level >= 2) for (d in listOf(-1f, 1f)) { drawLine(Wood, Offset(x + d * s * 0.36f, base - s * 0.7f), Offset(x + d * s * 0.36f, base - s * 1.15f), 2.5f); drawRect(k.accent, Offset(x + d * s * 0.36f, base - s * 1.15f + sin(t * 4f) * 2f), Size(s * 0.2f, s * 0.13f)) }
        }
        1 -> { house(k, x - s * 0.22f, base, s * 0.56f, s * 0.48f, damaged); if (level >= 2) house(k, x + s * 0.34f, base, s * 0.42f, s * 0.38f, damaged); if (level >= 3) house(k, x + s * 0.02f, base + s * 0.04f, s * 0.3f, s * 0.26f, damaged) }
        2 -> for (n in 0 until (if (level >= 3) 2 else 1)) { // marché : étals à auvent rayé
            val cx = x + (n - (if (level >= 3) 0.5f else 0f)) * s * 0.62f; val w = s * 0.52f
            for (d in listOf(-1f, 1f)) drawRect(Wood, Offset(cx + d * w * 0.46f - 2f, base - s * 0.55f), Size(4f, s * 0.55f))
            for (i in 0 until 5) drawPath(poly(cx - w / 2 + i * w / 5, base - s * 0.55f, cx - w / 2 + (i + 1) * w / 5, base - s * 0.55f, cx - w * 0.42f + (i + 1) * w * 0.168f, base - s * 0.75f, cx - w * 0.42f + i * w * 0.168f, base - s * 0.75f), if (i % 2 == 0) k.accent else Color.White)
            drawRect(Wood, Offset(cx - w * 0.46f, base - s * 0.24f), Size(w * 0.92f, s * 0.06f))
            for (i in 0 until level * 2) drawCircle(hsv(i * 70 + k.v * 20, 0.8f, 0.95f), s * 0.045f, Offset(cx - w * 0.34f + i * w * 0.14f, base - s * 0.28f))
        }
        3 -> {
            monument(k, x, base, s * 1.05f, t)
            if (level < Rules.MAX_LEVEL) for (i in 0 until 3) drawLine(Wood.copy(alpha = 0.8f), Offset(x - s * 0.6f + i * s * 0.6f, base), Offset(x - s * 0.6f + i * s * 0.6f, base - s * (0.5f + level * 0.2f)), 3f)
        }
        4 -> { house(k, x, base, s * 0.55f, s * 0.8f, damaged); if (level >= 2) house(k, x + s * 0.44f, base, s * 0.3f, s * 0.5f, damaged) }
        5 -> { // jardin
            drawOval(k.groundDark, Offset(x - s * 0.55f, base - s * 0.12f), Size(s * 1.1f, s * 0.2f))
            for (i in 0..level) tree(k.c.biome, x + (i - level / 2f) * s * 0.36f, base - (i % 2) * s * 0.04f, s * (0.7f + (i % 2) * 0.2f))
            if (level >= 3) for (i in 0 until 6) drawCircle(hsv(i * 60, 0.7f, 1f), s * 0.035f, Offset(x - s * 0.45f + i * s * 0.18f, base - s * 0.02f))
        }
        6 -> { // atelier : cheminée qui fume, caisses
            house(k, x, base, s * 0.62f, s * 0.44f, damaged)
            drawRect(k.wallDark, Offset(x + s * 0.18f, base - s * 0.78f), Size(s * 0.1f, s * 0.3f))
            for (i in 0 until 3) { val f = (t * 0.4f + i * 0.33f) % 1f; drawCircle(Color.White.copy(alpha = 0.5f * (1f - f)), s * (0.05f + f * 0.08f), Offset(x + s * 0.23f + sin(t + i) * s * 0.04f, base - s * 0.8f - f * s * 0.4f)) }
            for (i in 0 until level) drawRect(Color(0xFFB98A4E), Offset(x - s * 0.62f + i * s * 0.13f, base - s * 0.14f), Size(s * 0.12f, s * 0.14f))
        }
        else -> { // palais
            if (level >= 2) for (d in listOf(-1f, 1f)) house(k, x + d * s * 0.48f, base, s * 0.26f, s * 0.7f, damaged)
            house(k, x, base, s * 0.78f, s * 0.5f, damaged)
            drawLine(Wood, Offset(x, base - s * 0.9f), Offset(x, base - s * 1.3f), 2.5f); drawRect(k.accent, Offset(x, base - s * 1.3f + sin(t * 4f) * 2f), Size(s * 0.22f, s * 0.14f))
        }
    }
    if (level >= Rules.MAX_LEVEL && !damaged) star(Offset(x + s * 0.5f, base - s * 0.2f), s * 0.1f, Gold)
    if (damaged) {
        drawLine(Night, Offset(x - s * 0.2f, base - s * 0.5f), Offset(x, base - s * 0.28f), s * 0.04f)
        drawLine(Night, Offset(x, base - s * 0.28f), Offset(x - s * 0.08f, base - s * 0.08f), s * 0.04f)
        for (i in 0 until 3) { val f = (t * 0.4f + i * 0.33f) % 1f; drawCircle(Color.DarkGray.copy(alpha = 0.6f * (1f - f)), s * (0.1f + f * 0.14f), Offset(x + sin(i + t) * s * 0.1f, base - s * 0.6f - f * s * 0.6f)) }
    }
}

/** Position (centre au sol) et taille de chaque emplacement : monument et grands édifices à l'arrière. */
fun slot(i: Int, size: Size): Triple<Float, Float, Float> {
    val back = listOf(4, 3, 7, 5).indexOf(i)
    return if (back >= 0) Triple(size.width * (0.13f + back * 0.25f), size.height * 0.68f, size.width * (if (i == 3) 0.2f else 0.17f))
    else Triple(size.width * (0.15f + listOf(0, 1, 2, 6).indexOf(i) * 0.24f), size.height * 0.94f, size.width * 0.2f)
}

fun DrawScope.villageScene(village: Int, levels: List<Int>, damaged: List<Boolean>, t: Float, strike: Int = -1, strikeT: Float = 0f) {
    val k = Look(village)
    val w = size.width; val h = size.height
    val biome = k.c.biome
    clipRect {
        drawRect(Brush.verticalGradient(listOf(k.skyTop, k.skyBottom), 0f, h * 0.6f))
        val rnd = Random(village * 31 + 7)
        val sun = Offset(w * (0.2f + (village % 4) * 0.2f), h * 0.17f)
        drawCircle(Brush.radialGradient(listOf(Color(0x88FFF6C8), Color.Transparent), sun, w * 0.28f), w * 0.28f, sun)
        drawCircle(if (biome == 'n') Color(0xFFF4F8FF) else Color(0xFFFFF2B0), w * 0.065f, sun)
        repeat(4) { val cx = ((rnd.nextFloat() + t * 0.008f * (1 + it % 2)) % 1.3f - 0.15f) * w; val cy = h * (0.08f + rnd.nextFloat() * 0.25f)
            for (j in 0 until 3) drawOval(Color.White.copy(alpha = 0.75f), Offset(cx + j * w * 0.045f, cy - (j % 2) * h * 0.012f), Size(w * 0.13f, h * 0.04f)) }
        // Arrière-plan propre au paysage.
        val horizon = h * 0.52f
        when (biome) {
            'c' -> { drawRect(Brush.verticalGradient(listOf(k.far, Color(0xFF7FD8E8)), horizon - h * 0.06f, horizon + h * 0.1f), Offset(0f, horizon - h * 0.06f), Size(w, h * 0.2f))
                for (i in 0 until 5) drawLine(Color.White.copy(alpha = 0.5f), Offset(((i * 0.23f + t * 0.02f) % 1f) * w, horizon + i * h * 0.012f), Offset(((i * 0.23f + t * 0.02f) % 1f) * w + w * 0.08f, horizon + i * h * 0.012f), 2f)
                drawArc(Color(0xFF3F9A55), 180f, 180f, true, Offset(w * 0.68f, horizon - h * 0.1f), Size(w * 0.26f, h * 0.12f)) }
            'm', 'n' -> for (i in 0 until 5) { val px = w * (i * 0.25f - 0.05f + (village % 3) * 0.03f); val ph = h * (0.22f + ((i * 7 + village) % 4) * 0.045f)
                drawPath(poly(px - w * 0.22f, horizon + h * 0.05f, px + w * 0.22f, horizon + h * 0.05f, px, horizon - ph), k.far)
                drawPath(poly(px - w * 0.055f, horizon - ph * 0.72f, px + w * 0.055f, horizon - ph * 0.72f, px, horizon - ph), Color.White) }
            else -> for (layer in 0 until 2) drawPath(Path().apply {
                moveTo(0f, h); val y0 = horizon - h * 0.02f + layer * h * 0.05f; val amp = if (biome == 't' || biome == 's') 0.012f else if (biome == 'd') 0.045f else 0.035f
                for (i in 0..24) lineTo(w * i / 24f, y0 + sin(i * (if (biome == 'j') 1.3f else 0.6f) + village + layer * 2f) * h * amp)
                lineTo(w, h); close() }, if (layer == 0) k.far else androidx.compose.ui.graphics.lerp(k.far, k.ground, 0.5f))
        }
        if (biome == 's' || biome == 'j' || biome == 'p') for (i in 0 until 5) tree(biome, w * (0.08f + i * 0.21f + (village % 5) * 0.01f), horizon + h * 0.04f, h * 0.09f)
        drawRect(Brush.verticalGradient(listOf(k.ground, k.groundDark), horizon + h * 0.03f, h), Offset(0f, horizon + h * 0.03f), Size(w, h))
        drawPath(Path().apply { moveTo(w * 0.42f, h); quadraticTo(w * 0.5f, h * 0.8f, w * 0.46f, h * 0.7f); lineTo(w * 0.54f, h * 0.7f); quadraticTo(w * 0.6f, h * 0.8f, w * 0.62f, h); close() }, k.groundDark.copy(alpha = 0.5f))
        // Route pavée qui traverse le village, touffes d'herbe et fleurs.
        val road = Path().apply { moveTo(-w * 0.05f, h * 0.8f); quadraticTo(w * 0.5f, h * 0.74f, w * 1.05f, h * 0.8f); lineTo(w * 1.05f, h * 0.86f); quadraticTo(w * 0.5f, h * 0.8f, -w * 0.05f, h * 0.86f); close() }
        drawPath(road, androidx.compose.ui.graphics.lerp(k.groundDark, Color(0xFFE9D8B0), 0.45f))
        for (i in 0 until 14) drawOval(Color.White.copy(alpha = 0.22f), Offset(w * (i / 14f) + w * 0.02f, h * (0.795f - 0.03f * sin(i / 14f * 3.14f)) + (i % 2) * h * 0.02f), Size(w * 0.035f, h * 0.008f))
        repeat(16) { val gx = rnd.nextFloat() * w; val gy = h * (0.6f + rnd.nextFloat() * 0.38f); val gs = w * 0.012f
            if (it % 4 == 0) drawCircle(hsv(it * 53, 0.6f, 1f), gs * 0.6f, Offset(gx, gy))
            else for (j in -1..1) drawLine(androidx.compose.ui.graphics.lerp(k.ground, Color.White, 0.25f), Offset(gx + j * gs * 0.5f, gy), Offset(gx + j * gs, gy - gs * 1.4f), 2f) }
        for (i in listOf(4, 3, 7, 5)) { val (x, base, u) = slot(i, size); building(i, levels[i], damaged[i], k, x, base, u, t + i) }
        // Habitants qui se promènent.
        for (i in 0 until 4) { val dir = if (i % 2 == 0) 1f else -1f; val f = ((t * 0.035f * (1f + i * 0.25f) + i * 0.31f) % 1.2f)
            villager((if (dir > 0) f else 1.1f - f) * w - w * 0.05f, h * (0.805f + (i % 2) * 0.035f), w * 0.022f, hsv(k.v * 47 + i * 90, 0.65f, 0.9f), i, t * 7f + i, dir) }
        for (i in listOf(0, 1, 2, 6)) { val (x, base, u) = slot(i, size); building(i, levels[i], damaged[i], k, x, base, u, t + i) }
        // Vie du décor : neige, poussière ou oiseaux.
        repeat(14) {
            val sx = rnd.nextFloat(); val sy = rnd.nextFloat(); val sp = 0.03f + rnd.nextFloat() * 0.05f
            val f = (sy + t * sp) % 1f
            when (biome) {
                'n' -> drawCircle(Color.White.copy(alpha = 0.9f), 3f, Offset((sx + sin(t + it) * 0.02f) * w, f * h))
                'd', 's', 't' -> drawCircle(Color(0xFFFFF2D0).copy(alpha = 0.35f), 2.5f, Offset(((sx + t * sp) % 1f) * w, (0.5f + sy * 0.45f) * h))
                else -> if (it < 5) { val bx = ((sx + t * sp * 0.6f) % 1.1f) * w; val by = h * (0.1f + sy * 0.25f); val flap = sin(t * 8f + it) * 4f
                    drawLine(Night.copy(alpha = 0.6f), Offset(bx - 9f, by - flap), Offset(bx, by), 2f); drawLine(Night.copy(alpha = 0.6f), Offset(bx, by), Offset(bx + 9f, by - flap), 2f) }
            }
        }
        if (strike >= 0) { // éclair d'attaque puis explosion
            val (x, base, u) = slot(strike, size)
            val hit = Offset(x, base - u * 0.5f)
            if (strikeT < 0.4f) { // le marteau s'abat sur la cible
                val sw = (strikeT / 0.3f).coerceIn(0f, 1f)
                rotate(-70f + sw * sw * 95f, Offset(hit.x + u * 0.9f, hit.y)) { symbol(2, Offset(hit.x + u * 0.25f, hit.y - u * 0.75f), u * 0.75f) }
            }
            val f = ((strikeT - 0.2f) / 0.8f).coerceIn(0f, 1f)
            if (f > 0f) {
                drawCircle(Color.White.copy(alpha = (1f - f) * 0.9f), u * (0.3f + f * 1.4f), hit)
                drawCircle(Color(0xFFFF8A3D).copy(alpha = (1f - f) * 0.7f), u * (0.2f + f), hit)
                for (i in 0 until 10) { val a = i * 0.63f; drawCircle(Gold.copy(alpha = 1f - f), 6f, Offset(hit.x + cos(a) * u * f * 1.6f, hit.y + sin(a) * u * f * 1.6f + f * f * u)) }
            }
        }
    }
}

/** Petit habitant qui marche : jambes animées, vêtement aux couleurs du pays. */
fun DrawScope.villager(x: Float, y: Float, s: Float, cloth: Color, tone: Int, phase: Float, dir: Float) {
    val skin = listOf(Color(0xFF6B4226), Color(0xFFB47B4E), Color(0xFFE2B48A), Color(0xFF8D5A3B))[tone % 4]
    drawOval(Color.Black.copy(alpha = 0.2f), Offset(x - s * 0.8f, y - s * 0.15f), Size(s * 1.6f, s * 0.4f))
    val sw = sin(phase) * s * 0.6f
    drawLine(Color(0xFF3A2A4A), Offset(x, y - s * 1.2f), Offset(x + sw, y), s * 0.4f, StrokeCap.Round)
    drawLine(Color(0xFF3A2A4A), Offset(x, y - s * 1.2f), Offset(x - sw, y), s * 0.4f, StrokeCap.Round)
    drawRoundRect(cloth, Offset(x - s * 0.6f, y - s * 2.7f), Size(s * 1.2f, s * 1.7f), CornerRadius(s * 0.5f))
    drawLine(skin, Offset(x + dir * s * 0.3f, y - s * 2.3f), Offset(x + dir * s * 0.3f - sw * 0.7f, y - s * 1.4f), s * 0.3f, StrokeCap.Round)
    drawCircle(skin, s * 0.62f, Offset(x, y - s * 3.2f))
    drawCircle(Night, s * 0.1f, Offset(x + dir * s * 0.28f, y - s * 3.25f))
}

/** Illustration d'une carte, selon son album. */
fun DrawScope.cardArt(id: Int, t: Float) {
    val i = id % 8; val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension * 0.34f
    val hue = id * 47
    when (id / 8) {
        0 -> ghost(c.x, c.y, r * 1.25f, hsv(hue, 0.25f, 1f), listOf(Mood.IDLE, Mood.DANCE, Mood.IDLE, Mood.IDLE, Mood.WIN, Mood.IDLE, Mood.SAD, Mood.WIN)[i], t)
        1 -> { // créature
            val body = hsv(hue, 0.6f, 0.85f)
            for (d in listOf(-1f, 1f)) drawPath(poly(c.x + d * r * 0.35f, c.y - r * 0.6f, c.x + d * r * 0.95f, c.y - r * (1.0f + i * 0.05f), c.x + d * r * 0.75f, c.y - r * 0.2f), body)
            drawCircle(Brush.radialGradient(listOf(Color.White, body), Offset(c.x - r * 0.3f, c.y - r * 0.3f), r * 1.3f), r * 0.85f, c)
            for (k in 0..i % 3) { val ex = c.x + (k - (i % 3) / 2f) * r * 0.5f; drawCircle(Color.White, r * 0.2f, Offset(ex, c.y - r * 0.1f)); drawCircle(Night, r * 0.1f, Offset(ex + sin(t) * r * 0.04f, c.y - r * 0.1f)) }
            drawArc(Night, 10f, 160f, false, Offset(c.x - r * 0.35f, c.y + r * 0.1f), Size(r * 0.7f, r * 0.4f), style = Stroke(r * 0.07f, cap = StrokeCap.Round))
        }
        2 -> building(listOf(1, 4, 6, 7, 0, 2, 3, 3)[i], 3, false, Look(listOf(0, 54, 21, 14, 100, 135, 17, 139)[i]), c.x, c.y + r * 1.05f, r * 1.2f, t)
        3 -> { // relique : gemme taillée
            val g = hsv(hue, 0.7f, 1f)
            drawCircle(Brush.radialGradient(listOf(g.copy(alpha = 0.5f), Color.Transparent), c, r * 1.4f), r * 1.4f, c)
            val n = 4 + i % 4
            val p = Path()
            for (k in 0 until n) { val a = -PI / 2 + k * 2 * PI / n; val x = c.x + (cos(a) * r).toFloat(); val y = c.y + (sin(a) * r * 1.15f).toFloat(); if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            p.close(); drawPath(p, Brush.linearGradient(listOf(Color.White, g, hsv(hue, 0.9f, 0.5f)), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r)))
            drawPath(p, Color.White.copy(alpha = 0.7f), style = Stroke(r * 0.05f))
            star(Offset(c.x - r * 0.3f, c.y - r * 0.4f), r * (0.15f + 0.05f * sin(t * 3f)), Color.White)
        }
        4 -> { // sorcier : fantôme coiffé
            ghost(c.x, c.y + r * 0.3f, r * 1.1f, hsv(hue, 0.2f, 1f), Mood.IDLE, t)
            val hat = hsv(hue + 180, 0.7f, 0.6f); val y = c.y - r * 0.32f + sin(t * 2f) * r * 0.066f
            drawOval(hat, Offset(c.x - r * 0.8f, y - r * 0.1f), Size(r * 1.6f, r * 0.26f))
            drawPath(poly(c.x - r * 0.45f, y, c.x + r * 0.45f, y, c.x + r * 0.2f, y - r * (0.9f + i * 0.04f)), hat)
            star(Offset(c.x, y - r * 0.35f), r * 0.14f, Gold)
        }
        else -> when (i) { 0 -> symbol(0, c, r); 1 -> symbol(1, c, r); 2 -> chest(c, r, 0); 3 -> { drawCircle(Brush.radialGradient(listOf(Color(0xFF6A5A8A), Color(0xFF14101F)), Offset(c.x - r * 0.3f, c.y - r * 0.3f), r), r * 0.8f, c); drawCircle(Color.White, r * 0.14f, Offset(c.x - r * 0.3f, c.y - r * 0.3f)) }
            4 -> symbol(11, c, r); 5 -> { star(c, r, Color(0xFFFF4D6D)); star(c, r * 0.5f, Color(0xFFFFB3C1)) }; 6 -> chest(c, r, 2); else -> { symbol(7, c, r * 1.2f); symbol(0, c, r * 0.55f) } }
    }
}

@Composable
fun Icon(id: Int, modifier: Modifier) = Canvas(modifier) { symbol(id, Offset(size.width / 2, size.height / 2), size.minDimension / 2) }
