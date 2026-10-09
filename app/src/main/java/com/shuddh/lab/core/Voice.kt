package com.shuddh.lab.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech

/** Offline text-to-speech. Hindi/Kannada need the offline voice packs installed in system TTS settings. */
class Voice(ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ready = false

    @Volatile private var pending: Pair<String, Lang>? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        // Anything asked to be spoken while the engine was starting is spoken now.
        if (ready) pending?.let { (t, l) -> pending = null; speak(t, l) }
    }

    /** Speaks [text]; returns a problem description, or null on success (queued if the engine is still starting). */
    fun speak(text: String, lang: Lang): String? {
        if (!ready) { pending = text to lang; return null }
        val r = tts.setLanguage(lang.locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            return "${lang.label} voice is not installed (Settings → Text-to-speech → install voice data)"
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "shuddh")
        return null
    }

    fun stop() = tts.stop()
    fun shutdown() = tts.shutdown()
}

object Haptics {
    private fun vibrator(ctx: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /** Silent result patterns: two short = safe, three short = caution, one long = danger. */
    fun result(ctx: Context, level: Level) {
        val p = when (level) {
            Level.SAFE -> longArrayOf(0, 80, 120, 80)
            Level.CAUTION -> longArrayOf(0, 80, 120, 80, 120, 80)
            Level.UNSAFE -> longArrayOf(0, 900)
            Level.INCONCLUSIVE -> longArrayOf(0, 40)
        }
        vibrator(ctx).vibrate(VibrationEffect.createWaveform(p, -1))
    }

    /** Vibration-motor stirrer: pulses the phone to mix reagent and sample in a vial resting on it. */
    fun stir(ctx: Context, seconds: Int = 10) {
        val cycles = seconds * 4
        val p = LongArray(cycles * 2) { if (it % 2 == 0) 30L else 220L }
        vibrator(ctx).vibrate(VibrationEffect.createWaveform(p, -1))
    }

    fun cancel(ctx: Context) = vibrator(ctx).cancel()

    /** Master switch (Settings → Haptics). */
    @Volatile var enabled = true

    private fun play(ctx: Context, e: () -> VibrationEffect) {
        if (!enabled) return
        runCatching { val v = vibrator(ctx); if (v.hasVibrator()) v.vibrate(e()) }
    }

    /** Rich primitives (API 30+, if the motor supports them) else an amplitude waveform. */
    private fun composed(ctx: Context, prims: List<Pair<Int, Float>>, delays: List<Int>, fallback: () -> VibrationEffect) {
        if (!enabled) return
        val v = runCatching { vibrator(ctx) }.getOrNull() ?: return
        if (Build.VERSION.SDK_INT >= 30 && v.areAllPrimitivesSupported(*prims.map { it.first }.toIntArray())) {
            val c = VibrationEffect.startComposition()
            prims.forEachIndexed { i, (p, scale) -> c.addPrimitive(p, scale, delays.getOrElse(i) { 0 }) }
            runCatching { v.vibrate(c.compose()) }
        } else play(ctx, fallback)
    }

    private fun wave(t: LongArray, a: IntArray) = VibrationEffect.createWaveform(t, a, -1)

    /** Featherweight tick — UI feedback, gesture recognised, geiger click. */
    fun tick(ctx: Context, strength: Float = 0.5f) = composed(ctx, listOf(VibrationEffect.Composition.PRIMITIVE_TICK to strength), listOf(0)) {
        wave(longArrayOf(0, 12), intArrayOf(0, (60 + 150 * strength).toInt().coerceAtMost(255)))
    }

    /** Crisp click — a confirmed action. */
    fun click(ctx: Context) = composed(ctx, listOf(VibrationEffect.Composition.PRIMITIVE_CLICK to 0.9f), listOf(0)) {
        wave(longArrayOf(0, 25), intArrayOf(0, 220))
    }

    /** Heartbeat "lub-dub". */
    fun heartbeat(ctx: Context) = composed(
        ctx, listOf(VibrationEffect.Composition.PRIMITIVE_THUD to 0.9f, VibrationEffect.Composition.PRIMITIVE_THUD to 0.55f), listOf(0, 110),
    ) { wave(longArrayOf(0, 45, 90, 35), intArrayOf(0, 255, 0, 140)) }

    /** Sonar ping: a quick rising sweep. */
    fun ping(ctx: Context) = composed(ctx, listOf(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to 0.7f, VibrationEffect.Composition.PRIMITIVE_TICK to 1f), listOf(0, 40)) {
        wave(longArrayOf(0, 30, 30, 30, 20, 15), intArrayOf(0, 60, 120, 200, 0, 255))
    }

    /** A rumble whose strength encodes a value 0..1 (e.g. moisture): you can *feel* the reading. */
    fun rumble(ctx: Context, value: Float, ms: Long = 650) = play(ctx) {
        val amp = (40 + 215 * value.coerceIn(0f, 1f)).toInt()
        val pulses = (2 + value * 6).toInt()
        val t = LongArray(pulses * 2) { if (it % 2 == 0) 20L else ms / pulses }
        val a = IntArray(pulses * 2) { if (it % 2 == 0) 0 else amp }
        wave(t, a)
    }

    /** Slow swell, used while a whistle is building. */
    fun swell(ctx: Context) = composed(ctx, listOf(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE to 0.6f), listOf(0)) {
        wave(longArrayOf(0, 80, 80, 80), intArrayOf(0, 60, 120, 180))
    }

    /** Strong double thud — something counted. */
    fun thud(ctx: Context) = composed(ctx, listOf(VibrationEffect.Composition.PRIMITIVE_THUD to 1f, VibrationEffect.Composition.PRIMITIVE_CLICK to 1f), listOf(0, 90)) {
        wave(longArrayOf(0, 70, 60, 40), intArrayOf(0, 255, 0, 255))
    }

    /** Insistent alarm: three bursts × 3 — for "turn off the stove". */
    fun alarm(ctx: Context) = play(ctx) {
        val t = mutableListOf(0L); val a = mutableListOf(0)
        repeat(3) { repeat(3) { t += 140; a += 255; t += 90; a += 0 }; t += 350; a += 0 }
        wave(t.toLongArray(), a.toIntArray())
    }

    /** Plays a felt heartbeat at [bpm] for [beats] beats — "feel your pulse". */
    fun replayHeart(ctx: Context, bpm: Double, beats: Int = 6) = play(ctx) {
        val period = (60_000 / bpm.coerceIn(40.0, 180.0)).toLong()
        val t = mutableListOf(0L); val a = mutableListOf(0)
        repeat(beats) { t += 45; a += 255; t += 90; a += 0; t += 35; a += 140; t += (period - 170).coerceAtLeast(60); a += 0 }
        wave(t.toLongArray(), a.toIntArray())
    }
}
