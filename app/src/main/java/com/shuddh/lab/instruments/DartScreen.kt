package com.shuddh.lab.instruments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.Collector
import com.shuddh.lab.camera.Frames
import com.shuddh.lab.camera.Rgb
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.Dart
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Fold
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import androidx.compose.foundation.layout.Spacer
import com.shuddh.lab.ui.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

private val dartTint = mapOf(
    "starch_milk" to Color(0xFF818CF8), "iodised_salt" to Color(0xFFA78BFA),
    "tea_colour" to Color(0xFFFB923C), "water_milk" to Color(0xFF38BDF8), "detergent_milk" to Color(0xFF5EEAD4), "urea_milk" to Color(0xFFF472B6),
)
private fun tint(t: Dart.Test) = dartTint[t.id] ?: Palette.cyan

/** Shuddh DART — FSSAI's home adulteration tests, read by the camera. Hub → guided test runner. */
@Composable
fun DartScreen(app: AppState) {
    var open by remember { mutableStateOf<Dart.Test?>(null) }
    var queue by remember { mutableStateOf<List<Dart.Test>>(emptyList()) }
    var tool by remember { mutableStateOf<String?>(null) }
    val t = open
    if (tool == "bleach") { BleachPanel(app) { tool = null }; return }
    if (tool == "ors") { OrsPanel(app) { tool = null }; return }
    if (tool == "clarity") { WaterClarityPanel(app, onBleach = { tool = "bleach" }) { tool = null }; return }
    if (tool == "h2s") { H2sPanel(app, onBleach = { tool = "bleach" }) { tool = null }; return }
    if (t == null) DartHub(app, onTool = { tool = it }, onFullCheck = { queue = milkTests.map { Dart.test(it) }; open = queue.first() }) { queue = emptyList(); open = it }
    else {
        val next = queue.getOrNull(queue.indexOf(t) + 1).takeIf { t in queue }
        DartRunner(app, t, next, onNext = { open = next }) { open = null; queue = emptyList() }
    }
}

@Composable
private fun DartHub(app: AppState, onTool: (String) -> Unit, @Suppress("UNUSED_PARAMETER") onFullCheck: () -> Unit, @Suppress("UNUSED_PARAMETER") onOpen: (Dart.Test) -> Unit) {
    ScreenFrame("Sensor Lab", "No kits — your phone's sensors check it", onBack = { app.back() }) {
        val inf = rememberInfiniteTransition(label = "hero")
        val sweep by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "s")
        Glass(glow = Color(0xFF818CF8), padding = 18) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Canvas(Modifier.size(78.dp)) {
                    val r = size.minDimension / 2
                    drawCircle(Brush.sweepGradient(listOf(Color(0xFF38BDF8), Color(0xFF818CF8), Color(0xFF34D399), Color(0xFF38BDF8))), r * 0.82f)
                    drawCircle(Color.White.copy(alpha = 0.9f), r * 0.98f, style = Stroke(3f))
                    drawArc(Color.White, -90f + 360f * sweep, 50f, false, Offset(2f, 2f), Size(size.width - 4f, size.height - 4f), style = Stroke(5f, cap = StrokeCap.Round))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Is it safe? Ask your phone.", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text("Camera, torch, ring light, microphone, speaker, motion and magnetic sensors measure the food — Shuddh gives an educated estimate and how sure it is.", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Sensed" to "only phone sensors", "Estimated" to "safe / unsafe + % sure", "Sealed" to "evidence & report").forEach { (a, b) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0x14FFFFFF)).padding(10.dp)) {
                        Text(a, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(b, color = Palette.muted, fontSize = 10.sp, lineHeight = 13.sp)
                    }
                }
            }
        }
        Text("FOOD", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        sensorChecks.filter { it.group == "food" }.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { SensorCard(it, Modifier.weight(1f)) { app.go(it.screen) } }; if (row.size == 1) Box(Modifier.weight(1f)) } }
        Text("WATER", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SensorCard(SensorCheck("💧", "Water clarity", listOf("📷"), "cloudy water carries germs", Screen.DART, "water"), Modifier.weight(1f)) { onTool("clarity") }
            SensorCard(sensorChecks.first { it.screen == Screen.BOIL }, Modifier.weight(1f)) { app.go(Screen.BOIL) }
        }
        Text("KITCHEN & HOME", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        sensorChecks.filter { it.group == "home" }.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { SensorCard(it, Modifier.weight(1f)) { app.go(it.screen) } }; if (row.size == 1) Box(Modifier.weight(1f)) } }
        Text("ACT ON A RESULT", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolCard("🧴", "Make water safe", "bleach dose · 30 min timer", Color(0xFF38BDF8), Modifier.weight(1f)) { onTool("bleach") }
            ToolCard("🩺", "Sick from water?", "WHO ORS · danger signs · 108", Color(0xFF34D399), Modifier.weight(1f)) { onTool("ors") }
        }
        HowItWorks(listOf(
            "Every check uses only the phone: camera and torch (and the iQOO ring light) for colour and cloudiness, microphone and speaker for sound, accelerometer and magnetometer for motion.",
            "Readings are compared with a reference you give it once — your pure milk, fresh oil, a known-good coconut — so the phone measures the difference, which is what it can measure reliably.",
            "Each result is an educated estimate: safe or not, plus how sure it is, worked out from how far the reading is from the decision line versus its own noise.",
            "Phone sensors can't see dissolved chemicals (urea, pesticides) or germs directly — Shuddh says so instead of guessing, and flags what needs a lab.",
        ))
    }
}

private data class SensorCheck(val emoji: String, val title: String, val sensors: List<String>, val sub: String, val screen: Screen, val group: String)

/** Every check here uses only the phone's own sensors. */
private val sensorChecks = listOf(
    SensorCheck("🥛", "Milk purity + Milk Watch", listOf("📷", "🔦", "💡", "🧲"), "added water · learns your milkman", Screen.PURITY, "food"),
    SensorCheck("🍳", "Frying oil", listOf("📷", "🔦"), "how many times it's been reused", Screen.OIL, "food"),
    SensorCheck("🌾", "Grain check", listOf("📷"), "stones & broken grains in rice", Screen.GRAIN, "food"),
    SensorCheck("🥥", "Coconut & melon", listOf("🎤", "📳"), "full? ripe? by knock", Screen.ECHO, "food"),
    SensorCheck("🏷️", "Label check", listOf("📷"), "expiry, FSSAI licence, MRP", Screen.LENS, "food"),
    SensorCheck("♨️", "Boil guard", listOf("🎤", "⛰"), "boiled long enough to be safe", Screen.BOIL, "water"),
    SensorCheck("🌧", "Damp & mould risk", listOf("🔊", "🎤", "📷"), "stored rice, walls", Screen.NAMI, "home"),
    SensorCheck("🫖", "Cooker whistles", listOf("🎤"), "counts whistles for you", Screen.WHISTLE, "home"),
)

