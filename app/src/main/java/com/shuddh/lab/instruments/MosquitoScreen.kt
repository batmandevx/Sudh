package com.shuddh.lab.instruments

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.CommunityItem
import com.shuddh.lab.core.Dsp
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.MeshProto
import com.shuddh.lab.core.MosquitoRadar
import com.shuddh.lab.core.MosquitoRadar.Kind
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sin

private const val MSR = 22050
private const val MN = 4096

private val kindColor = mapOf(Kind.AEDES to Color(0xFFF43F5E), Kind.ANOPHELES to Color(0xFFF97316), Kind.CULEX to Palette.tint(Color(0xFFFBBF24)), Kind.MALE to Color(0xFF34D399))

/** Mosquito Radar — wingbeat pitch + time of day → likely mosquito type and the disease it can carry. */
@Composable
fun MosquitoScreen(app: AppState) {
    val ctx = app.ctx
    val view = LocalView.current
    var listening by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<MosquitoRadar.Frame?>(null) }
    var bars by remember { mutableStateOf(FloatArray(48)) }
    var est by remember { mutableStateOf<MosquitoRadar.Estimate?>(null) }
    val log = remember { mutableStateListOf<MosquitoRadar.Estimate>() }
    val checks = remember { mutableStateListOf<Int>() }
    var msg by remember { mutableStateOf<String?>(null) }

    DisposableEffect(listening) { view.keepScreenOn = listening; onDispose { view.keepScreenOn = false } }

    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { msg = "Microphone permission is needed"; listening = false; return@LaunchedEffect }
        app.voice.speak("Listening. Hold the phone's bottom edge near the buzzing mosquito.", app.lang)
        withContext(Dispatchers.Default) {
            listenBuzz(
                onFrame = { f, b -> live = f; bars = b },
                onHit = { hz ->
                    val e = MosquitoRadar.classify(hz, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
                    est = e; log.add(0, e); if (log.size > 20) log.removeAt(log.lastIndex)
                    Haptics.thud(ctx)
                    app.voice.speak("${e.top.label} likely. ${if (e.top == Kind.MALE) "Harmless male." else "Can carry ${e.top.disease}."}", app.lang)
                },
            )
        }
    }

    ScreenFrame("Mosquito Radar", "Wingbeat sound → dengue & malaria mosquito alert", onBack = { listening = false; app.back() }) {
        Glass(glow = est?.let { kindColor[it.top] } ?: Palette.cyan, padding = 14) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Radar(listening, live?.harmonic == true, est)
            }
            Text(
                when {
                    est != null -> "${est!!.top.label} · ${est!!.hz.toInt()} Hz"
                    listening && live?.harmonic == true -> "Buzz heard — hold steady…"
                    listening -> "Listening for a buzz…"
                    else -> "Find out which mosquito is biting you"
                },
                color = est?.let { kindColor[it.top] } ?: Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            if (listening) BuzzSpectrum(bars, live?.f0?.takeIf { live?.harmonic == true })
            BtnRow {
                Btn(if (listening) "Stop" else "🎙 Start listening") { listening = !listening; if (!listening) live = null }
                if (est != null && est!!.top != Kind.MALE) Btn("📍 Report to Hive", primary = false) {
                    val e = est!!
                    val item = CommunityItem("alert", "", app.prefs.area, "${e.top.label} mosquito", Level.CAUTION, "${e.hz.toInt()} Hz", System.currentTimeMillis(), 1, 0, "mosq")
                    app.community.add(item); if (app.mesh.running) app.mesh.send(MeshProto.ALERT, item.compact())
                    msg = "Reported — neighbours see it in Hive" + if (app.mesh.running) " and over the mesh." else "."
                    Haptics.click(ctx)
                }
            }
            msg?.let { Note(it, Palette.accent) }
        }

        est?.let { e ->
            Section("How likely is each type?") {
                e.probs.entries.sortedByDescending { it.value }.forEachIndexed { i, (k, p) -> ProbBar(k, p.toFloat(), i) }
                Note(MosquitoRadar.advice(e.top), Palette.text)
                Note("Pitch shifts ≈10 Hz per °C and with body size, and species overlap — so this is a likelihood, not a lab ID. Time of day is used too: Aedes bites by day, Culex and Anopheles mostly at night.")
            }
        }

        if (log.size > 1) Section("Heard today (${log.size})") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                log.take(12).forEach { e -> Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(kindColor[e.top]!!)) }
            }
            val aedes = log.count { it.top == Kind.AEDES }
            if (aedes >= 2) Note("$aedes likely dengue mosquitoes heard — they breed within ~100 m of where they bite. Check the list below today.", Palette.red)
        }

        Section("Stop the breeding (dengue mosquitoes need only a bottle-cap of water)") {
            MosquitoRadar.breedingChecklist.forEachIndexed { i, t ->
                val done = i in checks
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (done) checks.remove(i) else checks.add(i); Haptics.tick(ctx) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (done) "✅" else "⬜", fontSize = 18.sp); Spacer(Modifier.width(10.dp))
                    Text(t, color = if (done) Palette.muted else Palette.text, fontSize = 14.sp)
                }
            }
            val pct by animateFloatAsState(checks.size / MosquitoRadar.breedingChecklist.size.toFloat(), tween(500), label = "chk")
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.ink.copy(alpha = 0.06f))) {
                Box(Modifier.fillMaxWidth(pct).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(Palette.cyan, Palette.accent))))
            }
            Text(if (checks.size == MosquitoRadar.breedingChecklist.size) "🎉 Home is breeding-free this week" else "${checks.size}/${MosquitoRadar.breedingChecklist.size} done",
                color = Palette.muted, fontSize = 12.sp)
        }
        HowItWorks(listOf(
            "A mosquito's whine is its wings beating hundreds of times a second — the pitch is the wingbeat frequency.",
            "Shuddh learns your room's background, then looks for a harmonic buzz (fundamental + 2nd + 3rd harmonics) between 250 and 900 Hz, held for about a second.",
            "Fan and transformer hum sit exactly on multiples of 50 Hz and never wobble — those are rejected; speech and music wobble too much — also rejected.",
            "Published field data: female Aedes (dengue) ≈ 455 Hz, Culex lower, males ≈ 690 Hz (they don't bite). Because ranges overlap, the pitch likelihood is combined with the time of day, as in Stanford's Abuzz project.",
            "Report sightings to Hive to map dengue-mosquito hotspots in your neighbourhood — no internet needed.",
        ))
    }
}

