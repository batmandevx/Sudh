package com.shuddh.lab.instruments

import androidx.compose.runtime.rememberCoroutineScope
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.roi
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.ui.Fold
import com.shuddh.lab.ui.Glass
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Dsp
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Prefs
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.Words
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Chips
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.LineChart
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import com.shuddh.lab.ui.Series
import com.shuddh.lab.ui.StepTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

private const val SR = 44100
private const val N = 8192

/** One tap's acoustic fingerprint. */
data class Tap(
    val peakHz: Double,
    val decayMs: Double,
    val spectrum: FloatArray,
    val centroidHz: Double = 0.0,
    /** Energy above 1 kHz relative to below, dB. */
    val hiLoDb: Double = 0.0,
    val clipped: Boolean = false,
    val wave: FloatArray = FloatArray(0),
    /** Body-vibration features felt by the accelerometer (phone resting on the fruit), or null. */
    val acc: DoubleArray? = null,
) {
    /** Feature vector used by the on-device classifier. Logs make ratios behave linearly. */
    val features: DoubleArray
        get() = doubleArrayOf(ln(peakHz), ln(centroidHz.coerceAtLeast(1.0)), ln(decayMs.coerceAtLeast(1.0)), hiLoDb / 10)
}

private val featureFloor = doubleArrayOf(0.03, 0.03, 0.08, 0.15)

/**
 * Nearest-centroid classifier trained on-device from the user's reference taps.
 * Features are z-scored by the pooled within-class spread; class probability = softmax(−d²/2).
 */
class TapModel(val good: List<DoubleArray>, val bad: List<DoubleArray>, private val floor: DoubleArray = featureFloor) {
    private val dim = floor.size
    private fun centroid(xs: List<DoubleArray>) = DoubleArray(dim) { i -> xs.map { it[i] }.average() }
    private val cg = centroid(good)
    private val cb = centroid(bad)
    private val sd = DoubleArray(dim) { i ->
        val v = good.map { (it[i] - cg[i]).let { d -> d * d } } + bad.map { (it[i] - cb[i]).let { d -> d * d } }
        val within = if (v.size > 2) sqrt(v.sum() / (v.size - 2).coerceAtLeast(1)) else 0.0
        max(within, floor[i])
    }

    private fun d2(x: DoubleArray, c: DoubleArray) = (0 until dim).sumOf { ((x[it] - c[it]) / sd[it]).let { z -> z * z } }

    /** (probability of the "good" class, position 0..1 along the bad→good axis, separation in σ). */
    fun predict(x: DoubleArray): Triple<Double, Double, Double> {
        val dg = d2(x, cg); val db = d2(x, cb)
        val p = 1 / (1 + exp((dg - db) / 2))
        val axis = DoubleArray(dim) { (cg[it] - cb[it]) / sd[it] }
        val len2 = axis.sumOf { it * it }.coerceAtLeast(1e-9)
        val t = (0 until dim).sumOf { (x[it] - cb[it]) / sd[it] * axis[it] } / len2
        return Triple(p, t.coerceIn(0.0, 1.0), sqrt(len2))
    }
}

/** Accelerometer features: log peak shake, log ring-down ms, log dominant vibration Hz. */
val accFloor = doubleArrayOf(0.15, 0.1, 0.08)

/**
 * Ring buffer of accelerometer samples. On a tap, [features] looks at the last window: removes
 * gravity with the pre-tap baseline, then measures how hard, how long and how fast the body rang.
 */
class AccelTap {
    private val n = 1024
    private val t = LongArray(n); private val x = FloatArray(n); private val y = FloatArray(n); private val z = FloatArray(n)
    private var w = 0
    @Synchronized fun add(ts: Long, a: FloatArray) { val i = w % n; t[i] = ts; x[i] = a[0]; y[i] = a[1]; z[i] = a[2]; w++ }

    @Synchronized fun features(windowMs: Long = 450): DoubleArray? {
        val count = minOf(w, n); if (count < 40) return null
        val idx = (w - count until w).map { it % n }
        val tEnd = t[idx.last()]
        val win = idx.filter { tEnd - t[it] <= windowMs * 1_000_000 }
        if (win.size < 40) return null
        val rate = (win.size - 1) * 1e9 / (t[win.last()] - t[win.first()]).coerceAtLeast(1)
        val base = win.take((rate * 0.04).toInt().coerceIn(4, win.size / 4))
        val bx = base.map { x[it] }.average(); val by = base.map { y[it] }.average(); val bz = base.map { z[it] }.average()
        val rx = win.map { x[it] - bx }; val ry = win.map { y[it] - by }; val rz = win.map { z[it] - bz }
        val mag = win.indices.map { sqrt(rx[it] * rx[it] + ry[it] * ry[it] + rz[it] * rz[it]) }
        val pk = mag.indices.maxByOrNull { mag[it] } ?: return null
        if (mag[pk] < 0.25 || pk > win.size * 0.8) return null // no knock felt — phone not on the fruit
        val k = (rate * 0.012).toInt().coerceAtLeast(2) // ~12 ms envelope
        val env = mag.indices.map { i -> (i until minOf(mag.size, i + k)).maxOf { mag[it] } }
        val stop = (pk until env.size).firstOrNull { env[it] < mag[pk] / 5 } ?: env.size
        val decayMs = (stop - pk) * 1000.0 / rate
        // Dominant axis after the peak → zero crossings → vibration frequency (≤ Nyquist of the sensor).
        val ax = listOf(rx, ry, rz).maxByOrNull { a -> (pk until minOf(a.size, pk + k * 8)).sumOf { (a[it] * a[it]).toDouble() } }!!
        val seg = ax.subList(pk, minOf(ax.size, pk + (rate * 0.15).toInt()))
        val zc = seg.zipWithNext().count { (a, b) -> (a > 0) != (b > 0) }
        val hz = (zc / 2.0) / (seg.size / rate).coerceAtLeast(1e-3)
        return doubleArrayOf(ln(mag[pk].toDouble()), ln(decayMs.coerceAtLeast(2.0)), ln(hz.coerceAtLeast(5.0)))
    }
}

