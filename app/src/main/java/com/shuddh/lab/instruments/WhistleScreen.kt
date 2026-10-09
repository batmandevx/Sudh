package com.shuddh.lab.instruments

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Dsp
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Chips
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.SpectrumColors
import com.shuddh.lab.ui.pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import com.shuddh.lab.core.WhistleDetector
import com.shuddh.lab.core.WhistleTiming
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Section
import kotlin.math.PI
import kotlin.math.sin

private const val WSR = 22050
private const val WN = 1024

/**
 * Pressure-cooker whistle counter. Each 46 ms mic frame goes through [WhistleDetector]: per-bin
 * noise subtraction, tonality with hysteresis, spectral flatness, a minimum duration with stable
 * pitch, and a refractory gap. Learning the cooker's pitch narrows it further.
 */
@Composable
fun WhistleScreen(app: AppState) {
    val ctx = app.ctx
    val view = LocalView.current
    var running by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var target by remember { mutableIntStateOf(app.whistleTarget ?: 3) }
    var learning by remember { mutableStateOf(false) }
    var pitch by remember { mutableStateOf(app.prefs.double("whistle_pitch")) }
    var level by remember { mutableFloatStateOf(-90f) }
    var frame by remember { mutableStateOf<WhistleDetector.Frame?>(null) }
    var bars by remember { mutableStateOf(FloatArray(32)) }
    var startedAt by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val times = remember { mutableStateListOf<Long>() }
    val pitches = remember { mutableStateListOf<Double>() }
    var status by remember { mutableStateOf("Set the number of whistles, start, and put the phone near the kitchen.") }
    val inWhistle = frame?.inWhistle == true

    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }

    fun alarm() {
        Haptics.alarm(ctx)
        runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2500) }
        app.voice.speak(Txt("$target whistles done. Turn off the stove.", "$target सीटी हो गई। गैस बंद कीजिए।", "$target ಸೀಟಿ ಆಯಿತು. ಒಲೆ ಆರಿಸಿ.").get(app.lang), app.lang)
    }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Microphone permission is needed"; running = false; return@LaunchedEffect
        }
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        var lastSwell = 0L
        withContext(Dispatchers.Default) {
            listenWhistles(
                pitchProvider = { if (learning) null else pitch },
                onFrame = { lv, f, b ->
                    level = lv; frame = f; bars = b
                    // Felt build-up: a gentle swell when a whistle is half-way to being counted.
                    if (!f.inWhistle && f.progress > 0.5f && System.currentTimeMillis() - lastSwell > 1500) { Haptics.swell(ctx); lastSwell = System.currentTimeMillis() }
                },
                onWhistle = { hz ->
                    if (learning) {
                        pitch = hz; app.prefs.putDouble("whistle_pitch", hz); learning = false
                        Haptics.click(ctx)
                        status = "Learned your cooker: ${fmt(hz)} Hz. Counting now."
                        app.voice.speak("Learned your cooker. Counting now.", app.lang)
                    } else {
                        count++; times += System.currentTimeMillis(); pitches += hz
                        Haptics.thud(ctx)
                        status = "Whistle $count of $target · ${fmt(hz)} Hz"
                        if (count >= target) alarm()
                        else app.voice.speak(Txt("Whistle $count", "$count सीटी", "$count ಸೀಟಿ").get(app.lang), app.lang)
                    }
                },
            )
        }
    }

    ScreenFrame("Whistle Counter", "Mic → pressure-cooker whistles", onBack = { running = false; app.back() }) {
        Glass(glow = if (inWhistle) Palette.amber else Palette.cyan) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cooker(inWhistle, frame?.progress ?: 0f, running, Modifier.weight(1f).height(210.dp))
                CountRing(count, target, inWhistle)
            }
            Text(status, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally))
            BuildUpBar(frame?.progress ?: 0f, inWhistle)
            LiveBars(bars, inWhistle)
            BtnRow {
                Btn(if (running) "Stop" else "Start counting") {
                    running = !running; Haptics.click(ctx)
                    if (running) status = if (learning) "Waiting for one whistle to learn its pitch…" else "Listening for whistles…"
                }
                Btn("Reset", primary = false) { count = 0; times.clear(); pitches.clear(); startedAt = if (running) System.currentTimeMillis() else 0L }
                Btn(if (learning) "Learning…" else "Learn my cooker", primary = false) { learning = true; running = true; status = "Waiting for one whistle to learn its pitch…" }
            }
        }
        if (running || times.isNotEmpty()) TimelineCard(times, startedAt, now, target, count)
        Glass {
            Text("Whistles needed", color = Palette.text, fontWeight = FontWeight.Bold)
            Chips((1..8).toList(), target, { "$it" }) { target = it; Haptics.tick(ctx) }
            Note(
                pitch?.let { "Tuned to your cooker at ${fmt(it)} Hz (±20%). Other sounds are ignored." }
                    ?: "Untuned: any loud, steady whistle tone (0.9–6.5 kHz) counts. Tap 'Learn my cooker' during the first whistle for best accuracy.",
            )
            frame?.let { f ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Meter("Above noise", (f.snr / 30).toFloat(), "${f.snr.toInt()} dB", f.snr >= 14, Modifier.weight(1f))
                    Meter("Tonality", f.tonality.toFloat(), "${(f.tonality * 100).toInt()}%", f.tonality >= 0.33, Modifier.weight(1f))
                    Meter("Pure tone", (1 - f.flatness).toFloat(), "${((1 - f.flatness) * 100).toInt()}%", f.flatness < 0.25, Modifier.weight(1f))
                }
                Note("Level ${fmt(level.toDouble())} dBFS · peak ${fmt(f.peakHz)} Hz")
            }
        }
        HowItWorks(listOf(
            "Every 46 ms the mic's sound becomes a spectrum (FFT).",
            "Shuddh keeps a per-frequency noise floor of your kitchen, so a fan, mixer or TV hum is subtracted out before anything counts.",
            "A whistle must stand ≥14 dB above that floor, be nearly a pure tone (tonality + low spectral flatness), and keep a steady pitch — speech and music glide, clatter is broadband.",
            "About half a second of such frames = one whistle; after it ends there's a 1.4 s refractory gap, so a sputtering whistle never counts twice.",
            "Learning your cooker's pitch narrows the detector to ±20% around it.",
            "You feel each whistle as a double thud and hear the count; at your target the phone vibrates in alarm bursts, rings and speaks. Shuddh also predicts when the next whistle is due.",
        ))
    }
}

