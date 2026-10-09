package com.shuddh.lab.instruments

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
class TapModel(val good: List<DoubleArray>, val bad: List<DoubleArray>) {
    private fun centroid(xs: List<DoubleArray>) = DoubleArray(4) { i -> xs.map { it[i] }.average() }
    private val cg = centroid(good)
    private val cb = centroid(bad)
    private val sd = DoubleArray(4) { i ->
        val v = good.map { (it[i] - cg[i]).let { d -> d * d } } + bad.map { (it[i] - cb[i]).let { d -> d * d } }
        val within = if (v.size > 2) sqrt(v.sum() / (v.size - 2).coerceAtLeast(1)) else 0.0
        max(within, featureFloor[i])
    }

    private fun d2(x: DoubleArray, c: DoubleArray) = (0 until 4).sumOf { ((x[it] - c[it]) / sd[it]).let { z -> z * z } }

    /** (probability of the "good" class, position 0..1 along the bad→good axis, separation in σ). */
    fun predict(x: DoubleArray): Triple<Double, Double, Double> {
        val dg = d2(x, cg); val db = d2(x, cb)
        val p = 1 / (1 + exp((dg - db) / 2))
        val axis = DoubleArray(4) { (cg[it] - cb[it]) / sd[it] }
        val len2 = axis.sumOf { it * it }.coerceAtLeast(1e-9)
        val t = (0 until 4).sumOf { (x[it] - cb[it]) / sd[it] * axis[it] } / len2
        return Triple(p, t.coerceIn(0.0, 1.0), sqrt(len2))
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
    var status by remember { mutableStateOf("Hold the mic 5–10 cm away, tap firmly with a knuckle. Quiet room.") }

    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Microphone permission is needed"; listening = false; return@LaunchedEffect
        }
        withContext(Dispatchers.Default) {
            listen { tap ->
                taps.add(tap); if (taps.size > 8) taps.removeAt(0)
                status = if (tap.clipped) "Too loud — that tap clipped. Tap a little softer or move the phone back." else "Tap captured (${taps.size})."
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
        val (pGood, t, sep) = p
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

    ScreenFrame("Shuddh Echo", "Tap → resonance fingerprint → on-device classifier", onBack = { listening = false; app.back() }) {
        StepTracker(listOf("Listen" to listening, "3 taps" to (taps.size >= 3), "Teach refs" to (model != null), "Verdict" to false))
        Chips(profiles, profile, { it.name.en }) { profile = it; taps.clear() }
        Note(status, Palette.text)
        BtnRow {
            Btn(if (listening) "Stop listening" else "Start listening") { listening = !listening }
            Btn("Clear taps", primary = false) { taps.clear() }
        }
        if (listening) ListeningBars()

        pred?.let { (pGood, _, _) -> ProbabilityBar(pGood, profile.good, profile.bad) }

        taps.lastOrNull()?.let { t ->
            Section("Last tap") {
                LineChart(listOf(Series(FloatArray(t.wave.size) { it.toFloat() }, t.wave, Palette.cyan)), Modifier.fillMaxWidth().height(110.dp))
                val hz = SR.toFloat() / N
                val n = (2000 / hz).toInt()
                LineChart(
                    listOf(Series(FloatArray(n) { it * hz }, FloatArray(n) { t.spectrum[it] }, Palette.blue, fill = true)), xMin = 0f, xMax = 2000f, xLabel = "Hz",
                    markers = listOf(t.peakHz.toFloat() to Palette.amber, t.centroidHz.toFloat() to Palette.violet),
                )
                Note("Peak ${fmt(t.peakHz)} Hz (amber) · centroid ${fmt(t.centroidHz)} Hz (violet) · ring-down ${fmt(t.decayMs)} ms" + if (t.clipped) " · CLIPPED" else "", Palette.accent)
            }
        }

        if (goodSet.isNotEmpty() || badSet.isNotEmpty() || taps.isNotEmpty()) {
            Section("Cluster map — what the model sees") {
                ClusterMap(goodSet, badSet, taps.map { it.features })
                Note("x: resonance · y: ring-down. Green = ${profile.good} refs, red = ${profile.bad} refs, white = your taps.")
            }
        }

        Section("Teach the model · ${profile.name.en}") {
            Note("Tap a known ${profile.good.lowercase()} one 3–5 times, then save; same for ${profile.bad.lowercase()}. Each save adds the last clean taps as training examples.")
            Note("${profile.good}: ${goodSet.size} taps · ${profile.bad}: ${badSet.size} taps", Palette.text)
            BtnRow {
                Btn("Save as ${profile.good.uppercase()}", enabled = recent.isNotEmpty(), primary = false) {
                    goodSet = (goodSet + recent.map { it.features }).takeLast(30); saveSet(app.prefs, "echo2_${profile.id}_good", goodSet); taps.clear()
                }
                Btn("Save as ${profile.bad.uppercase()}", enabled = recent.isNotEmpty(), primary = false) {
                    badSet = (badSet + recent.map { it.features }).takeLast(30); saveSet(app.prefs, "echo2_${profile.id}_bad", badSet); taps.clear()
                }
                Btn("Forget", primary = false) {
                    goodSet = emptyList(); badSet = emptyList()
                    saveSet(app.prefs, "echo2_${profile.id}_good", goodSet); saveSet(app.prefs, "echo2_${profile.id}_bad", badSet)
                }
            }
        }
        Btn("Get verdict", Modifier.fillMaxWidth(), enabled = taps.isNotEmpty()) { listening = false; app.show(verdict()) }
        HowItWorks(listOf(
            "A knock makes the fruit ring at its natural (resonant) frequency, like a bell.",
            "More liquid inside adds mass and damping, which lowers and dulls the ring; a hollow or dry fruit rings higher and longer.",
            "Each tap becomes 4 numbers: resonant peak, spectral centroid, ring-down time and high/low energy balance.",
            "Your reference taps train a tiny on-device classifier. New taps are scored by their distance to each class, giving a probability, not a guess.",
            "Clipped (too loud) taps are discarded automatically, and the last 3 clean taps are averaged.",
        ))
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
