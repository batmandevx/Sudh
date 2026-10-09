package com.shuddh.lab.instruments

import android.graphics.RectF
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.Frames
import com.shuddh.lab.core.AnaemiaIndex
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.HbIndex
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Ppg
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private val anaemiaName = Txt("Haemoglobin screen", "हीमोग्लोबिन जाँच", "ಹಿಮೋಗ್ಲೋಬಿನ್ ತಪಾಸಣೆ", "హిమోగ్లోబిన్ పరీక్ష", "ஹீமோகுளோபின் சோதனை")
private val blood = Color(0xFFE11D48)
private const val MEASURE_S = 15

private enum class HbPhase { IDLE, PLACE, SETTLE, MEASURE, DONE }

/** Anaemia Screen — fingertip over camera + flash; red vs green light through the blood → haemoglobin index. */
@Composable
fun AnaemiaScreen(app: AppState) {
    val ctx = app.ctx
    val view = LocalView.current
    val cam = remember { CameraHandle() }
    var phase by remember { mutableStateOf(HbPhase.IDLE) }
    var finger by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val waveR = remember { mutableStateListOf<Float>() }
    val waveG = remember { mutableStateListOf<Float>() }
    val ts = remember { java.util.Collections.synchronizedList(mutableListOf<Double>()) }
    val rs = remember { java.util.Collections.synchronizedList(mutableListOf<Double>()) }
    val gs = remember { java.util.Collections.synchronizedList(mutableListOf<Double>()) }
    val recording = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var result by remember { mutableStateOf<HbIndex.Reading?>(null) }
    var cal by remember { mutableStateOf(app.prefs.json("hb_cal")?.let { it.getDouble("hb") to it.getDouble("idx") }) }
    var labInput by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(loadHist(app)) }
    val deb = remember { IntArray(3) }
    val hb = result?.let { r -> cal?.let { (h, i) -> HbIndex.estimateHb(r.index, h, i) } }
    val band = result?.let { HbIndex.band(it.index, hb) }

    fun say(en: String) = app.voice.speak(en, app.lang)
    DisposableEffect(phase) { view.keepScreenOn = phase != HbPhase.IDLE && phase != HbPhase.DONE; onDispose { view.keepScreenOn = false } }
    DisposableEffect(Unit) { onDispose { cam.torch(false) } }

    LaunchedEffect(phase, finger) {
        when (phase) {
            HbPhase.PLACE -> if (finger) phase = HbPhase.SETTLE else say("Cover the back camera and the flash with your fingertip. Rest it gently.")
            HbPhase.SETTLE -> {
                say("Good. Keep still.")
                delay(1800); if (!finger) { phase = HbPhase.PLACE; return@LaunchedEffect }
                // Lower exposure so the bright red channel doesn't clip, then lock it for a fair red/green comparison.
                cam.lock(false); cam.exposure((cam.evRange.first / 2)); delay(700); cam.lock(true)
                ts.clear(); rs.clear(); gs.clear(); recording.set(true); progress = 0f
                phase = HbPhase.MEASURE
            }
            HbPhase.MEASURE -> if (!finger) {
                recording.set(false); say("Finger moved. Place it back."); phase = HbPhase.PLACE
            } else {
                val start = System.currentTimeMillis()
                while (progress < 1f) {
                    delay(100)
                    progress = ((System.currentTimeMillis() - start) / (MEASURE_S * 1000f)).coerceAtMost(1f)
                    if (progress > 0.5f && progress < 0.52f) say("Halfway.")
                }
                recording.set(false)
                val r = HbIndex.analyse(ts.toList(), rs.toList(), gs.toList())
                result = r
                cam.torch(false)
                phase = HbPhase.DONE
                if (r == null || r.quality < 0.25) { say("The signal was unsteady. Please try again and keep very still."); Haptics.result(ctx, Level.INCONCLUSIVE) }
                else {
                    val b = HbIndex.band(r.index, cal?.let { (h, i) -> HbIndex.estimateHb(r.index, h, i) })
                    Haptics.result(ctx, b.level); say("${b.label}. ${b.advice}")
                    history = saveHist(app, r.index)
                }
            }
            else -> {}
        }
    }

    fun verdict(): Outcome {
        val r = result!!; val b = band!!
        val ev = listOf(
            Evidence("OBSERVATION", "Light through the fingertip: green/red ${fmt(r.gr)}, pulse ratio ${fmt(r.pulseRatio)} → index ${r.index.toInt()}/100"),
            Evidence("QUALITY", "Steady pulse signal (quality ${(r.quality * 100).toInt()}%)", r.quality >= 0.4),
            Evidence("CALIBRATION", cal?.let { "Calibrated to your lab Hb ${fmt(it.first)} g/dL" } ?: "Not calibrated — screening band only", cal != null),
            Evidence("HYPOTHESIS", b.label + (hb?.let { " (≈${fmt(it)} g/dL)" } ?: ""), b.level == Level.SAFE),
        )
        return Outcome("Shuddh Anaemia Screen", "hb_index", anaemiaName, hb ?: r.index, if (hb != null) "g/dL" else "/100", b.level, b.label, listOf(Txt(b.advice)), ev,
            "Screening, not diagnosis — finger thickness, cold hands and nail polish affect light transmission. Confirm with a blood Hb test.", levelLabel = Txt(b.label.uppercase()))
    }

    ScreenFrame("Anaemia Screen", "Fingertip on camera → haemoglobin check", onBack = { cam.torch(false); app.back() }) {
        Glass(glow = band?.let { Color(it.level.argb) } ?: blood, padding = 16) {
            AnimatedContent(phase == HbPhase.DONE && result != null, transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(300)) }, label = "hero") { done ->
                if (done) ResultHero(result!!, band!!, hb)
                else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(contentAlignment = Alignment.Center) {
                        ScanRing(phase, progress, finger)
                        if (phase != HbPhase.IDLE) Box(Modifier.size(96.dp).clip(CircleShape)) {
                            CameraView(cam, Modifier.size(96.dp), widthFraction = 1f, overlay = {}) { bmp ->
                                val c = Frames.meanRgb(bmp, RectF(0.3f, 0.3f, 0.7f, 0.7f))
                                val corners = listOf(RectF(0f, 0f, 0.2f, 0.2f), RectF(0.8f, 0f, 1f, 0.2f), RectF(0f, 0.8f, 0.2f, 1f), RectF(0.8f, 0.8f, 1f, 1f))
                                    .map { Frames.meanRgb(bmp, it).let { k -> floatArrayOf(k.r, k.g, k.b) } }
                                val raw = Ppg.fingerCovers(floatArrayOf(c.r, c.g, c.b), corners)
                                val v = if (raw) 1 else 0
                                deb[0] = if (v == deb[1]) deb[0] + 1 else 1; deb[1] = v
                                if (deb[0] >= (if (raw) 8 else 5)) deb[2] = v
                                val on = deb[2] == 1
                                if (recording.get() && on) { ts += System.nanoTime() / 1e9; rs += c.r.toDouble(); gs += c.g.toDouble() }
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    finger = on
                                    if (on) { waveR += c.r; waveG += c.g; if (waveR.size > 150) { waveR.removeAt(0); waveG.removeAt(0) } }
                                }
                            }
                        } else FingerOnPhone(Modifier.size(150.dp))
                    }
                    Text(
                        when (phase) {
                            HbPhase.IDLE -> "Check for anaemia in 15 seconds"
                            HbPhase.PLACE -> "Cover the back camera + flash with a fingertip"
                            HbPhase.SETTLE -> "Finger detected — hold still…"
                            HbPhase.MEASURE -> "Reading your blood… ${(MEASURE_S * (1 - progress)).toInt()} s"
                            HbPhase.DONE -> "Signal too unsteady — try again"
                        },
                        color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center,
                    )
                    if (phase != HbPhase.IDLE) Text(if (finger) "● FINGER DETECTED" else "○ NO FINGER", color = if (finger) Palette.accent else Palette.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (phase == HbPhase.MEASURE || phase == HbPhase.SETTLE) DualWave(waveR, waveG)
            Btn(
                when (phase) { HbPhase.IDLE -> "Start"; HbPhase.DONE -> "Measure again"; else -> "Cancel" }, Modifier.fillMaxWidth(),
                primary = phase == HbPhase.IDLE || phase == HbPhase.DONE,
            ) {
                when (phase) {
                    HbPhase.IDLE, HbPhase.DONE -> { result = null; waveR.clear(); waveG.clear(); cam.torch(true); phase = HbPhase.PLACE; Haptics.click(ctx) }
                    else -> { recording.set(false); cam.torch(false); phase = HbPhase.IDLE }
                }
            }
            if (result != null && phase == HbPhase.DONE) Btn("Full report", Modifier.fillMaxWidth(), primary = false) { app.show(verdict()) }
        }

        if (history.size >= 2) Section("Your trend") {
            Trend(history)
            Note("Haemoglobin changes slowly — compare readings taken a few weeks apart, same finger, same time of day.")
        }

        Section("Calibrate with a blood test (optional)") {
            Note(cal?.let { "Calibrated: your lab Hb ${fmt(it.first)} g/dL ↔ index ${it.second.toInt()}. Readings now show an estimated g/dL." }
                ?: "Enter the Hb value from your last blood test right after a reading here. Future readings then show a personal g/dL estimate.")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(labInput, { labInput = it }, label = { Text("Lab Hb (g/dL)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                Btn("Save", enabled = result != null && labInput.toDoubleOrNull()?.let { it in 4.0..20.0 } == true) {
                    val h = labInput.toDouble(); val i = result!!.index
                    cal = h to i; app.prefs.putJson("hb_cal", JSONObject().put("hb", h).put("idx", i)); labInput = ""
                    Haptics.click(ctx)
                }
            }
        }

        Section("Iron-rich food that helps") {
            AnaemiaIndex.ironFoods.forEach { Text(it, color = Palette.text, fontSize = 13.sp) }
            Note("Free Hb testing: PHCs and Anganwadi centres under Anaemia Mukt Bharat. WHO anaemia threshold: below 12 g/dL for women, 13 for men, 11 in pregnancy.")
        }
        HowItWorks(listOf(
            "The flash shines through your fingertip into the camera. Haemoglobin — the red pigment in blood — absorbs green light strongly and lets red through.",
            "Shuddh compares green vs red light coming through (more haemoglobin = less green) and how strongly each colour pulses with your heartbeat.",
            "Exposure is lowered and locked so the bright red channel doesn't clip; only frames with your finger fully covering the lens count.",
            "Based on published smartphone-haemoglobin research (HemaApp, University of Washington). It is a screen: a low result means get a blood test.",
        ))
    }
}