/** Animated pressure cooker: the weight rattles with the build-up, steam jets and sound arcs when it whistles. */
@Composable
private fun Cooker(whistling: Boolean, progress: Float, running: Boolean, modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "cooker")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "t")
    val jig by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(70, easing = LinearEasing), RepeatMode.Reverse), label = "jig")
    val glow by animateFloatAsState(if (whistling) 1f else progress * 0.6f, tween(300), label = "glow")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val bodyW = w * 0.62f; val bodyH = h * 0.36f
        val bx = (w - bodyW) / 2; val by = h - bodyH - 8f
        // Flame
        if (running) for (k in 0 until 5) {
            val fx = bx + bodyW * (0.15f + 0.175f * k)
            val fh = 14f + 8f * sin((t * 2 * PI + k).toFloat())
            drawOval(Brush.verticalGradient(listOf(Color(0xFF60A5FA), Color(0xFFF59E0B)), h - fh, h), Offset(fx - 7f, h - fh), Size(14f, fh))
        }
        // Body + lid
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFCBD5E1), Color(0xFF64748B)), by, by + bodyH), Offset(bx, by - 12f), Size(bodyW, bodyH), CornerRadius(26f))
        drawRoundRect(Color(0xFF94A3B8), Offset(bx - 8f, by - 22f), Size(bodyW + 16f, 16f), CornerRadius(8f))
        // Handle
        drawRoundRect(Color(0xFF1F2937), Offset(bx + bodyW - 4f, by - 20f), Size(w * 0.18f, 12f), CornerRadius(6f))
        // Weight (rattles as pressure builds, lifts when whistling)
        val shake = if (whistling) jig * 4f else if (progress > 0.2f) jig * 2f * progress else 0f
        val lift = if (whistling) 8f else 0f
        val cx = w / 2 + shake
        drawRoundRect(Color(0xFF334155), Offset(cx - 9f, by - 50f - lift), Size(18f, 30f), CornerRadius(6f))
        drawCircle(Color(0xFF475569), 12f, Offset(cx, by - 54f - lift))
        // Heat glow
        if (glow > 0f) drawCircle(Brush.radialGradient(listOf(Color(0xFFFBBF24).copy(alpha = 0.45f * glow), Color.Transparent), Offset(w / 2, by - 50f), 70f), 70f, Offset(w / 2, by - 50f))
        // Steam puffs + sound arcs while whistling
        if (whistling) {
            for (k in 0 until 6) {
                val p = (t + k / 6f) % 1f
                val sx = cx + sin((p * 6 + k).toDouble()).toFloat() * 14f * p
                drawCircle(Color.White.copy(alpha = (1 - p) * 0.55f), 8f + 18f * p, Offset(sx, by - 64f - p * (by - 70f)))
            }
            for (k in 0 until 3) {
                val p = (t + k / 3f) % 1f
                val r = 24f + p * 70f
                drawArc(Color(0xFFFBBF24).copy(alpha = 1 - p), -60f, 50f, false, Offset(cx - r, by - 60f - r), Size(2 * r, 2 * r), style = Stroke(4f, cap = StrokeCap.Round))
                drawArc(Color(0xFFFBBF24).copy(alpha = 1 - p), 190f, 50f, false, Offset(cx - r, by - 60f - r), Size(2 * r, 2 * r), style = Stroke(4f, cap = StrokeCap.Round))
            }
        } else if (running && progress == 0f) {
            val p = t
            drawCircle(Color.White.copy(alpha = (1 - p) * 0.18f), 6f + 8f * p, Offset(cx, by - 60f - p * 40f))
        }
    }
}

