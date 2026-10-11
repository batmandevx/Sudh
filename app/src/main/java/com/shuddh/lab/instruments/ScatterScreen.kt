package com.shuddh.lab.instruments

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Nephelo
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.Words
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Chips
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import com.shuddh.lab.ui.SteadyBar
import com.shuddh.lab.ui.StepTracker
import com.shuddh.lab.ui.rememberMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private enum class ScatterMode(val label: String, val id: String, val title: Txt, val bad: Txt) {
    AIR("🌫 Air (Hawa)", "air_scatter", Txt("Air particulates", "हवा में कण", "ಗಾಳಿಯಲ್ಲಿ ಕಣಗಳು"), Words.ventilate),
    WATER("💧 Water turbidity", "water_turbidity", Txt("Water clarity", "पानी का गंदलापन", "ನೀರಿನ ಮಬ್ಬು"), Words.turbid),
}

private val bandColors = listOf(Color(0xFF34D399), Palette.tint(Color(0xFFA3E635)), Palette.tint(Color(0xFFFBBF24)), Color(0xFFF97316), Color(0xFFF43F5E))

/** Mean RGB of the ROI plus "sparkle": the fraction of pixels far brighter than the ROI mean (coarse specks). */
private fun roiStats(bmp: Bitmap, r: RectF): DoubleArray {
    val x0 = (r.left * bmp.width).toInt(); val y0 = (r.top * bmp.height).toInt()
    val w = (r.width() * bmp.width).toInt().coerceAtLeast(1); val h = (r.height() * bmp.height).toInt().coerceAtLeast(1)
    val px = IntArray(w * h); bmp.getPixels(px, 0, w, x0, y0, w, h)
    var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
    val lum = FloatArray((px.size + 2) / 3)
    var k = 0
    for (i in px.indices step 3) {
        val c = px[i]; val rr = (c shr 16) and 0xff; val gg = (c shr 8) and 0xff; val bb = c and 0xff
        sr += rr; sg += gg; sb += bb; n++
        if (k < lum.size) lum[k++] = 0.299f * rr + 0.587f * gg + 0.114f * bb
    }
    val mr = sr / n; val mg = sg / n; val mb = sb / n
    val ml = 0.299 * mr + 0.587 * mg + 0.114 * mb
    var spark = 0
    for (i in 0 until k) if (lum[i] > maxOf(ml * 2.2, ml + 45)) spark++
    return doubleArrayOf(mr, mg, mb, spark.toDouble() / k.coerceAtLeast(1))
}

