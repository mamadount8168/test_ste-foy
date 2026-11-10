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
import com.ghost.coin.core.villageOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

val Night = Color(0xFF0A0616)
val Panel = Color(0xFF1A1033)
val Violet = Color(0xFF8B3DFF)
val Gold = Color(0xFFFFC83D)
val GoldDark = Color(0xFFB8801A)
val Electric = Color(0xFF2CC8FF)
val Ink = Color(0xFFF6F1FF)
val Dim = Color(0xFFA79BC9)
val Danger = Color(0xFFFF4D6D)
val RARITY = listOf(Color(0xFF8FA3C8), Electric, Violet, Gold, Color(0xFFFF4DD8))
val AVATARS = listOf(Ink, Color(0xFFBFF6FF), Color(0xFFFFE08A), Color(0xFFE2B8FF), Color(0xFFB8FFC9), Color(0xFFFFC0CB))

fun hsv(h: Int, s: Float, v: Float) = Color.hsv((((h % 360) + 360) % 360).toFloat(), s, v)

/** Palette d'un monde, dérivée de sa teinte. */
class Palette(v: Int) {
    private val w = villageOf(v)
    val skyTop = hsv(w.hue, 0.75f, 0.16f)
    val skyBottom = hsv(w.hue + 25, 0.65f, 0.48f)
    val hill = hsv(w.hue + 10, 0.55f, 0.22f)
    val ground = hsv(w.hue + 15, 0.5f, 0.13f)
    val wall = hsv(w.hue + 20, 0.22f, 0.82f)
    val wallDark = hsv(w.hue + 20, 0.3f, 0.55f)
    val roof = hsv(w.hue + 190, 0.6f, 0.62f)
    val glow = hsv(w.hue + 60, 0.75f, 1f)
    val deco = w.deco
    val roofStyle = w.roof
}

