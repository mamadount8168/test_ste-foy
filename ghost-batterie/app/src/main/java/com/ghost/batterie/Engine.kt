package com.ghost.batterie

import android.Manifest
import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/** Une action d'un mode. `advanced` = nécessite l'autorisation WRITE_SECURE_SETTINGS (ADB). */
data class ModeAction(val text: String, val advanced: Boolean)

enum class PowerMode(
    val title: String,
    val tagline: String,
    val brightness: Int?,      // 0..255, null = on ne touche pas
    val timeoutMs: Int,
    val syncOff: Boolean,
    val anim: String,
    val saver: Boolean,
    val aodOff: Boolean,
    val locationOff: Boolean,
) {
    FAIBLE("Faible", "Économie légère, usage intact", null, 60_000, false, "0.5", false, false, false),
    NORMAL("Normal", "Équilibre autonomie / confort", 90, 30_000, true, "0.5", false, true, false),
    ULTIME("Ultime", "Survie maximale", 25, 15_000, true, "0", true, true, true);

    val actions: List<ModeAction>
        get() = buildList {
            add(ModeAction("Veille de l'écran après ${timeoutMs / 1000} s", false))
            brightness?.let { add(ModeAction("Luminosité fixée à ${it * 100 / 255} %", false)) }
            add(ModeAction("Fermeture des apps en arrière-plan", false))
            if (syncOff) add(ModeAction("Synchronisation automatique coupée", false))
            add(ModeAction(if (anim == "0") "Animations système désactivées" else "Animations système accélérées (×$anim)", true))
            add(ModeAction("Recherche Wi-Fi / Bluetooth permanente coupée", true))
            if (aodOff) add(ModeAction("Écran toujours allumé (AOD) désactivé", true))
            if (saver) add(ModeAction("Économiseur de batterie Android forcé", true))
            if (locationOff) add(ModeAction("Localisation GPS désactivée", true))
        }
}

data class Report(val applied: Int, val locked: Int, val killed: Int)

object ModeEngine {
    private const val B = "S:screen_brightness"
    private const val BM = "S:screen_brightness_mode"
    private const val TO = "S:screen_off_timeout"
    private const val A1 = "G:window_animation_scale"
    private const val A2 = "G:transition_animation_scale"
    private const val A3 = "G:animator_duration_scale"
    private const val LP = "G:low_power"
    private const val WS = "G:wifi_scan_always_enabled"
    private const val BS = "G:ble_scan_always_enabled"
    private const val AOD = "X:doze_always_on"
    private const val LOC = "X:location_mode"
    private const val SYNC = "sync"
    private val KEYS = listOf(B, BM, TO, A1, A2, A3, LP, WS, BS, AOD, LOC, SYNC)

    /** Valeur d'origine supposée quand le système n'a jamais écrit la clé. */
    private val DEFAULTS = mapOf(A1 to "1", A2 to "1", A3 to "1", LP to "0")

    val current = MutableStateFlow<PowerMode?>(null)

    fun prefs(ctx: Context) = ctx.getSharedPreferences("ghost", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        current.value = prefs(ctx).getString("mode", null)?.let { runCatching { PowerMode.valueOf(it) }.getOrNull() }
    }

    fun canWrite(ctx: Context) = Settings.System.canWrite(ctx)

    fun canSecure(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun read(cr: ContentResolver, id: String): String? = runCatching {
        if (id == SYNC) return@runCatching ContentResolver.getMasterSyncAutomatically().toString()
        val k = id.substring(2)
        when (id[0]) {
            'S' -> Settings.System.getString(cr, k)
            'G' -> Settings.Global.getString(cr, k)
            else -> Settings.Secure.getString(cr, k)
        }
    }.getOrNull()

    private fun write(cr: ContentResolver, id: String, v: String): Boolean = runCatching {
        if (id == SYNC) {
            ContentResolver.setMasterSyncAutomatically(v.toBoolean()); return@runCatching true
        }
        val k = id.substring(2)
        when (id[0]) {
            'S' -> Settings.System.putString(cr, k, v)
            'G' -> Settings.Global.putString(cr, k, v)
            else -> Settings.Secure.putString(cr, k, v)
        }
    }.getOrDefault(false)

    private fun allowed(ctx: Context, id: String) = when (id[0]) {
        'S' -> canWrite(ctx)
        'G', 'X' -> canSecure(ctx)
        else -> true
    }

    /** Sauvegarde les réglages de l'utilisateur avant la première activation. */
    private fun snapshot(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("hasSnap", false)) return
        val e = p.edit().putBoolean("hasSnap", true)
        KEYS.forEach { id -> read(ctx.contentResolver, id)?.let { e.putString("snap|$id", it) } }
        e.apply()
    }