private data class EchoProfile(
    val id: String,
    val name: Txt,
    val good: String,
    val bad: String,
    val goodLabel: Txt,
    val badLabel: Txt,
)

private val profiles = listOf(
    EchoProfile("coconut", Txt("Coconut fill", "नारियल में पानी", "ತೆಂಗಿನಕಾಯಿ ನೀರು"), "Full", "Empty",
        Txt("FULL", "भरा हुआ", "ತುಂಬಿದೆ"), Txt("EMPTY", "खाली", "ಖಾಲಿ")),
    EchoProfile("watermelon", Txt("Watermelon ripeness", "तरबूज़ पका है?", "ಕಲ್ಲಂಗಡಿ ಹಣ್ಣಾಗಿದೆಯೇ"), "Ripe", "Unripe",
        Txt("RIPE", "पका हुआ", "ಹಣ್ಣಾಗಿದೆ"), Txt("UNRIPE", "कच्चा", "ಕಾಯಿ")),
    EchoProfile("container", Txt("Container level", "डिब्बे का स्तर", "ಡಬ್ಬದ ಮಟ್ಟ"), "Full", "Empty",
        Txt("FULL", "भरा हुआ", "ತುಂಬಿದೆ"), Txt("EMPTY", "खाली", "ಖಾಲಿ")),
)

private fun loadSet(p: Prefs, key: String): List<DoubleArray> = p.json(key)?.getJSONArray("f")?.let { a ->
    List(a.length()) { i -> a.getJSONArray(i).let { r -> DoubleArray(r.length()) { r.getDouble(it) } } }
} ?: emptyList()

private fun saveSet(p: Prefs, key: String, v: List<DoubleArray>) =
    p.putJson(key, if (v.isEmpty()) null else JSONObject().put("f", JSONArray(v.map { JSONArray(it.toList()) })))

/** Spectrum peak (60 Hz–4 kHz), centroid, hi/lo balance and −20 dB ring-down time of a tap. */
fun analyseTap(x: FloatArray): Tap {
    val spec = Dsp.spectrumDb(x)
    val hz = SR.toDouble() / N
    val lo = (60 / hz).toInt(); val hi = (4000 / hz).toInt(); val k1 = (1000 / hz).toInt()
    var best = lo
    for (i in lo..hi) if (spec[i] > spec[best]) best = i
    val peak = Dsp.parabolic(spec, best) * hz
    var num = 0.0; var den = 0.0; var eLo = 0.0; var eHi = 0.0
    for (i in lo..hi) {
        val p = Math.pow(10.0, spec[i] / 10.0)
        num += p * i * hz; den += p
        if (i < k1) eLo += p else eHi += p
    }
    val block = 256
    val env = (0 until x.size / block).map { b ->
        sqrt((0 until block).sumOf { (x[b * block + it] * x[b * block + it]).toDouble() } / block)
    }
    val top = env.indices.maxByOrNull { env[it] } ?: 0
    val stop = (top until env.size).firstOrNull { env[it] < env[top] / 10 } ?: env.size
    val wave = FloatArray(400) { x[(it * x.size / 400).coerceAtMost(x.size - 1)] }
    return Tap(
        peak, (stop - top) * block * 1000.0 / SR, spec,
        centroidHz = if (den > 0) num / den else peak,
        hiLoDb = 10 * log10((eHi + 1e-12) / (eLo + 1e-12)),
        clipped = x.any { kotlin.math.abs(it) > 0.97f },
        wave = wave,
    )
}

