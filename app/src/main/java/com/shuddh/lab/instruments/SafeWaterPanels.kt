package com.shuddh.lab.instruments

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.provider.AlarmClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.Collector
import com.shuddh.lab.camera.Frames
import com.shuddh.lab.camera.Rgb
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.SafeWater
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

private val waterBlue = Color(0xFF38BDF8)

/** System timer (the phone's Clock app) so the reminder survives closing Shuddh. */
private fun systemTimer(app: AppState, seconds: Int, message: String) {
    val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtMost(86_400))
        .putExtra(AlarmClock.EXTRA_MESSAGE, message).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { app.ctx.startActivity(i) }
}

/** Text contrast in a box from percentiles of the green channel — works wherever the text sits in the box. */
private fun textContrast(bmp: Bitmap, r: RectF): Double {
    val x0 = (r.left * bmp.width).toInt(); val y0 = (r.top * bmp.height).toInt()
    val w = ((r.right - r.left) * bmp.width).toInt().coerceAtLeast(2); val h = ((r.bottom - r.top) * bmp.height).toInt().coerceAtLeast(2)
    val px = IntArray(w * h); bmp.getPixels(px, 0, w, x0, y0, w, h)
    val g = IntArray((px.size + 1) / 2) { (px[it * 2] shr 8) and 0xff }.sortedArray()
    val hi = g[(g.size * 0.92).toInt().coerceAtMost(g.size - 1)].toDouble(); val lo = g[(g.size * 0.06).toInt()].toDouble()
    return if (hi < 1) 0.0 else ((hi - lo) / hi).coerceIn(0.0, 1.0)
}

// ── Make water safe with bleach ─────────────────────────────────────────────