private fun loadHist(app: AppState): List<Pair<Long, Double>> = app.prefs.json("hb_hist")?.optJSONArray("h")?.let { a ->
    (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getLong(0) to p.getDouble(1) } }
} ?: emptyList()

private fun saveHist(app: AppState, idx: Double): List<Pair<Long, Double>> {
    val h = (loadHist(app) + (System.currentTimeMillis() to idx)).takeLast(30)
    app.prefs.putJson("hb_hist", JSONObject().put("h", JSONArray(h.map { JSONArray().put(it.first).put(it.second) })))
    return h
}

/** Idle illustration: a fingertip gliding onto the back camera + flash. */
@Composable
private fun FingerOnPhone(modifier0: Modifier) {
    val modifier = modifier0.clipToBounds()
    val inf = rememberInfiniteTransition(label = "finger")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF334155), Color(0xFF1E293B))), Offset(w * 0.22f, h * 0.05f), Size(w * 0.56f, h * 0.9f), CornerRadius(28f))
        val lens = Offset(w * 0.4f, h * 0.2f); val flash = Offset(w * 0.58f, h * 0.2f)
        drawCircle(Color(0xFF0F172A), 16f, lens); drawCircle(Color(0xFF475569), 9f, lens)
        drawCircle(Color(0xFFFDE68A).copy(alpha = 0.4f + 0.6f * p), 8f, flash)
        // Finger slides in from below and covers lens + flash.
        val fy = h * (0.95f - 0.75f * p)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF5B7A0), Color(0xFFE29578)), fy - 20f, fy + 120f), Offset(w * 0.33f, fy - 22f), Size(w * 0.34f, h * 0.6f), CornerRadius(40f))
        if (p > 0.85f) drawCircle(blood.copy(alpha = (p - 0.85f) * 4), 34f, Offset(w * 0.49f, h * 0.2f))
    }
}

