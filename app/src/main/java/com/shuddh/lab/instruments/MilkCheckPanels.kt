package com.shuddh.lab.instruments

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.Dart
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.MilkType
import com.shuddh.lab.core.Spoilage
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

/** Luma pixels of a box (every 2nd pixel) with its width/height. */
private fun lumaBox(bmp: Bitmap, r: RectF): Triple<FloatArray, Int, Int> {
    val x0 = (r.left * bmp.width).toInt(); val y0 = (r.top * bmp.height).toInt()
    val w = ((r.right - r.left) * bmp.width).toInt().coerceAtLeast(4); val h = ((r.bottom - r.top) * bmp.height).toInt().coerceAtLeast(4)
    val px = IntArray(w * h); bmp.getPixels(px, 0, w, x0, y0, w, h)
    val ow = w / 2; val oh = h / 2
    val l = FloatArray(ow * oh) { i -> val c = px[(i / ow) * 2 * w + (i % ow) * 2]; 0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff) }
    return Triple(l, ow, oh)
}

@Composable
private fun Verdict(title: String, sub: String, level: Level, conf: Double, extra: String? = null) {
    val col = Color(level.argb)
    Text(title, color = col, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 24.sp)
    Text(sub, color = Palette.text, fontSize = 14.sp, lineHeight = 19.sp)
    Text(Dart.estimate(level, conf), color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    extra?.let { Text(it, color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp) }
}

// ── Spoiled milk? ────────────────────────────────────────────────────────────

@Composable
fun SpoilagePanel(app: AppState, type: MilkType) {
    val scope = rememberCoroutineScope()
    val cam = remember { CameraHandle() }
    val box = RectF(0.25f, 0.30f, 0.75f, 0.70f)
    val live = remember { doubleArrayOf(0.0) }
    val key = "spoil_${type.name.lowercase()}"
    var fresh by remember(type) { mutableStateOf(app.prefs.json(key)?.optDouble("fresh")?.takeIf { !it.isNaN() }) }
    var res by remember(type) { mutableStateOf<Double?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun measure(): Double {
        busy = true
        cam.torch(true); cam.lock(false); delay(800); cam.lock(true)
        val xs = mutableListOf<Double>(); repeat(14) { delay(90); xs += live[0] }
        cam.torch(false); cam.lock(false); busy = false
        return xs.sorted()[7]
    }

    Glass(glow = res?.let { Color(levelOf(Spoilage.call(it, fresh)).argb) } ?: Palette.tint(Color(0xFFFDE68A)), padding = 18) {
        val r = res
        if (r == null) {
            Text("Is this ${type.label.lowercase()} milk spoiled?", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text("Pour a spoon of milk on a dark plate, tilt it so a thin film flows, and hold the phone ~15 cm above — film inside the box.", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
        } else {
            val c = Spoilage.call(r, fresh)
            Verdict(
                when (c) { Spoilage.Call.FRESH -> "FRESH"; Spoilage.Call.SOURING -> "STARTING TO SOUR"; else -> "SPOILED" },
                when (c) { Spoilage.Call.FRESH -> "Smooth film, no curd flecks."; Spoilage.Call.SOURING -> "A few curd flecks — use it today, boil before drinking."; else -> "Curdled film — don't drink it." },
                levelOf(c), Spoilage.confidence(r, fresh),
                "Curd specks ${fmt(r)}% of the film" + (fresh?.let { " (fresh milk ${fmt(it)}%)" } ?: " — record fresh milk once for a sharper call") + ". Also smell it: sour = spoiled. The phone can't smell.",
            )
            if (c != Spoilage.Call.FRESH) Text("Spoiled milk can carry bacteria (Salmonella, E. coli, Listeria) — vomiting, diarrhoea and fever, worst for children and the elderly.", color = Palette.amber, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.6f, overlay = { roi(box, Palette.tint(Color(0xFFFDE68A))) }) { bmp -> val (l, w, h) = lumaBox(bmp, box); live[0] = Spoilage.speckle(l, w, h) }
        Text("📷 camera + 🔦 torch · milk film inside the box", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(if (fresh == null) "1 · Fresh milk (reference)" else "✓ Fresh ${fmt(fresh!!)}%", Modifier.weight(1f), primary = fresh == null, enabled = !busy) {
            scope.launch { val v = measure(); fresh = v; app.prefs.putJson(key, JSONObject().put("fresh", v)); Haptics.click(app.ctx) }
        }
        Btn(if (busy) "Looking…" else "2 · Check this milk", Modifier.weight(1f), enabled = !busy) {
            scope.launch {
                val v = measure(); res = v
                val c = Spoilage.call(v, fresh)
                when (c) { Spoilage.Call.FRESH -> Haptics.ping(app.ctx); Spoilage.Call.SOURING -> Haptics.thud(app.ctx); else -> Haptics.rumble(app.ctx, 1f, 900) }
                app.voice.speak(when (c) { Spoilage.Call.FRESH -> "Fresh."; Spoilage.Call.SOURING -> "Starting to sour."; else -> "Spoiled. Don't drink it." }, app.lang)
            }
        }
    }
    HowItWorks(listOf(
        "As milk sours, bacteria turn lactose into lactic acid; the acid makes the casein protein clump into tiny curds.",
        "Fresh milk spreads as a smooth, even film; souring milk shows flecks. The torch lights the film evenly and the camera counts pixels that stand out from their neighbourhood.",
        "Compared with your own fresh milk under the same light, the result is an estimate with how sure it is. Sour smell is the other half of the test — check it yourself.",
    ))
}

private fun levelOf(c: Spoilage.Call) = when (c) { Spoilage.Call.FRESH -> Level.SAFE; Spoilage.Call.SOURING -> Level.CAUTION; else -> Level.UNSAFE }

// ── Detergent in milk? (shake test) ─────────────────────────────────────────

/** Bubble texture: mean absolute Laplacian relative to brightness. */
private fun bubbles(bmp: Bitmap, r: RectF): Double {
    val (l, w, h) = lumaBox(bmp, r)
    var lap = 0.0; var sum = 0.0; var n = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) { val i = y * w + x; lap += abs(4 * l[i] - l[i - 1] - l[i + 1] - l[i - w] - l[i + w]); sum += l[i]; n++ }
    return if (n == 0 || sum <= 0) 0.0 else 100 * lap / sum
}

@Composable
fun DetergentPanel(app: AppState, type: MilkType) {
    val scope = rememberCoroutineScope()
    val cam = remember { CameraHandle() }
    val box = RectF(0.25f, 0.35f, 0.75f, 0.60f)
    val series = remember { mutableStateListOf<Pair<Double, Double>>() }
    var on by remember { mutableStateOf(false) }
    var t0 by remember { mutableStateOf(0L) }
    val key = "detergent_${type.name.lowercase()}"
    var control by remember(type) { mutableStateOf(app.prefs.json(key)?.optDouble("control")?.takeIf { !it.isNaN() }) }
    var res by remember(type) { mutableStateOf<Pair<Double, Double?>?>(null) }
    var lastRem by remember { mutableStateOf<Double?>(null) }

    Glass(glow = res?.let { Color(Dart.foamVerdict(it.first, control).first.argb) } ?: Palette.tint(Color(0xFF5EEAD4)), padding = 18) {
        val r = res
        if (on) {
            val el = ((System.nanoTime() - t0) / 1e9).toInt()
            Text("Watching the foam · ${(60 - el).coerceAtLeast(0)} s", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
            val p by animateFloatAsState(el / 60f, tween(900), label = "f")
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.veil(0x22))) { Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(Palette.tint(Color(0xFF5EEAD4)))) }
        } else if (r == null) {
            Text("Detergent in this ${type.label.lowercase()} milk?", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text("Put equal parts milk and water in a clear bottle, close it, shake hard for 10 s, stand it up and point the box at the foam.", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
        } else {
            val (lv, why) = Dart.foamVerdict(r.first, control)
            val ref = control ?: 0.35
            Verdict(if (lv == Level.SAFE) "NO DETERGENT" else if (lv == Level.CAUTION) "FOAM LASTING" else "DETERGENT LIKELY", why, lv,
                Dart.confidence(listOf(ref + 0.15, ref + 0.35).minOf { abs(r.first - it) }, 0.08),
                "Foam left after 60 s: ${fmt(r.first * 100)}%" + (control?.let { " (pure milk ${fmt(it * 100)}%)" } ?: " — record pure milk once for a sharper call") + (r.second?.let { " · half-life ${if (it.isInfinite()) "> 60 s" else "${fmt(it)} s"}" } ?: ""))
            if (lv != Level.SAFE) Text("Detergent in milk irritates the stomach and gut lining and can harm the liver and kidneys over time; it often signals synthetic milk made with cheap oil.", color = Palette.amber, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
    if (series.size > 4) {
        Canvas(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(Palette.well(0x33))) {
            val st = series.take(4).map { it.second }.average().takeIf { it > 0 } ?: 1.0
            fun X(t: Double) = (t / 60 * size.width).toFloat()
            fun Y(v: Double) = (size.height - (v / st / 1.2).coerceIn(0.0, 1.0) * size.height).toFloat()
            series.toList().zipWithNext().forEach { (a, b) -> drawLine(Palette.tint(Color(0xFF5EEAD4)), Offset(X(a.first), Y(a.second)), Offset(X(b.first), Y(b.second)), 4f, cap = StrokeCap.Round) }
            control?.let { c -> drawLine(Color.White.copy(alpha = 0.5f), Offset(0f, Y(c * st)), Offset(size.width, Y(c * st)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))) }
        }
        Text("Foam over 60 s · dashed: where pure milk ends up", color = Palette.muted, fontSize = 10.sp)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.6f, overlay = { roi(box, Palette.tint(Color(0xFF5EEAD4))) }) { bmp ->
            if (on) { val v = bubbles(bmp, box); val t = (System.nanoTime() - t0) / 1e9; synchronized(series) { series += t to v } }
        }
        Text("📷 camera · foam layer inside the box", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(if (on) "Watching…" else "▶ Shake, then start 60 s", Modifier.weight(1f), enabled = !on) {
            synchronized(series) { series.clear() }; res = null
            scope.launch {
                cam.lock(false); delay(500); cam.lock(true); t0 = System.nanoTime(); on = true
                app.voice.speak("Watching the foam for one minute. Keep still.", app.lang)
                while ((System.nanoTime() - t0) / 1e9 < 60) delay(250)
                on = false; cam.lock(false)
                val s = synchronized(series) { series.groupBy { (it.first / 0.5).toInt() }.toSortedMap().map { (k, v) -> k * 0.5 to v.map { it.second }.sorted()[v.size / 2] } }
                val rem = Dart.foamRemaining(s) ?: run { app.voice.speak("Couldn't see the foam.", app.lang); return@launch }
                lastRem = rem; res = rem to Dart.foamHalfLife(s)
                val lv = Dart.foamVerdict(rem, control).first
                when (lv) { Level.SAFE -> Haptics.ping(app.ctx); Level.CAUTION -> Haptics.thud(app.ctx); else -> Haptics.rumble(app.ctx, 1f, 900) }
                app.voice.speak(if (lv == Level.SAFE) "No detergent." else if (lv == Level.CAUTION) "Foam lasting longer than normal." else "Detergent likely.", app.lang)
            }
        }
        if (lastRem != null && !on) Btn("This was pure milk", Modifier.weight(1f), primary = false) {
            control = lastRem; app.prefs.putJson(key, JSONObject().put("control", lastRem)); Haptics.click(app.ctx)
        }
    }
    Note("Run it once with milk you trust and tap “This was pure milk” — then every test compares against it.", Palette.muted)
    HowItWorks(listOf(
        "Detergent is added to make cheap oil and water look like milk (synthetic milk) or to fake froth.",
        "Shaken real milk makes a little foam that collapses within seconds; detergent stabilises the bubbles, so the foam stands.",
        "The camera measures bubble texture in the foam twice a second for 60 s and reports how much is left — an objective version of FSSAI's lather test.",
    ))
}