@Composable
fun ScatterScreen(app: AppState) {
    val ctx = app.ctx
    val cam = remember { CameraHandle() }
    val motion = rememberMotion()
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ScatterMode.AIR) }
    val flashOn = remember { AtomicBoolean(false) }
    val recording = remember { AtomicBoolean(false) }
    val frames = remember { java.util.Collections.synchronizedList(mutableListOf<Nephelo.Frame>()) }
    val liveTrace = remember { mutableStateListOf<Pair<Float, Boolean>>() }
    var clean by remember { mutableStateOf<Nephelo.Reading?>(null) }
    var sample by remember { mutableStateOf<Nephelo.Reading?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var saturated by remember { mutableStateOf(false) }
    var monitorJob by remember { mutableStateOf<Job?>(null) }
    val trend = remember { mutableStateListOf<Float>() }
    var lastAlert by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf("Dark box: flash shining across it, camera looking at the beam from the side.") }
    val box = RectF(0.3f, 0.35f, 0.7f, 0.65f)
    val bands = if (mode == ScatterMode.AIR) Nephelo.airBands else Nephelo.waterBands

    DisposableEffect(Unit) { onDispose { monitorJob?.cancel(); cam.torch(false) } }

    /** Flash lock-in: settle AE with the beam on, lock it, then blink OFF/ON [cycles] times. */
    suspend fun lockIn(cycles: Int, label: String): Nephelo.Reading? {
        busy = label; progress = 0f
        cam.lock(false); cam.torch(true); flashOn.set(true); delay(900)
        cam.lock(true)
        frames.clear(); recording.set(true)
        val total = cycles * 2 + 1
        var step = 0
        cam.torch(false); flashOn.set(false); delay(650); progress = ++step / total.toFloat()
        repeat(cycles) {
            cam.torch(true); flashOn.set(true); Haptics.tick(ctx, 0.25f); delay(650); progress = ++step / total.toFloat()
            cam.torch(false); flashOn.set(false); delay(650); progress = ++step / total.toFloat()
        }
        recording.set(false)
        cam.torch(true); flashOn.set(true)
        busy = null
        return Nephelo.lockIn(frames.toList())
    }

    fun measureClean() {
        if (busy != null) return
        scope.launch {
            val r = lockIn(6, "Measuring clean ${if (mode == ScatterMode.AIR) "air" else "water"}")
            clean = r; sample = null
            if (r == null) status = "Couldn't lock in — keep the box closed and the phone still."
            else {
                status = "Clean baseline: ${fmt(r.signal)} ± ${fmt(r.ci)} (${r.cycles} flash cycles). Now add the sample."
                Haptics.click(ctx)
                if (r.signal < 0.8) status += " Signal is weak — aim the flash so it crosses the camera's view."
            }
        }
    }

    fun react(r: Double) {
        val b = Nephelo.band(r, bands)
        val idx = bands.indexOf(b)
        Haptics.rumble(ctx, (idx / (bands.size - 1f)).coerceIn(0f, 1f))
    }

    fun measureSample() {
        if (busy != null || clean == null) return
        scope.launch {
            val r = lockIn(6, "Measuring sample")
            sample = r
            val c = clean
            if (r != null && c != null) {
                val ratio = Nephelo.ratio(r, c)
                val b = Nephelo.band(ratio, bands)
                status = "${b.name}: ${fmt(ratio)}× the clean baseline"
                react(ratio)
                app.voice.speak("${b.name}. ${b.advice}", app.lang)
            } else status = "Couldn't lock in — try again holding steady."
        }
    }

    fun toggleMonitor() {
        monitorJob?.let { it.cancel(); monitorJob = null; busy = null; status = "Monitor stopped."; return }
        val c = clean ?: return
        trend.clear()
        monitorJob = scope.launch {
            status = "Smoke monitor running — leave the phone in the box."
            while (true) {
                val r = lockIn(3, "Monitoring") ?: continue
                sample = r
                val ratio = Nephelo.ratio(r, c)
                trend += ratio.toFloat(); if (trend.size > 60) trend.removeAt(0)
                val b = Nephelo.band(ratio, bands)
                if (bands.indexOf(b) >= 3 && System.currentTimeMillis() - lastAlert > 30_000) {
                    lastAlert = System.currentTimeMillis()
                    Haptics.alarm(ctx)
                    app.voice.speak("Smoke alert. ${b.advice}", app.lang)
                }
                delay(1500)
            }
        }
    }

    val ratio = sample?.let { s -> clean?.let { Nephelo.ratio(s, it) } }
    val ratioCi = sample?.let { s -> clean?.let { Nephelo.ratioCi(s, it) } }
    val alpha = sample?.let { s -> clean?.let { Nephelo.angstrom(s, it) } }
    val sparkEx = sample?.let { s -> clean?.let { (s.sparkle - it.sparkle).coerceAtLeast(0.0) } } ?: 0.0
    val band = ratio?.let { Nephelo.band(it, bands) }

    fun verdict(): Outcome {
        val s = sample!!; val c = clean!!; val r = ratio!!
        val ev = mutableListOf(
            Evidence("OBSERVATION", "Flash lock-in: sample ${fmt(s.signal)} ± ${fmt(s.ci)} vs clean ${fmt(c.signal)} ± ${fmt(c.ci)} (ON − OFF over ${s.cycles}/${c.cycles} cycles)"),
            Evidence("QUALITY", "Ambient and stray light cancelled by on/off subtraction; outlier cycles rejected (MAD)", s.cycles >= 4),
            Evidence("PATTERN", "Scatter = ${fmt(r)}× clean ± ${fmt(ratioCi ?: 0.0)}", (ratioCi ?: 1.0) < 0.3),
        )
        if (mode == ScatterMode.AIR) ev += Evidence("PATTERN", "Colour: ${Nephelo.particleType(alpha, sparkEx)}" + (alpha?.let { " (Ångström α ≈ ${fmt(it)})" } ?: ""))
        if (saturated) ev += Evidence("QUALITY", "Some pixels saturated — readings may be compressed", false)
        val idx = bands.indexOf(band)
        val lvl = when { idx <= 0 -> Level.SAFE; idx <= (if (mode == ScatterMode.AIR) 2 else 1) -> Level.CAUTION; else -> Level.UNSAFE }
        val adv = when (lvl) { Level.SAFE -> listOf(Words.ok); Level.CAUTION -> listOf(Words.useCare); else -> listOf(mode.bad) }
        ev += Evidence("HYPOTHESIS", "${mode.title.en}: ${band!!.name}", lvl == Level.SAFE)
        return Outcome("Shuddh Hawa", mode.id, mode.title, r, "×", lvl, "${band.name}: ${fmt(r)}× the clean baseline", adv, ev,
            "Relative nephelometry against your own clean baseline in the same box — not a calibrated PM2.5/NTU reading.")
    }

    ScreenFrame("Shuddh Hawa", "Flash lock-in light scatter → air & water haze", onBack = { app.back() }) {
        StepTracker(listOf("Set up box" to cam.ready, "Clean baseline" to (clean != null), "Sample" to (sample != null), "Verdict" to false))
        Chips(ScatterMode.entries, mode, { it.label }) { mode = it; sample = null; clean = null; monitorJob?.cancel(); monitorJob = null }

        Glass(glow = band?.let { bandColors[bands.indexOf(it).coerceAtMost(4)] } ?: Palette.cyan) {
            BeamScene(ratio ?: 1.0, alpha, sparkEx, busy != null, mode == ScatterMode.WATER)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HazeGauge(ratio, bands, Modifier.size(150.dp, 100.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(busy ?: band?.name ?: if (clean == null) "Step 1: clean baseline" else "Step 2: add the sample",
                        color = band?.let { bandColors[bands.indexOf(it).coerceAtMost(4)] } ?: Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    if (busy != null) ProgressBar(progress)
                    ratio?.let { Text("${fmt(it)}× clean" + (ratioCi?.let { c -> " ± ${fmt(c)}" } ?: ""), color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    band?.let { Text(it.advice, color = Palette.muted, fontSize = 12.sp) }
                }
            }
            Note(status, Palette.text)
            BtnRow {
                Btn(if (mode == ScatterMode.AIR) "1 · Clean air" else "1 · Clear water", enabled = busy == null, primary = clean == null) { measureClean() }
                Btn("2 · Sample", enabled = clean != null && busy == null, primary = clean != null) { measureSample() }
            }
            if (mode == ScatterMode.AIR) Btn(if (monitorJob != null) "■ Stop smoke monitor" else "🚨 Start smoke monitor", Modifier.fillMaxWidth(), enabled = clean != null, primary = false) { toggleMonitor() }
        }

        Section("Camera view (beam from the side)") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(140.dp)) {
                    CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 1f, overlay = { roi(box, Palette.tint(Color(0xFF9AD0C2))) }) { bmp ->
                        val st = roiStats(bmp, box)
                        val on = flashOn.get()
                        val sat = st[0] > 250 || st[1] > 250 || st[2] > 250
                        if (recording.get() && motion.steady) frames += Nephelo.Frame(System.currentTimeMillis(), on, st[0], st[1], st[2], st[3])
                        val l = (0.299 * st[0] + 0.587 * st[1] + 0.114 * st[2]).toFloat()
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            saturated = sat
                            liveTrace += l to on; if (liveTrace.size > 150) liveTrace.removeAt(0)
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Lock-in signal", color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    LockInTrace(liveTrace)
                    Note("Shaded = flash ON. The step height between ON and OFF is the particle scatter; room light cancels.")
                }
            }
            if (saturated) Note("⚠ Camera saturating — move the flash so the beam crosses the view, not shines into it.", Palette.amber)
        }
        SteadyBar(motion, null)

        sample?.let { s ->
            clean?.let { c ->
                if (mode == ScatterMode.AIR) Section("Particle fingerprint") {
                    ColourBars(s, c)
                    Text(Nephelo.particleType(alpha, sparkEx), color = Palette.cyan, fontWeight = FontWeight.Bold)
                    Note("Fine smoke scatters blue light much more than red (high Ångström α); coarse dust, flour and mist scatter all colours alike and sparkle as specks.")
                }
                Section("Consistency across flash cycles") {
                    CycleDots(s.perCycle, c.perCycle)
                    Note("Each dot is one ON − OFF cycle (cyan = clean, amber = sample). Separate clusters = a confident difference.")
                }
            }
        }
        if (trend.size > 1) Section("Smoke monitor trend") {
            TrendChart(trend, bands)
            Note("Alerts by voice + vibration when haze reaches \"${bands[3].name}\".")
        }
        Btn("Get verdict", Modifier.fillMaxWidth(), enabled = ratio != null && busy == null) { app.show(verdict()) }
        Note(if (mode == ScatterMode.AIR) "Demo: measure clean air, then wave a lit incense stick past the box opening, close it and measure the sample." else "Use the same clear vial for reference and test water.")
        HowItWorks(listOf(
            "Particles (smoke, dust, silt) scatter light sideways — the Tyndall effect you see in a sunbeam. Viewed from 90°, clean air looks dark and particles light up.",
            "Flash lock-in: the flash blinks ON/OFF six times; for every ON period the neighbouring OFF periods are subtracted. Room light, stray light and slow drift cancel — only flash-scattered light remains. This is how lab lock-in amplifiers beat noise.",
            "Frames in the first 250 ms after every switch are dropped while the LED settles; exposure is locked so ON and OFF are comparable; shaky frames are skipped.",
            "Cycles that disagree (a shadow, a bump) are rejected with the MAD rule, and the result carries a 95% confidence interval.",
            "Colour tells particle size: fine smoke scatters blue more than red (Ångström exponent), coarse dust scatters evenly and shows as bright specks.",
            "Smoke monitor repeats the lock-in every few seconds and alerts when haze climbs.",
        ))
    }
}

