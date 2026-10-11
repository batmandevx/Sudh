package com.shuddh.lab.ui

import android.graphics.Bitmap
import android.hardware.Sensor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.Agro
import com.shuddh.lab.core.Farm
import com.shuddh.lab.core.FarmTwin
import com.shuddh.lab.core.FieldKit
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.LeafDoctor
import com.shuddh.lab.core.Livestock
import com.shuddh.lab.core.Plan
import com.shuddh.lab.core.Schemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

// ── Leaf Doctor ────────────────────────────────────────────────────────────────────────────────

@Composable
fun LeafDoctorTab(app: AppState) {
    val cam = remember { CameraHandle() }
    val scope = rememberCoroutineScope()
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var live by remember { mutableStateOf<Bitmap?>(null) }
    var guesses by remember { mutableStateOf<List<LeafDoctor.Guess>>(emptyList()) }
    var health by remember { mutableStateOf<LeafDoctor.Health?>(null) }
    var busy by remember { mutableStateOf(false) }
    var noLeaf by remember { mutableStateOf(false) }
    val box = remember { android.graphics.RectF(0.15f, 0.1f, 0.85f, 0.9f) }

    fun analyse(b: Bitmap) = scope.launch {
        busy = true; shot = b; guesses = emptyList(); health = null
        val r = withContext(Dispatchers.Default) { runCatching { LeafDoctor.classify(app.ctx, b) }.getOrDefault(emptyList()) to LeafDoctor.health(b) }
        delay(900) // let the scan animation play once
        // No leaf in view (dark, sky or a wall): don't show a confident-looking guess.
        if (r.second.leafShare < 0.12) { guesses = emptyList(); health = null; busy = false; noLeaf = true; return@launch }
        noLeaf = false
        guesses = r.first; health = r.second; busy = false
        Haptics.click(app.ctx)
        r.first.firstOrNull()?.let { g -> app.voice.say(if (g.label.kind == LeafDoctor.Kind.HEALTHY) "${g.label.crop} leaf looks healthy" else "${g.label.crop}: likely ${g.label.problem}", app.lang) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { u -> runCatching { app.ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()?.let { analyse(it) } }
    }

    Glass(glow = Color(0xFF16A34A), padding = 14) {
        Text("🍃 Leaf Doctor", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
        Text("Snap one leaf against the sky or a plain cloth. The on-phone AI names the disease (80 crop problems) and the camera measures how much of the leaf is damaged.", color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
        if (shot == null) {
            CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.9f, overlay = { roi(box, Color(0xFF22C55E)) }) { bmp -> live = bmp }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("📷 Diagnose", Modifier.weight(1f), enabled = live != null) {
                    live?.let { b ->
                        val x = (box.left * b.width).toInt(); val y = (box.top * b.height).toInt()
                        analyse(Bitmap.createBitmap(b, x, y, ((box.right - box.left) * b.width).toInt(), ((box.bottom - box.top) * b.height).toInt()))
                    }
                }
                Btn(if (cam.torchOn) "🔦 Off" else "🔦", primary = false) { cam.torch(!cam.torchOn) }
                Btn("🖼", primary = false) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
        } else {
            ScanImage(shot!!, busy)
            if (noLeaf) Text("No leaf found in the photo — fill the green box with one leaf in good light (use 🔦 in the dark).", color = Palette.amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Btn("New leaf", Modifier.fillMaxWidth(), primary = false) { shot = null; guesses = emptyList(); health = null; noLeaf = false }
        }
    }
    if (guesses.isNotEmpty()) {
        val top = guesses.first()
        val c = if (top.label.kind == LeafDoctor.Kind.HEALTHY) Palette.accent else if (top.p > 0.6f) Palette.red else Palette.amber
        Glass(glow = c, padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(top.label.kind.emoji, fontSize = 34.sp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${top.label.crop} · ${top.label.problem}", color = c, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text("${top.label.kind.label} · ${(top.p * 100).toInt()}% sure" + if (top.p < 0.5f) " — check the other two below" else "", color = Palette.muted, fontSize = 12.sp)
                }
            }
            guesses.forEach { g -> ConfidenceBar("${g.label.crop} · ${g.label.problem}", g.p, if (g == top) c else Palette.muted) }
            health?.let { h ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DamageRing(h, Modifier.size(78.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Leaf damage: ${h.severity}", color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Green ${(h.green * 100).toInt()}% · yellow ${(h.yellow * 100).toInt()}% · brown ${(h.brown * 100).toInt()}%", color = Palette.muted, fontSize = 11.sp)
                        if (h.leafShare < 0.25) Text("Leaf fills little of the photo — move closer for a better reading.", color = Palette.amber, fontSize = 11.sp)
                    }
                }
            }
        }
        val (org, chem) = LeafDoctor.treatment(top.label)
        Glass(glow = Palette.accent, padding = 14) {
            Text("What to do", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            org.forEach { Text("🌿 $it", color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp) }
            chem.forEach { Text("🧴 $it", color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp) }
            Note("Tested on 60 reference leaf photos: right first guess 85%, within top three 98%. Field photos are harder — confirm with your KVK before spraying.")
        }
    }
}

@Composable
private fun ScanImage(b: Bitmap, scanning: Boolean) {
    val inf = rememberInfiniteTransition(label = "scan")
    val y by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "y")
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))) {
        Image(b.asImageBitmap(), "leaf", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (scanning) Canvas(Modifier.fillMaxSize()) {
            val yy = size.height * y
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x8822C55E), Color.Transparent), yy - 60f, yy + 10f), Offset(0f, yy - 60f), Size(size.width, 70f))
            drawLine(Color(0xFF4ADE80), Offset(0f, yy), Offset(size.width, yy), 3f)
            val step = size.width / 8
            for (i in 1 until 8) { drawLine(Color.White.copy(alpha = 0.12f), Offset(step * i, 0f), Offset(step * i, size.height), 1f); drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, step * i), Offset(size.width, step * i), 1f) }
        }
    }
}