enum class Mood { IDLE, JUMP, DANCE, WIN, SAD, HIT }

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
        0 -> { // pièce
            drawCircle(GoldDark, r * 0.92f, c); drawCircle(goldBrush, r * 0.84f, c)
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

/** Un bâtiment posé sur le sol : `x` = centre, `base` = ligne de sol, `u` = taille de référence. */
fun DrawScope.building(type: Int, level: Int, damaged: Boolean, pal: Palette, x: Float, base: Float, u: Float, t: Float) {
    if (level <= 0) { // terrain à bâtir
        drawOval(Color.Black.copy(alpha = 0.25f), Offset(x - u * 0.45f, base - u * 0.08f), Size(u * 0.9f, u * 0.16f))
        drawRect(pal.wallDark.copy(alpha = 0.7f), Offset(x - u * 0.03f, base - u * 0.42f), Size(u * 0.06f, u * 0.42f))
        drawRoundRect(pal.wall.copy(alpha = 0.7f), Offset(x - u * 0.22f, base - u * 0.52f), Size(u * 0.44f, u * 0.2f), CornerRadius(u * 0.03f))
        return
    }
    val s = u * (0.62f + 0.19f * level)
    val wall = if (damaged) pal.wallDark else pal.wall
    val glow = if (damaged) Color(0xFF3A2A4A) else pal.glow
    drawOval(Color.Black.copy(alpha = 0.3f), Offset(x - s * 0.6f, base - s * 0.07f), Size(s * 1.2f, s * 0.14f))
    fun box(cx: Float, w: Float, h: Float, y: Float = base, c: Color = wall) =
        drawRect(Brush.horizontalGradient(listOf(c, pal.wallDark), cx - w / 2, cx + w / 2), Offset(cx - w / 2, y - h), Size(w, h))
    fun roof(cx: Float, w: Float, h: Float, y: Float) = when (pal.roofStyle) {
        0 -> drawPath(poly(cx - w / 2, y, cx + w / 2, y, cx, y - h), pal.roof)
        1 -> { drawRect(pal.roof, Offset(cx - w / 2, y - h * 0.35f), Size(w, h * 0.35f)); for (i in 0 until 3) drawRect(pal.roof, Offset(cx - w / 2 + i * w * 0.4f, y - h * 0.6f), Size(w * 0.2f, h * 0.3f)) }
        else -> drawArc(pal.roof, 180f, 180f, true, Offset(cx - w / 2, y - h * 0.8f), Size(w, h * 1.6f))
    }
    fun window(cx: Float, cy: Float, w: Float) = drawRoundRect(glow, Offset(cx - w / 2, cy - w * 0.7f), Size(w, w * 1.4f), CornerRadius(w / 2))
    when (type) {
        0 -> { // maison fantôme
            box(x, s * 0.8f, s * 0.6f); roof(x, s * 0.95f, s * 0.5f, base - s * 0.6f)
            drawRect(pal.wallDark, Offset(x + s * 0.18f, base - s * 1.0f), Size(s * 0.12f, s * 0.3f))
            window(x - s * 0.18f, base - s * 0.34f, s * 0.14f); drawRoundRect(pal.roof, Offset(x + s * 0.06f, base - s * 0.32f), Size(s * 0.2f, s * 0.32f), CornerRadius(s * 0.1f))
        }
        1 -> { // château
            box(x, s * 1.0f, s * 0.55f)
            for (d in listOf(-1f, 1f)) { box(x + d * s * 0.42f, s * 0.3f, s * 0.95f); roof(x + d * s * 0.42f, s * 0.38f, s * 0.36f, base - s * 0.95f); window(x + d * s * 0.42f, base - s * 0.65f, s * 0.09f) }
            drawRoundRect(Night, Offset(x - s * 0.12f, base - s * 0.34f), Size(s * 0.24f, s * 0.34f), CornerRadius(s * 0.12f))
        }
        2 -> { // tour magique
            box(x, s * 0.36f, s * 1.15f); roof(x, s * 0.56f, s * 0.42f, base - s * 1.15f)
            window(x, base - s * 0.85f, s * 0.1f); window(x, base - s * 0.45f, s * 0.1f)
            val o = Offset(x, base - s * 1.68f + sin(t * 2f) * s * 0.03f)
            drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), o, s * 0.3f), s * 0.3f, o); drawCircle(glow, s * 0.09f, o)
        }
        3 -> { // banque
            box(x, s * 1.0f, s * 0.14f); box(x, s * 0.9f, s * 0.5f, base - s * 0.14f, pal.wallDark)
            for (i in 0 until 4) drawRect(wall, Offset(x - s * 0.4f + i * s * 0.235f, base - s * 0.64f), Size(s * 0.1f, s * 0.5f))
            drawPath(poly(x - s * 0.55f, base - s * 0.64f, x + s * 0.55f, base - s * 0.64f, x, base - s * 0.98f), pal.roof)
            symbol(0, Offset(x, base - s * 0.76f), s * 0.1f)
        }
        4 -> { // mine d'or
            drawArc(pal.hill, 180f, 180f, true, Offset(x - s * 0.6f, base - s * 0.75f), Size(s * 1.2f, s * 1.5f))
            drawArc(Night, 180f, 180f, true, Offset(x - s * 0.26f, base - s * 0.42f), Size(s * 0.52f, s * 0.84f))
            drawRect(Color(0xFF9B6B3A), Offset(x - s * 0.3f, base - s * 0.46f), Size(s * 0.6f, s * 0.07f))
            for (d in listOf(-1f, 1f)) drawRect(Color(0xFF9B6B3A), Offset(x + d * s * 0.27f - s * 0.035f, base - s * 0.42f), Size(s * 0.07f, s * 0.42f))
            for (i in 0 until level + 1) drawCircle(Gold, s * 0.06f, Offset(x - s * 0.4f + i * s * 0.26f, base - s * 0.04f - (i % 2) * s * 0.03f))
        }
        5 -> { // laboratoire
            box(x, s * 0.85f, s * 0.42f)
            drawArc(glow.copy(alpha = 0.55f), 180f, 180f, true, Offset(x - s * 0.36f, base - s * 0.78f), Size(s * 0.72f, s * 0.72f))
            drawArc(Color.White.copy(alpha = 0.6f), 180f, 180f, false, Offset(x - s * 0.36f, base - s * 0.78f), Size(s * 0.72f, s * 0.72f), style = Stroke(s * 0.03f))
            for (i in 0 until 3) { val k = (t * 0.5f + i * 0.33f) % 1f; drawCircle(glow.copy(alpha = 1f - k), s * 0.05f, Offset(x - s * 0.15f + i * s * 0.15f, base - s * 0.8f - k * s * 0.4f)) }
            window(x - s * 0.22f, base - s * 0.2f, s * 0.1f); window(x + s * 0.22f, base - s * 0.2f, s * 0.1f)
        }
        6 -> { // temple
            for (i in 0 until 3) box(x, s * (1.05f - i * 0.25f), s * 0.2f, base - i * s * 0.2f, if (i % 2 == 0) wall else pal.wallDark)
            for (d in listOf(-1f, 1f)) drawRect(wall, Offset(x + d * s * 0.16f - s * 0.035f, base - s * 0.9f), Size(s * 0.07f, s * 0.3f))
            drawPath(poly(x - s * 0.3f, base - s * 0.9f, x + s * 0.3f, base - s * 0.9f, x, base - s * 1.12f), pal.roof)
            val f = sin(t * 9f) * s * 0.02f
            drawPath(poly(x - s * 0.07f, base - s * 0.6f, x + s * 0.07f, base - s * 0.6f, x + f, base - s * 0.84f), glow)
        }
        else -> { // portail magique
            for (d in listOf(-1f, 1f)) box(x + d * s * 0.36f, s * 0.16f, s * 0.75f)
            drawArc(wall, 180f, 180f, false, Offset(x - s * 0.36f, base - s * 1.1f), Size(s * 0.72f, s * 0.72f), style = Stroke(s * 0.16f))
            val o = Offset(x, base - s * 0.5f)
            drawOval(Brush.radialGradient(listOf(Color.White, glow, Violet.copy(alpha = 0.2f)), o, s * 0.4f), Offset(x - s * 0.26f, base - s * 0.95f), Size(s * 0.52f, s * 0.95f))
            for (i in 0 until 4) { val a = t * 2f + i * 1.57f; drawCircle(Color.White, s * 0.03f, Offset(o.x + cos(a) * s * 0.16f, o.y + sin(a) * s * 0.3f)) }
        }
    }
    if (level >= Rules.MAX_LEVEL && !damaged) star(Offset(x + s * 0.42f, base - s * 0.25f), s * 0.11f, Gold)
    if (damaged) {
        drawLine(Night, Offset(x - s * 0.2f, base - s * 0.5f), Offset(x, base - s * 0.28f), s * 0.04f)
        drawLine(Night, Offset(x, base - s * 0.28f), Offset(x - s * 0.08f, base - s * 0.08f), s * 0.04f)
        for (i in 0 until 3) { val k = (t * 0.4f + i * 0.33f) % 1f; drawCircle(Color.Gray.copy(alpha = 0.5f * (1f - k)), s * (0.1f + k * 0.14f), Offset(x + sin(i + t) * s * 0.1f, base - s * 0.6f - k * s * 0.6f)) }
    }
}