@Composable
private fun ProgressBar(p: Float) {
    val a by animateFloatAsState(p, tween(300), label = "p")
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.ink.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxWidth(a).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient(listOf(Palette.cyan, Palette.accent))))
    }
}

/** Dark box with the flash beam; floating particles scale with the measured haze and take the particle type's colour. */
@Composable
private fun BeamScene(ratio: Double, alpha: Double?, spark: Double, busy: Boolean, water: Boolean) {
    val inf = rememberInfiniteTransition(label = "beam")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "t")
    val blink by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "blink")
    val haze by animateFloatAsState(((ratio - 1) / 3).toFloat().coerceIn(0f, 1f), tween(1200), label = "haze")
    val pColor = when { spark > 0.004 -> Color(0xFFE7C99A); alpha != null && alpha >= 1.3 -> Color(0xFFBFDBFE); else -> Color(0xFFE5E7EB) }
    val particles = remember { List(140) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(if (water) Color(0xFF0B1A2E) else Color(0xFF0A0F1A), Offset.Zero, size, CornerRadius(22f))
        val beamOn = !busy || blink < 0.5f
        // Beam cone from the flash on the left.
        val src = Offset(18f, h * 0.5f)
        val beam = Path().apply { moveTo(src.x, src.y - 8f); lineTo(w, h * 0.22f); lineTo(w, h * 0.78f); lineTo(src.x, src.y + 8f); close() }
        if (beamOn) drawPath(beam, Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.35f + 0.3f * haze), Color.White.copy(alpha = 0.04f + 0.18f * haze)), 0f, w))
        drawCircle(if (beamOn) Color.White else Color(0xFF334155), 10f, src)
        // Camera eye looking up at the beam from below (90°).
        val eye = Offset(w * 0.62f, h - 16f)
        drawRoundRect(Color(0xFF1F2937), Offset(eye.x - 26f, eye.y - 12f), Size(52f, 24f), CornerRadius(8f))
        drawCircle(Palette.cyan, 7f, eye)
        drawLine(Palette.cyan.copy(alpha = 0.35f), eye, Offset(eye.x, h * 0.5f), 2f)
        // Particles: more with haze; lit ones inside the beam glow.
        val n = (12 + haze * 128).toInt()
        particles.take(n).forEach { (px, py, ps) ->
            val x = (px * w + t * w * (0.3f + ps)) % w
            val y = h * (0.12f + 0.76f * ((py + 0.05f * sin((t * 2 * PI * (1 + ps) + px * 6).toFloat())) % 1f))
            val inBeam = y in (h * 0.5f - (h * 0.28f) * x / w - 8f)..(h * 0.5f + (h * 0.28f) * x / w + 8f)
            val lit = beamOn && inBeam
            drawCircle(if (lit) pColor else pColor.copy(alpha = 0.15f), if (spark > 0.004 && ps > 0.85f) 4f else 1.5f + 2f * ps, Offset(x, y))
            if (lit) drawCircle(pColor.copy(alpha = 0.18f), 6f + 4f * ps, Offset(x, y))
        }
    }
}