@Composable
fun BleachPanel(app: AppState, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cam = remember { CameraHandle() }
    val box = RectF(0.25f, 0.32f, 0.75f, 0.68f)
    val live = remember { doubleArrayOf(0.0) }
    val saved = app.prefs.json("safe_water") ?: JSONObject()
    var pct by remember { mutableStateOf(saved.optDouble("pct", 5.0)) }
    var litres by remember { mutableStateOf(saved.optDouble("litres", 1.0)) }
    var cloudy by remember { mutableStateOf(false) }
    var direct by remember { mutableStateOf<Double?>(null) }
    var clarity by remember { mutableStateOf<Double?>(null) }
    var showCam by remember { mutableStateOf(false) }
    val dose = SafeWater.bleachDose(litres, pct, cloudy)

    suspend fun sample(): Double { val xs = mutableListOf<Double>(); repeat(12) { xs += live[0]; delay(80) }; return xs.sorted()[6] }

    ScreenFrame("Make water safe", "Household bleach disinfection — US EPA emergency guidance", onBack = close) {
        Glass(glow = waterBlue, padding = 18) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DropsIcon(dose.drops)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${dose.drops} drops", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 34.sp)
                    Text("≈ ${String.format(java.util.Locale.US, "%.2f", dose.ml)} mL of ${one(pct)}% bleach for ${one(litres)} L of ${if (cloudy) "cloudy" else "clear"} water", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                    Text("Then wait ${dose.contactMin} minutes", color = waterBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Btn("⏱  Added it · start 30-min timer", Modifier.fillMaxWidth()) {
                systemTimer(app, dose.contactMin * 60, "Water: smell check — slight chlorine smell = safe to drink")
                app.prefs.putJson("safe_water", JSONObject().put("pct", pct).put("litres", litres))
                Haptics.click(app.ctx)
                app.voice.speak("Timer set for thirty minutes. Keep the water covered.", app.lang)
                app.show(Outcome(
                    "Shuddh Safe Water", "water_bleach", Txt("Water disinfection"), dose.drops.toDouble(), "drops", Level.SAFE,
                    "Treated ${fmt(litres)} L with ${dose.drops} drops of ${fmt(pct)}% bleach (${if (cloudy) "cloudy — double dose" else "clear"})",
                    listOf(Txt("Wait 30 minutes. A slight chlorine smell means it worked; if there's none, add the same dose again and wait 15 more minutes.")),
                    listOfNotNull(
                        Evidence("OBSERVATION", "Volume ${fmt(litres)} L, bleach ${fmt(pct)}% sodium hypochlorite, ${if (cloudy) "cloudy" else "clear"} water"),
                        clarity?.let { Evidence("QUALITY", "Camera clarity check: ${fmt(it * 100)}% of text contrast through the water", it >= SafeWater.CLOUDY_BELOW) },
                        Evidence("PATTERN", "US EPA: 2 drops per litre at 6% (scaled to ${fmt(pct)}%), doubled for cloudy water; 30 min contact"),
                    ),
                    "US EPA emergency disinfection guidance. Kills bacteria and viruses; does not remove chemicals such as arsenic, fluoride or pesticides.",
                    levelLabel = Txt("TREATED"),
                ))
            }
        }

        Glass(padding = 16) {
            Stepper("Bleach strength", "${one(pct)}%", "“sodium hypochlorite” % on the label", waterBlue) { d -> pct = (pct + d * 0.5).coerceIn(1.0, 15.0) }
            Stepper("Water", "${one(litres)} L", "bucket ≈ 15–20 L · bottle 1 L", waterBlue) { d -> litres = (litres + d * if (litres >= 5) 5.0 else 0.5).coerceIn(0.5, 200.0) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "Clear", true to "Cloudy").forEach { (v, t) ->
                    val sel = v == cloudy
                    Text(t, color = if (sel) Palette.text else Palette.muted, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) waterBlue.copy(alpha = 0.25f) else Palette.veil(0x10))
                            .border(1.dp, if (sel) waterBlue else Palette.veil(0x22), RoundedCornerShape(12.dp)).clickable { cloudy = v }.padding(vertical = 10.dp))
                }
            }
            Btn(if (showCam) "Hide camera" else "📷  Check clarity with camera", Modifier.fillMaxWidth(), primary = false) { showCam = !showCam }
        }

        if (showCam) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.6f, overlay = { roi(box, waterBlue) }) { bmp -> live[0] = textContrast(bmp, box) }
                Text("Printed text inside the box", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (direct == null) "1 · Text, no glass" else "✓ Direct ${fmt((direct ?: 0.0) * 100)}%", Modifier.weight(1f), primary = direct == null) {
                    scope.launch { direct = sample(); Haptics.click(app.ctx) }
                }
                Btn("2 · Through the water", Modifier.weight(1f), enabled = direct != null) {
                    scope.launch {
                        val c = SafeWater.clarity(sample(), direct!!)
                        clarity = c; cloudy = c < SafeWater.CLOUDY_BELOW; Haptics.click(app.ctx)
                    }
                }
            }
            clarity?.let { Note(if (it < SafeWater.CLOUDY_BELOW) "Cloudy (${fmt(it * 100)}% of the text contrast). Filter through a clean cloth and let it settle first — the dose has been doubled." else "Clear (${fmt(it * 100)}% of the text contrast) — normal dose.", if (it < SafeWater.CLOUDY_BELOW) Palette.amber else Palette.accent) }
        }

        Glass(padding = 16) {
            Text("SAFETY", color = Palette.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            listOf(
                "Use plain, unscented bleach only — not scented, colour-safe or “with cleaners”.",
                "Stir, cover, and wait the full 30 minutes before drinking.",
                "A slight chlorine smell afterwards means it worked. No smell? Repeat the dose, wait 15 more minutes.",
                "Bleach kills germs. It does not remove arsenic, fluoride, nitrate or pesticides.",
            ).forEach { Text("•  $it", color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp) }
        }
        HowItWorks(listOf(
            "Faecally contaminated water causes most diarrhoeal disease; free chlorine kills the bacteria and viruses responsible within about 30 minutes.",
            "The dose comes from the US EPA emergency-disinfection table (2 drops per litre of 6% bleach), scaled to your bottle's strength and doubled for cloudy water.",
            "Cloudy water shields germs and uses up chlorine, so it gets a double dose — the camera judges cloudiness by how much the water blurs printed text.",
            "A leftover chlorine smell after 30 minutes is the field check that enough free chlorine remained to do the job.",
        ))
    }
}