@Composable
private fun SensorCard(c: SensorCheck, modifier: Modifier, onTap: () -> Unit) {
    val tint = when (c.group) { "water" -> Color(0xFF38BDF8); "home" -> Color(0xFF34D399); else -> Color(0xFF818CF8) }
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.20f), Color(0x0CFFFFFF))))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(20.dp)).clickable { onTap() }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) { Text(c.emoji, fontSize = 22.sp) }
            Spacer(Modifier.weight(1f))
            Text(c.sensors.joinToString(" "), fontSize = 13.sp)
        }
        Text(c.title, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp)
        Text(c.sub, color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun TestCard(t: Dart.Test, last: JSONObject?, modifier: Modifier, onTap: () -> Unit) {
    val c = tint(t)
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(c.copy(alpha = 0.20f), Color(0x0CFFFFFF))))
            .border(1.dp, c.copy(alpha = 0.45f), RoundedCornerShape(20.dp)).clickable { onTap() }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) { Text(t.emoji, fontSize = 24.sp) }
        Text(t.title, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("${t.needs.size} items · ${when (t.kind) { Dart.Kind.COLOUR -> "colour"; Dart.Kind.LACTO -> "lactometer"; Dart.Kind.FOAM -> "foam"; Dart.Kind.SPOTS -> "spots" }}", color = Palette.muted, fontSize = 11.sp)
        last?.let { l ->
            val lv = runCatching { Level.valueOf(l.getString("level")) }.getOrDefault(Level.INCONCLUSIVE)
            Text(l.getString("label"), color = Color(lv.argb), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(lv.argb).copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

private data class LabRead(val l: Double, val a: Double, val b: Double, val sdB: Double, val issues: List<String>)

/** One guided test: steps, timer, camera, measurement, result, and (colour tests) lab validation. */
@Composable
private fun DartRunner(app: AppState, t: Dart.Test, next: Dart.Test?, onNext: () -> Unit, close: () -> Unit) {
    val ctx = app.ctx
    val scope = rememberCoroutineScope()
    val cam = remember { CameraHandle() }
    val key = "dart_${t.id}"
    var store by remember { mutableStateOf(app.prefs.json(key) ?: JSONObject()) }
    fun save(o: JSONObject) { store = o; app.prefs.putJson(key, o) }
    var step by remember { mutableStateOf(0) }
    var timer by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Outcome?>(null) }
    var resultConf by remember { mutableStateOf(0.0) }
    var msg by remember { mutableStateOf<String?>(null) }
    val c = tint(t)

    // Colour ROIs (white paper left, sample right) and frame collector.
    val whiteRoi = RectF(0.10f, 0.40f, 0.34f, 0.60f)
    val spotRoi = RectF(0.58f, 0.42f, 0.80f, 0.58f)
    val foamRoi = RectF(0.25f, 0.35f, 0.75f, 0.60f)
    val collector = remember { Collector<List<Pair<Rgb, Rgb>>> { it.flatten() } }
    /** Latest spot uniformity (luma CV) from the camera thread. */
    val spotCv = remember { doubleArrayOf(0.0) }
    val foam = remember { mutableStateListOf<Pair<Double, Double>>() }
    var foamOn by remember { mutableStateOf(false) }
    var t0 by remember { mutableStateOf(0L) }

    suspend fun grab(n: Int) = withTimeoutOrNull(8000) { suspendCancellableCoroutine<List<Pair<Rgb, Rgb>>> { k -> collector.start(n) { if (k.isActive) k.resume(it) } } }

    /**
     * Three separate bursts (exposure locked) that must agree; quality gates on light, glare and whether the
     * spot fills its box. σ combines frame noise with burst-to-burst spread, so the verdict knows its own error.
     */
    suspend fun readColour(): LabRead? {
        cam.lock(false); delay(700); cam.lock(true); delay(200)
        val bursts = mutableListOf<List<Pair<Rgb, Rgb>>>()
        repeat(3) { val fr = grab(8); if (fr == null) { cam.lock(false); return null }; bursts += fr; delay(250) }
        cam.lock(false)
        val all = bursts.flatten()
        val w = Rgb.average(all.map { it.first }); val sp = Rgb.average(all.map { it.second })
        val issues = mutableListOf<String>()
        if (w.luma < 70f) issues += "Too dark — move to brighter light"
        if (w.saturated > 0.05f || sp.saturated > 0.05f) issues += "Glare on the paper or spot — tilt the phone slightly"
        if (spotCv[0] > 0.22) issues += "The test spot doesn't fill the coloured box"
        val labs = all.map { (wh, s) -> Frames.relativeLab(s, wh) }
        val burstB = bursts.map { b -> b.map { (wh, s) -> Frames.relativeLab(s, wh).b }.average() }
        val spread = burstB.max() - burstB.min()
        if (spread > 3.0) issues += "Reading moved between captures — hold steadier"
        val bs = labs.map { it.b }; val mb = bs.average()
        val frameSd = sqrt(bs.sumOf { (it - mb) * (it - mb) } / (bs.size - 1))
        return LabRead(labs.map { it.l }.average(), labs.map { it.a }.average(), mb, sqrt(frameSd * frameSd / bs.size + (spread / 2) * (spread / 2) + 0.5 * 0.5), issues)
    }

    fun speak(s: String) = app.voice.speak(s, app.lang)
    fun lastSave(label: String, level: Level, o: Outcome, conf: Double) {
        resultConf = conf
        save(JSONObject(store.toString()).put("last", JSONObject().put("label", label).put("level", level.name).put("at", System.currentTimeMillis()).put("detail", o.headline).put("value", o.valueText()).put("conf", conf)))
        result = o; Haptics.rumble(ctx, if (level == Level.SAFE) 0.2f else 0.9f)
        speak(label)
    }

    // Countdown for reactions (step "wait")
    LaunchedEffect(timer) {
        val s = timer ?: return@LaunchedEffect
        if (s <= 0) { timer = null; Haptics.ping(ctx); speak("Ready to measure."); step = t.steps.lastIndex; return@LaunchedEffect }
        delay(1000); timer = s - 1
    }

    ScreenFrame(t.title, t.fssai, onBack = { cam.torch(false); close() }) {
        // ── Result / hero ──
        val r = result
        Glass(glow = r?.let { Color(it.level.argb) } ?: c, padding = 18) {
            if (r != null) DartResult(t, r, resultConf)
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) { Text(t.emoji, fontSize = 32.sp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(busy ?: "Step ${step + 1} of ${t.steps.size}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text(t.steps[step], color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            msg?.let { Text(it, color = Palette.cyan, fontSize = 13.sp, lineHeight = 18.sp) }
            if (r != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Test another", Modifier.weight(1f), primary = false) { result = null; step = 0; msg = null }
                Btn("Seal report", Modifier.weight(1f)) { app.show(r) }
            }
            if (r != null && next != null) Btn("Next test: ${next.emoji} ${next.title}  →", Modifier.fillMaxWidth()) { onNext() }
            if (r != null && next == null && t.id in milkTests) Btn("See the milk report card  →", Modifier.fillMaxWidth(), primary = false) { close() }
            if (r != null && r.level == Level.UNSAFE) Btn("📣  Report to FSSAI", Modifier.fillMaxWidth(), primary = false) { reportToFssai(ctx, app.prefs.json("dart_vendor")?.optString("name").orEmpty(), listOf(t to r)) }
        }

        // ── What you need ──
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            t.needs.take(4).forEach { n ->
                Text(n, color = Palette.text, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color(0x14FFFFFF)).padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }

        // ── Steps ──
        Glass(padding = 14) {
            t.steps.forEachIndexed { i, s ->
                val done = i < step; val now = i == step
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().clickable { step = i; speak(s) }) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(if (done) Palette.accent.copy(alpha = 0.2f) else if (now) c.copy(alpha = 0.25f) else Color(0x10FFFFFF))
                            .border(1.5.dp, if (done) Palette.accent else if (now) c else Color(0x22FFFFFF), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (done) "✓" else "${i + 1}", color = if (done) Palette.accent else Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    Text(s, color = if (now) Palette.text else Palette.muted, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                }
            }
            if (step < t.steps.lastIndex) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.waitS > 0 && step == t.steps.lastIndex - 2) Btn("Done · start ${t.waitS}s timer", Modifier.weight(1f)) { step++; timer = t.waitS; speak("Timer started.") }
                else Btn("Done · next step", Modifier.weight(1f)) { step++; speak(t.steps[step]) }
            }
            timer?.let { s -> TimerRing(s, t.waitS, c) }
        }

        // ── Measurement ──
        when (t.kind) {
            Dart.Kind.COLOUR -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.6f, overlay = { roi(whiteRoi, Color.White); roi(spotRoi, c) }) { bmp ->
                        spotCv[0] = lumaCv(bmp, spotRoi)
                        collector.offer(listOf(Frames.meanRgb(bmp, whiteRoi) to Frames.meanRgb(bmp, spotRoi)))
                    }
                    Text("White box: paper · coloured box: the test spot", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
                val control = store.optJSONObject("control")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(if (control == null) "1 · Capture control" else "✓ Control saved", Modifier.weight(1f), primary = control == null, enabled = busy == null) {
                        busy = "Reading control…"
                        scope.launch {
                            val rd = readColour(); busy = null
                            if (rd == null) { msg = "Couldn't read — keep the paper and the spot inside the boxes."; return@launch }
                            if (rd.issues.isNotEmpty()) { msg = "Control not saved: ${rd.issues.joinToString("; ")}."; return@launch }
                            save(JSONObject(store.toString()).put("control", JSONObject().put("l", rd.l).put("a", rd.a).put("b", rd.b).put("sd", rd.sdB).put("at", System.currentTimeMillis())))
                            msg = "Control saved (${controlName(t)}). Now test the sample."
                            Haptics.click(ctx)
                        }
                    }
                    Btn("2 · Test sample", Modifier.weight(1f), enabled = busy == null && control != null) {
                        busy = "Measuring colour…"
                        scope.launch {
                            val rd = readColour(); busy = null
                            if (rd == null) { msg = "Couldn't read — keep the paper and the spot inside the boxes."; return@launch }
                            val ctl = store.getJSONObject("control")
                            val sig = Dart.blueShift(ctl.getDouble("b"), rd.b, ctl.getDouble("l"), rd.l)
                            val noise = sqrt(ctl.optDouble("sd", 1.0).let { it * it } + rd.sdB * rd.sdB)
                            val cal = calFrom(store)?.let { Dart.calibrate(it) }
                            val stale = System.currentTimeMillis() - ctl.optLong("at", 0) > 24 * 3600_000L
                            val issues = rd.issues + listOfNotNull(if (stale) "Control is over a day old — capture a fresh one with today's reagent" else null)
                            val severe = rd.issues.any { it.startsWith("Too dark") || it.startsWith("Glare") }
                            val call = if (severe) null else Dart.colourCall(sig, noise)
                            val conc = if (call == true) cal?.predict(sig) else null
                            val label = when (call) { true -> t.positive; false -> t.negative; null -> "Retest" }
                            val level = when {
                                call == null -> Level.INCONCLUSIVE
                                t.id == "iodised_salt" -> if (call) Level.SAFE else Level.UNSAFE
                                call -> Level.UNSAFE
                                else -> Level.SAFE
                            }
                            val ev = mutableListOf(
                                Evidence("OBSERVATION", "Sample colour L* ${fmt(rd.l)} a* ${fmt(rd.a)} b* ${fmt(rd.b)} vs control L* ${fmt(ctl.getDouble("l"))} b* ${fmt(ctl.getDouble("b"))} (white-paper referenced, 3 captures)"),
                                Evidence("PATTERN", "Blue shift ${fmt(sig)} ± ${fmt(noise)} vs threshold ${fmt(Dart.COLOUR_THRESHOLD)} → ${if (call == null) "too close to call" else "${fmt(abs(sig - Dart.COLOUR_THRESHOLD) / noise.coerceAtLeast(0.1))}σ from threshold"}", call != null),
                            )
                            issues.forEach { ev += Evidence("QUALITY", it, false) }
                            cal?.let { ev += Evidence("CALIBRATION", "Your dose curve: ${it.n} known samples, R² ${fmt(it.r2)}, leave-one-out error ±${fmt(it.mae)} ${t.unit}", it.r2 > 0.9) }
                            ev += Evidence("HYPOTHESIS", label + (conc?.let { " — about ${fmt(it)} ${t.unit}" } ?: ""), level == Level.SAFE)
                            val conf = Dart.confidence(sig - Dart.COLOUR_THRESHOLD, noise)
                            ev.add(0, Evidence("PATTERN", "Estimate: ${Dart.estimate(level, conf)} (distance from the decision line ÷ measured noise)", level == Level.SAFE))
                            lastSave(label, level, Outcome(
                                "Shuddh DART", t.id, Txt(t.title), conc ?: sig, if (conc != null) t.unit else "Δ blue", level,
                                label + (conc?.let { " · ≈${fmt(it)} ${t.unit} ±${fmt(cal!!.mae)}" } ?: " · blue shift ${fmt(sig)} ± ${fmt(noise)}"),
                                listOf(Txt(when {
                                    call == null -> "Too close to call. ${issues.firstOrNull() ?: "Repeat with a fresh sample and the same amount of reagent."}"
                                    level == Level.SAFE -> "Matches the FSSAI negative result."
                                    else -> "Matches the FSSAI positive result. Keep the sample and report it."
                                })), ev,
                                t.fssai + " Read objectively by camera colorimetry against your own control.", levelLabel = Txt(label.uppercase()),
                            ), conf)
                        }
                    }
                }
                LabValidation(t, store, ::save, busy == null) { conc ->
                    busy = "Reading known sample…"
                    scope.launch {
                        val rd = readColour(); busy = null
                        val ctl = store.optJSONObject("control")
                        if (rd == null || ctl == null) { msg = "Capture the control first, then known samples."; return@launch }
                        val sig = Dart.blueShift(ctl.getDouble("b"), rd.b, ctl.getDouble("l"), rd.l)
                        val pts = (store.optJSONArray("cal") ?: JSONArray()).put(JSONArray(listOf(conc, sig)))
                        save(JSONObject(store.toString()).put("cal", pts))
                        msg = "Added ${fmt(conc)} ${t.unit} → blue shift ${fmt(sig)}."
                        Haptics.click(ctx)
                    }
                }
            }

            Dart.Kind.LACTO -> {
                var milk by remember { mutableStateOf(Dart.Milk.entries.firstOrNull { it.name == store.optString("milk") } ?: Dart.Milk.COW) }
                var reading by remember { mutableStateOf(store.optDouble("reading", 29.0)) }
                var temp by remember { mutableStateOf(store.optDouble("temp", 27.0)) }
                var fat by remember(milk) { mutableStateOf(store.optDouble("fat_${milk.name}", milk.typicalFat)) }
                var calib by remember { mutableStateOf(store.optDouble("calib", 27.0)) }
                val clr = Dart.correctedClr(reading, temp, calib)
                val snf = Dart.snf(clr, fat)
                val water = Dart.addedWater(snf, milk.minSnf)
                Glass(padding = 16) {
                    Text("MILK TYPE", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dart.Milk.entries.forEach { m ->
                            val sel = m == milk
                            Text(m.label, color = if (sel) Palette.text else Palette.muted, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) c.copy(alpha = 0.25f) else Color(0x10FFFFFF))
                                    .border(1.dp, if (sel) c else Color(0x22FFFFFF), RoundedCornerShape(12.dp)).clickable { milk = m }.padding(vertical = 9.dp))
                        }
                    }
                    Text("LACTOMETER CALIBRATED AT (printed on it)", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dart.lactoCalibrations.forEach { cc ->
                            val sel = cc == calib
                            Text("${fmt(cc)} °C", color = if (sel) Palette.text else Palette.muted, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) c.copy(alpha = 0.25f) else Color(0x10FFFFFF))
                                    .border(1.dp, if (sel) c else Color(0x22FFFFFF), RoundedCornerShape(12.dp)).clickable { calib = cc }.padding(vertical = 9.dp))
                        }
                    }
                    Stepper("Lactometer reading", one(reading), "at the milk surface", c) { d -> reading = (reading + d * 0.5).coerceIn(14.0, 40.0) }
                    Stepper("Milk temperature", "${one(temp)}°", "room ≈ 27–30 · fridge ≈ 8–10", c) { d -> temp = (temp + d).coerceIn(5.0, 45.0) }
                    Stepper("Fat", "${one(fat)}%", "from the packet, or typical ${milk.label.lowercase()}", c) { d -> fat = (fat + d * 0.1).coerceIn(0.5, 10.0) }
                    SnfGauge(snf, milk.minSnf, water, c)
                    if (!Dart.plausibleClr(clr)) Note("A corrected reading of ${fmt(clr)} isn't possible for milk — check the lactometer floats freely, isn't touching the glass, and there's no foam.", Palette.amber)
                    Btn("Check this milk", Modifier.fillMaxWidth()) {
                        save(JSONObject(store.toString()).put("milk", milk.name).put("reading", reading).put("temp", temp).put("calib", calib).put("fat_${milk.name}", fat))
                        val ok = Dart.plausibleClr(clr)
                        val (lv0, why) = Dart.lactoVerdict(water)
                        val lv = if (ok) lv0 else Level.INCONCLUSIVE
                        val label = if (!ok) "Retest" else if (lv == Level.SAFE) t.negative else t.positive
                        // ±1 lactometer division ≈ ±3 % water: distance to the nearest decision line (3 % / 10 %) in those units.
                        val conf = if (!ok) 50.0 else Dart.confidence(listOf(3.0, 10.0).minOf { abs(water - it) }, 3.0)
                        lastSave(label, lv, Outcome(
                            "Shuddh DART", t.id, Txt(t.title), water, "% water", lv, if (ok) "$label · $why" else "Retest · reading ${fmt(clr)} is outside the milk range",
                            listOf(Txt(if (lv == Level.SAFE) "Solids-not-fat meets the FSSAI standard for ${milk.label.lowercase()} milk." else "SNF is below the FSSAI minimum — the usual cause is added water. You are paying for water.")),
                            listOf(
                                Evidence("OBSERVATION", "Lactometer ${fmt(reading)} at ${fmt(temp)} °C (calibrated ${fmt(calib)} °C) → corrected CLR ${fmt(clr)}; fat ${fmt(fat)} %", ok),
                                Evidence("PATTERN", "SNF = CLR/4 + 0.21×fat + 0.36 = ${fmt(snf)} % (FSSAI minimum ${fmt(milk.minSnf)} % for ${milk.label.lowercase()})", snf >= milk.minSnf),
                                Evidence("QUALITY", "±1 lactometer division ≈ ±3 % water", true),
                                Evidence("HYPOTHESIS", "$label — ${fmt(water)} % added water", lv == Level.SAFE),
                            ),
                            t.fssai, levelLabel = Txt(label.uppercase()),
                        ), conf)
                    }
                }
            }

            Dart.Kind.SPOTS -> {
                val area = remember { doubleArrayOf(0.0) }
                val big = RectF(0.18f, 0.25f, 0.82f, 0.75f)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.6f, overlay = { roi(big, c) }) { bmp -> area[0] = dyeArea(bmp, big) }
                    Text("The wet paper fills the box", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
                suspend fun measure(): Double { cam.lock(false); delay(600); cam.lock(true); val xs = mutableListOf<Double>(); repeat(15) { delay(100); xs += area[0] }; cam.lock(false); return xs.sorted()[7] }
                val control = store.optDouble("controlArea").takeIf { !it.isNaN() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(if (control == null) "1 · Control (trusted brand)" else "✓ Control ${fmt(control)}%", Modifier.weight(1f), primary = control == null, enabled = busy == null) {
                        busy = "Reading control…"
                        scope.launch { val a = measure(); busy = null; save(JSONObject(store.toString()).put("controlArea", a)); msg = "Control: ${fmt(a)}% coloured. Now test the sample the same way."; Haptics.click(ctx) }
                    }
                    Btn("2 · Test sample", Modifier.weight(1f), enabled = busy == null) {
                        busy = "Measuring colour spots…"
                        scope.launch {
                            val a = measure(); busy = null
                            val call = Dart.spotsCall(a, control)
                            val label = when (call) { true -> t.positive; false -> t.negative; null -> "Retest" }
                            val lv = when (call) { true -> Level.UNSAFE; false -> Level.SAFE; null -> Level.INCONCLUSIVE }
                            val line = maxOf(1.5, (control ?: 0.3) * 3)
                            val conf = Dart.confidence(a - line, maxOf(0.3, 0.25 * a))
                            lastSave(label, lv, Outcome(
                                "Shuddh DART", t.id, Txt(t.title), a, "% coloured", lv, "$label · ${fmt(a)}% of the paper dyed" + (control?.let { " (trusted brand ${fmt(it)}%)" } ?: ""),
                                listOf(Txt(when (call) { true -> "Bright dye bled out of the leaves — the FSSAI sign of added colour."; false -> "No dye bled out — matches the FSSAI negative result."; null -> "Borderline. Use fresh wet paper and the same pinch size, and repeat." })),
                                listOf(
                                    Evidence("OBSERVATION", "Dyed area ${fmt(a)}% of the wet paper (bright, saturated pixels vs the paper colour; dark leaf bits excluded)"),
                                    Evidence("CALIBRATION", control?.let { "Control from a trusted brand: ${fmt(it)}%" } ?: "No control — absolute floor 1.5% used", control != null),
                                    Evidence("HYPOTHESIS", label, lv == Level.SAFE),
                                ),
                                t.fssai + " Coloured area measured by the camera.", levelLabel = Txt(label.uppercase()),
                            ), conf)
                        }
                    }
                }
            }

            Dart.Kind.FOAM -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.6f, overlay = { roi(foamRoi, c) }) { bmp ->
                        if (foamOn) { val v = texture(bmp, foamRoi); val tt = (System.nanoTime() - t0) / 1e9; synchronized(foam) { foam += tt to v } }
                    }
                    Text("Foam layer inside the box", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
                val series = remember(foam.size / 5) { synchronized(foam) { bin(foam.toList(), 0.5) } }
                if (series.isNotEmpty() || store.has("controlSeries")) FoamChart(seriesFrom(store.optJSONArray("controlSeries")), series, c)
                if (foamOn) {
                    val el = ((System.nanoTime() - t0) / 1e9).toInt()
                    TimerRing((60 - el).coerceAtLeast(0), 60, c)
                }
                val control = store.optDouble("control").takeIf { !it.isNaN() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(if (foamOn) "Watching foam…" else "▶ Start 60 s", Modifier.weight(1f), enabled = !foamOn) {
                        synchronized(foam) { foam.clear() }
                        cam.lock(false)
                        scope.launch {
                            delay(500); cam.lock(true); t0 = System.nanoTime(); foamOn = true; speak("Watching the foam for one minute. Keep still.")
                            while ((System.nanoTime() - t0) / 1e9 < 60) delay(250)
                            foamOn = false; cam.lock(false)
                            val s = synchronized(foam) { bin(foam.toList(), 0.5) }
                            val rem = Dart.foamRemaining(s); val hl = Dart.foamHalfLife(s)
                            if (rem == null) { msg = "Couldn't see the foam — keep the foam layer inside the box."; return@launch }
                            save(JSONObject(store.toString()).put("lastRem", rem).put("lastSeries", seriesJson(s)))
                            val (lv, why) = Dart.foamVerdict(rem, control)
                            val label = if (lv == Level.SAFE) t.negative else t.positive
                            val ref = control ?: 0.35
                            val conf = Dart.confidence(listOf(ref + 0.15, ref + 0.35).minOf { abs(rem - it) }, 0.08)
                            lastSave(label, lv, Outcome(
                                "Shuddh DART", t.id, Txt(t.title), rem * 100, "% foam left", lv, "$label · $why",
                                listOf(Txt(if (lv == Level.SAFE) "Foam collapsed like normal milk." else "Stable lather is the FSSAI sign of detergent. Keep the sample and report it.")),
                                listOf(
                                    Evidence("OBSERVATION", "Bubble texture left after 60 s: ${fmt(rem * 100)}%; half-life ${hl?.let { if (it.isInfinite()) "> 60 s" else "${fmt(it)} s" } ?: "—"}"),
                                    Evidence("CALIBRATION", control?.let { "Reference: your pure milk kept ${fmt(it * 100)}%" } ?: "No pure-milk reference — typical milk keeps ~35%", control != null),
                                    Evidence("HYPOTHESIS", label, lv == Level.SAFE),
                                ),
                                t.fssai + " Foam measured by camera texture analysis over 60 s.", levelLabel = Txt(label.uppercase()),
                            ), conf)
                        }
                    }
                    val lastRem = store.optDouble("lastRem").takeIf { !it.isNaN() }
                    if (lastRem != null && !foamOn) Btn("Use as pure reference", Modifier.weight(1f), primary = false) {
                        save(JSONObject(store.toString()).put("control", lastRem).put("controlSeries", store.optJSONArray("lastSeries") ?: JSONArray()))
                        msg = "Pure milk foam saved as reference."
                    }
                }
            }
        }
        HowItWorks(t.science)
    }
}

