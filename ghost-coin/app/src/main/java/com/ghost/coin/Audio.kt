package com.ghost.coin

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class Sfx { CLICK, SPIN, TICK, COIN, WIN, ATTACK, BUILD, CHEST, LOSE, SHIELD }

/**
 * Tous les sons sont synthétisés au premier lancement (aucun fichier audio tiers) puis mis en cache.
 * Musique et effets ont chacun leur volume.
 */
class Audio(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("audio", Context.MODE_PRIVATE)
    private val pool = SoundPool.Builder().setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()).build()
    private val ids = HashMap<Sfx, Int>()
    private var music: MediaPlayer? = null
    private var started = false

    var musicVolume = prefs.getFloat("music", 0.5f)
        set(v) { field = v; prefs.edit().putFloat("music", v).apply(); music?.setVolume(v, v) }
    var sfxVolume = prefs.getFloat("sfx", 0.8f)
        set(v) { field = v; prefs.edit().putFloat("sfx", v).apply() }

    init {
        thread(isDaemon = true) {
            runCatching {
                val dir = File(ctx.cacheDir, "snd3").apply { mkdirs() }
                Sfx.entries.forEach { s ->
                    val f = File(dir, "${s.name}.wav")
                    if (!f.exists()) wav(f, synth(s))
                    synchronized(ids) { ids[s] = pool.load(f.path, 1) }
                }
                val m = File(dir, "music.wav")
                if (!m.exists()) wav(m, musicLoop())
                music = MediaPlayer().apply { setDataSource(m.path); isLooping = true; setVolume(musicVolume, musicVolume); prepare() }
                if (started) music?.start()
            }
        }
    }

    fun play(s: Sfx) {
        val id = synchronized(ids) { ids[s] } ?: return
        if (sfxVolume > 0.01f) pool.play(id, sfxVolume, sfxVolume, 1, 0, 1f)
    }

    fun start() { started = true; runCatching { music?.start() } }
    fun stop() { started = false; runCatching { music?.pause() } }

    private companion object {
        const val RATE = 22050

        /** Note glissant de f0 à f1, avec enveloppe décroissante. `shape` : 0 sinus, 1 carré doux, 2 bruit. */
        fun tone(out: FloatArray, at: Int, ms: Int, f0: Float, f1: Float, vol: Float, shape: Int = 0, decay: Float = 4f) {
            val n = ms * RATE / 1000
            var phase = 0.0
            val rnd = Random(at)
            for (i in 0 until n) {
                if (at + i >= out.size) break
                val k = i / n.toFloat()
                phase += 2 * PI * (f0 + (f1 - f0) * k) / RATE
                val v = when (shape) { 0 -> sin(phase); 1 -> sin(phase) + sin(phase * 3) / 3 + sin(phase * 5) / 5; else -> rnd.nextDouble(-1.0, 1.0) }
                val attack = minOf(1f, i / 60f)
                out[at + i] += (v * vol * attack * exp(-decay * k)).toFloat()
            }
        }

        fun buf(ms: Int) = FloatArray(ms * RATE / 1000)
        fun ms(t: Int) = t * RATE / 1000

        fun synth(s: Sfx): FloatArray = when (s) {
            Sfx.CLICK -> buf(60).also { tone(it, 0, 50, 700f, 500f, 0.5f) }
            Sfx.TICK -> buf(70).also { tone(it, 0, 60, 320f, 180f, 0.7f, 1, 6f) }
            Sfx.SPIN -> buf(520).also { for (i in 0 until 9) tone(it, ms(i * 55), 50, 380f + i * 55, 420f + i * 55, 0.3f, 1, 5f) }
            Sfx.COIN -> buf(420).also { tone(it, 0, 120, 1318f, 1318f, 0.45f); tone(it, ms(80), 320, 1760f, 1760f, 0.45f) }
            Sfx.WIN -> buf(900).also { listOf(523f, 659f, 784f, 1047f, 1319f).forEachIndexed { i, f -> tone(it, ms(i * 110), 420, f, f, 0.4f, 1, 3f) } }
            Sfx.ATTACK -> buf(700).also { tone(it, 0, 180, 1800f, 300f, 0.5f, 1, 2f); tone(it, ms(150), 520, 0f, 0f, 0.6f, 2, 5f); tone(it, ms(150), 520, 140f, 40f, 0.8f, 0, 4f) }
            Sfx.BUILD -> buf(520).also { for (i in 0 until 3) { tone(it, ms(i * 130), 90, 160f, 90f, 0.7f, 0, 5f); tone(it, ms(i * 130), 40, 0f, 0f, 0.3f, 2, 8f) }; tone(it, ms(380), 130, 660f, 990f, 0.35f) }
            Sfx.CHEST -> buf(950).also { tone(it, 0, 300, 90f, 180f, 0.4f, 1, 1.5f); listOf(988f, 1319f, 1568f, 1976f, 2637f).forEachIndexed { i, f -> tone(it, ms(280 + i * 70), 360, f, f, 0.3f, 0, 4f) } }
            Sfx.LOSE -> buf(520).also { tone(it, 0, 240, 392f, 330f, 0.4f, 1, 2f); tone(it, ms(220), 300, 294f, 220f, 0.4f, 1, 3f) }
            Sfx.SHIELD -> buf(460).also { tone(it, 0, 440, 440f, 880f, 0.35f, 0, 2f); tone(it, 0, 440, 660f, 1320f, 0.25f, 0, 2f) }
        }

        /** Boucle d'ambiance : nappe et arpège lents en la mineur (la – fa – do – sol). */
        fun musicLoop(): FloatArray {
            val bar = 3200
            val out = buf(bar * 4)
            val chords = listOf(listOf(220f, 261.6f, 329.6f), listOf(174.6f, 220f, 261.6f), listOf(130.8f, 196f, 261.6f), listOf(196f, 246.9f, 293.7f))
            chords.forEachIndexed { b, ch ->
                ch.forEach { f -> tone(out, ms(b * bar), bar, f / 2, f / 2, 0.11f, 0, 0.8f) }
                val arp = listOf(ch[0], ch[1], ch[2], ch[1] * 2, ch[2], ch[1], ch[0] * 2, ch[2])
                arp.forEachIndexed { i, f -> tone(out, ms(b * bar + i * 400), 900, f * 2, f * 2, 0.09f, 0, 3.5f) }
            }
            return out
        }

        fun wav(f: File, samples: FloatArray) {
            val b = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(samples.size * 2)
            samples.forEach { b.putShort((it.coerceIn(-1f, 1f) * 32000).toInt().toShort()) }
            f.writeBytes(b.array())
        }
    }
}