/** Semicircular haze gauge with coloured bands and an animated needle (0–5× clean). */
@Composable
private fun HazeGauge(ratio: Double?, bands: List<Nephelo.Band>, modifier: Modifier) {
    val v by animateFloatAsState(((ratio ?: 0.0) / 5).toFloat().coerceIn(0f, 1f), tween(1200), label = "needle")
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            val st = 18f
            val r = minOf(size.width / 2, size.height) - st
            val c = Offset(size.width / 2, size.height - 6f)
            bands.forEachIndexed { i, b ->
                val from = (b.min / 5).toFloat().coerceAtMost(1f); val to = ((bands.getOrNull(i + 1)?.min ?: 5.0) / 5).toFloat().coerceAtMost(1f)
                drawArc(bandColors[i.coerceAtMost(4)], 180f + 180f * from, 180f * (to - from) - 1.5f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(st))
            }
            if (ratio != null) {
                val a = Math.toRadians(180.0 + 180.0 * v)
                val tip = Offset(c.x + (r - 6) * cos(a).toFloat(), c.y + (r - 6) * sin(a).toFloat())
                drawLine(Palette.ink, c, tip, 6f, StrokeCap.Round)
                drawCircle(Palette.ink, 9f, c)
            }
        }
        Text(ratio?.let { "${fmt(it)}×" } ?: "—", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.padding(bottom = 14.dp))
    }
}