// ── Result card ──────────────────────────────────────────────────────────────

@Composable
private fun DartResult(t: Dart.Test, r: Outcome, conf: Double) {
    val col = Color(r.level.argb)
    val scale by animateFloatAsState(1f, tween(500), label = "s")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(col.copy(alpha = 0.16f)).border(3.dp, col, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (r.level == Level.SAFE) "✓" else if (r.level == Level.CAUTION) "!" else "✕", color = col, fontSize = (40 * scale).sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.levelLabel?.en ?: r.level.name, color = col, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(r.headline.substringAfter(" · ", r.headline), color = Palette.text, fontSize = 14.sp, lineHeight = 19.sp)
            Text(t.fssai.substringBefore(" —"), color = Palette.muted, fontSize = 11.sp)
        }
    }
    EstimateBar(r.level, conf, col)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dart.sensors(t).forEach { Text(it, color = Palette.text, fontSize = 11.sp, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x14FFFFFF)).padding(horizontal = 8.dp, vertical = 5.dp)) }
    }
}

/** "Likely unsafe · 94% sure" with a bar — the educated estimate, honest about its own certainty. */
@Composable
private fun EstimateBar(level: Level, conf: Double, col: Color) {
    val p by animateFloatAsState(((conf - 50) / 49).toFloat().coerceIn(0f, 1f), tween(900), label = "conf")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("ESTIMATE", color = Palette.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
            Text(Dart.estimate(level, conf), color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxWidth(p.coerceAtLeast(0.03f)).height(8.dp).background(col))
        }
        Text("coin-toss ← how sure → certain", color = Palette.muted, fontSize = 10.sp)
    }
}