@Composable
fun EchoScreen(app: AppState) {
    val ctx = app.ctx
    var profile by remember { mutableStateOf(profiles.first()) }
    var listening by remember { mutableStateOf(false) }
    val taps = remember { mutableStateListOf<Tap>() }
    var goodSet by remember(profile) { mutableStateOf(loadSet(app.prefs, "echo2_${profile.id}_good")) }
    var badSet by remember(profile) { mutableStateOf(loadSet(app.prefs, "echo2_${profile.id}_bad")) }
    var status by remember { mutableStateOf("Rest the phone flat on the fruit, then knock firmly with a knuckle ~5 cm from it. Quiet room.") }
    var goodAcc by remember(profile) { mutableStateOf(loadSet(app.prefs, "echo2_${profile.id}_good_acc")) }
    var badAcc by remember(profile) { mutableStateOf(loadSet(app.prefs, "echo2_${profile.id}_bad_acc")) }
    val accel = remember { AccelTap() }
    androidx.compose.runtime.DisposableEffect(listening) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) = accel.add(e.timestamp, e.values)
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (listening) sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, android.hardware.SensorManager.SENSOR_DELAY_FASTEST) }
        onDispose { sm.unregisterListener(l) }
    }

    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Microphone permission is needed"; listening = false; return@LaunchedEffect
        }
        withContext(Dispatchers.Default) {
            listen { tap0 ->
                val tap = tap0.copy(acc = accel.features())
                taps.add(tap); if (taps.size > 8) taps.removeAt(0)
                status = if (tap.clipped) "Too loud — that tap clipped. Tap a little softer or move the phone back." else "Tap captured (${taps.size})" + if (tap.acc != null) " · 🎤 sound + 📳 vibration" else " · 🎤 sound only (rest the phone on the fruit to add vibration)"
            }
        }
    }

    val model = if (goodSet.isNotEmpty() && badSet.isNotEmpty()) TapModel(goodSet, badSet) else null
    val recent = taps.filter { !it.clipped }.takeLast(3)
    val pred = model?.let { m ->
        if (recent.isEmpty()) null else recent.map { m.predict(it.features) }.let { ps ->
            Triple(ps.map { it.first }.average(), ps.map { it.second }.average(), ps.first().third)
        }
    }

    val accModel = if (goodAcc.size >= 2 && badAcc.size >= 2) TapModel(goodAcc, badAcc, accFloor) else null
    /** Each sensor's position along bad→good (0..1) with σ: within-class spread is 1/separation in t units; plus tap-to-tap scatter. */
    fun sensorEstimate(name: String, ts: List<Triple<Double, Double, Double>>): com.shuddh.lab.core.Fusion.Estimate? {
        if (ts.isEmpty()) return null
        val sep = ts.first().third.coerceAtLeast(0.5)
        val pos = ts.map { it.second }; val m = pos.average()
        val scatter = if (pos.size > 1) sqrt(pos.sumOf { (it - m) * (it - m) } / (pos.size - 1)) / sqrt(pos.size.toDouble()) else 0.0
        return com.shuddh.lab.core.Fusion.Estimate(name, m, sqrt((1 / sep) * (1 / sep) / pos.size + scatter * scatter + 0.03 * 0.03))
    }
    val fused = run {
        val mic = model?.let { m -> sensorEstimate("🎤 Mic · sound", recent.map { m.predict(it.features) }) }
        val acc = accModel?.let { m -> sensorEstimate("📳 Accelerometer", recent.mapNotNull { it.acc }.map { m.predict(it) }) }
        com.shuddh.lab.core.Fusion.combine(listOfNotNull(mic, acc))
    }

    fun verdict(): Outcome {
        val last = recent.lastOrNull() ?: taps.last()
        val ev = mutableListOf(
            Evidence("OBSERVATION", "Resonance ${fmt(last.peakHz)} Hz · centroid ${fmt(last.centroidHz)} Hz · ring-down ${fmt(last.decayMs)} ms · hi/lo ${fmt(last.hiLoDb)} dB"),
            Evidence("QUALITY", "${recent.size} clean taps used, ${taps.count { it.clipped }} clipped taps discarded", recent.size >= 2),
        )
        val p = pred
        if (model == null || p == null) {
            ev += Evidence("HYPOTHESIS", "Teach it at least one '${profile.good}' and one '${profile.bad}' tap", false)
            return Outcome("Shuddh Echo", "echo_${profile.id}", profile.name, last.peakHz, "Hz", Level.INCONCLUSIVE,
                "Taps measured; references needed to interpret them", listOf(Words.calibrate), ev)
        }
        val (pGood0, t0, sep) = p
        val f = fused
        val t = f?.value?.coerceIn(0.0, 1.0) ?: t0
        // Fused position → probability: logistic around the midpoint, steepness from the fused σ.
        val pGood = if (f != null && f.parts.size > 1) 1 / (1 + exp(-(t - 0.5) / f.sigma.coerceAtLeast(0.05))) else pGood0
        f?.takeIf { it.parts.size > 1 }?.let { ff ->
            ff.parts.forEachIndexed { i, pt -> ev += Evidence("OBSERVATION", "${pt.sensor}: ${fmt(pt.value * 100)}% toward ${profile.good.lowercase()} ± ${fmt(pt.sigma * 100)}% (weight ${(ff.weights[i] * 100).toInt()}%)") }
            ev += Evidence("QUALITY", "Sound and vibration agree ${(ff.agreement * 100).toInt()}%" + (ff.outlier?.let { " — $it disagrees" } ?: ""), ff.agreement >= 0.6)
        }
        ev += Evidence("CALIBRATION", "On-device model: ${goodSet.size} '${profile.good}' + ${badSet.size} '${profile.bad}' reference taps, classes ${fmt(sep)}σ apart", sep > 2)
        ev += Evidence("PATTERN", "P(${profile.good.lowercase()}) = ${fmt(pGood * 100)}% · position ${fmt(t * 100)}% toward ${profile.good.lowercase()}", true)
        val conf = kotlin.math.abs(pGood - 0.5) * 2
        val (lvl, label, adv) = when {
            pGood >= 0.7 -> Triple(Level.SAFE, profile.goodLabel, listOf(Words.buyIt))
            pGood >= 0.35 -> Triple(Level.CAUTION, Txt("MAYBE", "शायद", "ಬಹುಶಃ"), listOf(Words.retest))
            else -> Triple(Level.UNSAFE, profile.badLabel, listOf(Words.skipIt))
        }
        ev += Evidence("HYPOTHESIS", "${label.en} with ${fmt(conf * 100)}% confidence", lvl == Level.SAFE)
        return Outcome("Shuddh Echo", "echo_${profile.id}", profile.name, t * 100, "%", lvl,
            "${fmt(t * 100)}% ${profile.good.lowercase()} · P=${fmt(pGood * 100)}% (on-device classifier)", adv, ev,
            "Knock-test acoustics, learned from your own reference fruit. More reference taps = better accuracy.", levelLabel = label)
    }

    // Listening starts by itself — the user just knocks.
    LaunchedEffect(Unit) { listening = true }
    fun saveRefs(good: Boolean) {
        if (good) {
            goodSet = (goodSet + recent.map { it.features }).takeLast(30); saveSet(app.prefs, "echo2_${profile.id}_good", goodSet)
            goodAcc = (goodAcc + recent.mapNotNull { it.acc }).takeLast(30); saveSet(app.prefs, "echo2_${profile.id}_good_acc", goodAcc)
        } else {
            badSet = (badSet + recent.map { it.features }).takeLast(30); saveSet(app.prefs, "echo2_${profile.id}_bad", badSet)
            badAcc = (badAcc + recent.mapNotNull { it.acc }).takeLast(30); saveSet(app.prefs, "echo2_${profile.id}_bad_acc", badAcc)
        }
        taps.clear(); Haptics.click(ctx)
        status = "Saved as ${if (good) profile.good else profile.bad}. ${if (goodSet.isEmpty() || badSet.isEmpty()) "Now teach the other one." else "Knock any ${profile.name.en.lowercase().substringBefore(' ')} to test."}"
    }
    val f = fused
    val pg = pred?.let { (pGood, _, _) -> if (f != null && f.parts.size > 1) 1 / (1 + exp(-(f.value.coerceIn(0.0, 1.0) - 0.5) / f.sigma.coerceAtLeast(0.05))) else pGood }
    val emoji = profileEmoji(profile.id)

    val melon = profile.id == "watermelon"
    var kg by remember { mutableStateOf(app.prefs.json("melon")?.optDouble("kg", 5.0) ?: 5.0) }
    var dye by remember(profile) { mutableStateOf<Pair<Double, Double>?>(null) } // (a*, confidence)
    ScreenFrame(if (melon) "Watermelon check" else "Tap Test",
        if (melon) "① knock → ripeness  ·  ② tissue → natural colour or dye" else "Knock → sound + vibration → ${profile.goodLabel.en.lowercase()} or ${profile.badLabel.en.lowercase()}?",
        onBack = { listening = false; app.back() }) {
        // Fruit picker
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            profiles.forEach { p ->
                val sel = p == profile
                val bg by animateColorAsState(if (sel) Palette.blue.copy(alpha = 0.2f) else Color(0x10FFFFFF), tween(300), label = "bg")
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(bg)
                        .border(1.5.dp, if (sel) Palette.blue else Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                        .clickable { profile = p; taps.clear(); status = "" }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(profileEmoji(p.id), fontSize = if (sel) 26.sp else 22.sp)
                    Text(p.id.replaceFirstChar { it.uppercase() }, color = if (sel) Palette.text else Palette.muted, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        if (melon) MelonSteps(ripe = pg != null && model != null, dyeDone = dye != null)
        // Hero: knock zone + result
        val glow = when { pg == null -> Palette.blue; pg >= 0.7 -> Palette.accent; pg >= 0.35 -> Palette.amber; else -> Palette.red }
        Glass(glow = glow, padding = 18) {
            if (pg != null && model != null) {
                VerdictGauge(emoji, pg, profile, f)
                if (melon) RipenessMeter(pg)
            } else KnockZone(emoji, listening, recent.size, taps.lastOrNull()?.acc != null)
            if (melon) taps.lastOrNull { !it.clipped }?.let { t ->
                Text("Stiffness index ${String.format(java.util.Locale.US, "%.0f", com.shuddh.lab.core.Melon.stiffness(t.peakHz, kg) / 1000)}k  ·  ${fmt(t.peakHz)} Hz knock · ${one(kg)} kg — lower = softer, riper flesh", color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Text(
                when {
                    !listening -> "Tap Start, then knock."
                    status.isNotBlank() -> status
                    model == null -> "Teach it first: knock a known ${profile.good.lowercase()} one 3 times, then tap its tile below."
                    else -> "Rest the phone on the ${profile.id}, knock firmly with a knuckle ~5 cm away."
                },
                color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (listening) "■  Stop" else "●  Start listening", Modifier.weight(1f), primary = !listening) { listening = !listening }
                if (taps.isNotEmpty()) Btn("Clear", Modifier.weight(0.6f), primary = false) { taps.clear(); status = "" }
            }
            if (pg != null && model != null) Btn("Full report", Modifier.fillMaxWidth(), primary = false) { listening = false; app.show(verdict()) }
        }

        if (melon) {
            Glass(padding = 14) {
                Stepper("Watermelon weight", "${one(kg)} kg", "from the shop scale — makes the stiffness index comparable", Color(0xFFF87171)) { d ->
                    kg = (kg + d * 0.5).coerceIn(1.0, 15.0); app.prefs.putJson("melon", org.json.JSONObject().put("kg", kg))
                }
            }
            DyeCheck(app) { a, c -> dye = a to c }
            MelonReport(pg?.takeIf { model != null }, f, dye)
        }
        // Teach: two tiles
        Text("TEACH IT · YOUR OWN ${profile.id.uppercase()}S", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TeachTile("${emoji} ${profile.goodLabel.en}", goodSet.size, goodAcc.size, Palette.accent, ready = recent.size >= 2, Modifier.weight(1f)) { saveRefs(true) }
            TeachTile("${emoji} ${profile.badLabel.en}", badSet.size, badAcc.size, Palette.red, ready = recent.size >= 2, Modifier.weight(1f)) { saveRefs(false) }
        }
        Note(if (recent.size >= 2) "↑ ${recent.size} fresh knocks ready — tap the tile that matches this ${profile.id}." else "Knock a known ${profile.good.lowercase()} one 3×, tap its tile; then the same for ${profile.bad.lowercase()}.", if (recent.size >= 2) Palette.cyan else Palette.muted)

        if (f != null && f.parts.size > 1) Fold("Sensor breakdown · sound + vibration agree ${(f.agreement * 100).toInt()}%", Palette.cyan) {
            com.shuddh.lab.ui.FusionBars(f, 1.0) { v, sg -> "${(v * 100).toInt()}±${(sg * 100).toInt()}" }
        }
        taps.lastOrNull()?.let { t ->
            Fold("Last knock · ${fmt(t.peakHz)} Hz, rings ${fmt(t.decayMs)} ms", Palette.blue) {
                LineChart(listOf(Series(FloatArray(t.wave.size) { it.toFloat() }, t.wave, Palette.cyan)), Modifier.fillMaxWidth().height(100.dp))
                val hz = SR.toFloat() / N
                val n = (2000 / hz).toInt()
                LineChart(
                    listOf(Series(FloatArray(n) { it * hz }, FloatArray(n) { t.spectrum[it] }, Palette.blue, fill = true)), xMin = 0f, xMax = 2000f, xLabel = "Hz",
                    markers = listOf(t.peakHz.toFloat() to Palette.amber, t.centroidHz.toFloat() to Palette.violet),
                )
                if (goodSet.isNotEmpty() || badSet.isNotEmpty()) {
                    ClusterMap(goodSet, badSet, taps.map { it.features })
                    Note("Green = ${profile.good.lowercase()} knocks, red = ${profile.bad.lowercase()}, white = now. x: pitch · y: ring time.")
                }
            }
        }
        if (goodSet.isNotEmpty() || badSet.isNotEmpty()) Fold("Training data", Palette.violet) {
            Note("${profile.good}: ${goodSet.size} knocks (${goodAcc.size} with vibration) · ${profile.bad}: ${badSet.size} knocks (${badAcc.size} with vibration). More knocks from different fruit = better accuracy.")
            Btn("Forget all training for ${profile.id}", Modifier.fillMaxWidth(), primary = false) {
                goodSet = emptyList(); badSet = emptyList(); goodAcc = emptyList(); badAcc = emptyList()
                listOf("good", "bad", "good_acc", "bad_acc").forEach { saveSet(app.prefs, "echo2_${profile.id}_$it", emptyList()) }
            }
        }
        HowItWorks(listOf(
            "A knock makes the fruit ring like a bell. More liquid adds mass and damping — the ring gets lower and dies faster.",
            "The microphone turns each knock into 4 numbers: pitch, brightness, ring time and high/low balance.",
            "With the phone resting on the fruit, the accelerometer feels the same knock — liquid damps the vibration quickly.",
            "Your own reference knocks train two tiny on-device classifiers (sound and vibration); their answers are fused with an agreement check.",
            "Too-loud knocks are thrown away automatically; the last 3 clean knocks are averaged.",
        ))
    }
}

private fun profileEmoji(id: String) = when (id) { "coconut" -> "🥥"; "watermelon" -> "🍉"; else -> "🫙" }

/** Knock zone: fruit in the middle, ripples on each knock, three dots filling as clean knocks arrive. */
@Composable
private fun KnockZone(emoji: String, listening: Boolean, knocks: Int, vib: Boolean) {
    val inf = rememberInfiniteTransition(label = "knock")
    val r by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "r")
    val bump = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(knocks) { if (knocks > 0) { bump.snapTo(1.25f); bump.animateTo(1f, tween(350)) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
            if (listening) Canvas(Modifier.fillMaxSize()) {
                for (k in 0..2) {
                    val ph = (r + k / 3f) % 1f
                    drawCircle(Palette.blue.copy(alpha = (1 - ph) * 0.5f), size.minDimension / 2 * (0.35f + 0.65f * ph), style = Stroke(3f))
                }
            }
            Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0x1AFFFFFF)), contentAlignment = Alignment.Center) {
                Text(emoji, fontSize = 52.sp, modifier = Modifier.graphicsLayer { scaleX = bump.value; scaleY = bump.value })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { i -> Box(Modifier.size(12.dp).clip(CircleShape).background(if (i < knocks) Palette.accent else Color(0x33FFFFFF))) }
            Text(if (knocks == 0) "knock 3×" else "$knocks / 3", color = Palette.muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorChip("🎤 Sound", knocks > 0)
            SensorChip("📳 Vibration", vib)
        }
    }
}

@Composable
private fun SensorChip(t: String, on: Boolean) = Text(
    t, color = if (on) Palette.accent else Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.accent.copy(alpha = 0.14f) else Color(0x10FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp),
)

/** Verdict: probability ring around the fruit, label pill and confidence. */
@Composable
private fun VerdictGauge(emoji: String, pGood: Double, profile: EchoProfile, f: com.shuddh.lab.core.Fusion.Fused?) {
    val sweep by animateFloatAsState(pGood.toFloat().coerceIn(0f, 1f), tween(900), label = "sw")
    val (label, col) = when { pGood >= 0.7 -> profile.goodLabel.en to Palette.accent; pGood >= 0.35 -> "MAYBE" to Palette.amber; else -> profile.badLabel.en to Palette.red }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val st = 14.dp.toPx(); val o = Offset(st / 2, st / 2); val sz = androidx.compose.ui.geometry.Size(size.width - st, size.height - st)
                drawArc(Palette.red.copy(alpha = 0.35f), 0f, 360f, false, o, sz, style = Stroke(st, cap = StrokeCap.Round))
                drawArc(col, -90f, 360f * sweep, false, o, sz, style = Stroke(st, cap = StrokeCap.Round))
            }
            Text(emoji, fontSize = 48.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = col, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp))
            Text("${(pGood * 100).toInt()}% ${profile.good.lowercase()}", color = Palette.text, fontFamily = com.shuddh.lab.ui.Display, fontWeight = FontWeight.Black, fontSize = 24.sp)
            Text(
                if (f != null && f.parts.size > 1) "Sound + vibration · agree ${(f.agreement * 100).toInt()}%" else "Sound only — rest the phone on it to add vibration",
                color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
    }
}

