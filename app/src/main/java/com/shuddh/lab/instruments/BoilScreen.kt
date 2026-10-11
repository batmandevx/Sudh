package com.shuddh.lab.instruments

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.BoilDetector
import com.shuddh.lab.core.BoilDetector.Stage
import com.shuddh.lab.core.BoilPhysics
import com.shuddh.lab.core.Dsp
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private const val BSR = 22050
private const val BN = 1024

/**
 * Boil Guard — the phone listens to a pot of drinking water, recognises a rolling boil, reads the
 * barometer for your altitude-specific boiling point, then times the WHO safe boil.
 */
@Composable
fun BoilScreen(app: AppState) {
    val ctx = app.ctx
    val view = LocalView.current
    var running by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(Stage.LISTENING) }
    var progress by remember { mutableFloatStateOf(0f) }
    var rise by remember { mutableFloatStateOf(0f) }
    val levels = remember { mutableStateListOf<Float>() }
    var hPa by remember { mutableStateOf<Double?>(null) }
    // Phones without a barometer (e.g. iQOO 15): the user picks their altitude; pressure follows from the standard atmosphere.
    val hasBaro = remember { (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_PRESSURE) != null }
    var manualAlt by remember { mutableStateOf(app.prefs.double("boil_alt") ?: 0.0) }
    LaunchedEffect(hasBaro, manualAlt) { if (!hasBaro) hPa = 1013.25 * Math.pow(1 - 2.25577e-5 * manualAlt, 5.25588) }
    var boilStart by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var safeDone by remember { mutableStateOf(false) }
    val detector = remember { arrayOf(BoilDetector()) }
    val altitude = hPa?.let { SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it.toFloat()).toDouble() }
    val bp = hPa?.let { BoilPhysics.boilingPointC(it) }
    val safeSecs = BoilPhysics.safeBoilSeconds(altitude)
    val boiledFor = if (stage == Stage.BOILING && boilStart > 0) ((now - boilStart) / 1000).toInt() else 0

    // Barometer → altitude → boiling point.
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { hPa = e.values[0].toDouble() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL) }
        onDispose { sm.unregisterListener(l) }
    }
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(250) } }

    // Stage changes → voice + haptics; safe-boil completion.
    LaunchedEffect(stage) {
        when (stage) {
            Stage.SINGING -> { Haptics.swell(ctx); app.voice.speak("Almost boiling.", app.lang) }
            Stage.BOILING -> {
                if (boilStart == 0L) boilStart = System.currentTimeMillis()
                Haptics.thud(ctx)
                app.voice.speak(Txt("Rolling boil. Keep it boiling for ${safeSecs / 60} minute${if (safeSecs > 60) "s" else ""}.",
                    "पानी उबल रहा है। ${safeSecs / 60} मिनट तक उबलने दें।", "ನೀರು ಕುದಿಯುತ್ತಿದೆ. ${safeSecs / 60} ನಿಮಿಷ ಕುದಿಯಲಿ.").get(app.lang), app.lang)
            }
            Stage.STOPPED -> if (!safeDone) { Haptics.alarm(ctx); app.voice.speak("The boil stopped too early. Turn the heat back on.", app.lang); boilStart = 0L }
            else -> {}
        }
    }
    LaunchedEffect(boiledFor >= safeSecs && stage == Stage.BOILING) {
        if (boiledFor >= safeSecs && stage == Stage.BOILING && !safeDone) {
            safeDone = true
            Haptics.alarm(ctx)
            app.voice.speak(Txt("Water is now safe to drink. Turn off the stove and let it cool, covered.",
                "पानी अब पीने के लिए सुरक्षित है। गैस बंद करें और ढककर ठंडा करें।", "ನೀರು ಈಗ ಕುಡಿಯಲು ಸುರಕ್ಷಿತ. ಒಲೆ ಆರಿಸಿ, ಮುಚ್ಚಿ ತಣ್ಣಗಾಗಿಸಿ.").get(app.lang), app.lang)
            app.prefs.bump("safe_boils")
        }
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { running = false; return@LaunchedEffect }
        detector[0] = BoilDetector(); boilStart = 0L; safeDone = false; levels.clear()
        withContext(Dispatchers.Default) {
            listenBoil(detector[0]) { st, p, r, lv ->
                stage = st; progress = p; rise = r
                levels += lv; if (levels.size > 240) levels.removeAt(0)
            }
        }
    }

    ScreenFrame("Boil Guard", "Mic + barometer → safe drinking water", onBack = { running = false; app.back() }) {
        Glass(glow = when (stage) { Stage.BOILING -> if (safeDone) Palette.accent else Palette.amber; Stage.STOPPED -> Palette.red; else -> Palette.cyan }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pot(stage, progress, running, Modifier.weight(1f).height(200.dp))
                SafeRing(boiledFor, safeSecs, stage, safeDone, Modifier.size(150.dp))
            }
            Text("${stage.emoji} ${if (safeDone) "Safe to drink ✓" else if (running) stage.label else "Place the phone near the pot"}",
                color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            StageTrack(stage, running)
            BtnRow {
                Btn(if (running) "Stop" else "Start Boil Guard") { running = !running; Haptics.click(ctx); if (running) app.voice.speak("Listening. Put the phone near the pot.", app.lang) }
                if (running && stage != Stage.BOILING) Btn("It's boiling", primary = false) { detector[0].forceBoiling(); stage = Stage.BOILING }
            }
        }
        Section("Your altitude & boiling point") {
            if (!hasBaro) {
                Note("No barometer on this phone — pick your altitude (city height):")
                com.shuddh.lab.ui.Chips(listOf(0.0, 500.0, 920.0, 1500.0, 2000.0, 3000.0), manualAlt, { if (it == 920.0) "920 m (Bengaluru)" else "${it.toInt()} m" }) {
                    manualAlt = it; app.prefs.putDouble("boil_alt", it)
                }
            }
            if (hPa != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat3("🧭", "${fmt(hPa!!)} hPa", if (hasBaro) "air pressure" else "estimated", Modifier.weight(1f))
                Stat3("⛰", "${altitude!!.toInt()} m", "altitude", Modifier.weight(1f))
                Stat3("🌡", "${fmt(bp!!)} °C", "water boils at", Modifier.weight(1f))
            }
            Note("WHO: a rolling boil for 1 minute kills bacteria, viruses and parasites; above 2,000 m boil for 3 minutes because water boils cooler there.")
        }
        if (levels.size > 2) Section("Sound of the pot") {
            LevelChart(levels)
            Note("Rises as the water heats, peaks while it 'sings', then dips and turns bubbly at a rolling boil. Level now +${fmt(rise.toDouble())} dB over the room.")
        }
        HowItWorks(listOf(
            "The first 3 seconds learn your kitchen's background sound.",
            "Heating water gets louder; just before boiling, collapsing vapour bubbles make it 'sing'.",
            "At a rolling boil the bubbles reach the surface instead of collapsing: the hiss drops a few dB from its peak and becomes irregular. Either pattern held for several seconds = boiling.",
            "The barometer gives air pressure → your altitude and the exact temperature water boils at (Clausius–Clapeyron).",
            "Shuddh times the WHO safe boil, warns if the boil stops early, and tells you when the water is safe.",
        ))
    }
}

