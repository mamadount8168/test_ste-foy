package com.ghost.batterie

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val Bg = Color(0xFF05060A)
private val Ink = Color(0xFFF4F7FF)
private val Dim = Color(0xFF8B93A7)
private val Glass = Color(0x0FFFFFFF)
private val Line = Color(0x1FFFFFFF)

private fun PowerMode?.color() = when (this) {
    PowerMode.FAIBLE -> Color(0xFF3DF5C4)
    PowerMode.NORMAL -> Color(0xFF6C8CFF)
    PowerMode.ULTIME -> Color(0xFFFF3D8B)
    null -> Color(0xFFB9C2D6)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ModeEngine.init(this)
        if (ModeEngine.prefs(this).getBoolean("guardian", false)) runCatching { GhostService.set(this, true) }
        setContent { GhostApp() }
    }
}

@Composable
private fun GhostApp() {
    val ctx = LocalContext.current
    val mode by ModeEngine.current.collectAsState()
    var info by remember { mutableStateOf(readBattery(ctx)) }
    var canWrite by remember { mutableStateOf(ModeEngine.canWrite(ctx)) }
    var canSecure by remember { mutableStateOf(ModeEngine.canSecure(ctx)) }
    var toast by remember { mutableStateOf<String?>(null) }
    val prefs = remember { ModeEngine.prefs(ctx) }
    var guardian by remember { mutableStateOf(prefs.getBoolean("guardian", false)) }
    var auto by remember { mutableStateOf(prefs.getBoolean("auto", false)) }
    var threshold by remember { mutableFloatStateOf(prefs.getInt("threshold", 15).toFloat()) }

    LaunchedEffect(Unit) {
        while (true) {
            info = readBattery(ctx); canWrite = ModeEngine.canWrite(ctx); canSecure = ModeEngine.canSecure(ctx)
            delay(1500)
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    fun setGuardian(on: Boolean) {
        guardian = on
        prefs.edit().putBoolean("guardian", on).apply()
        runCatching { GhostService.set(ctx, on) }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setGuardian(true) }

    val accent by animateColorAsState(mode.color(), tween(700), label = "accent")

    Box(Modifier.fillMaxSize().background(Bg)) {
        Aurora(accent)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))
            Text("GHOST BATTERIE", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
            Text(
                if (mode == null) "Aucun mode actif" else "Mode ${mode!!.title} actif",
                color = accent, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Gauge(info, accent)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Température", "%.1f °C".format(info.tempC), Modifier.weight(1f))
                Stat("Courant", "${info.currentMa} mA", Modifier.weight(1f))
                Stat("Puissance", "%.2f W".format(info.watts), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Tension", "%.2f V".format(info.volts), Modifier.weight(1f))
                Stat("Santé", info.health, Modifier.weight(1f))
                Stat("Capacité", if (info.capacityMah > 0) "${info.capacityMah} mAh" else "—", Modifier.weight(1f))
            }

            Section("MODES")
            PowerMode.entries.forEach { m ->
                ModeCard(m, mode == m, canSecure) {
                    if (mode == m) {
                        ModeEngine.deactivate(ctx); toast = "Réglages d'origine restaurés"
                    } else {
                        val r = ModeEngine.apply(ctx, m)
                        toast = "Mode ${m.title} : ${r.applied} réglages appliqués, ${r.killed} apps mises en veille" +
                            if (r.locked > 0) " · ${r.locked} verrouillés (voir Autorisations)" else ""
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Section("GARDIEN")
            Panel {
                Toggle("Surveillance permanente", "Notification en direct et nettoyage régulier de l'arrière-plan", guardian, accent) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else setGuardian(on)
                }
                Spacer(Modifier.height(14.dp))
                Toggle("Ultime automatique", "Sous ${threshold.toInt()} %, retour au mode précédent à la recharge", auto, accent) { on ->
                    auto = on; prefs.edit().putBoolean("auto", on).apply()
                    if (on && !guardian) {
                        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else setGuardian(true)
                    }
                }
                AnimatedVisibility(auto) {
                    Slider(
                        value = threshold, onValueChange = { threshold = it },
                        onValueChangeFinished = { prefs.edit().putInt("threshold", threshold.toInt()).apply() },
                        valueRange = 5f..50f,
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Line),
                    )
                }
            }

            Section("AUTORISATIONS")
            Panel {
                Perm("Réglages système", "Luminosité et veille de l'écran", canWrite, accent) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }
                Spacer(Modifier.height(14.dp))
                Perm("Mode avancé", "Économiseur Android, animations, GPS, AOD, scans", canSecure, accent, null)
                if (!canSecure) {
                    Text(
                        "À accorder une seule fois depuis un ordinateur, téléphone branché en débogage USB :",
                        color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        "adb shell pm grant com.ghost.batterie android.permission.WRITE_SECURE_SETTINGS",
                        color = Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x33000000)).padding(10.dp),
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }

        AnimatedVisibility(
            toast != null, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
        ) {
            Text(
                toast.orEmpty(), color = Bg, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(accent).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

/** Fond vivant : deux halos de lumière qui dérivent lentement. */
@Composable
private fun Aurora(accent: Color) {
    val t by rememberInfiniteTransition(label = "aurora").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "t",
    )
    Canvas(Modifier.fillMaxSize()) {
        fun halo(cx: Float, cy: Float, r: Float, c: Color) {
            val o = Offset(cx, cy)
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), o, r), r, o)
        }
        halo(size.width * (0.25f + 0.2f * cos(t)), size.height * (0.16f + 0.05f * sin(t)), size.width * 0.9f, accent.copy(alpha = 0.26f))
        halo(size.width * (0.8f + 0.15f * sin(t * 2)), size.height * (0.62f + 0.1f * cos(t)), size.width * 0.8f, Color(0xFF5B2BFF).copy(alpha = 0.16f))
    }
}

@Composable
private fun Gauge(info: BatteryInfo, accent: Color) {
    val level by animateFloatAsState(info.level / 100f, tween(1200, easing = FastOutSlowInEasing), label = "level")
    val inf = rememberInfiniteTransition(label = "gauge")
    val orbit by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(if (info.charging) 2500 else 9000, easing = LinearEasing)), label = "orbit")
    val bob by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val pulse by inf.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    val blink by inf.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 4200; 1f at 0; 1f at 3900; 0.1f at 4050; 1f at 4200 }), label = "blink",
    )

    Box(Modifier.padding(vertical = 22.dp).size(270.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 14.dp.toPx()
            val inset = w * 2
            val arc = Size(size.width - inset * 2, size.height - inset * 2)
            val tl = Offset(inset, inset)
            drawArc(Line, 135f, 270f, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
            // Halo lumineux : le même arc, plus large et translucide.
            for (k in 3 downTo 1) drawArc(
                accent.copy(alpha = 0.07f * pulse), 135f, 270f * level, false, tl, arc,
                style = Stroke(w + k * 9.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                Brush.linearGradient(listOf(accent, Ink)), 135f, 270f * level, false, tl, arc,
                style = Stroke(w, cap = StrokeCap.Round),
            )
            // Particule en orbite.
            val r = arc.width / 2 + w * 1.4f
            val a = Math.toRadians(orbit.toDouble())
            val p = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
            drawCircle(accent.copy(alpha = 0.35f), 7.dp.toPx(), p)
            drawCircle(Ink, 2.5.dp.toPx(), p)
            ghost(center.x, center.y - 52.dp.toPx() + bob * 5.dp.toPx(), 54.dp.toPx(), accent, blink)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 58.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${(level * 100).toInt()}", color = Ink, fontSize = 64.sp, fontWeight = FontWeight.Light)
                Text("%", color = Dim, fontSize = 22.sp, modifier = Modifier.padding(bottom = 14.dp, start = 2.dp))
            }
            Text(
                if (info.charging) "En charge · ${info.source}" else info.source,
                color = accent, fontSize = 12.sp, letterSpacing = 1.sp,
            )
            Text(
                when {
                    info.remainingMin < 0 -> "Estimation en cours"
                    info.charging -> "Pleine dans ${fmt(info.remainingMin)}"
                    else -> "Reste environ ${fmt(info.remainingMin)}"
                },
                color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun fmt(min: Long) = if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)}" else "$min min"

private fun DrawScope.ghost(cx: Float, cy: Float, w: Float, accent: Color, eye: Float) {
    val h = w * 1.15f
    val l = cx - w / 2
    val t = cy - h / 2
    val r = cx + w / 2
    val b = cy + h / 2
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), Offset(cx, cy), w * 1.2f), w * 1.2f, Offset(cx, cy))
    val body = Path().apply {
        moveTo(l, b)
        lineTo(l, t + w / 2)
        arcTo(Rect(l, t, r, t + w), 180f, 180f, false)
        lineTo(r, b)
        val seg = w / 3
        for (i in 0 until 3) quadraticTo(r - seg * i - seg / 2, b - w * 0.26f, r - seg * (i + 1), b)
        close()
    }
    drawPath(body, Brush.verticalGradient(listOf(Ink, accent.copy(alpha = 0.75f)), t, b))
    val ew = w * 0.13f
    val eh = w * 0.2f * eye
    listOf(-1, 1).forEach { s ->
        drawOval(Color(0xFF05060A), Offset(cx + s * w * 0.18f - ew / 2, t + w * 0.48f - eh / 2), Size(ew, eh))
    }
}