/** Teach tile: tap to save the latest knocks as this class. Pulses when knocks are waiting. */
@Composable
private fun TeachTile(title: String, n: Int, vib: Int, tint: Color, ready: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "teach")
    val a by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(tint.copy(alpha = if (n > 0) 0.10f else 0.04f))
            .border(1.5.dp, if (ready) tint.copy(alpha = a) else tint.copy(alpha = if (n > 0) 0.7f else 0.25f), RoundedCornerShape(18.dp))
            .clickable(enabled = ready) { onTap() }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(if (n == 0) "Not taught yet" else "✓ $n knocks" + if (vib > 0) " · $vib 📳" else "", color = if (n > 0) tint else Palette.muted, fontSize = 12.sp)
        if (ready) Text("Tap to save", color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ListeningBars() {
    val t = rememberInfiniteTransition(label = "listen")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val n = 32
        val bw = size.width / n
        for (i in 0 until n) {
            val h = size.height * (0.15f + 0.7f * kotlin.math.abs(kotlin.math.sin(i * 0.6f + p * 6f)) * (0.4f + 0.6f * p))
            drawRoundRect(Palette.blue.copy(alpha = 0.7f), Offset(i * bw + 2, (size.height - h) / 2), androidx.compose.ui.geometry.Size(bw - 4, h), androidx.compose.ui.geometry.CornerRadius(4f))
        }
    }
}

