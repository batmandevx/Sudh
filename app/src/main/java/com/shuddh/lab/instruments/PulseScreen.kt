package com.shuddh.lab.instruments

import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.Frames
import com.shuddh.lab.core.Ppg
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.stamp
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Badge
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.LineChart
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import com.shuddh.lab.ui.Series
import com.shuddh.lab.ui.rememberMotion
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

private enum class Phase { IDLE, PLACE, SETTLE, MEASURE, DONE }

private const val MEASURE_S = 20.0

private val heartRed = Color(0xFFFF4D6D)

/** Thread-safe sample buffer filled by the camera analyser thread. */
private class Samples {
    private val t = ArrayList<Double>(); private val v = ArrayList<Double>()
    @Synchronized fun add(time: Double, value: Double) { t += time; v += value; while (t.isNotEmpty() && time - t.first() > 30) { t.removeAt(0); v.removeAt(0) } }
    @Synchronized fun clear() { t.clear(); v.clear() }
    @Synchronized fun since(t0: Double): Pair<List<Double>, List<Double>> { val i = t.indexOfFirst { it >= t0 }.coerceAtLeast(0); return t.subList(i, t.size).toList() to v.subList(i, v.size).toList() }
}

@Composable
fun PulseScreen(app: AppState) {
    val cam = remember { CameraHandle() }
    val motion = rememberMotion()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val samples = remember { Samples() }
    // Green channel kept too: under the flash it often carries a cleaner pulse than (near-saturated) red.
    val samplesG = remember { Samples() }
    /** Analyses red and green, returns the more periodic (higher-quality) result. */
    fun best(t0: Double): Ppg.Result? {
        val (t, v) = samples.since(t0); val (tg, vg) = samplesG.since(t0)
        return listOfNotNull(Ppg.analyse(t, v), Ppg.analyse(tg, vg)).maxByOrNull { it.quality }
    }
    var phase by remember { mutableStateOf(Phase.IDLE) }
    var finger by remember { mutableStateOf(false) }
    val deb = remember { IntArray(3) } // streak, lastRaw, debounced
    // Kalman: live BPM wanders slowly; low-quality windows count less (larger measurement variance).
    val bpmKf = remember { com.shuddh.lab.core.Kalman1D(q = 0.6, r = 9.0) }
    var liveBpm by remember { mutableStateOf<Double?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var startT by remember { mutableStateOf(0.0) }
    var result by remember { mutableStateOf<Ppg.Result?>(null) }
    var beatTick by remember { mutableStateOf(0) }
    val wave = remember { mutableStateListOf<Float>() }
    var history by remember { mutableStateOf(loadHistory(app)) }
    // Online beat detector state (camera thread).
    val det = remember { BeatDetector() }

    fun say(t: Txt) { app.voice.speak(t.get(app.lang), app.lang) }

    DisposableEffect(phase) {
        view.keepScreenOn = phase == Phase.PLACE || phase == Phase.SETTLE || phase == Phase.MEASURE
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { cam.torch(false) } }

    // Guided flow.
    LaunchedEffect(phase, finger) {
        when (phase) {
            Phase.PLACE -> if (finger) { phase = Phase.SETTLE } else say(Txt(
                "Gently cover the back camera and flash with your fingertip. Don't press hard.",
                "पीछे के कैमरे और फ्लैश को अपनी उंगली से हल्के से ढकें। ज़ोर से न दबाएँ।",
                "ಹಿಂದಿನ ಕ್ಯಾಮೆರಾ ಮತ್ತು ಫ್ಲ್ಯಾಶ್ ಅನ್ನು ಬೆರಳಿನಿಂದ ಹಗುರವಾಗಿ ಮುಚ್ಚಿ.",
            ))
            Phase.SETTLE -> {
                if (!finger) { phase = Phase.PLACE; return@LaunchedEffect }
                say(Txt("Good. Hold still and breathe normally.", "बढ़िया। स्थिर रहें और सामान्य साँस लें।", "ಒಳ್ಳೆಯದು. ಅಲುಗಾಡದೆ ಸಾಮಾನ್ಯವಾಗಿ ಉಸಿರಾಡಿ."))
                delay(2500)
                cam.lock(true)
                samples.clear(); samplesG.clear(); wave.clear(); liveBpm = null; bpmKf.reset()
                startT = System.nanoTime() / 1e9
                phase = Phase.MEASURE
            }
            Phase.MEASURE -> if (!finger) {
                say(Txt("Finger moved. Place it back over the camera.", "उंगली हट गई। उसे वापस कैमरे पर रखें।", "ಬೆರಳು ಸರಿಯಿತು. ಮತ್ತೆ ಕ್ಯಾಮೆರಾ ಮೇಲೆ ಇಡಿ."))
                cam.lock(false); phase = Phase.PLACE
            }
            else -> {}
        }
    }
    // Progress, live BPM and completion.
    LaunchedEffect(phase) {
        if (phase != Phase.MEASURE) return@LaunchedEffect
        var spokeHalf = false
        while (phase == Phase.MEASURE) {
            delay(250)
            val now = System.nanoTime() / 1e9
            progress = ((now - startT) / MEASURE_S).toFloat().coerceIn(0f, 1f)
            best(now - 10)?.takeIf { it.quality > 0.25 }?.let { liveBpm = bpmKf.update(it.bpm, 4.0 + 40.0 * (1 - it.quality)) }
            if (!spokeHalf && progress > 0.5f) { spokeHalf = true; say(Txt("Halfway there.", "आधा हो गया।", "ಅರ್ಧ ಆಯಿತು.")) }
            if (progress >= 1f) {
                val r = best(startT)
                result = r; phase = Phase.DONE; cam.lock(false); cam.torch(false)
                if (r != null && r.quality > 0.2) {
                    history = saveHistory(app, r)
                    say(Txt("Your heart rate is ${r.bpm.toInt()} beats per minute.", "आपकी हृदय गति ${r.bpm.toInt()} धड़कन प्रति मिनट है।", "ನಿಮ್ಮ ಹೃದಯ ಬಡಿತ ನಿಮಿಷಕ್ಕೆ ${r.bpm.toInt()}."))
                } else {
                    say(Txt("The signal was too noisy. Please try again, holding still.", "सिग्नल साफ़ नहीं था। स्थिर रहकर फिर कोशिश करें।", "ಸಿಗ್ನಲ್ ಸ್ಪಷ್ಟವಾಗಿಲ್ಲ. ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ."))
                }
            }
        }
    }
    // Heart bump + haptic on each detected beat.
    LaunchedEffect(beatTick) { if (beatTick > 0 && phase == Phase.MEASURE) com.shuddh.lab.core.Haptics.heartbeat(app.ctx) }

    ScreenFrame("Pulse", "Heart rate with camera + flash (PPG)", onBack = { app.back() }) {
        Glass(glow = heartRed, padding = 18) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                HeartDial(phase, progress, liveBpm ?: result?.bpm, beatTick, finger)
            }
            Text(
                when (phase) {
                    Phase.IDLE -> "Measure your heart rate in 20 seconds"
                    Phase.PLACE -> "Cover the back camera + flash with a fingertip"
                    Phase.SETTLE -> "Finger detected — hold still…"
                    Phase.MEASURE -> if (motion.shake > 0.25f) "Hold still — movement detected" else "Measuring… ${(MEASURE_S * (1 - progress)).toInt()} s left"
                    Phase.DONE -> if (result != null && result!!.quality > 0.2) "Done" else "Couldn't get a clean signal"
                },
                color = if (phase == Phase.MEASURE && motion.shake > 0.25f) Palette.amber else Palette.text,
                fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            if (phase != Phase.IDLE && phase != Phase.DONE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(CircleShape)) {
                        CameraView(cam, Modifier.size(64.dp), widthFraction = 1f, overlay = {}) { bmp ->
                            val c = Frames.meanRgb(bmp, RectF(0.3f, 0.3f, 0.7f, 0.7f))
                            val corners = listOf(RectF(0f, 0f, 0.2f, 0.2f), RectF(0.8f, 0f, 1f, 0.2f), RectF(0f, 0.8f, 0.2f, 1f), RectF(0.8f, 0.8f, 1f, 1f))
                                .map { Frames.meanRgb(bmp, it).let { k -> floatArrayOf(k.r, k.g, k.b) } }
                            // Debounce: 8 consistent frames to switch on, 5 to switch off.
                            val raw = Ppg.fingerCovers(floatArrayOf(c.r, c.g, c.b), corners)
                            val r01 = if (raw) 1 else 0
                            deb[0] = if (r01 == deb[1]) deb[0] + 1 else 1; deb[1] = r01
                            if (deb[0] >= (if (raw) 8 else 5)) deb[2] = r01
                            val on = deb[2] == 1
                            val t = System.nanoTime() / 1e9
                            // Green carries the strongest pulse when red saturates; use red unless clipped.
                            val v = if (c.r < 245f) c.r.toDouble() else c.g.toDouble()
                            android.os.Handler(android.os.Looper.getMainLooper()).post { finger = on }
                            if (on) {
                                samples.add(t, v); samplesG.add(t, c.g.toDouble())
                                det.push(t, v)?.let { android.os.Handler(android.os.Looper.getMainLooper()).post { beatTick++ } }
                                val y = det.display()
                                android.os.Handler(android.os.Looper.getMainLooper()).post { wave.add(y); if (wave.size > 180) wave.removeAt(0) }
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Badge(if (finger) "FINGER DETECTED" else "NO FINGER", if (finger) Palette.accent else Palette.amber)
                        Text("Flash on · exposure ${if (cam.locked) "locked" else "auto"}", color = Palette.muted, fontSize = 11.sp)
                    }
                }
                LiveWave(wave)
            }
            when (phase) {
                Phase.IDLE, Phase.DONE -> Btn(if (phase == Phase.IDLE) "❤ Start measuring" else "Measure again", Modifier.fillMaxWidth()) {
                    result = null; progress = 0f; liveBpm = null; bpmKf.reset(); wave.clear(); cam.torch(true); phase = Phase.PLACE
                }
                else -> Btn("Cancel", Modifier.fillMaxWidth(), primary = false) { cam.torch(false); cam.lock(false); phase = Phase.IDLE }
            }
        }

        result?.takeIf { phase == Phase.DONE && it.quality > 0.2 }?.let { r ->
            LaunchedEffect(r) { kotlinx.coroutines.delay(1200); com.shuddh.lab.core.Haptics.replayHeart(app.ctx, r.bpm, 4) }
            ResultCard(r) { com.shuddh.lab.core.Haptics.replayHeart(app.ctx, r.bpm) }
        }

        if (history.size >= 2) {
            Section("Your readings") {
                val xs = FloatArray(history.size) { it.toFloat() }
                LineChart(listOf(Series(xs, history.map { it.second.toFloat() }.toFloatArray(), heartRed, fill = true), Series(xs, history.map { it.second.toFloat() }.toFloatArray(), Color.White, dots = true)),
                    Modifier.fillMaxWidth().height(140.dp), yMin = 40f, yMax = 140f)
                history.takeLast(4).reversed().forEach { (t, b) ->
                    Row { Text(stamp(t), color = Palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("${b.toInt()} bpm", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Note("Wellness estimate, not a medical device. If you feel unwell or get unusual readings, consult a doctor.")
        HowItWorks(listOf(
            "The flash shines through your fingertip; the camera sees the light that comes back out.",
            "With every heartbeat a little more blood fills the capillaries and absorbs more light, so brightness dips in time with your pulse — this is photoplethysmography (PPG).",
            "Shuddh samples the fingertip about 30 times a second, removes slow drift, and uses autocorrelation to find the repeating period — robust even with noise.",
            "Beat-to-beat intervals give heart-rate variability (RMSSD); the strength of the repetition gives the signal-quality score.",
            "The accelerometer warns you if you move, and the reading restarts if your finger slips off.",
        ))
    }
}

/** Real-time beat detector for the animation: detrend over ~1 s, peaks above 0.6σ with a 0.35 s refractory. */
private class BeatDetector {
    private val buf = ArrayDeque<Double>()
    private var lastBeat = 0.0
    private var prev = 0.0; private var prev2 = 0.0
    private var lastDisplay = 0f
    @Synchronized fun push(t: Double, v: Double): Double? {
        buf.addLast(v); if (buf.size > 30) buf.removeFirst()
        val mean = buf.average()
        val sd = sqrt(buf.sumOf { (it - mean) * (it - mean) } / buf.size).coerceAtLeast(0.05)
        val x = -(v - mean) / sd // inverted: beats are peaks
        lastDisplay = x.toFloat()
        val isPeak = prev > prev2 && prev >= x && prev > 0.6 && t - lastBeat > 0.35
        prev2 = prev; prev = x
        return if (isPeak && buf.size >= 20) { lastBeat = t; t } else null
    }
    @Synchronized fun display() = lastDisplay
}

/** Big beating heart with a progress ring and the live BPM. */
@Composable
private fun HeartDial(phase: Phase, progress: Float, bpm: Double?, beatTick: Int, finger: Boolean) {
    val bump = remember { Animatable(1f) }
    LaunchedEffect(beatTick) { if (beatTick > 0) { bump.snapTo(1.18f); bump.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 400f)) } }
    val t = rememberInfiniteTransition(label = "heart")
    val idle by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "idle")
    val glow by t.animateFloat(0.3f, 0.7f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "glow")
    val ring by animateFloatAsState(progress, tween(300), label = "ring")
    val scale = if (phase == Phase.MEASURE) bump.value else if (phase == Phase.IDLE || phase == Phase.DONE) idle else 1f
    Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(230.dp)) {
            val st = 16f
            drawCircle(Brush.radialGradient(listOf(heartRed.copy(alpha = glow * 0.45f), Color.Transparent)), size.minDimension / 2)
            drawArc(Palette.ink.copy(alpha = 0.07f), 0f, 360f, false, Offset(st, st), Size(size.width - 2 * st, size.height - 2 * st), style = Stroke(st))
            drawArc(Brush.sweepGradient(listOf(Color(0xFFFF8FA3), heartRed, Color(0xFFFF8FA3))), -90f, 360f * ring, false,
                Offset(st, st), Size(size.width - 2 * st, size.height - 2 * st), style = Stroke(st, cap = StrokeCap.Round))
        }
        Box(Modifier.scale(scale), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(120.dp)) {
                val w = size.width; val h = size.height
                val heart = Path().apply {
                    moveTo(w / 2, h * 0.92f)
                    cubicTo(w * 0.05f, h * 0.6f, w * 0.0f, h * 0.18f, w * 0.28f, h * 0.12f)
                    cubicTo(w * 0.42f, h * 0.09f, w * 0.5f, h * 0.22f, w / 2, h * 0.28f)
                    cubicTo(w * 0.5f, h * 0.22f, w * 0.58f, h * 0.09f, w * 0.72f, h * 0.12f)
                    cubicTo(w * 1.0f, h * 0.18f, w * 0.95f, h * 0.6f, w / 2, h * 0.92f)
                    close()
                }
                drawPath(heart, Brush.verticalGradient(listOf(Color(0xFFFF8FA3), heartRed, Color(0xFFC9184A))))
                if (!finger && phase == Phase.PLACE) drawPath(heart, Palette.ink.copy(alpha = 0.25f), style = Stroke(4f))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(bpm?.let { "${it.toInt()}" } ?: if (phase == Phase.IDLE || phase == Phase.DONE) "" else "--", color = Color.White, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 30.sp)
                if (bpm != null) Text("BPM", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Scrolling, glowing pulse waveform (last ~6 s). */
@Composable
private fun LiveWave(w: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(16.dp)).background(Palette.well(0x55))) {
        for (k in 1..3) drawLine(Palette.ink.copy(alpha = 0.05f), Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
        if (w.size < 3) return@Canvas
        val n = 180
        val step = size.width / n
        val path = Path()
        w.forEachIndexed { i, y ->
            val x = size.width - (w.size - 1 - i) * step
            val yy = size.height / 2 - (y.coerceIn(-3f, 3f) / 3f) * size.height * 0.42f
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        drawPath(path, heartRed.copy(alpha = 0.3f), style = Stroke(12f, cap = StrokeCap.Round))
        drawPath(path, heartRed, style = Stroke(4f, cap = StrokeCap.Round))
        val lastY = size.height / 2 - (w.last().coerceIn(-3f, 3f) / 3f) * size.height * 0.42f
        drawCircle(Color.White, 6f, Offset(size.width - 2f, lastY))
    }
}

@Composable
private fun ResultCard(r: Ppg.Result, onFeel: () -> Unit) {
    Glass(glow = heartRed) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${r.bpm.toInt()}", color = heartRed, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 48.sp)
            Text("  beats / min", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        ZoneGauge(r.bpm)
        Text(Ppg.zone(r.bpm), color = Palette.text, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge("HRV ${r.rmssd?.let { "${it.toInt()} ms" } ?: "—"}", Palette.violet)
            Badge("Quality ${(r.quality * 100).toInt()}%", if (r.quality > 0.5) Palette.accent else Palette.amber)
            Badge("${r.beats.size} beats", Palette.cyan)
        }
        com.shuddh.lab.ui.Btn("💓 Feel your pulse (vibration)", Modifier.fillMaxWidth(), primary = false, onClick = onFeel)
        if (r.clean.size > 30) {
            val tail = r.clean.takeLast((Ppg.FS * 8).toInt())
            LineChart(listOf(Series(FloatArray(tail.size) { it / Ppg.FS.toFloat() }, tail.map { it.toFloat() }.toFloatArray(), heartRed, fill = true)),
                Modifier.fillMaxWidth().height(110.dp), xLabel = "s")
        }
    }
}

/** Animated gauge 40–160 BPM with resting / normal / elevated bands. */
@Composable
private fun ZoneGauge(bpm: Double) {
    val target = ((bpm - 40) / 120).toFloat().coerceIn(0f, 1f)
    val a = remember { Animatable(0f) }
    LaunchedEffect(bpm) { a.animateTo(target, tween(1100, easing = FastOutSlowInEasing)) }
    Canvas(Modifier.fillMaxWidth().height(26.dp)) {
        val y = size.height / 2
        val w = size.width
        val bands = listOf(0f to 1f / 6 to Color(0xFF60A5FA), 1f / 6 to 0.5f to Color(0xFF34D399), 0.5f to 1f to Palette.tint(Color(0xFFFBBF24)))
        bands.forEach { (r, c) -> drawLine(c, Offset(w * r.first + 4, y), Offset(w * r.second - 4, y), 12f, StrokeCap.Round) }
        drawCircle(Color.White, 13f, Offset(w * a.value, y))
        drawCircle(heartRed, 7f, Offset(w * a.value, y))
    }
}

private fun loadHistory(app: AppState): List<Pair<Long, Double>> = runCatching {
    val a = app.prefs.json("pulse_hist")?.getJSONArray("h") ?: return emptyList()
    List(a.length()) { a.getJSONObject(it).let { o -> o.getLong("t") to o.getDouble("b") } }
}.getOrDefault(emptyList())

private fun saveHistory(app: AppState, r: Ppg.Result): List<Pair<Long, Double>> {
    val h = (loadHistory(app) + (System.currentTimeMillis() to r.bpm)).takeLast(30)
    app.prefs.putJson("pulse_hist", JSONObject().put("h", JSONArray(h.map { (t, b) -> JSONObject().put("t", t).put("b", b) })))
    return h
}

