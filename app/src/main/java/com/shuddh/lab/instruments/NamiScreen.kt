package com.shuddh.lab.instruments

import android.Manifest
import com.shuddh.lab.camera.roi
import android.content.pm.PackageManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Prefs
import com.shuddh.lab.core.Sonar
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.Words
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.OnWave
import com.shuddh.lab.ui.SteadyBar
import com.shuddh.lab.ui.rememberMotion
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Chips
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.Glyph
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.InstrumentGlyph
import com.shuddh.lab.ui.LineChart
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import com.shuddh.lab.ui.Series
import com.shuddh.lab.ui.StepTracker
import com.shuddh.lab.ui.pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.style.TextAlign
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.enter
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

private data class Surface(
    val id: String,
    val name: Txt,
    val hint: String,
    /** Moisture fraction thresholds for caution / unsafe, and what to say at each level. */
    val judge: (Double) -> Triple<Level, Txt, List<Txt>>,
)

private val dryLbl = Txt("DRY", "सूखा", "ಒಣ")
private val dampLbl = Txt("DAMP", "नम", "ತೇವ")
private val wetLbl = Txt("WET", "गीला", "ಒದ್ದೆ")

private val waterPlant = Txt("Soil is dry. Water the plant.", "मिट्टी सूखी है। पौधे को पानी दें।", "ಮಣ್ಣು ಒಣಗಿದೆ. ಗಿಡಕ್ಕೆ ನೀರು ಹಾಕಿ.")
private val soilOk = Txt("Soil moisture is good.", "मिट्टी में नमी ठीक है।", "ಮಣ್ಣಿನ ತೇವಾಂಶ ಸರಿಯಾಗಿದೆ.")
private val soilLogged = Txt("Waterlogged. Stop watering for now.", "पानी ज़्यादा है। अभी पानी न दें।", "ನೀರು ಹೆಚ್ಚಾಗಿದೆ. ಈಗ ನೀರು ಹಾಕಬೇಡಿ.")
private val wallDamp = Txt("Wall is damp. Check for seepage before mould grows.", "दीवार में नमी है। फफूंद से पहले रिसाव जाँचें।", "ಗೋಡೆ ತೇವವಾಗಿದೆ. ಬೂಷ್ಟು ಬರುವ ಮೊದಲು ಸೋರಿಕೆ ಪರಿಶೀಲಿಸಿ.")
private val grainDryFirst = Txt("Too moist to store. Sun-dry before storing.", "भंडारण के लिए बहुत नम। पहले धूप में सुखाएँ।", "ಸಂಗ್ರಹಣೆಗೆ ತುಂಬಾ ತೇವ. ಮೊದಲು ಬಿಸಿಲಲ್ಲಿ ಒಣಗಿಸಿ.")
private val grainMould = Txt("High mould and aflatoxin risk. Do not store sealed.", "फफूंद और एफ्लाटॉक्सिन का खतरा। बंद करके न रखें।", "ಬೂಷ್ಟು ಮತ್ತು ಅಫ್ಲಾಟಾಕ್ಸಿನ್ ಅಪಾಯ. ಮುಚ್ಚಿ ಇಡಬೇಡಿ.")
private val clothDamp = Txt("Still damp. Dry longer to avoid odour.", "अभी भी नम है। और सुखाएँ।", "ಇನ್ನೂ ತೇವವಿದೆ. ಇನ್ನಷ್ಟು ಒಣಗಿಸಿ.")

private val surfaces = listOf(
    Surface("soil", Txt("Soil (plants)", "मिट्टी", "ಮಣ್ಣು"), "Pot or field soil, phone edge 1 cm above") { m ->
        when {
            m < 0.3 -> Triple(Level.CAUTION, dryLbl, listOf(waterPlant))
            m < 0.75 -> Triple(Level.SAFE, dampLbl, listOf(soilOk))
            else -> Triple(Level.CAUTION, wetLbl, listOf(soilLogged))
        }
    },
    Surface("wall", Txt("Wall dampness", "दीवार की सीलन", "ಗೋಡೆಯ ತೇವ"), "Plaster wall, phone edge against a 1 cm spacer") { m ->
        when {
            m < 0.3 -> Triple(Level.SAFE, dryLbl, listOf(Words.ok))
            m < 0.6 -> Triple(Level.CAUTION, dampLbl, listOf(wallDamp))
            else -> Triple(Level.UNSAFE, wetLbl, listOf(wallDamp))
        }
    },
    Surface("grain", Txt("Grain / spice storage", "अनाज भंडारण", "ಧಾನ್ಯ ಸಂಗ್ರಹ"), "Rice, wheat, chilli or peanuts in a bowl") { m ->
        when {
            m < 0.3 -> Triple(Level.SAFE, dryLbl, listOf(Words.ok))
            m < 0.55 -> Triple(Level.CAUTION, dampLbl, listOf(grainDryFirst))
            else -> Triple(Level.UNSAFE, wetLbl, listOf(grainMould))
        }
    },
    Surface("cloth", Txt("Laundry / fabric", "कपड़े", "ಬಟ್ಟೆ"), "Folded cloth, phone edge resting on it") { m ->
        if (m < 0.25) Triple(Level.SAFE, dryLbl, listOf(Words.ok)) else Triple(Level.CAUTION, dampLbl, listOf(clothDamp))
    },
)

private val namiName = Txt("Surface moisture", "सतह की नमी", "ಮೇಲ್ಮೈ ತೇವಾಂಶ")

/** Reference format version — bumped when capture changed (volume lock, gating), so stale references are ignored. */
private const val REF_VERSION = 2