@Composable
private fun ProbabilityBar(pGood: Double, good: String, bad: String) {
    val p by animateFloatAsState(pGood.toFloat(), tween(700), label = "prob")
    Section("On-device verdict") {
        Row {
            Text("$bad ${fmt((1 - pGood) * 100)}%", color = Palette.red, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("$good ${fmt(pGood * 100)}%", color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.red.copy(alpha = 0.5f))) {
            Box(Modifier.fillMaxWidth(p.coerceIn(0.01f, 1f)).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Brush.horizontalGradient(listOf(Palette.cyan, Palette.accent))))
        }
    }
}

/** 2-D scatter of reference and live taps in (log peak, log ring-down) space. */
@Composable
private fun ClusterMap(good: List<DoubleArray>, bad: List<DoubleArray>, live: List<DoubleArray>) {
    val all = good + bad + live
    if (all.isEmpty()) return
    val t = rememberInfiniteTransition(label = "cluster")
    val pulse by t.animateFloat(0.6f, 1.4f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse")
    val x0 = all.minOf { it[0] } - 0.05; val x1 = all.maxOf { it[0] } + 0.05
    val y0 = all.minOf { it[2] } - 0.1; val y1 = all.maxOf { it[2] } + 0.1
    Canvas(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x66000000))) {
        fun pt(f: DoubleArray) = Offset(
            (20 + (f[0] - x0) / (x1 - x0).coerceAtLeast(1e-6) * (size.width - 40)).toFloat(),
            (size.height - 20 - (f[2] - y0) / (y1 - y0).coerceAtLeast(1e-6) * (size.height - 40)).toFloat(),
        )
        for (k in 1..3) drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
        good.forEach { drawCircle(Palette.accent.copy(alpha = 0.8f), 9f, pt(it)) }
        bad.forEach { drawCircle(Palette.red.copy(alpha = 0.8f), 9f, pt(it)) }
        live.forEachIndexed { i, f ->
            val last = i == live.lastIndex
            if (last) drawCircle(Color.White.copy(alpha = 0.25f), 18f * pulse, pt(f))
            if (last) drawCircle(Color.White, 9f, pt(f)) else drawCircle(Color.White, 6f, pt(f), style = Stroke(3f))
        }
    }
}