@Composable
private fun TimerRing(left: Int, total: Int, c: Color) {
    val p by animateFloatAsState(left.toFloat() / total.coerceAtLeast(1), tween(900), label = "t")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val st = 7.dp.toPx()
                drawArc(Color(0x22FFFFFF), 0f, 360f, false, Offset(st / 2, st / 2), Size(size.width - st, size.height - st), style = Stroke(st))
                drawArc(c, -90f, 360f * p, false, Offset(st / 2, st / 2), Size(size.width - st, size.height - st), style = Stroke(st, cap = StrokeCap.Round))
            }
            Text("$left", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
        Text("seconds — let the reaction finish", color = Palette.muted, fontSize = 13.sp)
    }
}

// ── Lab validation (colour tests) ───────────────────────────────────────────

private fun calFrom(o: JSONObject): List<Pair<Double, Double>>? = o.optJSONArray("cal")?.let { a -> List(a.length()) { i -> a.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } } }

@Composable
private fun LabValidation(t: Dart.Test, store: JSONObject, save: (JSONObject) -> Unit, enabled: Boolean, add: (Double) -> Unit) {
    val pts = calFrom(store) ?: emptyList()
    val cal = Dart.calibrate(pts)
    Fold("Lab mode · prove the accuracy" + (cal?.let { " · R² ${fmt(it.r2)}" } ?: ""), Color(0xFF818CF8)) {
        Note("Spike known amounts (e.g. 0, 1, 2, 5, 10 ${t.unit}), run the test on each, and Shuddh fits a dose curve. Each point is then predicted from the others (leave-one-out), so the error shown is honest.")
        val levels = when (t.id) { "starch_milk" -> listOf(0.0, 1.0, 2.0, 5.0, 10.0); "urea_milk" -> listOf(0.0, 0.2, 0.5, 1.0); else -> listOf(0.0, 5.0, 15.0, 30.0) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            levels.forEach { v -> Btn(fmt(v), Modifier.weight(1f), primary = false, enabled = enabled) { add(v) } }
        }
        if (pts.isNotEmpty()) {
            ValidationChart(cal, pts)
            cal?.let { Note("${it.n} samples · R² ${fmt(it.r2)} · average error ±${fmt(it.mae)} ${t.unit} (leave-one-out)", if (it.r2 > 0.9) Palette.accent else Palette.amber) }
                ?: Note("Add at least 3 samples at 2+ different levels.", Palette.amber)
            Btn("Clear lab data", Modifier.fillMaxWidth(), primary = false) { save(JSONObject(store.toString()).apply { remove("cal") }) }
        }
    }
}