@SuppressLint("MissingPermission")
private suspend fun listenBoil(det: BoilDetector, onFrame: (Stage, Float, Float, Float) -> Unit) {
    val rec = AudioRecord(MediaRecorder.AudioSource.MIC, BSR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        max(AudioRecord.getMinBufferSize(BSR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), BN * 8))
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
    val buf = ShortArray(BN)
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    var prev: FloatArray? = null
    val hz = BSR.toDouble() / BN
    val lo = (300 / hz).toInt(); val hi = (4000 / hz).toInt()
    rec.startRecording()
    try {
        while (kotlin.coroutines.coroutineContext.isActive) {
            var got = 0
            while (got < BN) { val n = rec.read(buf, got, BN - got); if (n <= 0) break; got += n }
            val spec = Dsp.spectrumDb(FloatArray(BN) { buf[it] / 32768f })
            var p = 0.0
            for (i in lo..hi) p += 10.0.pow(spec[i] / 10.0)
            val db = 10 * log10(p + 1e-12)
            // Spectral flux: how much the band's spectrum changed since the last frame (bubble bursts).
            var flux = 0.0
            prev?.let { pv -> for (i in lo..hi) { val d = spec[i] - pv[i]; if (d > 0) flux += d } }
            prev = spec
            val st = det.push(db, flux / (hi - lo))
            val r = (det.level - det.baseline).let { if (it.isNaN()) 0.0 else it }
            main.post { onFrame(st, det.progress.toFloat(), r.toFloat(), db.toFloat()) }
        }
    } finally { rec.stop(); rec.release() }
}