/** Blocks reading the mic until cancelled; calls [onTap] (on the main thread) for every detected knock. */
@SuppressLint("MissingPermission")
private suspend fun listen(onTap: (Tap) -> Unit) {
    val chunk = 512
    val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, max(minBuf, N * 4),
    )
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
    val buf = ShortArray(chunk)
    val prev = ShortArray(chunk)
    var noise = 200.0
    var cooldownUntil = 0L
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    rec.startRecording()
    try {
        while (kotlin.coroutines.coroutineContext.isActive) {
            val n = rec.read(buf, 0, chunk)
            if (n <= 0) continue
            val rms = sqrt((0 until n).sumOf { (buf[it] * buf[it]).toDouble() } / n)
            val now = System.currentTimeMillis()
            if (rms > max(noise * 8, 900.0) && now > cooldownUntil) {
                val x = FloatArray(N)
                var w = 0
                for (s in prev) { x[w++] = s / 32768f }
                for (i in 0 until n) { if (w < N) x[w++] = buf[i] / 32768f }
                while (w < N) {
                    val m = rec.read(buf, 0, minOf(chunk, N - w))
                    if (m <= 0) break
                    for (i in 0 until m) x[w++] = buf[i] / 32768f
                }
                val tap = analyseTap(x)
                main.post { onTap(tap) }
                cooldownUntil = System.currentTimeMillis() + 500
            } else {
                noise = 0.95 * noise + 0.05 * rms
            }
            buf.copyInto(prev)
        }
    } finally {
        rec.stop(); rec.release()
    }
}