private fun loadRef(p: Prefs, key: String): Pair<FloatArray, Int>? = p.json(key)?.takeIf { it.optInt("v") == REF_VERSION }?.let { o ->
    val a = o.getJSONArray("b"); FloatArray(a.length()) { a.getDouble(it).toFloat() } to o.optInt("n", 1)
}
private fun staleRef(p: Prefs, key: String) = p.json(key)?.let { it.optInt("v") != REF_VERSION } ?: false
private fun saveRef(p: Prefs, key: String, v: FloatArray, n: Int) =
    p.putJson(key, org.json.JSONObject().put("b", JSONArray(v.map { it.toDouble() })).put("n", n).put("v", REF_VERSION))

/** Running average: each "Add" folds the new reading into the stored reference. */
private fun accumulate(old: Pair<FloatArray, Int>?, new: FloatArray): Pair<FloatArray, Int> =
    if (old == null) new to 1 else FloatArray(new.size) { (old.first[it] * old.second + new[it]) / (old.second + 1) } to old.second + 1

private data class Spot(val name: String, val pct: Double, val ci: Double, val level: Level)

private fun levelColor(l: Level?) = when (l) {
    Level.SAFE -> Palette.accent
    Level.CAUTION -> Palette.amber
    Level.UNSAFE -> Palette.red
    else -> Palette.cyan
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NamiScreen(app: AppState) {
    val ctx = app.ctx
    val scope = rememberCoroutineScope()
    val motion = rememberMotion()
    var surface by remember { mutableStateOf(surfaces.first()) }
    var reading by remember { mutableStateOf<Sonar.Reading?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var shakenDuring by remember { mutableStateOf(false) }
    var dry by remember(surface) { mutableStateOf(loadRef(app.prefs, "nami_${surface.id}_dry")) }
    var wet by remember(surface) { mutableStateOf(loadRef(app.prefs, "nami_${surface.id}_wet")) }
    val spots = remember(surface) { mutableStateListOf<Spot>() }
    var status by remember { mutableStateOf("Media volume up, quiet room. Hold the phone's bottom edge (speaker + mic) at a fixed 1 cm from the surface.") }
    val volume = remember(busy) { Sonar.mediaVolumeFraction(ctx) }

    fun say(t: String) = app.voice.speak(t, app.lang)
    var popup by remember { mutableStateOf(false) }
    var failMsg by remember { mutableStateOf<String?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    fun addDry() { dry = accumulate(dry, reading!!.bands).also { saveRef(app.prefs, "nami_${surface.id}_dry", it.first, it.second); savedMsg = "Saved as DRY reference ×${it.second}" } }
    fun addWet() { wet = accumulate(wet, reading!!.bands).also { saveRef(app.prefs, "nami_${surface.id}_wet", it.first, it.second); savedMsg = "Saved as WET reference ×${it.second}" } }

    var air by remember { mutableStateOf(loadRef(app.prefs, "nami_air")?.first) }

    // Sensor 2 — camera: wet porous material darkens (water fills air gaps, less scattering). L* vs a white card.
    val cam = remember { com.shuddh.lab.camera.CameraHandle() }
    val camWhite = android.graphics.RectF(0.08f, 0.40f, 0.30f, 0.60f)
    val camSurf = android.graphics.RectF(0.45f, 0.30f, 0.92f, 0.70f)
    val camCollector = remember { com.shuddh.lab.camera.Collector<List<Double>> { it.flatten() } }
    var camRef by remember(surface) { mutableStateOf(app.prefs.json("nami_${surface.id}_cam") ?: org.json.JSONObject()) }
    var camNow by remember(surface) { mutableStateOf<Pair<Double, Double>?>(null) }
    var camBusy by remember { mutableStateOf(false) }
    var camLive by remember { mutableStateOf<Double?>(null) }
    fun camCapture(then: (Pair<Double, Double>) -> Unit) {
        if (camCollector.busy) return
        camBusy = true
        camCollector.start(15) { ls ->
            val m = ls.average(); val sd = kotlin.math.sqrt(ls.sumOf { (it - m) * (it - m) } / (ls.size - 1).coerceAtLeast(1))
            camBusy = false; Haptics.click(ctx); then(m to sd)
        }
    }
    fun camEstimate(): com.shuddh.lab.core.Fusion.Estimate? {
        val (l, sd) = camNow ?: return null
        if (!camRef.has("dry") || !camRef.has("wet")) return null
        val ld = camRef.getDouble("dry"); val lw = camRef.getDouble("wet")
        if (ld - lw < 3) return null // camera can't tell this material's dry from wet
        val t = ((ld - l) / (ld - lw)).coerceIn(-0.2, 1.2)
        return com.shuddh.lab.core.Fusion.Estimate("Camera · darkening", t, kotlin.math.sqrt((sd / kotlin.math.sqrt(15.0) / (ld - lw)).let { it * it } + 0.06 * 0.06))
    }
    var airBusy by remember { mutableStateOf(false) }

    /**
     * Three steady pings at a fixed, known loudness: media volume is pinned to 85 % for the
     * capture (then restored) so every reading — and every reference — is made at the same level.
     * Pings during which the phone moved are discarded and retaken (up to 6 attempts).
     */
    suspend fun capturePooled(am: android.media.AudioManager): List<Sonar.Reading> {
        val stream = android.media.AudioManager.STREAM_MUSIC
        val before = am.getStreamVolume(stream)
        runCatching { am.setStreamVolume(stream, (am.getStreamMaxVolume(stream) * 0.85f).toInt().coerceAtLeast(1), 0) }
        val reads = mutableListOf<Sonar.Reading>()
        try {
            var attempts = 0
            val moved = mutableListOf<Sonar.Reading>()
            while (reads.size < 3 && attempts < 6) {
                attempts++
                progress = reads.size + 1
                // Movement = the phone's orientation changing during the ping. (The accelerometer's
                // "shake" can't be used here: the speaker's own chirps vibrate the phone.)
                val tx = motion.tiltX; val ty = motion.tiltY
                val r = withContext(Dispatchers.Default) { runCatching { Sonar.analyse(Sonar.capture(ctx)) }.getOrNull() }
                val still = kotlin.math.abs(motion.tiltX - tx) < 8f && kotlin.math.abs(motion.tiltY - ty) < 8f
                if (r != null) { if (still) reads += r else { moved += r; shakenDuring = true } }
            }
            // Never fail just because the phone was handled: fall back to the moved pings (flagged in the quality chips).
            if (reads.size < 3) reads += moved.take(3 - reads.size)
            Haptics.ping(ctx)
            // If the phone never settled, keep what we have rather than nothing.
        } finally {
            runCatching { am.setStreamVolume(stream, before, 0) }
        }
        return reads
    }

    fun calibrateAir() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        airBusy = true; busy = true
        say("Hold the phone up in the air, away from everything.")
        scope.launch {
            kotlinx.coroutines.delay(2500)
            val r = Sonar.pool(capturePooled(am))
            busy = false; airBusy = false; progress = 0
            if (r != null) {
                air = r.bands; saveRef(app.prefs, "nami_air", r.bands, 1)
                status = "Phone calibrated in open air — quick estimates now cancel this phone's speaker and mic colouring."
                say("Phone calibrated.")
            } else status = "Air calibration failed — raise volume and try again."
        }
    }

    fun ping() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Microphone permission is needed"; return
        }
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        if (am.mode == android.media.AudioManager.MODE_IN_CALL || am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) {
            failMsg = "You're on a call — the call is using the speaker and microphone. End the call, then ping again."
            status = failMsg!!; popup = true; say("Please end the call first."); return
        }
        busy = true; progress = 0; shakenDuring = false; savedMsg = null
        scope.launch {
            val reads = capturePooled(am)
            val r = Sonar.pool(reads)
            busy = false; progress = 0
            reading = r
            failMsg = if (r == null) "Couldn't hear the chirps. Turn media volume up (above half), make sure no call or music is playing, and hold the phone's bottom edge (speaker + mic) about 1 cm from the surface." else null
            popup = true
            status = r?.let {
                "Pooled ${it.chirpsFound} chirps from ${reads.size} pings (${it.rejected} outliers rejected) · SNR ${fmt(20 * log10(it.snr))} dB · ±${fmt(it.spreadDb)} dB" +
                    (if (it.clipped) " · mic clipped: lower volume a notch" else "") +
                    (if (shakenDuring) " · phone moved during the ping" else "")
            } ?: "Couldn't hear the chirps clearly — raise volume and keep the bottom edge near the surface."
            val d = dry; val w = wet
            if (r != null) {
                android.util.Log.d("Nami", "tilt=${fmt(Sonar.tilt(r.bands))} bands=${r.bands.joinToString { fmt(it.toDouble()) }} noise=${fmt(r.noiseDb)}")
                val ax = air
                val (t, _, _) = if (d != null && w != null) {
                    if (ax != null) Sonar.moistureStats(Sonar.echo(r, ax), Sonar.echo(d.first, ax), Sonar.echo(w.first, ax)) else Sonar.moistureStats(r, d.first, w.first)
                } else Sonar.quickEstimate(r, air)
                val (_, label, adv) = surface.judge(t.coerceIn(0.0, 1.0))
                Haptics.rumble(ctx, t.toFloat().coerceIn(0f, 1f)) // feel the wetness
                say("${label.get(app.lang)}. ${(t * 100).coerceIn(0.0, 100.0).toInt()} %. ${adv.firstOrNull()?.get(app.lang) ?: ""}")
            }
        }
    }

    // With an open-air calibration, compare surface *echoes* (direct speaker→mic sound removed).
    fun e(r: Sonar.Reading) = air?.let { Sonar.echo(r, it) } ?: r
    fun e(b: FloatArray) = air?.let { Sonar.echo(b, it) } ?: b
    val stats = reading?.let { r -> val d = dry; val w = wet; if (d != null && w != null) Sonar.moistureStats(e(r), e(d.first), e(w.first)) else null }
    val off = reading?.let { r -> val d = dry; val w = wet; if (d != null && w != null) Sonar.moisture(e(r).bands, e(d.first), e(w.first), Sonar.weights(e(r))).second else null }
    val stale = remember(surface) { staleRef(app.prefs, "nami_${surface.id}_dry") || staleRef(app.prefs, "nami_${surface.id}_wet") || staleRef(app.prefs, "nami_air") }
    val quick = reading?.takeIf { stats == null }?.let { Sonar.quickEstimate(it, air) }
    val sonarEst = (stats ?: quick)?.let { (t, ci, _) -> com.shuddh.lab.core.Fusion.Estimate(if (stats != null) "Sonar · echo" else "Sonar · quick", t, (ci / 1.96).coerceAtLeast(0.03)) }
    val fused = com.shuddh.lab.core.Fusion.combine(listOfNotNull(sonarEst, camEstimate()))?.takeIf { it.parts.size > 1 }
    val shown = fused?.let { Triple(it.value, 1.96 * it.sigma, (stats ?: quick)!!.third) } ?: stats ?: quick
    val accuracy: Pair<String, Color> = when {
        fused != null && stats != null && fused.agreement >= 0.6 -> "★★★ High accuracy — sonar + camera agree" to Palette.accent
        stats != null && dry!!.second >= 2 && wet!!.second >= 2 && stats.second < 0.08 -> "★★★ High accuracy" to Palette.accent
        stats != null -> "★★☆ Good — add 2+ captures per reference for high" to Palette.cyan
        air != null -> "★☆☆ Estimate (phone-calibrated)" to Palette.amber
        else -> "☆☆☆ Rough estimate — calibrate for accuracy" to Palette.amber
    }
    val judged = shown?.let { surface.judge(it.first.coerceIn(0.0, 1.0)) }

    fun verdict(): Outcome {
        val r = reading!!
        val goodBands = r.bandSnr.count { it > 10f }
        val ev = mutableListOf(
            Evidence("OBSERVATION", "Echo transfer function 2–18 kHz, ${r.chirpsFound} chirps pooled, ${r.rejected} outliers rejected (MAD rule)"),
            Evidence("QUALITY", "Matched-filter SNR ${fmt(20 * log10(r.snr))} dB, band spread ±${fmt(r.spreadDb)} dB", r.snr > 8 && r.spreadDb < 3),
            Evidence("QUALITY", "Room noise ${fmt(r.noiseDb)} dBFS · $goodBands/16 bands above 10 dB SNR (noisy bands down-weighted)", goodBands >= 10),
        )
        if (r.clipped) ev += Evidence("QUALITY", "Microphone clipped — lower media volume slightly", false)
        val st = stats ?: run {
            val (qt, qci, _) = quick!!
            val qp = (qt * 100).coerceIn(0.0, 100.0)
            val (lvl, label, adv) = surface.judge(qt.coerceIn(0.0, 1.0))
            ev += Evidence("PATTERN", "Quick estimate ${fmt(qp)}% ± ${fmt(qci * 100)}% from spectral tilt ${fmt(Sonar.tilt(r.bands))} dB (wet pores reflect more high frequencies)", qci < 0.2)
            ev += Evidence("CALIBRATION", "No dry/wet references yet — add them for an accurate, material-specific reading", false)
            return Outcome("Shuddh Nami", "nami_${surface.id}", namiName, qp, "%", if (r.chirpsFound < 8) Level.INCONCLUSIVE else lvl,
                "${surface.name.en}: about ${qp.toInt()}% moisture (quick estimate)", adv + Words.calibrate, ev,
                "Quick estimate without references. Save a dry and a wet reference of the same material for an accurate reading.", levelLabel = label)
        }
        val (t, ci, _) = if (fused != null) Triple(fused.value, 1.96 * fused.sigma, st.third) else st
        val pct = (t * 100).coerceIn(0.0, 100.0)
        fused?.let { f -> f.parts.forEachIndexed { i, p -> ev += Evidence("OBSERVATION", "${p.sensor}: ${fmt(p.value * 100)}% ± ${fmt(p.sigma * 100)}% (weight ${(f.weights[i] * 100).toInt()}%)") }
            ev += Evidence("QUALITY", "Sonar and camera agree ${(f.agreement * 100).toInt()}%" + (f.outlier?.let { " — $it disagrees" } ?: ""), f.agreement >= 0.6) }
        ev += Evidence("PATTERN", "Moisture index ${fmt(pct)}% ± ${fmt(ci * 100)}% (95% CI over ${r.perChirp.size} chirps, inverse-variance weighted, level-invariant)", ci < 0.15)
        ev += Evidence("CALIBRATION", "References: dry ×${dry!!.second}, wet ×${wet!!.second} averaged captures", dry!!.second >= 2 && wet!!.second >= 2)
        ev += Evidence("QUALITY", "Off-axis distance ${fmt(off ?: 0.0)} dB (large = different material or distance)", (off ?: 0.0) < 3)
        if ((off ?: 0.0) > 6 || r.chirpsFound < 8 || goodBands < 4) {
            ev += Evidence("HYPOTHESIS", "Reading does not resemble either reference", false)
            return Outcome("Shuddh Nami", "nami_${surface.id}", namiName, pct, "%", Level.INCONCLUSIVE,
                "Doesn't match the references — same material and spacer?", listOf(Words.retest), ev)
        }
        if (ci > 0.25) {
            ev += Evidence("HYPOTHESIS", "Confidence interval too wide to call", false)
            return Outcome("Shuddh Nami", "nami_${surface.id}", namiName, pct, "%", Level.INCONCLUSIVE,
                "Too noisy (±${fmt(ci * 100)}%) — quieter room, steadier hold", listOf(Words.retest), ev)
        }
        val (lvl, label, adv) = surface.judge(t.coerceIn(0.0, 1.0))
        ev += Evidence("HYPOTHESIS", "${surface.name.en}: ${label.en.lowercase()} (moisture index ${fmt(pct)}%)", lvl == Level.SAFE)
        return Outcome("Shuddh Nami", "nami_${surface.id}", namiName, pct, "%", lvl,
            "${surface.name.en}: moisture index ${fmt(pct)}% ± ${fmt(ci * 100)}% (0 = your dry ref, 100 = your wet ref)", adv, ev,
            "Acoustic screening of porous surfaces, relative to your own references. Not a calibrated moisture meter.", levelLabel = label)
    }

    ScreenFrame("Shuddh Nami", "Speaker + mic sonar → surface moisture", onBack = { app.back() }) {
        StepTracker(listOf("Air calib" to (air != null), "Dry ref" to (dry != null), "Wet ref" to (wet != null), "Ping" to (reading != null && stats != null)))
        if (stale) Note("⚙️ Accuracy upgrade: your old references were measured the old way and have been set aside. Calibrate in air once, then record DRY and WET again (2 each).", Palette.amber)
        if (air == null) Note("For accurate readings, do the one-time phone calibration below first: hold the phone up in open air and tap Calibrate.", Palette.cyan)
        Chips(surfaces, surface, { it.name.en }) { surface = it; reading = null }
        OnWave(motion) { if (!busy) ping() }

        Glass(glow = levelColor(judged?.first)) {
            SonarScene(busy, progress, stats?.first, surface.id)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LiquidGauge(shown?.first, shown?.second, judged?.second?.en, levelColor(judged?.first), busy, Modifier.size(132.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            busy -> "Ping $progress of 3 — listening…"
                            reading == null -> surface.hint
                            else -> "${surface.name.en}: ${judged!!.second.en.lowercase()}"
                        },
                        color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Display, lineHeight = 18.sp,
                    )
                    judged?.third?.firstOrNull()?.let { Text(it.en, color = levelColor(judged.first), fontSize = 12.sp) }
                    Note(status)
                }
            }
            fused?.let { com.shuddh.lab.ui.FusionBars(it, 1.0) { v, sg -> "${(v * 100).toInt()}±${(sg * 100).toInt()}%" } }
            QualityChecks(volume, reading, motion.shake < 0.12f && !shakenDuring)
            Btn(if (busy) "Pinging $progress/3…" else "Ping surface (3× averaged)", Modifier.fillMaxWidth(), enabled = !busy) { ping() }
        }
        if (shown != null && judged != null && !busy) ResultBanner(shown.first, shown.second, stats != null, judged.first, judged.second.get(app.lang), judged.third.firstOrNull()?.get(app.lang), dry != null, wet != null, accuracy)
        SteadyBar(motion, "ping the surface")

        reading?.let { r ->
            val xs = FloatArray(Sonar.BANDS) { 2.5f + it }
            val series = mutableListOf(Series(xs, r.bands, Palette.cyan, fill = true))
            dry?.let { series += Series(xs, it.first, Palette.amber) }
            wet?.let { series += Series(xs, it.first, Palette.blue) }
            Section("Acoustic fingerprint") {
                Note("Transfer function (dB) vs kHz — cyan: now · amber: dry ref · blue: wet ref")
                LineChart(series, xLabel = "kHz")
            }
            Section("Signal quality per band") {
                BandSnrStrip(r.bandSnr)
                Note("Each bar is one 1 kHz band: its echo vs the room's own noise. Faded bands are down-weighted automatically, so a fan or a voice in one band can't skew the result.")
            }
            if (r.envelope.isNotEmpty()) Section("Echo trace") {
                EchoTrace(r.envelope)
                Note("The 10 chirps of the last ping, as the microphone heard them. Even spikes = clean capture.")
            }
            stats?.third?.takeIf { it.size >= 5 }?.let { ts ->
                Section("Per-chirp moisture distribution") {
                    Histogram(ts.map { it.coerceIn(0.0, 1.0) })
                    Note("Each bar counts chirps landing in that 10% band. A tight cluster = a confident reading.")
                }
            }
        }

        Section("Spot survey — find the wettest patch") {
            Note("Ping several spots (e.g. along a wall or across a field) and save each. Shuddh ranks them so you can trace a leak to its source.")
            BtnRow {
                Btn("📍 Save this spot", enabled = shown != null && !busy) {
                    val (t, ci, _) = shown!!
                    spots += Spot("Spot ${spots.size + 1}", (t * 100).coerceIn(0.0, 100.0), ci * 100, judged!!.first)
                }
                if (spots.isNotEmpty()) Btn("Clear", primary = false) { spots.clear() }
            }
            if (spots.isNotEmpty()) SpotBars(spots)
        }

        Section("Sensor 2 — camera (fused with sonar)") {
            Note("Wet ${surface.name.en.lowercase()} looks darker: water fills the air gaps that scatter light. Put a white paper in the white box and the surface in the blue box.")
            com.shuddh.lab.camera.CameraView(cam, Modifier.fillMaxWidth(), overlay = { roi(camWhite, Color.White); roi(camSurf, Palette.cyan) }) { bmp ->
                val l = com.shuddh.lab.camera.Frames.relativeLab(com.shuddh.lab.camera.Frames.meanRgb(bmp, camSurf), com.shuddh.lab.camera.Frames.meanRgb(bmp, camWhite)).l
                camLive = l
                if (motion.steady) camCollector.offer(listOf(l))
            }
            Note("Live lightness L* ${camLive?.let { fmt(it) } ?: "—"}" + (camRef.optDouble("dry").takeIf { !it.isNaN() }?.let { " · dry ${fmt(it)}" } ?: "") + (camRef.optDouble("wet").takeIf { !it.isNaN() }?.let { " · wet ${fmt(it)}" } ?: "") + (camNow?.let { " · now ${fmt(it.first)}" } ?: ""))
            BtnRow {
                Btn(if (camBusy) "Reading…" else "📷 Read surface", enabled = !camBusy) { camCapture { camNow = it } }
                Btn("DRY ref", primary = false, enabled = !camBusy) { camCapture { camRef = org.json.JSONObject(camRef.toString()).put("dry", it.first); app.prefs.putJson("nami_${surface.id}_cam", camRef); camNow = it } }
                Btn("WET ref", primary = false, enabled = !camBusy) { camCapture { camRef = org.json.JSONObject(camRef.toString()).put("wet", it.first); app.prefs.putJson("nami_${surface.id}_cam", camRef); camNow = it } }
            }
            if (camRef.has("dry") && camRef.has("wet") && camRef.getDouble("dry") - camRef.getDouble("wet") < 3) Note("Camera can't see a difference for this material — only sonar will be used.", Palette.amber)
        }
        Section("References for ${surface.name.en}") {
            Note("For ★★★ accuracy: ping the same material fully dry and fully wet, same distance, and add 2–3 captures of each — they're averaged.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RefPill("DRY", dry?.second ?: 0, Palette.amber, Modifier.weight(1f))
                RefPill("WET", wet?.second ?: 0, Palette.blue, Modifier.weight(1f))
            }
            BtnRow {
                Btn("Add to DRY", enabled = reading != null, primary = false) { addDry() }
                Btn("Add to WET", enabled = reading != null, primary = false) { addWet() }
                Btn("Reset", primary = false) {
                    dry = null; wet = null
                    app.prefs.putJson("nami_${surface.id}_dry", null); app.prefs.putJson("nami_${surface.id}_wet", null)
                }
            }
        }
        Section("Phone calibration (one-time)") {
            Note("Hold the phone up in open air and ping once. This records your phone's own speaker + mic sound so quick estimates (without references) become much more reliable.")
            Note(if (air != null) "✓ This phone is calibrated" else "Not calibrated yet", if (air != null) Palette.accent else Palette.amber)
            Btn(if (airBusy) "Calibrating… hold in the air" else if (air != null) "Re-calibrate in air" else "📱 Calibrate phone in air", Modifier.fillMaxWidth(), enabled = !busy, primary = air == null) { calibrateAir() }
        }
        Btn("Get verdict", Modifier.fillMaxWidth(), enabled = reading != null) { app.show(verdict()) }
        if (popup) androidx.compose.ui.window.Dialog(onDismissRequest = { popup = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Palette.card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val fm = failMsg
                if (fm != null) {
                    Text("🔇", fontSize = 44.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("No echo heard", color = Palette.amber, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text(fm, color = Palette.text, fontSize = 14.sp, textAlign = TextAlign.Center)
                    BtnRow {
                        Btn("Ping again") { popup = false; ping() }
                        Btn("Close", primary = false) { popup = false }
                    }
                } else if (shown != null && judged != null) {
                    ResultBanner(shown.first, shown.second, stats != null, judged.first, judged.second.get(app.lang), judged.third.firstOrNull()?.get(app.lang), dry != null, wet != null, accuracy)
                    savedMsg?.let { Text("✓ $it", color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    if (stats == null) Note("Is this surface fully dry or soaking wet? Save it as a reference to calibrate:")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("This is DRY", Modifier.weight(1f), primary = false) { addDry() }
                        Btn("This is WET", Modifier.weight(1f), primary = false) { addWet() }
                    }
                    Btn("Full report", Modifier.fillMaxWidth()) { popup = false; app.show(verdict()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Ping again", Modifier.weight(1f), primary = false) { popup = false; ping() }
                        Btn("Close", Modifier.weight(1f), primary = false) { popup = false }
                    }
                }
            }
        }
        HowItWorks(listOf(
            "The speaker plays twelve 40 ms chirps sweeping 2 → 18 kHz — a tiny sonar — three times.",
            "The microphone hears each chirp plus its reflection from the surface right below the phone.",
            "A matched filter finds every chirp; dividing what came back by what was sent gives the surface's acoustic fingerprint in 16 bands.",
            "The silence before the chirps measures the room's noise in every band; bands drowned by a fan or a voice get almost no weight.",
            "All 36 chirps are pooled; pings taken while the phone moved are thrown away and retaken; any chirp far from the median (a cough, a bump) is rejected by the MAD rule.",
            "Dry porous materials soak up high frequencies in their air pockets; water fills the pockets and the surface reflects more.",
            "The score uses the spectrum's shape, not its loudness, so holding the phone a few mm closer doesn't read as \"wetter\".",
            "Media volume is pinned to 85% during every ping (then restored), so references and readings are always made at the same loudness.",
            "Each chirp is projected between your averaged dry and wet references, giving a moisture index with a 95% confidence interval.",
            "A second, independent sensor — the camera — measures how much darker the surface is than your dry reference (water fills the air gaps that scatter light).",
            "Sonar and camera are fused by inverse-variance weighting; a χ² test checks they agree. ★★★ only when both sensors tell the same story.",
        ))
    }
}

/** The big, unmissable answer after a ping: %, label, advice, and whether it's calibrated. */
@Composable
private fun ResultBanner(t: Double, ci: Double, calibrated: Boolean, level: Level, label: String, advice: String?, hasDry: Boolean, hasWet: Boolean, accuracy: Pair<String, Color>) {
    val c = levelColor(level)
    val pct = (t * 100).coerceIn(0.0, 100.0)
    val shown by animateFloatAsState(pct.toFloat(), tween(1200), label = "pct")
    Glass(Modifier.enter(), glow = c, padding = 18) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MOISTURE", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${shown.toInt()}", color = c, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 56.sp, lineHeight = 56.sp)
                    Text("%", color = c, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.padding(bottom = 8.dp, start = 2.dp))
                    Text("  ± ${ci.times(100).toInt()}", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
            Text(label, color = Palette.on(c), fontWeight = FontWeight.Black, fontFamily = Display, fontSize = 18.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(c).padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val y = size.height / 2
            drawLine(Brush.horizontalGradient(listOf(Palette.tint(Color(0xFFFBBF24)), Color(0xFF34D399), Color(0xFF22D3EE), Color(0xFF3B82F6))), Offset(8f, y), Offset(size.width - 8f, y), 12f, StrokeCap.Round)
            val x = 8f + (size.width - 16f) * shown / 100f
            drawCircle(Color.White, 11f, Offset(x, y)); drawCircle(c, 6f, Offset(x, y))
        }
        Row { Text("DRY", color = Palette.amber, fontSize = 10.sp, modifier = Modifier.weight(1f)); Text("WET", color = Palette.blue, fontSize = 10.sp) }
        advice?.let { Text(it, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        Text(accuracy.first, color = accuracy.second, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        val missing = listOfNotNull("DRY".takeIf { !hasDry }, "WET".takeIf { !hasWet }).joinToString(" and a ")
        val chip = if (calibrated) "✓ Calibrated to your dry + wet references" else "⚡ Quick estimate — for accuracy, add a $missing reference below"
        val cc = if (calibrated) Palette.accent else Palette.amber
        Text(chip, color = cc, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(cc.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** Phone edge above a textured surface; chirps radiate down and echoes come back while pinging. Pores fill with water as moisture rises. */
@Composable
private fun SonarScene(busy: Boolean, progress: Int, t: Double?, surfaceId: String) {
    val inf = rememberInfiniteTransition(label = "sonar")
    val wave by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "w")
    val shimmer by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    val m by animateFloatAsState((t ?: 0.0).toFloat().coerceIn(0f, 1f), tween(1200), label = "fill")
    val cyan = Palette.cyan
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val w = size.width; val h = size.height
        val top = h * 0.66f
        // Surface with a texture per material.
        val base = when (surfaceId) { "soil" -> Color(0xFF5B3A23); "wall" -> Color(0xFF8A7A6A); "grain" -> Color(0xFFC9A55C); else -> Color(0xFF4A5A7A) }
        val wetTint = Color(0xFF1E3A8A)
        drawRoundRect(Brush.verticalGradient(listOf(lerpC(base, wetTint, m * 0.55f), lerpC(base, wetTint, m * 0.35f).copy(alpha = 0.7f)), top, h),
            Offset(0f, top), Size(w, h - top), CornerRadius(18f))
        texture(surfaceId, top, w, h)
        // Water in the pores: more and bigger droplets as moisture rises.
        val rnd = java.util.Random(7)
        repeat(60) { i ->
            val x = rnd.nextFloat() * w; val y = top + 8f + rnd.nextFloat() * (h - top - 14f)
            val show = (i / 60f) < m
            if (show) drawCircle(Color(0xFF60A5FA).copy(alpha = 0.35f + 0.4f * shimmer * (i % 3) / 2f), 2.5f + 4f * m, Offset(x, y))
        }
        // Phone held upright, bottom edge (speaker + mic) facing the surface.
        val pw = w * 0.30f; val px = (w - pw) / 2; val py = top - 40f
        drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(px + 6f, 16f), Size(pw, py - 10f), CornerRadius(28f))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF475569), Color(0xFF1E293B)), 10f, py), Offset(px, 10f), Size(pw, py - 10f), CornerRadius(28f))
        drawRoundRect(Brush.verticalGradient(listOf(cyan.copy(alpha = 0.35f), Color(0xFF0F172A))), Offset(px + 8f, 18f), Size(pw - 16f, py - 34f), CornerRadius(20f))
        repeat(5) { k -> drawCircle(Color.White.copy(alpha = 0.7f), 2.5f, Offset(px + pw * 0.58f + k * 8f, py - 8f)) }
        drawCircle(Color.White.copy(alpha = 0.8f), 3.5f, Offset(px + pw * 0.25f, py - 8f))
        // Gap marker.
        drawLine(Color.White.copy(alpha = 0.3f), Offset(px - 18f, py), Offset(px - 18f, top), 2f)
        if (busy) {
            val sx = px + pw * 0.72f; val sy = py
            repeat(3) { k ->
                val p = (wave + k / 3f) % 1f
                val r = 12f + p * (top - sy + 30f)
                drawArc(cyan.copy(alpha = (1 - p) * 0.8f), 30f, 120f, false, Offset(sx - r, sy - r), Size(2 * r, 2 * r), style = Stroke(3f, cap = StrokeCap.Round))
                // Echo coming back up towards the mic.
                val e = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
                if (e > 0f) {
                    val mx = px + pw * 0.25f; val re = 10f + (1 - e) * (top - sy)
                    drawArc(Color(0xFF60A5FA).copy(alpha = e * 0.7f), 210f, 120f, false, Offset(mx - re, top - re), Size(2 * re, 2 * re), style = Stroke(2.5f, cap = StrokeCap.Round))
                }
            }
            // Progress dots for the three pings.
            repeat(3) { k -> drawCircle(if (k < progress) cyan else Palette.ink.copy(alpha = 0.2f), 6f, Offset(px + pw + 30f, 30f + k * 22f)) }
        }
    }
}

private fun lerpC(a: Color, b: Color, f: Float) = Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, 1f)