/** Position (centre au sol) et taille de chaque bâtiment dans la scène. */
fun slot(i: Int, size: Size): Triple<Float, Float, Float> {
    val back = listOf(1, 2, 6, 7).indexOf(i)
    return if (back >= 0) Triple(size.width * (0.14f + back * 0.24f), size.height * 0.66f, size.width * 0.17f)
    else Triple(size.width * (0.16f + listOf(0, 3, 4, 5).indexOf(i) * 0.25f), size.height * 0.93f, size.width * 0.21f)
}

fun DrawScope.villageScene(village: Int, levels: List<Int>, damaged: List<Boolean>, t: Float, strike: Int = -1, strikeT: Float = 0f) {
    val pal = Palette(village)
    val w = size.width; val h = size.height
    clipRect {
        drawRect(Brush.verticalGradient(listOf(pal.skyTop, pal.skyBottom)))
        val rnd = Random(village * 31 + 7)
        repeat(40) {
            val o = Offset(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.55f)
            drawCircle(Color.White.copy(alpha = 0.3f + 0.5f * abs(sin(t * (0.6f + rnd.nextFloat()) + it))), 1.2f + rnd.nextFloat() * 1.8f, o)
        }
        val moon = Offset(w * (0.2f + (village % 4) * 0.2f), h * 0.2f)
        drawCircle(Brush.radialGradient(listOf(pal.glow.copy(alpha = 0.45f), Color.Transparent), moon, w * 0.3f), w * 0.3f, moon)
        drawCircle(Color(0xFFFFF8E0), w * 0.075f, moon); drawCircle(Color(0x22000000), w * 0.02f, Offset(moon.x - w * 0.02f, moon.y + w * 0.01f))
        for (layer in 0 until 2) {
            val p = Path().apply {
                moveTo(0f, h); val y0 = h * (0.5f + layer * 0.1f)
                for (k in 0..20) { val x = w * k / 20f; lineTo(x, y0 + sin(k * 0.7f + village + layer * 2f) * h * 0.05f) }
                lineTo(w, h); close()
            }
            drawPath(p, if (layer == 0) pal.hill else pal.ground)
        }
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, pal.ground), h * 0.6f, h * 0.72f), Offset(0f, h * 0.6f), Size(w, h * 0.4f))
        for (i in listOf(1, 2, 6, 7, 0, 3, 4, 5)) {
            val (x, base, u) = slot(i, size)
            building(i, levels[i], damaged[i], pal, x, base, u, t + i)
        }
        // Particules d'ambiance propres au monde.
        repeat(26) {
            val sx = rnd.nextFloat(); val sy = rnd.nextFloat(); val sp = 0.03f + rnd.nextFloat() * 0.06f
            val k = (sy + t * sp) % 1f
            val (o, c) = when (pal.deco) {
                1 -> Offset((sx + sin(t + it) * 0.02f) * w, k * h) to Color.White.copy(alpha = 0.8f)
                2 -> Offset((sx + sin(t * 2 + it) * 0.02f) * w, (1f - k) * h) to Color(0xFFFF8A3D).copy(alpha = 1f - k)
                3 -> Offset(((sx + t * sp) % 1f) * w, (0.55f + sy * 0.4f) * h) to Color(0xFFBFEFFF).copy(alpha = 0.35f)
                4 -> Offset(((sx + k * 0.3f) % 1f) * w, k * h) to Color(0xFFFFB347).copy(alpha = 0.7f)
                else -> Offset((sx + sin(t * 0.7f + it) * 0.03f) * w, (1f - k) * h) to pal.glow.copy(alpha = 0.7f * abs(sin(t + it)))
            }
            drawCircle(c, if (pal.deco == 3) 9f else 3.2f, o)
        }
        if (strike >= 0) { // éclair d'attaque puis explosion
            val (x, base, u) = slot(strike, size)
            val hit = Offset(x, base - u * 0.5f)
            if (strikeT < 0.35f) {
                val p = Path().apply { moveTo(x + u * 0.5f, 0f); lineTo(x - u * 0.2f, hit.y * 0.45f); lineTo(x + u * 0.25f, hit.y * 0.55f); lineTo(hit.x, hit.y) }
                drawPath(p, Electric, style = Stroke(10f, cap = StrokeCap.Round)); drawPath(p, Color.White, style = Stroke(4f, cap = StrokeCap.Round))
            }
            val k = ((strikeT - 0.2f) / 0.8f).coerceIn(0f, 1f)
            if (k > 0f) {
                drawCircle(Color.White.copy(alpha = (1f - k) * 0.9f), u * (0.3f + k * 1.4f), hit)
                drawCircle(Color(0xFFFF8A3D).copy(alpha = (1f - k) * 0.7f), u * (0.2f + k), hit)
                for (i in 0 until 10) { val a = i * 0.63f; drawCircle(Gold.copy(alpha = 1f - k), 6f, Offset(hit.x + cos(a) * u * k * 1.6f, hit.y + sin(a) * u * k * 1.6f + k * k * u)) }
            }
        }
    }
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
        2 -> building(i, 3, false, Palette(id), c.x, c.y + r * 1.05f, r * 1.25f, t)
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