@Composable
private fun Section(title: String) = Text(
    title, color = Dim, fontSize = 11.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp),
)

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Glass)
        .border(1.dp, Line, RoundedCornerShape(24.dp)).padding(18.dp),
    content = content,
)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) = Column(
    modifier.clip(RoundedCornerShape(18.dp)).background(Glass).border(1.dp, Line, RoundedCornerShape(18.dp))
        .padding(vertical = 12.dp, horizontal = 10.dp),
) {
    Text(label, color = Dim, fontSize = 10.sp, letterSpacing = 0.5.sp, maxLines = 1)
    Text(value, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ModeCard(mode: PowerMode, selected: Boolean, canSecure: Boolean, onClick: () -> Unit) {
    val c = mode.color()
    val scale by animateFloatAsState(if (selected) 1f else 0.97f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "scale")
    val glow by rememberInfiniteTransition(label = "card").animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "glow",
    )
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.fillMaxWidth().scale(scale).clip(shape)
            .background(
                if (selected) Brush.linearGradient(listOf(c.copy(alpha = 0.24f), c.copy(alpha = 0.05f)))
                else Brush.linearGradient(listOf(Glass, Glass))
            )
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.copy(alpha = glow) else Line, shape)
            .clickable(onClick = onClick).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Jauge d'intensité : 1, 2 ou 3 barres.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { i ->
                    Box(
                        Modifier.width(5.dp).height((10 + i * 7).dp).clip(RoundedCornerShape(3.dp))
                            .background(if (i <= mode.ordinal) c else Line)
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(mode.title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(mode.tagline, color = Dim, fontSize = 12.sp)
            }
            Text(
                if (selected) "ACTIF" else "ACTIVER",
                color = if (selected) Bg else c, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.clip(CircleShape).background(if (selected) c else c.copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        mode.actions.forEach { a ->
            val locked = a.advanced && !canSecure
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (locked) Dim else c))
                Text(
                    a.text, color = if (locked) Dim else Ink.copy(alpha = 0.88f), fontSize = 13.sp,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                if (locked) Text("AVANCÉ", color = Dim, fontSize = 9.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Dim, fontSize = 12.sp)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent, checkedThumbColor = Bg,
                uncheckedTrackColor = Glass, uncheckedThumbColor = Dim, uncheckedBorderColor = Line,
            ),
        )
    }

@Composable
private fun Perm(title: String, sub: String, granted: Boolean, accent: Color, onGrant: (() -> Unit)?) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Dim, fontSize = 12.sp)
        }
        val label = if (granted) "ACCORDÉ" else if (onGrant != null) "AUTORISER" else "VERROUILLÉ"
        Text(
            label, color = if (granted) accent else if (onGrant != null) Bg else Dim,
            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            modifier = Modifier.clip(CircleShape)
                .background(if (!granted && onGrant != null) accent else Glass)
                .then(if (!granted && onGrant != null) Modifier.clickable(onClick = onGrant) else Modifier)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