@Composable
private fun BuildUpBar(p: Float, on: Boolean) {
    val a by animateFloatAsState(p, tween(120), label = "build")
    Column {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.06f))) {
            Box(Modifier.fillMaxWidth(a).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(Palette.cyan, if (on) Palette.amber else Palette.cyan))))
        }
        Text(if (on) "🔔 WHISTLING" else if (p > 0f) "Whistle building… ${(p * 100).toInt()}%" else "Listening", color = if (on) Palette.amber else Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Meter(label: String, f: Float, value: String, ok: Boolean, modifier: Modifier) {
    val c = if (ok) Palette.accent else Palette.muted
    val a by animateFloatAsState(f.coerceIn(0f, 1f), tween(150), label = label)
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.04f)).padding(8.dp)) {
        Text(label, color = Palette.muted, fontSize = 10.sp)
        Text(value, color = c, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.06f))) {
            Box(Modifier.fillMaxWidth(a).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c))
        }
    }
}

private fun mmss(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

/** Timeline of whistles since start + predicted next whistle. */
@Composable
private fun TimelineCard(times: List<Long>, start: Long, now: Long, target: Int, count: Int) {
    Section("Cooking timeline") {
        val span = ((WhistleTiming.predict(times)?.second ?: now) - start).coerceAtLeast(now - start).coerceAtLeast(60_000L).toFloat()
        val pred = WhistleTiming.predict(times)
        Canvas(Modifier.fillMaxWidth().height(54.dp)) {
            val y = size.height / 2
            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, y), Offset(size.width, y), 4f, StrokeCap.Round)
            val nx = size.width * (now - start) / span
            drawLine(Palette.cyan, Offset(0f, y), Offset(nx.coerceAtMost(size.width), y), 4f, StrokeCap.Round)
            times.forEach { tm ->
                val x = size.width * (tm - start) / span
                drawCircle(Palette.amber, 11f, Offset(x, y)); drawCircle(Color.White, 4f, Offset(x, y))
            }
            pred?.let { (_, next) ->
                if (count < target) {
                    val x = (size.width * (next - start) / span).coerceAtMost(size.width - 10f)
                    drawCircle(Palette.amber.copy(alpha = 0.35f), 11f, Offset(x, y), style = Stroke(3f))
                }
            }
            drawCircle(Palette.cyan, 7f, Offset(nx.coerceAtMost(size.width), y))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TStat("⏱", mmss(now - start), "cooking", Modifier.weight(1f))
            TStat("↔", pred?.let { mmss(it.first) } ?: "—", "avg gap", Modifier.weight(1f))
            TStat("🔮", pred?.takeIf { count < target }?.let { mmss((it.second - now).coerceAtLeast(0)) } ?: "—", "next in", Modifier.weight(1f))
        }
        if (pred != null && count < target) {
            val eta = pred.second + pred.first * (target - count - 1)
            Note("At this rhythm, whistle $target arrives in about ${mmss((eta - now).coerceAtLeast(0))}.", Palette.cyan)
        }
    }
}