@SuppressLint("MissingPermission")
private suspend fun listenBuzz(onFrame: (MosquitoRadar.Frame, FloatArray) -> Unit, onHit: (Double) -> Unit) {
    val rec = AudioRecord(MediaRecorder.AudioSource.MIC, MSR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        max(AudioRecord.getMinBufferSize(MSR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), MN * 4))
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val det = MosquitoRadar.Detector(MSR, MN)
    val win = ShortArray(MN); val hop = MN / 2
    var cool = 0L
    rec.startRecording()
    try {
        while (kotlin.coroutines.coroutineContext.isActive) {
            // 50 % overlap: shift the window and read half a frame of new audio.
            System.arraycopy(win, hop, win, 0, MN - hop)
            var got = 0
            while (got < hop) { val n = rec.read(win, MN - hop + got, hop - got); if (n <= 0) break; got += n }
            val spec = Dsp.spectrumDb(FloatArray(MN) { win[it] / 32768f })
            val hit = det.push(spec)
            val hz = MSR.toDouble() / MN
            val b = FloatArray(48) { k ->
                val i0 = ((150 + k * 20) / hz).toInt(); val i1 = ((170 + k * 20) / hz).toInt()
                ((i0..i1).maxOf { spec[it] } + 90f).div(70f).coerceIn(0f, 1f)
            }
            val f = det.lastFrame
            main.post { onFrame(f, b) }
            if (hit != null && System.currentTimeMillis() - cool > 2500) { cool = System.currentTimeMillis(); main.post { onHit(hit) } }
        }
    } finally { rec.stop(); rec.release() }
}