// ── Watermelon flow ─────────────────────────────────────────────────────────

@Composable
private fun MelonSteps(ripe: Boolean, dyeDone: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(Triple("1", "Ripeness", ripe), Triple("2", "Dye check", dyeDone), Triple("3", "Report", ripe && dyeDone)).forEach { (n, t, done) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (done) Palette.accent.copy(alpha = 0.14f) else Color(0x10FFFFFF)).padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text(if (done) "✓" else n, color = if (done) Palette.accent else Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Black)
                Text(t, color = if (done) Palette.text else Palette.muted, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

/** Unripe ← → ripe bar with a marker at the fused probability. */
@Composable
private fun RipenessMeter(p: Double) {
    val x by animateFloatAsState(p.toFloat().coerceIn(0f, 1f), tween(900), label = "ripe")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            drawRoundRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF86EFAC), Color(0xFFFDE047), Color(0xFFF87171))), cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f))
            val cx = size.width * x
            drawCircle(Color.White, size.height * 0.75f, Offset(cx, size.height / 2))
            drawCircle(Color(0xFF0F172A), size.height * 0.45f, Offset(cx, size.height / 2))
        }
        Row { Text("Unripe", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); Text("Ripe", color = Palette.muted, fontSize = 11.sp) }
    }
}

/**
 * Step 2 — FSSAI tissue test, read by the camera: redness (a*) of the rubbed spot relative to clean tissue.
 */