@Composable
private fun TStat(icon: String, v: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.05f)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 16.sp)
        Text(v, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 17.sp)
        Text(label, color = Palette.muted, fontSize = 10.sp)
    }
}

@Composable
private fun CountRing(count: Int, target: Int, active: Boolean) {
    val p by animateFloatAsState((count.toFloat() / target).coerceIn(0f, 1f), spring(dampingRatio = 0.5f), label = "ring")
    val bump by animateFloatAsState(if (active) 1.08f else 1f, spring(dampingRatio = 0.4f), label = "bump")
    Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(160.dp)) {
            val st = 20f
            val tl = Offset(st, st); val sz = Size(size.width - 2 * st, size.height - 2 * st)
            drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, tl, sz, style = Stroke(st))
            drawArc(Brush.sweepGradient(SpectrumColors + SpectrumColors.first()), -90f, 360f * p, false, tl, sz, style = Stroke(st, cap = StrokeCap.Round))
            for (i in 0 until target) {
                val a = Math.toRadians(-90.0 + 360.0 * i / target)
                val r = size.width / 2 - st
                drawCircle(if (i < count) Color.White else Color.White.copy(alpha = 0.2f), 5f, Offset(center.x + (r * kotlin.math.cos(a)).toFloat(), center.y + (r * kotlin.math.sin(a)).toFloat()))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", color = if (active) Palette.amber else Palette.text, fontSize = (52 * bump).sp, fontWeight = FontWeight.Black, fontFamily = Display)
            Text("of $target", color = Palette.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LiveBars(b: FloatArray, active: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(60.dp)) {
        val bw = size.width / b.size
        b.forEachIndexed { i, v ->
            val h = (size.height * v).coerceAtLeast(3f)
            drawRoundRect(
                if (active) Palette.amber else SpectrumColors[(i * SpectrumColors.size / b.size).coerceAtMost(SpectrumColors.lastIndex)],
                Offset(i * bw + 2, size.height - h), Size(bw - 4, h), androidx.compose.ui.geometry.CornerRadius(4f),
            )
        }
    }
}

@SuppressLint("MissingPermission")
private suspend fun listenWhistles(
    pitchProvider: () -> Double?,
    onFrame: (Float, WhistleDetector.Frame, FloatArray) -> Unit,
    onWhistle: (Double) -> Unit,
) {
    val rec = AudioRecord(
        MediaRecorder.AudioSource.MIC, WSR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        max(AudioRecord.getMinBufferSize(WSR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), WN * 8),
    )
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
    val buf = ShortArray(WN)
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val det = WhistleDetector(WSR, WN)
    rec.startRecording()
    try {
        while (kotlin.coroutines.coroutineContext.isActive) {
            var got = 0
            while (got < WN) { val n = rec.read(buf, got, WN - got); if (n <= 0) break; got += n }
            val x = FloatArray(WN) { buf[it] / 32768f }
            val rms = sqrt(x.sumOf { (it * it).toDouble() } / WN)
            val spec = Dsp.spectrumDb(x)
            det.learnedHz = pitchProvider()
            val f = det.push(spec) { hz -> main.post { onWhistle(hz) } }
            // Bars relative to this frame's median level, so they show shape, not raw gain.
            val half = spec.size / 2
            val med = spec.copyOfRange(0, half).sorted()[half / 2]
            val bars = FloatArray(32) { k ->
                val i0 = (k * half / 32); val i1 = ((k + 1) * half / 32)
                val m = (i0 until i1).maxOf { spec[it] }
                ((m - med) / 45f).coerceIn(0f, 1f)
            }
            val lv = (20 * log10(rms + 1e-9)).toFloat()
            main.post { onFrame(lv, f, bars) }
        }
    } finally {
        rec.stop(); rec.release()
    }
}