@Composable
private fun DropsIcon(n: Int) {
    Box(Modifier.size(84.dp).clip(CircleShape).background(waterBlue.copy(alpha = 0.18f)).border(2.dp, waterBlue, CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(56.dp)) {
            val k = n.coerceAtMost(9); val cols = 3
            for (i in 0 until k) {
                val cx = size.width * (0.2f + 0.3f * (i % cols)); val cy = size.height * (0.2f + 0.3f * (i / cols))
                drawCircle(waterBlue, size.minDimension * 0.09f, Offset(cx, cy))
            }
        }
    }
}

// ── H₂S faecal contamination test ───────────────────────────────────────────

@Composable
fun H2sPanel(app: AppState, onBleach: () -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cam = remember { CameraHandle() }
    val whiteRoi = RectF(0.10f, 0.40f, 0.34f, 0.60f)
    val vialRoi = RectF(0.58f, 0.36f, 0.82f, 0.64f)
    val collector = remember { Collector<List<Pair<Rgb, Rgb>>> { it.flatten() } }
    var store by remember { mutableStateOf(app.prefs.json("h2s") ?: JSONObject()) }
    var source by remember { mutableStateOf(store.optString("source", "Tap / well / tanker")) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val started = store.optLong("at", 0L).takeIf { it > 0 }
    val hours = started?.let { (System.currentTimeMillis() - it) / 3_600_000.0 }

    suspend fun readL(): Double? {
        cam.lock(false); delay(700); cam.lock(true); delay(200)
        val fr = withTimeoutOrNull(8000) { suspendCancellableCoroutine<List<Pair<Rgb, Rgb>>> { k -> collector.start(15) { if (k.isActive) k.resume(it) } } }
        cam.lock(false)
        return fr?.map { (w, v) -> Frames.relativeLab(v, w).l }?.sorted()?.let { it[it.size / 2] }
    }

    ScreenFrame("Germs in water (H₂S test)", "WHO-recognised field screen for faecal contamination", onBack = close) {
        Glass(glow = if (started != null) Palette.amber else waterBlue, padding = 18) {
            if (started == null) {
                Text("Start a test", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("Fill an H₂S test vial to the mark with the water, close it, and keep it at room temperature away from sunlight.", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
            } else {
                Text("Incubating · ${fmt(hours!!)} h", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("${store.optString("source")}. Read it at 24 h, and again at 48 h if it hasn't turned black.", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                val p by animateFloatAsState((hours / 48.0).toFloat().coerceIn(0f, 1f), tween(600), label = "h")
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.veil(0x22))) {
                    Box(Modifier.fillMaxWidth(p).height(8.dp).background(Palette.amber))
                }
                Text("24 h ─────────────── 48 h", color = Palette.muted, fontSize = 11.sp)
            }
            msg?.let { Text(it, color = waterBlue, fontSize = 13.sp, lineHeight = 18.sp) }
        }
        if (started == null) {
            androidx.compose.material3.OutlinedTextField(
                value = source, onValueChange = { source = it.take(40) }, label = { Text("Where is this water from?") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text, focusedBorderColor = waterBlue, unfocusedBorderColor = Palette.veil(0x33), focusedLabelColor = waterBlue, unfocusedLabelColor = Palette.muted),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.6f, overlay = { roi(whiteRoi, Color.White); roi(vialRoi, waterBlue) }) { bmp ->
                collector.offer(listOf(Frames.meanRgb(bmp, whiteRoi) to Frames.meanRgb(bmp, vialRoi)))
            }
            Text("White box: paper · blue box: the vial's contents", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (started == null) Btn("▶  Record start colour & set 24 h reminder", Modifier.fillMaxWidth(), enabled = !busy) {
            busy = true
            scope.launch {
                val l = readL(); busy = false
                if (l == null) { msg = "Couldn't read — keep the paper and the vial in their boxes."; return@launch }
                store = JSONObject().put("at", System.currentTimeMillis()).put("startL", l).put("source", source); app.prefs.putJson("h2s", store)
                systemTimer(app, 86_400, "H₂S water test: read the vial now (24 h)")
                msg = "Started. Reminder set for 24 hours."; Haptics.click(app.ctx)
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Read the vial now", Modifier.weight(1f), enabled = !busy) {
                busy = true
                scope.launch {
                    val l = readL(); busy = false
                    if (l == null) { msg = "Couldn't read — keep the paper and the vial in their boxes."; return@launch }
                    val startL = store.getDouble("startL")
                    val call = SafeWater.h2sPositive(startL, l)
                    val h = hours ?: 0.0
                    if (call == null && h < 48) { msg = "Partly darkened (${fmt(startL - l)} L* units). Read again at 48 h."; systemTimer(app, ((48 - h) * 3600).toInt().coerceAtLeast(60), "H₂S water test: final reading (48 h)"); return@launch }
                    if (call == false && h < 24) { msg = "No change yet after ${fmt(h)} h — read at 24 h."; return@launch }
                    val positive = call == true
                    val lv = if (positive) Level.UNSAFE else if (call == null) Level.CAUTION else Level.SAFE
                    val label = when { positive -> "Faecal contamination"; call == null -> "Doubtful"; else -> "No contamination seen" }
                    app.show(Outcome(
                        "Shuddh Safe Water", "water_h2s", Txt("Germs in water (H₂S)"), startL - l, "ΔL*", lv, "$label · ${store.optString("source")} after ${fmt(h)} h",
                        listOf(Txt(if (positive) "Don't drink it untreated. Boil, or use the bleach dose, and warn others who use this source." else if (call == null) "Treat the water to be safe and retest the source." else "No faecal contamination detected by this screen.")),
                        listOf(
                            Evidence("OBSERVATION", "Vial lightness L* ${fmt(startL)} at start → ${fmt(l)} now (${fmt(startL - l)} darker), relative to white paper"),
                            Evidence("PATTERN", "Positive = strong blackening (iron sulphide from faecal bacteria): ΔL* > 25 and L* < 45", !positive),
                            Evidence("HYPOTHESIS", label, lv == Level.SAFE),
                        ),
                        "H₂S strip test: a low-cost presence/absence screen for faecal contamination, used in community water monitoring. Confirm positives with a lab.",
                        levelLabel = Txt(label.uppercase()),
                    ))
                    if (positive) app.voice.speak("Contaminated. Do not drink this water untreated.", app.lang)
                    store = JSONObject().put("source", store.optString("source")); app.prefs.putJson("h2s", store)
                }
            }
            Btn("Cancel test", Modifier.weight(1f), primary = false) { store = JSONObject(); app.prefs.putJson("h2s", store); msg = null }
        }
        Btn("💧  Make water safe with bleach", Modifier.fillMaxWidth(), primary = false) { onBleach() }
        HowItWorks(listOf(
            "Bacteria from human and animal faeces produce hydrogen sulphide; in the vial it reacts with iron to form a black precipitate.",
            "Black within 24–48 hours means the water very likely carries faecal contamination; no change means none was detected.",
            "The camera records the vial's colour at the start and compares later readings against white paper, so a judgement by eye becomes a measured, dated record.",
            "It's a screening test — a positive should be confirmed by a lab, but treating the water (boil or bleach) should start immediately.",
        ))
    }
}

// ── Sick from water: ORS + danger signs (WHO) ────────────────────────────────

@Composable
fun OrsPanel(app: AppState, close: () -> Unit) {
    var litres by remember { mutableStateOf(1.0) }
    var age by remember { mutableStateOf(5.0) }
    val checked = remember { mutableStateOf(setOf<Int>()) }
    val o = SafeWater.ors(litres)
    val danger = checked.value.isNotEmpty()
    ScreenFrame("Sick from bad water?", "WHO oral rehydration & danger signs", onBack = close) {
        Glass(glow = if (danger) Palette.red else Palette.accent, padding = 18) {
            if (danger) {
                Text("Go to a health facility now", color = Palette.red, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("A WHO danger sign is present. Keep giving ORS sips on the way.", color = Palette.text, fontSize = 14.sp, lineHeight = 19.sp)
                Btn("📞  Call 108 (ambulance)", Modifier.fillMaxWidth()) {
                    runCatching { app.ctx.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:108")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            } else {
                Text("Make ORS at home", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OrsItem("💧", "${one(litres)} L", "safe water", Modifier.weight(1f))
                    OrsItem("🥄", "${one(o.sugarTsp)} tsp", "sugar, level", Modifier.weight(1f))
                    OrsItem("🧂", "${String.format(java.util.Locale.US, "%.2f", o.saltTsp).trimEnd('0').trimEnd('.')} tsp", "salt, level", Modifier.weight(1f))
                }
                Text("Stir until dissolved. Use within 24 hours. Too much salt is dangerous — measure level spoons.", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Glass(padding = 16) {
            Stepper("Amount to make", "${one(litres)} L", "boiled or bleach-treated water", Palette.accent) { d -> litres = (litres + d * 0.5).coerceIn(0.5, 5.0) }
            Stepper("Age of the sick person", "${one(age)} y", "how much to give changes with age", Palette.accent) { d -> age = (age + d * if (age < 2) 0.5 else 1.0).coerceIn(0.5, 90.0) }
            val r = SafeWater.orsPerStool(age)
            Text("Give ${r.first}–${r.last} mL after every loose stool, in small sips. Keep feeding; continue breastfeeding.", color = Palette.text, fontSize = 14.sp, lineHeight = 19.sp)
            if (age < 5) Text("Children under 5: ask a pharmacy or health worker for zinc tablets (10–14 days) — WHO recommends ORS + zinc.", color = Palette.cyan, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Glass(padding = 16) {
            Text("DANGER SIGNS — tick any you see", color = Palette.red, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            SafeWater.dangerSigns.forEachIndexed { i, sgn ->
                val on = i in checked.value
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { checked.value = if (on) checked.value - i else checked.value + i }.padding(vertical = 6.dp)) {
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Palette.red else Palette.veil(0x14)).border(1.5.dp, if (on) Palette.red else Palette.veil(0x33), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        if (on) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(sgn, color = Palette.text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        HowItWorks(listOf(
            "Diarrhoea kills mainly by dehydration — the body loses water and salts faster than it takes them in.",
            "ORS works because glucose carries sodium (and water with it) across the gut wall even during diarrhoea. The 6 : ½ sugar-to-salt ratio is the WHO home recipe.",
            "Zinc shortens the illness and reduces the next episodes in children; WHO recommends it alongside ORS.",
            "Danger signs are from the WHO/IMCI guidelines: any one means the person needs care beyond home treatment.",
        ))
    }
}

@Composable
private fun OrsItem(e: String, v: String, l: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Palette.veil(0x14)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(e, fontSize = 22.sp)
        Text(v, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 17.sp, maxLines = 1)
        Text(l, color = Palette.muted, fontSize = 11.sp)
    }
}

// ── Water clarity (camera only) ─────────────────────────────────────────────

/** Clarity class boundaries on the text-contrast ratio (through water ÷ direct). */
private val clarityLines = listOf(0.6, 0.85)

@Composable
fun WaterClarityPanel(app: AppState, onBleach: () -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cam = remember { CameraHandle() }
    val box = RectF(0.25f, 0.32f, 0.75f, 0.68f)
    val live = remember { doubleArrayOf(0.0) }
    var direct by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var result by remember { mutableStateOf<Triple<Double, Double, Level>?>(null) }
    var busy by remember { mutableStateOf(false) }

    /** Median and spread of 15 frames of text contrast. */
    suspend fun sample(): Pair<Double, Double> {
        cam.lock(false); delay(600); cam.lock(true)
        val xs = mutableListOf<Double>(); repeat(15) { delay(80); xs += live[0] }
        cam.lock(false)
        val s = xs.sorted(); return s[7] to (s[11] - s[3]) / 1.35
    }

    ScreenFrame("Water clarity", "Camera only — how much the water blurs printed text", onBack = close) {
        val r = result
        Glass(glow = r?.let { Color(it.third.argb) } ?: waterBlue, padding = 18) {
            if (r == null) {
                Text(if (direct == null) "Step 1 · Text without the glass" else "Step 2 · Same text through the water", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(if (direct == null) "Point the box at any printed text (a newspaper, a label) from ~15 cm." else "Now put a clear glass of the water on that text and look down through it from the same height.", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
            } else {
                val (c, conf, lv) = r
                val label = when { c >= clarityLines[1] -> "Clear"; c >= clarityLines[0] -> "Slightly cloudy"; else -> "Cloudy" }
                Text(label.uppercase(), color = Color(lv.argb), fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 26.sp)
                Text("The water keeps ${fmt(c * 100)}% of the text's sharpness · ${com.shuddh.lab.core.Dart.estimate(lv, conf)}", color = Palette.text, fontSize = 14.sp, lineHeight = 19.sp)
                Text(when (lv) {
                    Level.SAFE -> "Clear water — still boil or treat it if the source isn't trusted. Clarity can't show dissolved chemicals or germs."
                    Level.CAUTION -> "Some particles. Let it settle, filter through a clean folded cloth, then boil or treat with a double bleach dose."
                    else -> "Cloudy water often carries germs and shields them from chlorine. Settle, cloth-filter, then boil or use a double bleach dose."
                }, color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Test again", Modifier.weight(1f), primary = false) { result = null; direct = null }
                    if (lv != Level.SAFE) Btn("Make it safe →", Modifier.weight(1f)) { onBleach() }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.6f, overlay = { roi(box, waterBlue) }) { bmp -> live[0] = textContrast(bmp, box) }
            Text("Printed text inside the box · 📷 camera only", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (result == null) Btn(if (busy) "Reading…" else if (direct == null) "1 · Read text directly" else "2 · Read through the water", Modifier.fillMaxWidth(), enabled = !busy) {
            busy = true
            scope.launch {
                val (m, sd) = sample(); busy = false
                val d = direct
                if (d == null) {
                    if (m < 0.2) { app.voice.speak("I can't see any text. Point at printed text.", app.lang); return@launch }
                    direct = m to sd; Haptics.click(app.ctx); return@launch
                }
                val c = SafeWater.clarity(m, d.first)
                // σ of the ratio from both readings' frame spread (plus 2 % for re-positioning).
                val sigma = kotlin.math.sqrt((sd / d.first).let { it * it } + (d.second * c / d.first).let { it * it } + 0.02 * 0.02)
                val lv = when { c >= clarityLines[1] -> Level.SAFE; c >= clarityLines[0] -> Level.CAUTION; else -> Level.UNSAFE }
                val conf = com.shuddh.lab.core.Dart.confidence(clarityLines.minOf { kotlin.math.abs(c - it) }, sigma)
                result = Triple(c, conf, lv); Haptics.rumble(app.ctx, if (lv == Level.SAFE) 0.2f else 0.8f)
                app.voice.speak(if (lv == Level.SAFE) "Clear water." else if (lv == Level.CAUTION) "Slightly cloudy." else "Cloudy water.", app.lang)
            }
        }
        HowItWorks(listOf(
            "Particles in water scatter light and blur whatever is behind them — that's what turbidity is, and cloudy water is far more likely to carry germs.",
            "The camera measures the sharpness of printed text (contrast between the darkest and brightest parts) directly, then through the glass of water.",
            "The ratio is the water's clarity; exposure is locked during each reading, and 15 frames are combined so a shaky hand doesn't decide it.",
            "Clear doesn't mean germ-free — dissolved chemicals and bacteria are invisible. It does tell you when water needs settling, filtering and a double chlorine dose.",
        ))
    }
}