@Composable
fun ConfidenceBar(label: String, p: Float, c: Color) {
    val a by animateFloatAsState(p, tween(900, easing = FastOutSlowInEasing), label = "cb")
    Column {
        Row { Text(label, color = Palette.text, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("${(p * 100).toInt()}%", color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.veil(0x12))) {
            Box(Modifier.fillMaxWidth(a.coerceIn(0.01f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c))
        }
    }
}

@Composable
private fun DamageRing(h: LeafDoctor.Health, modifier: Modifier) {
    val a by animateFloatAsState(1f, tween(1100), label = "dr")
    Canvas(modifier) {
        val st = 10.dp.toPx(); val o = Offset(st / 2, st / 2); val s = Size(size.width - st, size.height - st)
        var start = -90f
        listOf(h.green to Color(0xFF22C55E), h.yellow to Color(0xFFEAB308), h.brown to Color(0xFF92400E)).forEach { (v, c) ->
            val sweep = (360 * v * a).toFloat(); drawArc(c, start, sweep, false, o, s, style = Stroke(st)); start += sweep
        }
    }
}

// ── Herd & animal health ───────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HerdTab(app: AppState) {
    val store = remember { com.shuddh.lab.core.HerdStore(app.ctx) }
    var herd by remember { mutableStateOf(store.herd) }
    fun save(h: List<Livestock.Animal>) { herd = h; store.save(h) }
    var species by remember { mutableStateOf(Livestock.Species.COW) }
    val picked = remember { mutableStateListOf<String>() }
    var bpm by remember { mutableStateOf<Int?>(null) }

    Glass(glow = Color(0xFFF59E0B), padding = 14) {
        Text("🐄 Animal health check", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
        Text("Tap what you see. Built from standard veterinary signs — it tells you how urgent it is, not a final diagnosis.", color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Livestock.Species.entries.forEach { s -> Chip("${s.emoji} ${s.label}", species == s) { species = s } } }
        Livestock.signs.groupBy { it.group }.forEach { (g, list) ->
            Text(g, color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEach { s -> Chip(s.label, s.key in picked) { if (s.key in picked) picked.remove(s.key) else picked.add(s.key) } }
            }
        }
    }
    BreathMeter(app, species) { bpm = it }
    val matches = Livestock.check(species, picked.toSet(), bpm)
    if (matches.isNotEmpty()) Glass(glow = if (matches.first().disease.urgency >= 3) Palette.red else Palette.amber, padding = 14) {
        Text("Most likely", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        matches.forEachIndexed { i, m ->
            val c = when (m.disease.urgency) { 3 -> Palette.red; 2 -> Palette.amber; else -> Palette.blue }
            Column(Modifier.fillMaxWidth().enter(i).clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.08f)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ConfidenceBar(m.disease.name, m.pct / 100f, c)
                if (i == 0 || m.pct > 25) {
                    Text((if (m.disease.urgency >= 3) "🚨 " else "⚠️ ") + m.disease.action, color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp)
                    Text("Prevent: ${m.disease.prevent}", color = Palette.muted, fontSize = 11.sp)
                    if (!m.disease.milkSafe) Text("🥛 Don't sell or drink this animal's milk until the vet clears it.", color = Palette.red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Note("Emergency signs or more than one sick animal: call the vet / 1962 animal helpline (in many states) now.")
    }
    HerdBook(herd) { save(it) }
}

/** Breathing rate from the camera watching the animal's flank rise and fall. */
@Composable
private fun BreathMeter(app: AppState, species: Livestock.Species, onBpm: (Int?) -> Unit) {
    val cam = remember { CameraHandle() }
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<Pair<Int, Double>?>(null) }
    val samples = remember { mutableListOf<Pair<Long, Double>>() }
    val box = remember { android.graphics.RectF(0.25f, 0.3f, 0.75f, 0.7f) }
    var wave by remember { mutableStateOf(listOf<Double>()) }
    Glass(glow = Palette.cyan, padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📷 Breathing rate", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Point at the flank (belly side) from 2 m for 30 s. Normal ${species.label.lowercase()}: ${species.breathLo}–${species.breathHi}/min.", color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp)
            }
            if (!open) Btn("Measure", primary = false) { open = true }
        }
        if (open) {
            CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.8f, overlay = { roi(box, Palette.cyan) }) { bmp ->
                if (!recording) return@CameraView
                val x0 = (box.left * bmp.width).toInt(); val y0 = (box.top * bmp.height).toInt(); val w = ((box.right - box.left) * bmp.width).toInt(); val h = ((box.bottom - box.top) * bmp.height).toInt()
                val px = IntArray(w * h); bmp.getPixels(px, 0, w, x0, y0, w, h)
                // Flank movement shifts the brightness edge; the vertical brightness centroid tracks it.
                var sw = 0.0; var sy = 0.0
                for (yy in 0 until h step 2) for (xx in 0 until w step 4) { val c = px[yy * w + xx]; val l = (((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff)) / 3.0; sw += l; sy += l * yy }
                synchronized(samples) { samples += System.currentTimeMillis() to (if (sw > 0) sy / sw else 0.0) }
            }
            if (wave.isNotEmpty()) Canvas(Modifier.fillMaxWidth().height(50.dp)) {
                val mn = wave.min(); val mx = wave.max().coerceAtLeast(mn + 1e-6)
                for (i in 1 until wave.size) drawLine(Palette.cyan, Offset(size.width * (i - 1) / wave.size, (size.height * (1 - (wave[i - 1] - mn) / (mx - mn))).toFloat()),
                    Offset(size.width * i / wave.size, (size.height * (1 - (wave[i] - mn) / (mx - mn))).toFloat()), 3f, StrokeCap.Round)
            }
            Btn(if (recording) "Hold steady… $left s" else "▶ Start 30 s", Modifier.fillMaxWidth(), enabled = !recording) {
                synchronized(samples) { samples.clear() }; recording = true; result = null
                scope.launch {
                    for (s in 30 downTo 1) { left = s; delay(1000); wave = synchronized(samples) { samples.takeLast(120).map { it.second } } }
                    recording = false
                    val xs = synchronized(samples) { samples.toList() }
                    val fps = if (xs.size > 2) (xs.size - 1) * 1000.0 / (xs.last().first - xs.first().first).coerceAtLeast(1) else 0.0
                    result = Livestock.breathRate(xs.map { it.second }, fps)
                    onBpm(result?.first); Haptics.click(app.ctx)
                }
            }
        }
        result?.let { (b, q) ->
            Text("$b breaths/min" + if (q < 0.3) " (weak signal — hold the phone still)" else "", color = if (b > species.breathHi) Palette.red else Palette.accent, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text(Livestock.breathVerdict(species, b), color = Palette.text, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HerdBook(herd: List<Livestock.Animal>, onSave: (List<Livestock.Animal>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var sp by remember { mutableStateOf(Livestock.Species.COW) }
    val today = LocalDate.now().toEpochDay()
    Glass(glow = Palette.blue, padding = 14) {
        Text("📒 Herd book", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        val dues = Livestock.dues(herd).filter { it.inDays <= 30 }.take(6)
        if (dues.isNotEmpty()) {
            Text("Coming up", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            dues.forEach { d ->
                Text("${d.what} — ${d.animal.name} · ${if (d.inDays <= 0) "due now" else "in ${d.inDays} days"}", color = if (d.inDays <= 0) Palette.red else Palette.text, fontSize = 12.sp)
            }
        }
        herd.forEach { a ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.veil(0x0A)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${a.species.emoji} ${a.name}", color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Livestock.milkTrend(a)?.let { tr -> Text(if (tr < -0.15) "🥛 ${(tr * 100).toInt()}% — check udder" else "🥛 ${if (tr >= 0) "+" else ""}${(tr * 100).toInt()}%", color = if (tr < -0.15) Palette.red else Palette.muted, fontSize = 11.sp) }
                    Text("✕", color = Palette.muted, modifier = Modifier.clickable { onSave(herd - a) }.padding(start = 8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("💉 Vaccinated today", false) {
                        onSave(herd.map { if (it.id == a.id) it.copy(vaccines = it.vaccines + Livestock.vaccines.filter { v -> a.species in v.species && Livestock.dues(listOf(a)).any { d -> d.what.contains(v.label) && d.inDays <= 7 } }.associate { v -> v.key to today }) else it })
                    }
                    Chip("❤️ Bred today", false) { onSave(herd.map { if (it.id == a.id) it.copy(bred = today) else it }) }
                    Chip("🥛 +Milk", false) { onSave(herd.map { if (it.id == a.id) it.copy(milk = (it.milk.filter { m -> m.first != today } + (today to ((it.milk.lastOrNull { m -> m.first == today }?.second ?: 0.0) + 1.0))).takeLast(60)) else it }) }
                }
                a.milk.lastOrNull { it.first == today }?.let { Text("Today: ${it.second} L logged", color = Palette.muted, fontSize = 11.sp) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.text.BasicTextField(name, { name = it }, singleLine = true,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Palette.veil(0x0C)).padding(10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 14.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.cyan),
                decorationBox = { inner -> Box { if (name.isEmpty()) Text("Animal name / tag", color = Palette.muted, fontSize = 14.sp); inner() } })
            Text(sp.emoji, fontSize = 22.sp, modifier = Modifier.clickable { sp = Livestock.Species.entries[(sp.ordinal + 1) % Livestock.Species.entries.size] })
            Btn("Add", enabled = name.isNotBlank()) { onSave(herd + Livestock.Animal(System.currentTimeMillis(), name.trim(), sp, "", LocalDate.now().year, true)); name = "" }
        }
        Note("Vaccination dates follow the national schedule: FMD every 6 months, HS/BQ before the monsoon, deworming every ~3 months.")
    }
}

// ── Field Kit: every sensor, measuring the field ──────────────────────────────────────────────

@Composable
fun FieldKitTab(app: AppState, farm: Farm, onFarm: (Farm) -> Unit) {
    WalkArea(app, farm, onFarm)
    SlopeCard()
    CompassCard()
    SunCard()
    CanopyCard()
    FieldWeatherCard(farm)
    Glass(glow = Palette.cyan, padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🔊 Soil moisture sonar", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Nami uses the speaker and microphone as sonar to read soil wetness.", color = Palette.muted, fontSize = 11.sp)
            }
            Btn("Open", primary = false) { app.go(Screen.NAMI) }
        }
    }
}

@Composable
private fun SensorCard(title: String, sub: String, glow: Color, content: @Composable () -> Unit) {
    Glass(glow = glow, padding = 14) {
        Text(title, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(sub, color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp)
        content()
    }
}

@Composable
private fun WalkArea(app: AppState, farm: Farm, onFarm: (Farm) -> Unit) {
    val pts = remember { mutableStateListOf<Pair<Double, Double>>() }
    var walking by remember { mutableStateOf(false) }
    var acc by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(walking) {
        if (!walking) return@LaunchedEffect
        app.geo.start()
        while (walking) {
            app.geo.lastKnown()?.let { f ->
                acc = f.accuracy
                val p = f.lat to f.lon
                if (f.accuracy < 20 && (pts.isEmpty() || FieldKit.distM(pts.last(), p) > 3)) { pts += p; Haptics.click(app.ctx) }
            }
            delay(1000)
        }
    }
    val area = FieldKit.areaM2(pts); val acres = area / FieldKit.M2_PER_ACRE
    SensorCard("🛰 Walk the boundary (GPS)", "Walk around the field edge; corners are marked every few metres. Closes automatically into an area.", Palette.accent) {
        Canvas(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)).background(Palette.well(0x22))) {
            if (pts.size >= 2) {
                val la = pts.map { it.first }; val lo = pts.map { it.second }
                val sx = (lo.max() - lo.min()).coerceAtLeast(1e-6); val sy = (la.max() - la.min()).coerceAtLeast(1e-6)
                val sc = minOf(size.width / sx, size.height / sy) * 0.85
                fun p(q: Pair<Double, Double>) = Offset((size.width / 2 + (q.second - (lo.max() + lo.min()) / 2) * sc).toFloat(), (size.height / 2 - (q.first - (la.max() + la.min()) / 2) * sc).toFloat())
                val path = androidx.compose.ui.graphics.Path().apply { moveTo(p(pts[0]).x, p(pts[0]).y); pts.drop(1).forEach { lineTo(p(it).x, p(it).y) }; close() }
                drawPath(path, Color(0x3322C55E)); drawPath(path, Color(0xFF22C55E), style = Stroke(4f))
                pts.forEach { drawCircle(Color.White, 5f, p(it)); drawCircle(Color(0xFF16A34A), 3f, p(it)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${"%.2f".format(acres)} acres", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text("${area.toInt()} m² · ${"%.2f".format(acres / FieldKit.ACRES_PER_BIGHA)} bigha · ${pts.size} points" + (acc?.let { " · GPS ±${it.toInt()} m" } ?: ""), color = Palette.muted, fontSize = 11.sp)
            }
            Btn(if (walking) "■ Stop" else "▶ Walk", primary = !walking) { if (!walking) pts.clear(); walking = !walking }
        }
        if (!walking && acres > 0.05) Btn("Use ${"%.2f".format(acres)} acres for my farm", Modifier.fillMaxWidth(), primary = false) {
            onFarm(farm.copy(acres = (acres * 100).toInt() / 100.0, lat = pts.map { it.first }.average(), lon = pts.map { it.second }.average()))
        }
    }
}

@Composable
private fun SlopeCard() {
    val g = rememberSensor(Sensor.TYPE_GRAVITY) ?: rememberSensor(Sensor.TYPE_ACCELEROMETER)
    val slope = g?.let { FieldKit.slopePct(it[0], it[1], it[2]) } ?: 0.0
    val sm by animateFloatAsState(slope.toFloat().coerceAtMost(40f), tween(300), label = "sl")
    SensorCard("📐 Field slope (accelerometer)", "Lay the phone flat on the ground, along the slope.", Palette.amber) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(90.dp, 60.dp)) {
                val ang = Math.toDegrees(kotlin.math.atan(sm / 100.0)).toFloat()
                rotate(-ang, Offset(size.width / 2, size.height * 0.7f)) {
                    drawLine(Color(0xFF92400E), Offset(0f, size.height * 0.7f), Offset(size.width, size.height * 0.7f), 8f, StrokeCap.Round)
                    drawCircle(Color(0xFF38BDF8), 6f, Offset(size.width * 0.5f + ang * 2, size.height * 0.7f - 10f))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("${"%.1f".format(slope)}% slope", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(FieldKit.slopeAdvice(slope), color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun CompassCard() {
    val rv = rememberSensor(Sensor.TYPE_ROTATION_VECTOR)
    val heading = rv?.let {
        val m = FloatArray(9); val o = FloatArray(3)
        android.hardware.SensorManager.getRotationMatrixFromVector(m, it.copyOf(minOf(it.size, 5))); android.hardware.SensorManager.getOrientation(m, o)
        FieldKit.heading(o[0])
    } ?: 0
    val hd by animateFloatAsState(heading.toFloat(), tween(250), label = "hd")
    SensorCard("🧭 Row direction (compass)", "Point the phone along your planned crop rows.", Palette.violet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(80.dp)) {
                drawCircle(Palette.veil(0x14), size.minDimension / 2)
                rotate(-hd) {
                    drawLine(Color(0xFFE11D48), center, Offset(center.x, 6f), 5f, StrokeCap.Round)
                    drawLine(Palette.ink.copy(alpha = 0.5f), center, Offset(center.x, size.height - 6f), 5f, StrokeCap.Round)
                }
                drawCircle(Palette.ink, 4f, center)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("$heading°", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(FieldKit.rowAdvice(heading), color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun SunCard() {
    val l = rememberSensor(Sensor.TYPE_LIGHT, android.hardware.SensorManager.SENSOR_DELAY_UI)
    val lux = l?.get(0) ?: 0f
    val dli = FieldKit.dli(lux)
    val inf = rememberInfiniteTransition(label = "sun")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "r")
    SensorCard("☀️ Sunlight meter (light sensor)", "Hold the phone face-up at crop height at midday.", Color(0xFFF59E0B)) {
        if (l == null) Note("This phone has no light sensor.") else Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(70.dp)) {
                val k = (lux / 100000f).coerceIn(0.1f, 1f)
                rotate(rot) { for (i in 0 until 12) { val a = i * 30.0 * Math.PI / 180; drawLine(Color(0xFFFACC15), Offset(center.x + (20 * kotlin.math.cos(a)).toFloat(), center.y + (20 * kotlin.math.sin(a)).toFloat()), Offset(center.x + ((20 + 14 * k) * kotlin.math.cos(a)).toFloat(), center.y + ((20 + 14 * k) * kotlin.math.sin(a)).toFloat()), 4f, StrokeCap.Round) } }
                drawCircle(Color(0xFFFACC15), 16f, center)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("${"%,.0f".format(lux)} lux", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text("≈ ${FieldKit.ppfd(lux).toInt()} µmol/m²/s · ~${"%.0f".format(dli)} mol/m²/day", color = Palette.muted, fontSize = 11.sp)
                Text(FieldKit.lightAdvice(dli), color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun CanopyCard() {
    val cam = remember { CameraHandle() }
    var open by remember { mutableStateOf(false) }
    var c by remember { mutableStateOf<FieldKit.Canopy?>(null) }
    SensorCard("🌿 Crop vigour (camera)", "Point down at the crop from chest height. Measures green cover and leaf greenness (VARI).", Palette.accent) {
        if (!open) Btn("Measure", Modifier.fillMaxWidth(), primary = false) { open = true }
        else CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.8f) { bmp ->
            val s = Bitmap.createScaledBitmap(bmp, 96, 72, true); val px = IntArray(96 * 72); s.getPixels(px, 0, 96, 0, 0, 96, 72)
            c = FieldKit.canopy(px)
        }
        c?.let { k ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("${(k.cover * 100).toInt()}%", "green cover", Palette.accent, Modifier.weight(1f))
                StatTile("%.2f".format(k.vari), "VARI greenness", Palette.text, Modifier.weight(1f))
            }
            Text(k.words, color = Palette.text, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FieldWeatherCard(farm: Farm) {
    var now by remember { mutableStateOf<FieldKit.Now?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SensorCard("🌦 Spray window & disease risk", "Hourly wind, rain and humidity for your plot (next 3 days).", Palette.blue) {
        if (now == null) Btn(if (loading) "Loading…" else "Check now", Modifier.fillMaxWidth(), primary = false, enabled = farm.located && !loading) {
            loading = true; scope.launch { now = FieldKit.now(farm.lat, farm.lon); loading = false }
        }
        now?.let { n ->
            val win = FieldKit.sprayWindows(n.hours).take(3)
            Text(if (win.isEmpty()) "🚫 No safe spray window in 3 days (wind, rain or heat)." else "✅ Best spray times:", color = if (win.isEmpty()) Palette.red else Palette.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            win.forEach { Text("• ${FarmTwin.fmtDay(it.start.toLocalDate().toEpochDay())}, ${it.start.hour}:00 for ${it.hours} h", color = Palette.text, fontSize = 12.sp) }
            val bh = FieldKit.blightHours(n.hours)
            Text("🍄 ${FieldKit.diseaseRisk(bh)} ($bh humid hours)", color = if (bh >= 22) Palette.red else Palette.text, fontSize = 12.sp, lineHeight = 16.sp)
            n.hours.firstOrNull { it.soilMoist != null }?.soilMoist?.let { Text("Soil moisture (satellite model): ${"%.0f".format(it * 100)}% by volume", color = Palette.muted, fontSize = 11.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                n.aqi?.let { StatTile("$it", "air quality (US AQI)", if (it > 150) Palette.red else if (it > 100) Palette.amber else Palette.accent, Modifier.weight(1f)) }
                n.uv?.let { StatTile("%.0f".format(it), "UV index", if (it >= 8) Palette.red else Palette.text, Modifier.weight(1f)) }
            }
        }
        if (!farm.located) Note("Set your farm's location first.")
    }
}

// ── Crop calendar ──────────────────────────────────────────────────────────────────────────────

@Composable
fun CalendarTab(app: AppState, farm: Farm, plan: Plan?, st: TwinState) {
    val crop = Agro.crop(plan?.crop ?: "") ?: run { Note("Pick a crop first — the calendar is built from its simulation."); return }
    var tasks by remember { mutableStateOf<List<FieldKit.Task>>(emptyList()) }
    LaunchedEffect(plan, st.scenario, st.forecast.size) {
        tasks = withContext(Dispatchers.Default) { FieldKit.calendar(FarmTwin.simulate(farm, plan!!, st.wxFor(farm.lat)(plan.sowDay, (crop.days * 1.8).toInt()), st.scenario)) }
    }
    val today = LocalDate.now().toEpochDay()
    Glass(glow = Palette.accent, padding = 14) {
        Text("📅 ${crop.emoji} ${crop.name} — season plan", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Dates come from the twin's growth simulation; fertiliser doses are for ${"%.1f".format(plan!!.acres)} acres.", color = Palette.muted, fontSize = 11.sp)
    }
    tasks.forEachIndexed { i, t ->
        val past = t.day < today; val now = t.day in today..(today + 7)
        Row(Modifier.fillMaxWidth().enter(i.coerceAtMost(8)), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(36.dp)) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(if (now) Palette.accent else if (past) Palette.veil(0x18) else Palette.veil(0x0C)), contentAlignment = Alignment.Center) { Text(t.emoji, fontSize = 14.sp) }
                if (i < tasks.size - 1) Box(Modifier.width(2.dp).height(44.dp).background(Palette.veil(0x18)))
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title, color = if (past) Palette.muted else Palette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(FarmTwin.fmtDay(t.day), color = if (now) Palette.accent else Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(t.detail, color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp)
                if (!past) Text("＋ Remind me", color = Palette.cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                    val ms = LocalDate.ofEpochDay(t.day).atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    runCatching {
                        app.ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms).putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, ms + 3600_000)
                            .putExtra(android.provider.CalendarContract.Events.TITLE, "${crop.emoji} ${t.title}").putExtra(android.provider.CalendarContract.Events.DESCRIPTION, t.detail)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }.padding(vertical = 4.dp))
            }
        }
    }
}

// ── Government schemes & form helper ───────────────────────────────────────────────────────────

@Composable
fun SchemesTab(app: AppState, farm: Farm) {
    val herd = remember { com.shuddh.lab.core.HerdStore(app.ctx).herd.size }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val list = Schemes.all.sortedByDescending { it.fits(farm, herd) }
    Glass(glow = Palette.amber, padding = 14) {
        Text("🏛 Schemes you can claim", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("${list.count { it.fits(farm, herd) }} of ${list.size} central schemes match your farm profile. Your name and number stay on this phone.", color = Palette.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { SmallField("Your name", name) { name = it } }
            Box(Modifier.weight(1f)) { SmallField("Mobile", phone) { phone = it } }
        }
    }
    list.forEachIndexed { i, s ->
        var open by remember { mutableStateOf(false) }
        val fit = s.fits(farm, herd)
        Column(
            Modifier.fillMaxWidth().enter(i.coerceAtMost(8)).clip(RoundedCornerShape(18.dp))
                .background(Brush.horizontalGradient(listOf((if (fit) Palette.accent else Palette.muted).copy(alpha = 0.08f), Palette.surface)))
                .border(1.dp, if (fit) Palette.accent.copy(alpha = 0.4f) else Palette.line, RoundedCornerShape(18.dp)).clickable { open = !open }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.emoji, fontSize = 24.sp); Spacer(Modifier.width(8.dp))
                Text(s.name, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Tag(if (fit) "you likely qualify" else "check", if (fit) Palette.accent else Palette.muted)
            }
            Text(s.benefit, color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp)
            if (open) {
                Text("Who: ${s.who}", color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp)
                if (s.deadline.isNotBlank()) Text("⏰ ${s.deadline}", color = Palette.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                s.docs.forEach { Text("☐ $it", color = Palette.text, fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("📝 Fill my form", Modifier.weight(1f)) {
                        val text = Schemes.formText(s, farm, name, phone)
                        app.ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), s.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    Btn("Open portal", Modifier.weight(1f), primary = false) {
                        runCatching { app.ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(s.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallField(label: String, value: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(value, onChange, singleLine = true,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.veil(0x0C)).padding(10.dp),
        textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 13.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.cyan),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(label, color = Palette.muted, fontSize = 13.sp); inner() } })
}