@Composable
private fun DyeCheck(app: AppState, onResult: (Double, Double) -> Unit) {
    val cam = remember { com.shuddh.lab.camera.CameraHandle() }
    val scope = rememberCoroutineScope()
    val clean = android.graphics.RectF(0.10f, 0.40f, 0.34f, 0.60f)
    val spot = android.graphics.RectF(0.58f, 0.40f, 0.82f, 0.60f)
    val live = remember { mutableListOf<Double>() }
    var open by remember { mutableStateOf(false) }
    var res by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    Glass(glow = res?.let { (a, _) -> when (com.shuddh.lab.core.Melon.dyeCall(a)) { com.shuddh.lab.core.Melon.Dye.NATURAL -> Palette.accent; com.shuddh.lab.core.Melon.Dye.DYE -> Palette.red; else -> Palette.amber } } ?: Color(0xFFF87171), padding = 16) {
        Text("② NATURAL COLOUR OR DYE?", color = Color(0xFFF87171), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text("Cut the watermelon. Rub a white tissue firmly on the red flesh for 5 seconds. Lay it on the table — rubbed spot in the red box, a clean part of the tissue in the white box.", color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp)
        res?.let { (a, c) ->
            val call = com.shuddh.lab.core.Melon.dyeCall(a)
            val lv = when (call) { com.shuddh.lab.core.Melon.Dye.NATURAL -> Level.SAFE; com.shuddh.lab.core.Melon.Dye.DYE -> Level.UNSAFE; else -> Level.CAUTION }
            Text(when (call) { com.shuddh.lab.core.Melon.Dye.NATURAL -> "NATURAL COLOUR"; com.shuddh.lab.core.Melon.Dye.DYE -> "DYE SUSPECTED"; else -> "BORDERLINE" }, color = Color(lv.argb), fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("Tissue redness a* ${fmt(a)} (natural < 18, dye > 35) · ${com.shuddh.lab.core.Dart.estimate(lv, c)}", color = Palette.muted, fontSize = 12.sp)
        }
        if (!open) Btn(if (res == null) "📷  Check the tissue" else "Check again", Modifier.fillMaxWidth(), primary = res == null) { open = true }
        else {
            CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.6f, overlay = { roi(clean, Color.White); roi(spot, Color(0xFFF87171)) }) { bmp ->
                val a = com.shuddh.lab.camera.Frames.relativeLab(com.shuddh.lab.camera.Frames.meanRgb(bmp, spot), com.shuddh.lab.camera.Frames.meanRgb(bmp, clean)).a
                synchronized(live) { live += a; while (live.size > 30) live.removeAt(0) }
            }
            Btn("Measure redness", Modifier.fillMaxWidth()) {
                scope.launch {
                    cam.lock(false); kotlinx.coroutines.delay(700); cam.lock(true)
                    synchronized(live) { live.clear() }
                    kotlinx.coroutines.delay(1500)
                    val xs = synchronized(live) { live.toList() }.sorted()
                    cam.lock(false)
                    if (xs.size < 5) return@launch
                    val a = xs[xs.size / 2]
                    val c = com.shuddh.lab.core.Melon.dyeConfidence(a)
                    res = a to c; open = false; onResult(a, c)
                    com.shuddh.lab.core.Haptics.click(app.ctx)
                }
            }
        }
    }
}

/** One card that answers the user's questions: ripe? natural colour? organic? */
@Composable
private fun MelonReport(pRipe: Double?, f: com.shuddh.lab.core.Fusion.Fused?, dye: Pair<Double, Double>?) {
    Glass(padding = 16) {
        Text("WATERMELON REPORT", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        @Composable fun row(e: String, k: String, v: String, c: Color, sub: String) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(e, fontSize = 20.sp)
                Column(Modifier.weight(1f)) { Text(k, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text(sub, color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp) }
                Text(v, color = c, fontSize = 14.sp, fontWeight = FontWeight.Black)
            }
        }
        if (pRipe == null) row("🔊", "Ripeness", "knock first", Palette.muted, "microphone + accelerometer")
        else {
            val lv = if (pRipe >= 0.7) Level.SAFE else if (pRipe >= 0.35) Level.CAUTION else Level.UNSAFE
            val conf = com.shuddh.lab.core.Dart.confidence((pRipe - 0.5) / 0.5, (f?.sigma ?: 0.2) * 2)
            row("🔊", "Ripeness", if (pRipe >= 0.7) "RIPE" else if (pRipe >= 0.35) "MAYBE" else "UNRIPE", Color(lv.argb), "${(pRipe * 100).toInt()}% ripe-like · ${conf.toInt()}% sure")
        }
        if (dye == null) row("🧻", "Natural colour", "tissue test next", Palette.muted, "camera redness of the rubbed tissue")
        else {
            val call = com.shuddh.lab.core.Melon.dyeCall(dye.first)
            val lv = when (call) { com.shuddh.lab.core.Melon.Dye.NATURAL -> Level.SAFE; com.shuddh.lab.core.Melon.Dye.DYE -> Level.UNSAFE; else -> Level.CAUTION }
            row("🧻", "Natural colour", when (call) { com.shuddh.lab.core.Melon.Dye.NATURAL -> "NATURAL"; com.shuddh.lab.core.Melon.Dye.DYE -> "DYE"; else -> "UNSURE" }, Color(lv.argb), "a* ${fmt(dye.first)} · ${dye.second.toInt()}% sure")
        }
        row("🌱", "Organic", "can't test", Palette.muted, "Organic is how it was farmed — no phone or home test can measure it. Look for the Jaivik Bharat / India Organic logo.")
    }
}