/** Progress ring around the live fingertip view, with a pulsing glow while measuring. */
@Composable
private fun ScanRing(phase: HbPhase, progress: Float, finger: Boolean) {
    if (phase == HbPhase.IDLE) return
    val inf = rememberInfiniteTransition(label = "ring")
    val glow by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "glow")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin")
    val p by animateFloatAsState(progress, tween(150), label = "prog")
    Canvas(Modifier.size(170.dp)) {
        val st = 12f
        val tl = Offset(st, st); val sz = Size(size.width - 2 * st, size.height - 2 * st)
        drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, tl, sz, style = Stroke(st))
        if (phase == HbPhase.MEASURE) drawArc(Brush.sweepGradient(listOf(Color(0xFF22C55E), blood, Color(0xFF22C55E))), -90f, 360f * p, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
        else drawArc((if (finger) Palette.accent else Palette.amber).copy(alpha = 0.8f), spin, 70f, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
        drawCircle(blood.copy(alpha = 0.18f * glow), size.minDimension / 2 - 20f)
    }
}

/** Live red & green light through the finger — green dips deeper with each beat when haemoglobin is high. */
@Composable
private fun DualWave(r: List<Float>, g: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        fun line(v: List<Float>, c: Color) {
            if (v.size < 3) return
            val lo = v.min(); val hi = v.max().coerceAtLeast(lo + 0.5f)
            val dx = size.width / (v.size - 1)
            val p = Path(); v.forEachIndexed { i, x -> val y = size.height - 4f - (size.height - 8f) * (x - lo) / (hi - lo); if (i == 0) p.moveTo(0f, y) else p.lineTo(i * dx, y) }
            drawPath(p, c, style = Stroke(4f, cap = StrokeCap.Round))
        }
        line(r, Color(0xFFF87171)); line(g, Color(0xFF4ADE80))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("━ red light", color = Color(0xFFF87171), fontSize = 11.sp); Text("━ green light", color = Color(0xFF4ADE80), fontSize = 11.sp)
    }
}