@Composable
private fun LockInTrace(trace: List<Pair<Float, Boolean>>) {
    val cyan = Palette.cyan
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        if (trace.size < 2) return@Canvas
        val lo = trace.minOf { it.first }; val hi = trace.maxOf { it.first }.coerceAtLeast(lo + 1f)
        val dx = size.width / (trace.size - 1)
        trace.forEachIndexed { i, (_, on) -> if (on) drawRect(Palette.tint(Color(0xFFFBBF24)).copy(alpha = 0.12f), Offset(i * dx - dx / 2, 0f), Size(dx + 1f, size.height)) }
        val p = Path()
        trace.forEachIndexed { i, (v, _) ->
            val y = size.height - 6f - (size.height - 12f) * (v - lo) / (hi - lo)
            if (i == 0) p.moveTo(0f, y) else p.lineTo(i * dx, y)
        }
        drawPath(p, cyan, style = Stroke(3f, cap = StrokeCap.Round))
    }
}

@Composable
private fun ColourBars(s: Nephelo.Reading, c: Nephelo.Reading) {
    val ex = listOf(
        Triple("Red", (s.r - c.r) / c.r.coerceAtLeast(0.5), Color(0xFFF87171)),
        Triple("Green", (s.g - c.g) / c.g.coerceAtLeast(0.5), Palette.tint(Color(0xFF4ADE80))),
        Triple("Blue", (s.b - c.b) / c.b.coerceAtLeast(0.5), Color(0xFF60A5FA)),
    )
    val top = ex.maxOf { it.second }.coerceAtLeast(0.01)
    ex.forEachIndexed { i, (name, v, col) ->
        val a by animateFloatAsState((v / top).toFloat().coerceIn(0.02f, 1f), tween(900, delayMillis = i * 120), label = name)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Palette.text, fontSize = 12.sp, modifier = Modifier.width(50.dp))
            Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.ink.copy(alpha = 0.06f))) {
                Box(Modifier.fillMaxWidth(a).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Brush.horizontalGradient(listOf(col.copy(alpha = 0.5f), col))))
            }
            Text(" +${(v * 100).toInt()}%", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.width(54.dp))
        }
    }
}

@Composable
private fun CycleDots(sample: List<Double>, clean: List<Double>) {
    val cyan = Palette.cyan; val amber = Palette.amber
    Canvas(Modifier.fillMaxWidth().height(60.dp)) {
        val all = sample + clean
        val lo = all.min(); val hi = all.max().coerceAtLeast(lo + 0.5)
        fun x(v: Double) = 12f + (size.width - 24f) * ((v - lo) / (hi - lo)).toFloat()
        drawLine(Palette.ink.copy(alpha = 0.15f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
        clean.forEachIndexed { i, v -> drawCircle(cyan, 8f, Offset(x(v), size.height / 2 - 10f + (i % 2) * 6f)) }
        sample.forEachIndexed { i, v -> drawCircle(amber, 8f, Offset(x(v), size.height / 2 + 10f - (i % 2) * 6f)) }
    }
}

@Composable
private fun TrendChart(trend: List<Float>, bands: List<Nephelo.Band>) {
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val maxV = 5f
        bands.forEachIndexed { i, b ->
            val y0 = size.height * (1 - (b.min / maxV).toFloat())
            val y1 = size.height * (1 - ((bands.getOrNull(i + 1)?.min ?: 5.0) / maxV).toFloat())
            drawRect(bandColors[i.coerceAtMost(4)].copy(alpha = 0.08f), Offset(0f, y1), Size(size.width, y0 - y1))
        }
        val dx = size.width / (trend.size - 1).coerceAtLeast(1)
        val p = Path()
        trend.forEachIndexed { i, v -> val y = size.height * (1 - (v / maxV).coerceIn(0f, 1f)); if (i == 0) p.moveTo(0f, y) else p.lineTo(i * dx, y) }
        drawPath(p, Color.White, style = Stroke(4f, cap = StrokeCap.Round))
        trend.lastOrNull()?.let { drawCircle(Palette.ink, 7f, Offset((trend.size - 1) * dx, size.height * (1 - (it / maxV).coerceIn(0f, 1f)))) }
    }
}