/** Measured vs actual (leave-one-out predictions) with the y = x line. */
@Composable
private fun ValidationChart(cal: Dart.Calibration?, pts: List<Pair<Double, Double>>) {
    val loo = cal?.loo ?: emptyList()
    val mx = (pts.maxOf { it.first } * 1.15).coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x33000000))) {
        val pad = 20f
        fun X(v: Double) = (pad + v / mx * (size.width - 2 * pad)).toFloat()
        fun Y(v: Double) = (size.height - pad - v / mx * (size.height - 2 * pad)).toFloat()
        for (k in 1..3) drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
        drawLine(Color.White.copy(alpha = 0.5f), Offset(X(0.0), Y(0.0)), Offset(X(mx), Y(mx)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
        loo.forEach { (a, p) -> drawCircle(Color(0xFF818CF8), 9f, Offset(X(a), Y(p))); drawCircle(Color.White, 9f, Offset(X(a), Y(p)), style = Stroke(2f)) }
    }
    Text("x: amount you added · y: amount Shuddh measured · dashed: perfect", color = Palette.muted, fontSize = 11.sp)
}

// ── Foam helpers ─────────────────────────────────────────────────────────────

/** Bubble texture: mean absolute Laplacian of luma in the box, relative to brightness (lighting-invariant). */
private fun texture(bmp: Bitmap, r: RectF): Double {
    val x0 = (r.left * bmp.width).toInt(); val y0 = (r.top * bmp.height).toInt()
    val w = ((r.right - r.left) * bmp.width).toInt().coerceAtLeast(4); val h = ((r.bottom - r.top) * bmp.height).toInt().coerceAtLeast(4)
    val px = IntArray(w * h); bmp.getPixels(px, 0, w, x0, y0, w, h)
    fun g(x: Int, y: Int) = ((px[y * w + x] shr 8) and 0xff).toDouble()
    var lap = 0.0; var sum = 0.0; var n = 0
    for (y in 1 until h - 1 step 2) for (x in 1 until w - 1 step 2) {
        lap += abs(4 * g(x, y) - g(x - 1, y) - g(x + 1, y) - g(x, y - 1) - g(x, y + 1)); sum += g(x, y); n++
    }
    return if (n == 0 || sum <= 0) 0.0 else 100 * lap / sum
}

private fun bin(xs: List<Pair<Double, Double>>, dt: Double): List<Pair<Double, Double>> =
    xs.groupBy { (it.first / dt).toInt() }.toSortedMap().map { (k, v) -> (k * dt) to v.map { it.second }.sorted()[v.size / 2] }

private fun seriesJson(s: List<Pair<Double, Double>>) = JSONArray(s.map { JSONArray(listOf(it.first, it.second)) })
private fun seriesFrom(a: JSONArray?): List<Pair<Double, Double>> = a?.let { List(it.length()) { i -> it.getJSONArray(i).let { p -> p.getDouble(0) to p.getDouble(1) } } } ?: emptyList()

@Composable
private fun FoamChart(control: List<Pair<Double, Double>>, now: List<Pair<Double, Double>>, c: Color) {
    Glass(padding = 14) {
        Text("Foam left over time", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val norm = { s: List<Pair<Double, Double>> -> val st = s.take(4).map { it.second }.average().takeIf { it > 0 } ?: 1.0; s.map { it.first to (it.second / st) } }
        val a = norm(control); val b = norm(now)
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            fun X(t: Double) = (t / 60.0 * size.width).toFloat()
            fun Y(v: Double) = (size.height - (v / 1.2).coerceIn(0.0, 1.0) * size.height).toFloat()
            for (k in 1..3) drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
            a.zipWithNext().forEach { (p, q) -> drawLine(Color.White.copy(alpha = 0.6f), Offset(X(p.first), Y(p.second)), Offset(X(q.first), Y(q.second)), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))) }
            b.zipWithNext().forEach { (p, q) -> drawLine(c, Offset(X(p.first), Y(p.second)), Offset(X(q.first), Y(q.second)), 5f, cap = StrokeCap.Round) }
        }
        Text("dashed: pure milk · colour: this sample — a line that stays high = stable (detergent) foam", color = Palette.muted, fontSize = 11.sp)
    }
}