/** Result: animated semicircle gauge (index 0–100 or g/dL), band label, heart-rate bonus. */
@Composable
private fun ResultHero(r: HbIndex.Reading, b: HbIndex.Band, hb: Double?) {
    val a = remember(r) { Animatable(0f) }
    LaunchedEffect(r) { a.animateTo((r.index / 100).toFloat().coerceIn(0f, 1f), tween(1400, easing = FastOutSlowInEasing)) }
    val col = Color(b.level.argb)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.size(240.dp, 130.dp)) {
                val st = 22f
                val tl = Offset(st, st); val sz = Size(size.width - 2 * st, (size.height - st) * 2)
                listOf(0f to 0.45f to Color(0xFFF43F5E), 0.45f to 0.58f to Color(0xFFFBBF24), 0.58f to 1f to Color(0xFF34D399)).forEach { (range, c) ->
                    drawArc(c.copy(alpha = 0.85f), 180f + 180f * range.first, 180f * (range.second - range.first) - 2f, false, tl, sz, style = Stroke(st))
                }
                val ang = Math.toRadians(180.0 + 180.0 * a.value)
                val cx = size.width / 2; val cy = size.height
                val rr = sz.width / 2 - 4f
                drawLine(Color.White, Offset(cx, cy), Offset(cx + (rr * kotlin.math.cos(ang)).toFloat(), cy + (rr * kotlin.math.sin(ang)).toFloat()), 7f, StrokeCap.Round)
                drawCircle(Color.White, 12f, Offset(cx, cy))
            }
            Text(hb?.let { "${fmt(it)} g/dL" } ?: "${(a.value * 100).toInt()}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 30.sp, modifier = Modifier.padding(bottom = 18.dp))
        }
        Text(b.label, color = col, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 26.sp)
        Text(b.advice, color = Palette.text, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("❤️ ${r.bpm?.toInt() ?: "—"} bpm", blood)
            Pill("Signal ${(r.quality * 100).toInt()}%", if (r.quality > 0.5) Palette.accent else Palette.amber)
            Pill(if (hb != null) "Calibrated" else "Index /100", Palette.cyan)
        }
    }
}

@Composable
private fun Pill(t: String, c: Color) {
    Text(t, color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun Trend(h: List<Pair<Long, Double>>) {
    val grow = remember(h.size) { Animatable(0f) }
    LaunchedEffect(h.size) { grow.animateTo(1f, tween(1000)) }
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val ys = h.map { it.second.toFloat() }
        val lo = minOf(ys.min(), 40f); val hi = maxOf(ys.max(), 80f)
        val dx = size.width / (ys.size - 1)
        fun pt(i: Int) = Offset(i * dx, size.height - 8f - (size.height - 16f) * (ys[i] - lo) / (hi - lo))
        val y58 = size.height - 8f - (size.height - 16f) * (58f - lo) / (hi - lo)
        drawLine(Color(0xFF34D399).copy(alpha = 0.3f), Offset(0f, y58), Offset(size.width, y58), 2f)
        val n = (ys.size * grow.value).toInt().coerceIn(1, ys.size)
        for (i in 1 until n) drawLine(blood.copy(alpha = 0.7f), pt(i - 1), pt(i), 4f, StrokeCap.Round)
        for (i in 0 until n) drawCircle(if (ys[i] >= 58) Color(0xFF34D399) else Color(0xFFFBBF24), 7f, pt(i))
    }
}