    private fun baseline(ctx: Context): Map<String, String> {
        val p = prefs(ctx)
        return buildMap {
            KEYS.forEach { id -> (p.getString("snap|$id", null) ?: DEFAULTS[id])?.let { put(id, it) } }
        }
    }

    private fun push(ctx: Context, target: Map<String, String>): Pair<Int, Int> {
        var ok = 0
        var locked = 0
        target.forEach { (id, v) ->
            if (read(ctx.contentResolver, id) == v) return@forEach
            if (allowed(ctx, id) && write(ctx.contentResolver, id, v)) ok++ else locked++
        }
        return ok to locked
    }

    fun apply(ctx: Context, mode: PowerMode): Report {
        snapshot(ctx)
        val base = baseline(ctx)
        val t = base.toMutableMap()
        // On ne modifie que les clés dont on connaît la valeur d'origine, pour pouvoir restaurer.
        fun set(id: String, v: String) { if (base.containsKey(id)) t[id] = v }
        set(TO, mode.timeoutMs.toString())
        mode.brightness?.let { set(BM, "0"); set(B, it.toString()) }
        listOf(A1, A2, A3).forEach { set(it, mode.anim) }
        set(WS, "0"); set(BS, "0")
        if (mode.aodOff) set(AOD, "0")
        if (mode.saver) set(LP, "1")
        if (mode.locationOff) set(LOC, "0")
        if (mode.syncOff) set(SYNC, "false")
        val (ok, locked) = push(ctx, t)
        val killed = killBackground(ctx)
        prefs(ctx).edit().putString("mode", mode.name).apply()
        current.value = mode
        return Report(ok, locked, killed)
    }

    fun deactivate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("hasSnap", false)) push(ctx, baseline(ctx))
        val e = p.edit().remove("mode").remove("hasSnap").remove("autoApplied")
        KEYS.forEach { e.remove("snap|$it") }
        e.apply()
        current.value = null
    }

    /** Demande à Android de fermer les processus d'arrière-plan des apps tierces. */
    fun killBackground(ctx: Context): Int {
        val am = ctx.getSystemService(ActivityManager::class.java)
        var n = 0
        runCatching {
            ctx.packageManager.getInstalledApplications(0).forEach {
                if (it.packageName != ctx.packageName && it.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                    am.killBackgroundProcesses(it.packageName); n++
                }
            }
        }
        return n
    }
}

data class BatteryInfo(
    val level: Int = 0,
    val charging: Boolean = false,
    val source: String = "Sur batterie",
    val tempC: Float = 0f,
    val volts: Float = 0f,
    val currentMa: Int = 0,
    val watts: Float = 0f,
    val health: String = "—",
    val capacityMah: Int = 0,
    val remainingMin: Long = -1,
)

fun readBattery(ctx: Context): BatteryInfo {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return BatteryInfo()
    val bm = ctx.getSystemService(BatteryManager::class.java)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / scale
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    val source = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        BatteryManager.BATTERY_PLUGGED_AC -> "Secteur"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Sans fil"
        else -> "Sur batterie"
    }
    val volts = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
    // Selon l'appareil la valeur est en µA ou en mA : on normalise en mA.
    val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    val ma = if (raw == Int.MIN_VALUE) 0 else if (abs(raw) > 20_000) abs(raw) / 1000 else abs(raw)
    val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) // µAh
    val mah = if (counter > 0) counter / 1000 else 0
    val remaining = when {
        charging && Build.VERSION.SDK_INT >= 28 -> bm.computeChargeTimeRemaining().let { if (it > 0) it / 60_000 else -1 }
        !charging && ma > 0 && mah > 0 -> mah * 60L / ma
        else -> -1
    }
    val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Bonne"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Surchauffe"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Usée"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Surtension"
        BatteryManager.BATTERY_HEALTH_COLD -> "Froide"
        else -> "—"
    }
    return BatteryInfo(
        level, charging, source,
        i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
        volts, ma, ma * volts / 1000f, health,
        if (mah > 0 && level > 0) mah * 100 / level else 0,
        remaining,
    )
}