// ── Lactometer UI ────────────────────────────────────────────────────────────

@Composable
internal fun Stepper(title: String, value: String, hint: String, c: Color, change: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, color = Palette.muted, fontSize = 11.sp)
        }
        Text("−", color = Palette.text, fontSize = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0x14FFFFFF)).clickable { change(-1) }.padding(top = 4.dp))
        Text(value, color = c, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(92.dp))
        Text("+", color = Palette.text, fontSize = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0x14FFFFFF)).clickable { change(1) }.padding(top = 4.dp))
    }
}

/** SNF against the legal minimum, with the implied added water. */
@Composable
private fun SnfGauge(snf: Double, min: Double, water: Double, c: Color) {
    val p by animateFloatAsState(((snf - 6.0) / 4.0).toFloat().coerceIn(0f, 1f), tween(500), label = "snf")
    val m = ((min - 6.0) / 4.0).toFloat()
    val ok = snf >= min
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("SNF ${String.format(java.util.Locale.US, "%.2f", snf)}%", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.weight(1f))
            Text(if (ok) "meets standard" else "≈ ${one(water)}% water", color = if (ok) Palette.accent else if (water < 10) Palette.amber else Palette.red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            drawRoundRect(Color(0x22FFFFFF), cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f))
            drawRoundRect(if (ok) Color(0xFF34D399) else if (water < 10) Color(0xFFFBBF24) else Color(0xFFF43F5E), size = Size(size.width * p, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f))
            drawLine(Color.White, Offset(size.width * m, -4f), Offset(size.width * m, size.height + 4f), 4f)
        }
        Text("White line: FSSAI minimum ${fmt(min)} %", color = Palette.muted, fontSize = 11.sp)
    }
}

