package com.ghost.batterie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat

/**
 * Gardien : surveille la batterie en continu, bascule automatiquement en mode Ultime
 * sous le seuil choisi, et renvoie régulièrement les apps d'arrière-plan en veille.
 */
class GhostService : Service() {
    private var lastKill = 0L
    private var lastText = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = tick()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ModeEngine.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Gardien Ghost", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, 1, build("Surveillance active"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        tick(); return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(receiver); super.onDestroy()
    }

    private fun tick() {
        val info = readBattery(this)
        val p = ModeEngine.prefs(this)
        val threshold = p.getInt("threshold", 15)
        if (p.getBoolean("auto", false)) {
            if (!info.charging && info.level <= threshold && ModeEngine.current.value != PowerMode.ULTIME) {
                // On retient le mode précédent pour le remettre à la recharge.
                p.edit().putString("autoPrev", ModeEngine.current.value?.name ?: "").apply()
                ModeEngine.apply(this, PowerMode.ULTIME)
                p.edit().putBoolean("autoApplied", true).apply()
            } else if (info.charging && p.getBoolean("autoApplied", false)) {
                val prev = p.getString("autoPrev", "").orEmpty()
                p.edit().remove("autoApplied").remove("autoPrev").apply()
                if (prev.isEmpty()) ModeEngine.deactivate(this) else ModeEngine.apply(this, PowerMode.valueOf(prev))
            }
        }
        val mode = ModeEngine.current.value
        val now = SystemClock.elapsedRealtime()
        if (mode != null && mode != PowerMode.FAIBLE && now - lastKill > 10 * 60_000) {
            lastKill = now
            ModeEngine.killBackground(this)
        }
        val text = "${info.level} % · ${info.currentMa} mA · ${"%.1f".format(info.tempC)} °C · mode ${mode?.title ?: "désactivé"}"
        if (text != lastText) {
            lastText = text
            getSystemService(NotificationManager::class.java).notify(1, build(text))
        }
    }

    private fun build(text: String): Notification =
        Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_ghost)
            .setContentTitle("Ghost Batterie")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .build()

    companion object {
        private const val CH = "ghost"
        fun set(ctx: Context, on: Boolean) {
            val i = Intent(ctx, GhostService::class.java)
            if (on) ctx.startForegroundService(i) else ctx.stopService(i)
        }
    }
}