/** Radar sweep; a mosquito with flapping wings appears when a buzz is heard, tinted by its likely type. */
@Composable
private fun Radar(on: Boolean, buzzing: Boolean, est: MosquitoRadar.Estimate?) {
    val inf = rememberInfiniteTransition(label = "radar")
    val sweep by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "sweep")
    val wing by inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(60, easing = LinearEasing), RepeatMode.Reverse), label = "wing")
    val drift by inf.animateFloat(0f, 6.283f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "drift")
    val col = est?.let { kindColor[it.top]!! } ?: Palette.cyan
    Canvas(Modifier.size(220.dp)) {
        val c = center; val r = size.minDimension / 2 - 4f
        for (k in 1..4) drawCircle(col.copy(alpha = 0.12f), r * k / 4, c, style = Stroke(2f))
        drawLine(col.copy(alpha = 0.15f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
        drawLine(col.copy(alpha = 0.15f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
        if (on) rotate(sweep, c) {
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, col.copy(alpha = 0.45f)), c), -60f, 60f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
        }
        if (buzzing || est != null) {
            val p = Offset(c.x + r * 0.45f * kotlin.math.cos(drift), c.y + r * 0.35f * sin(drift * 1.3f))
            // Mosquito: body, legs, flapping wings.
            drawLine(Color(0xFF1F2937), Offset(p.x - 16f, p.y), Offset(p.x + 16f, p.y), 7f, StrokeCap.Round)
            drawCircle(Color(0xFF111827), 6f, Offset(p.x + 18f, p.y))
            drawLine(Color(0xFF111827), Offset(p.x + 22f, p.y), Offset(p.x + 34f, p.y + 4f), 2f)
            for (dx in listOf(-8f, 0f, 8f)) { drawLine(Color(0xFF374151), Offset(p.x + dx, p.y), Offset(p.x + dx - 6f, p.y + 16f), 2f); drawLine(Color(0xFF374151), Offset(p.x + dx, p.y), Offset(p.x + dx + 6f, p.y + 16f), 2f) }
            val wy = 18f * wing
            val wingPath = Path().apply { moveTo(p.x, p.y); quadraticTo(p.x - 18f, p.y - 22f - wy, p.x - 4f, p.y - 30f - wy); close() }
            drawPath(wingPath, Color.White.copy(alpha = 0.55f))
            val wing2 = Path().apply { moveTo(p.x, p.y); quadraticTo(p.x + 14f, p.y - 22f + wy, p.x + 2f, p.y - 30f + wy); close() }
            drawPath(wing2, Color.White.copy(alpha = 0.4f))
            if (est?.top == Kind.AEDES) for (dx in listOf(-10f, -2f, 6f)) drawCircle(Color.White, 2f, Offset(p.x + dx, p.y)) // tiger stripes
            for (k in 0 until 3) drawCircle(col.copy(alpha = 0.25f), 22f + 12f * k + 4f * wing, p, style = Stroke(2f))
        }
        drawCircle(col, 6f, c)
    }
}

/** Live 150–1100 Hz spectrum, with the detected wingbeat and its harmonics marked. */
@Composable
private fun BuzzSpectrum(b: FloatArray, f0: Double?) {
    val cyan = Palette.cyan
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val bw = size.width / b.size
        b.forEachIndexed { i, v -> val h = size.height * v; drawRect(cyan.copy(alpha = 0.35f + 0.6f * v), Offset(i * bw + 1f, size.height - h), Size(bw - 2f, h)) }
        f0?.let { f -> for (h in 1..2) { val x = ((f * h - 150) / 960 * size.width).toFloat(); if (x in 0f..size.width) drawLine(Color(0xFFF43F5E), Offset(x, 0f), Offset(x, size.height), 3f) } }
    }
    Row { Text("150 Hz", color = Palette.muted, fontSize = 10.sp, modifier = Modifier.weight(1f)); Text("1.1 kHz", color = Palette.muted, fontSize = 10.sp) }
}

@Composable
private fun ProbBar(k: Kind, p: Float, i: Int) {
    val a by animateFloatAsState(p, tween(800, delayMillis = i * 100), label = "p$i")
    val c = kindColor[k]!!
    Column(Modifier.padding(vertical = 3.dp)) {
        Row { Text("${k.emoji} ${k.label}", color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text("${(p * 100).toInt()}%", color = c, fontWeight = FontWeight.Black) }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Palette.ink.copy(alpha = 0.06f))) {
            Box(Modifier.fillMaxWidth(a).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.5f), c))))
        }
        Text("Carries: ${k.disease}", color = Palette.muted, fontSize = 11.sp)
    }
}