// ── Milk report card + FSSAI complaint ───────────────────────────────────────

private val milkTests = listOf("water_milk", "starch_milk", "urea_milk", "detergent_milk")

/** Combines the milk tests run in the last 6 hours into one certificate. */
@Composable
private fun MilkReportCard(app: AppState) {
    val now = System.currentTimeMillis()
    val rows = milkTests.map { id -> Dart.test(id) to app.prefs.json("dart_$id")?.optJSONObject("last")?.takeIf { now - it.optLong("at") < 6 * 3600_000L } }
    val done = rows.filter { it.second != null }
    if (done.isEmpty()) return
    val worst = done.maxOf { runCatching { Level.valueOf(it.second!!.getString("level")) }.getOrDefault(Level.INCONCLUSIVE).ordinal }
    val bad = done.any { it.second!!.getString("level") == Level.UNSAFE.name }
    val col = if (bad) Palette.red else if (done.any { it.second!!.getString("level") == Level.CAUTION.name }) Palette.amber else Palette.accent
    Glass(glow = col, padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MILK REPORT CARD" + (app.prefs.json("dart_vendor")?.optString("name")?.takeIf { it.isNotBlank() }?.let { " · ${it.uppercase()}" } ?: ""), color = col, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text(if (bad) "Adulterated" else if (done.size < milkTests.size) "${done.size} of ${milkTests.size} tests done" else "Passed all tests", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            Text("${done.size}/${milkTests.size}", color = col, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 26.sp)
        }
        rows.forEach { (t, last) ->
            val lv = last?.let { runCatching { Level.valueOf(it.getString("level")) }.getOrNull() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t.emoji, fontSize = 18.sp)
                Text(t.title, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(last?.getString("label") ?: "not tested", color = lv?.let { Color(it.argb) } ?: Palette.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Seal certificate", Modifier.weight(1f)) {
                val lvl = Level.entries[worst]
                app.show(Outcome(
                    "Shuddh DART", "milk_panel", Txt("Milk report card"), done.size.toDouble(), "tests", lvl,
                    done.joinToString(" · ") { "${it.first.title}: ${it.second!!.getString("label")}" },
                    listOf(Txt(if (bad) "Keep the sample, stop buying from this source, and report it." else "This milk passed the tests you ran.")),
                    done.map { (t, l) -> Evidence("OBSERVATION", "${t.title}: ${l!!.getString("label")} — ${l.optString("detail")}", l.getString("level") == Level.SAFE.name) },
                    "Combined FSSAI DART home tests, each read by the phone and sealed.", levelLabel = Txt(if (bad) "ADULTERATED" else "PASSED"),
                ))
            }
            if (bad) Btn("📣 Report", Modifier.weight(1f), primary = false) {
                reportToFssai(app.ctx, app.prefs.json("dart_vendor")?.optString("name").orEmpty(), done.map { (t, l) -> t to Outcome("Shuddh DART", t.id, Txt(t.title), null, "", runCatching { Level.valueOf(l!!.getString("level")) }.getOrDefault(Level.INCONCLUSIVE), l!!.optString("detail", l.getString("label")), emptyList(), emptyList()) })
            }
        }
    }
}