/** Pot of water: bubbles grow in number and size with the stage; steam when boiling. */
@Composable
private fun Pot(stage: Stage, progress: Float, running: Boolean, modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "pot")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t")
    val heat by animateFloatAsState(if (stage == Stage.BOILING) 1f else progress, tween(800), label = "heat")
    val bubbles = remember { List(40) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val px = w * 0.12f; val pw = w * 0.76f; val py = h * 0.36f; val ph = h * 0.5f
        // Flame
        if (running) for (k in 0 until 6) {
            val fx = px + pw * (0.1f + 0.16f * k)
            val fh = 10f + 10f * heat + 6f * sin((t * 2 * PI * 3 + k).toFloat())
            drawOval(Brush.verticalGradient(listOf(Color(0xFF60A5FA), Color(0xFFF59E0B)), h - fh, h), Offset(fx - 7f, h - fh - 2f), Size(14f, fh))
        }
        // Pot body
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF94A3B8), Color(0xFF475569)), py, py + ph), Offset(px, py), Size(pw, ph), CornerRadius(18f))
        drawRoundRect(Color(0xFF64748B), Offset(px - 18f, py + 10f), Size(22f, 10f), CornerRadius(5f))
        drawRoundRect(Color(0xFF64748B), Offset(px + pw - 4f, py + 10f), Size(22f, 10f), CornerRadius(5f))
        // Water surface glimpse + bubbles
        val wTop = py + 8f
        drawRoundRect(Color(0xFF38BDF8).copy(alpha = 0.55f), Offset(px + 8f, wTop), Size(pw - 16f, 14f), CornerRadius(7f))
        val n = (4 + heat * 36).toInt()
        bubbles.take(n).forEach { (bx, by, bs) ->
            val p = (t * (0.6f + heat) + by) % 1f
            val x = px + 16f + bx * (pw - 32f) + sin((p * 10 + bx * 6).toDouble()).toFloat() * 4f
            val y = py + ph - 10f - p * (ph - 22f)
            drawCircle(Color.White.copy(alpha = 0.35f + 0.4f * (1 - p)), 2f + bs * (3f + 6f * heat), Offset(x, y), style = Stroke(2f))
        }
        // Steam
        if (stage == Stage.BOILING) for (k in 0 until 7) {
            val p = (t * 1.3f + k / 7f) % 1f
            val sx = px + pw * (0.15f + 0.11f * k) + sin((p * 8 + k).toDouble()).toFloat() * 10f
            drawCircle(Color.White.copy(alpha = (1 - p) * 0.4f), 10f + 18f * p, Offset(sx, py - 6f - p * py * 0.95f))
        }
    }
}

/** Countdown ring for the WHO safe boil. */
@Composable
private fun SafeRing(boiled: Int, safe: Int, stage: Stage, done: Boolean, modifier: Modifier) {
    val f by animateFloatAsState((boiled.toFloat() / safe).coerceIn(0f, 1f), tween(400), label = "safe")
    val col = if (done) Palette.accent else if (stage == Stage.BOILING) Palette.amber else Palette.muted
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(150.dp)) {
            val st = 16f
            val tl = Offset(st, st); val sz = Size(size.width - 2 * st, size.height - 2 * st)
            drawArc(Palette.ink.copy(alpha = 0.07f), 0f, 360f, false, tl, sz, style = Stroke(st))
            drawArc(Brush.sweepGradient(listOf(Palette.amber, Palette.accent, Palette.amber)), -90f, 360f * f, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (done) "✓" else if (stage == Stage.BOILING) "${(safe - boiled).coerceAtLeast(0)}s" else "${safe}s",
                color = col, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 30.sp)
            Text(if (done) "safe" else if (stage == Stage.BOILING) "to safe" else "safe boil", color = Palette.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StageTrack(stage: Stage, running: Boolean) {
    val steps = listOf(Stage.COLD, Stage.HEATING, Stage.SINGING, Stage.BOILING)
    val idx = steps.indexOf(stage).let { if (it < 0) (if (stage == Stage.STOPPED) 3 else -1) else it }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, s ->
            val on = running && i <= idx
            val c = if (on) (if (s == Stage.BOILING) Palette.amber else Palette.cyan) else Palette.ink.copy(alpha = 0.1f)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c))
                Text("${s.emoji} ${s.label.substringBefore(" —")}", color = if (on) Palette.text else Palette.muted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LevelChart(levels: List<Float>) {
    val cyan = Palette.cyan
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val lo = levels.min(); val hi = levels.max().coerceAtLeast(lo + 3f)
        val dx = size.width / (levels.size - 1)
        val p = Path()
        levels.forEachIndexed { i, v -> val y = size.height - 4f - (size.height - 8f) * (v - lo) / (hi - lo); if (i == 0) p.moveTo(0f, y) else p.lineTo(i * dx, y) }
        drawPath(p, cyan, style = Stroke(3f, cap = StrokeCap.Round))
    }
}

@Composable
private fun Stat3(icon: String, v: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Palette.ink.copy(alpha = 0.05f)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 18.sp)
        Text(v, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 15.sp, maxLines = 1)
        Text(label, color = Palette.muted, fontSize = 10.sp, maxLines = 1)
    }
}