private fun DrawScope.texture(id: String, top: Float, w: Float, h: Float) {
    val ink = Palette.ink.copy(alpha = 0.12f)
    when (id) {
        "wall" -> {
            var y = top + 18f; var row = 0
            while (y < h) {
                drawLine(ink, Offset(0f, y), Offset(w, y), 2f)
                var x = if (row % 2 == 0) 0f else 30f
                while (x < w) { drawLine(ink, Offset(x, y - 18f), Offset(x, y), 2f); x += 60f }
                y += 18f; row++
            }
        }
        "grain" -> {
            val rnd = java.util.Random(3)
            repeat(70) { drawOval(Palette.ink.copy(alpha = 0.18f), Offset(rnd.nextFloat() * w, top + 4f + rnd.nextFloat() * (h - top - 12f)), Size(14f, 8f)) }
        }
        "cloth" -> {
            var x = 0f; while (x < w) { drawLine(ink, Offset(x, top), Offset(x, h), 1.5f); x += 10f }
            var y = top; while (y < h) { drawLine(ink, Offset(0f, y), Offset(w, y), 1.5f); y += 10f }
        }
        else -> {
            val rnd = java.util.Random(11)
            repeat(90) { drawCircle(Color.Black.copy(alpha = 0.25f), 1.5f + rnd.nextFloat() * 3f, Offset(rnd.nextFloat() * w, top + 4f + rnd.nextFloat() * (h - top - 8f))) }
        }
    }
}