/** Drafts a complaint with the sealed readings and opens the share sheet (WhatsApp, email, FSSAI app). */
private fun reportToFssai(ctx: Context, vendor: String, items: List<Pair<Dart.Test, Outcome>>) {
    val time = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("en", "IN")).format(java.util.Date())
    val body = buildString {
        appendLine("Food adulteration complaint")
        appendLine("Date/time of test: $time")
        appendLine("Product: Milk / food sample (vendor: ${vendor.ifBlank { "________" }}, area: ________)")
        appendLine()
        appendLine("Home test results (FSSAI DART methods, read and recorded with the Shuddh app):")
        items.forEach { (t, o) -> appendLine("• ${t.title}: ${o.headline}  [${t.fssai.substringBefore(" —")}]") }
        appendLine()
        appendLine("The sample has been kept. I request the Food Safety Officer to collect and test it.")
        appendLine("FSSAI grievance portal: https://foscos.fssai.gov.in")
    }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Food adulteration complaint — $time")
        putExtra(android.content.Intent.EXTRA_TEXT, body)
    }
    ctx.startActivity(android.content.Intent.createChooser(send, "Report to FSSAI").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun controlName(t: Dart.Test) = when (t.id) { "starch_milk" -> "pure milk + iodine"; "urea_milk" -> "pure milk + soybean on red litmus"; else -> "potato + lemon, no salt" }

/** Luma coefficient of variation inside a box — high means the box isn't filled by one uniform spot. */
private fun lumaCv(bmp: Bitmap, r: RectF): Double {
    val x0 = (r.left * bmp.width).toInt(); val y0 = (r.top * bmp.height).toInt()
    val w = ((r.right - r.left) * bmp.width).toInt().coerceAtLeast(2); val h = ((r.bottom - r.top) * bmp.height).toInt().coerceAtLeast(2)
    val px = IntArray(w * h); bmp.getPixels(px, 0, w, x0, y0, w, h)
    var s = 0.0; var s2 = 0.0; var n = 0
    for (i in px.indices step 3) { val c = px[i]; val l = 0.299 * ((c shr 16) and 0xff) + 0.587 * ((c shr 8) and 0xff) + 0.114 * (c and 0xff); s += l; s2 += l * l; n++ }
    val m = s / n; return if (m < 1) 1.0 else sqrt((s2 / n - m * m).coerceAtLeast(0.0)) / m
}

/** The problem in three lines — no unverified statistics. */
@Composable
private fun PainCard() {
    Glass(padding = 16) {
        Text("WHY THIS MATTERS", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        listOf(
            "🥛" to "Most homes buy milk from a milkman or loose — it reaches the kitchen untested.",
            "👁" to "Water, starch, urea and detergent are invisible. FSSAI's home tests exist, but people judge them by eye.",
            "🧾" to "A by-eye result leaves no proof. Shuddh measures it, seals it, and drafts the complaint.",
        ).forEach { (e, t) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(e, fontSize = 16.sp)
                Text(t, color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Who sold this milk — goes on the report card and into the complaint. */
@Composable
private fun VendorField(app: AppState) {
    var name by remember { mutableStateOf(app.prefs.json("dart_vendor")?.optString("name").orEmpty()) }
    androidx.compose.material3.OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(40); app.prefs.putJson("dart_vendor", JSONObject().put("name", name)) },
        label = { Text("Milkman / brand being tested") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = Palette.text, unfocusedTextColor = Palette.text,
            focusedBorderColor = Color(0xFF818CF8), unfocusedBorderColor = Color(0x33FFFFFF),
            focusedLabelColor = Color(0xFF818CF8), unfocusedLabelColor = Palette.muted,
        ),
    )
}

internal fun one(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

@Composable
private fun ToolCard(emoji: String, title: String, sub: String, c: Color, modifier: Modifier, onTap: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Brush.verticalGradient(listOf(c.copy(alpha = 0.22f), Color(0x0CFFFFFF))))
            .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).clickable { onTap() }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 24.sp) }
        Text(title, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(sub, color = Palette.muted, fontSize = 11.sp)
    }
}

/** % of the box covered by bright, saturated dye relative to the paper (paper = median of the brightest half). */
private fun dyeArea(bmp: Bitmap, r: RectF): Double {
    val x0 = (r.left * bmp.width).toInt(); val y0 = (r.top * bmp.height).toInt()
    val w = ((r.right - r.left) * bmp.width).toInt().coerceAtLeast(4); val h = ((r.bottom - r.top) * bmp.height).toInt().coerceAtLeast(4)
    val px = IntArray(w * h); bmp.getPixels(px, 0, w, x0, y0, w, h)
    val sample = px.filterIndexed { i, _ -> i % 3 == 0 }
    val bright = sample.sortedByDescending { ((it shr 16) and 0xff) + ((it shr 8) and 0xff) + (it and 0xff) }.take(sample.size / 2)
    fun med(f: (Int) -> Int) = bright.map(f).sorted()[bright.size / 2].toDouble()
    val pr = med { (it shr 16) and 0xff }; val pg = med { (it shr 8) and 0xff }; val pb = med { it and 0xff }
    val n = sample.count { Dart.isDye(((it shr 16) and 0xff).toDouble(), ((it shr 8) and 0xff).toDouble(), (it and 0xff).toDouble(), pr, pg, pb) }
    return 100.0 * n / sample.size.coerceAtLeast(1)
}