/** Round "glass" filled with animated water to the moisture level; outer arc shows the 95% CI. */
@Composable
private fun LiquidGauge(t: Double?, ci: Double?, label: String?, color: Color, busy: Boolean, modifier: Modifier) {
    val level by animateFloatAsState((t ?: 0.0).toFloat().coerceIn(0f, 1f), tween(1400), label = "lvl")
    val band by animateFloatAsState((ci ?: 0.0).toFloat().coerceIn(0f, 0.5f), tween(1000), label = "ci")
    val inf = rememberInfiniteTransition(label = "liquid")
    val ph by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(if (busy) 900 else 2400, easing = LinearEasing)), label = "ph")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(132.dp)) {
            val r = size.minDimension / 2 - 10f
            val c = center
            drawCircle(Palette.ink.copy(alpha = 0.05f), r)
            val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
            clipPath(clip) {
                val y0 = c.y + r - 2 * r * level
                for (layer in 0..1) {
                    val p = Path()
                    p.moveTo(c.x - r, c.y + r)
                    var x = c.x - r
                    while (x <= c.x + r) {
                        val amp = if (t == null) 0f else 5f + 3f * layer
                        p.lineTo(x, y0 + amp * sin(((x - c.x) / r * 2.4f + ph + layer * 1.7f).toDouble()).toFloat())
                        x += 4f
                    }
                    p.lineTo(c.x + r, c.y + r); p.close()
                    drawPath(p, Brush.verticalGradient(listOf(Color(0xFF38BDF8).copy(alpha = 0.55f - layer * 0.2f), Color(0xFF1D4ED8).copy(alpha = 0.8f)), y0, c.y + r))
                }
            }
            drawCircle(color.copy(alpha = 0.6f), r, style = Stroke(3f))
            if (t != null && band > 0f) {
                val mid = 90f - 360f * level // 0% at bottom, rising clockwise
                drawArc(color, mid - 360f * band, 720f * band, false, Offset(c.x - r - 7f, c.y - r - 7f), Size(2 * r + 14f, 2 * r + 14f), style = Stroke(5f, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t?.let { "${(it * 100).coerceIn(0.0, 100.0).toInt()}%" } ?: if (busy) "…" else "—", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 28.sp)
            Text(ci?.let { "± ${fmt(it * 100)}" } ?: (label ?: "moisture"), color = Palette.text.copy(alpha = 0.8f), fontSize = 11.sp)
            if (label != null) Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityChecks(volume: Float, r: Sonar.Reading?, steady: Boolean) {
    val checks = listOf(
        "🔊 Volume locked 85%" to true,
        (r?.let { "🤫 Room ${fmt(it.noiseDb)} dB" } ?: "🤫 Quiet room") to (r?.noiseDb?.let { it < -45 } ?: true),
        "✋ Steady" to steady,
        "📈 No clipping" to (r?.clipped != true),
        (r?.let { "🎯 ${it.chirpsFound} chirps" } ?: "🎯 Chirps") to (r?.let { it.chirpsFound >= 20 } ?: true),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        checks.forEach { (t, ok) ->
            val c = if (ok) Palette.accent else Palette.amber
            Text(t, color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun BandSnrStrip(snr: FloatArray) {
    val grow = remember(snr) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(snr) { grow.animateTo(1f, tween(900)) }
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val bw = size.width / snr.size
        snr.forEachIndexed { i, s ->
            val q = ((s - 0f) / 40f).coerceIn(0.05f, 1f)
            val c = when { s > 15 -> Palette.accent; s > 8 -> Palette.amber; else -> Palette.red }
            val bh = (size.height - 18f) * q * grow.value
            drawRoundRect(Brush.verticalGradient(listOf(c, c.copy(alpha = 0.25f))), Offset(i * bw + 3f, size.height - 16f - bh), Size(bw - 6f, bh), CornerRadius(6f))
        }
    }
    Row { Text("2 kHz", color = Palette.muted, fontSize = 10.sp, modifier = Modifier.weight(1f)); Text("18 kHz", color = Palette.muted, fontSize = 10.sp) }
}

@Composable
private fun EchoTrace(env: FloatArray) {
    val draw = remember(env) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(env) { draw.animateTo(1f, tween(1400, easing = LinearEasing)) }
    val cyan = Palette.cyan
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val n = (env.size * draw.value).toInt()
        val mid = size.height / 2
        for (i in 0 until n) {
            val x = size.width * i / env.size
            val a = env[i] * (size.height / 2 - 2f)
            drawLine(cyan.copy(alpha = 0.35f + 0.65f * env[i]), Offset(x, mid - a), Offset(x, mid + a), 2.5f, StrokeCap.Round)
        }
    }
}

@Composable
private fun RefPill(name: String, n: Int, c: Color, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = 0.12f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = c, fontWeight = FontWeight.Black, fontFamily = Display, modifier = Modifier.weight(1f))
        repeat(3) { k -> Box(Modifier.padding(start = 4.dp).size(10.dp).clip(RoundedCornerShape(50)).background(if (k < n) c else Palette.ink.copy(alpha = 0.12f))) }
        Spacer(Modifier.width(6.dp))
        Text(if (n == 0) "not set" else "×$n", color = Palette.text, fontSize = 12.sp)
    }
}

@Composable
private fun SpotBars(spots: List<Spot>) {
    val wettest = spots.maxByOrNull { it.pct }
    spots.forEachIndexed { i, s ->
        val f by animateFloatAsState((s.pct / 100).toFloat().coerceIn(0.02f, 1f), tween(900), label = "spot$i")
        val c = levelColor(s.level)
        Row(Modifier.enter(i.coerceAtMost(6)), verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, color = Palette.text, fontSize = 12.sp, modifier = Modifier.width(56.dp))
            Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(Palette.ink.copy(alpha = 0.06f))) {
                Box(Modifier.fillMaxWidth(f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.5f), c))))
            }
            Text(" ${s.pct.toInt()}%" + if (s === wettest && spots.size > 1) " 💧" else "", color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        }
    }
    if (spots.size > 1 && wettest != null) Note("💧 ${wettest.name} is the wettest — the leak or seepage source is most likely nearest it.", Palette.cyan)
}

/** Animated 10-bin histogram of values in 0..1. */
@Composable
fun Histogram(values: List<Double>, color: Color = Palette.cyan) {
    val bins = IntArray(10).also { b -> values.forEach { v -> b[(v * 10).toInt().coerceIn(0, 9)]++ } }
    val maxN = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
    val grow = remember(values) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(values) { grow.animateTo(1f, tween(800)) }
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val bw = size.width / 10
        bins.forEachIndexed { i, n ->
            val h = (size.height - 20f) * n / maxN * grow.value
            drawRoundRect(
                Brush.verticalGradient(listOf(color, color.copy(alpha = 0.3f))),
                Offset(i * bw + 4f, size.height - 18f - h), Size(bw - 8f, h),
                androidx.compose.ui.geometry.CornerRadius(8f),
            )
        }
        drawLine(Palette.ink.copy(alpha = 0.2f), Offset(0f, size.height - 16f), Offset(size.width, size.height - 16f), 2f)
    }
    Row {
        Text("dry 0%", color = Palette.amber, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text("wet 100%", color = Palette.blue, fontSize = 10.sp)
    }
}
