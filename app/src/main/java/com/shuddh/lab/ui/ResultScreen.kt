package com.shuddh.lab.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.CommunityItem
import com.shuddh.lab.core.Complaint
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Passport
import com.shuddh.lab.core.Qr
import com.shuddh.lab.core.ScanRecord
import com.shuddh.lab.core.stamp
import java.io.File

@Composable
fun ResultScreen(app: AppState) {
    val o = app.outcome ?: return
    val ctx = app.ctx
    var tag by remember { mutableStateOf(app.prefs.lastTag) }
    var vendor by remember { mutableStateOf(app.prefs.lastVendor) }
    var area by remember { mutableStateOf(app.prefs.area) }
    var saved by remember { mutableStateOf<ScanRecord?>(null) }
    var qr by remember { mutableStateOf<CommunityItem?>(null) }
    val color = Color(o.level.argb)

    fun speak() {
        app.voice.speak(o.spoken(app.lang), app.lang)?.let { ctx.toastLong(it) }
    }
    fun ensureSaved(): ScanRecord = saved ?: app.geo.lastKnown().let { f -> app.store.add(o, tag, vendor, area, f?.lat, f?.lon) }.also {
        saved = it; app.prefs.lastTag = tag; app.prefs.lastVendor = vendor
    }
    LaunchedEffect(o) {
        Haptics.result(ctx, o.level)
        if (app.prefs.autoSpeak) speak()
    }

    ScreenFrame("Verdict", o.instrument, onBack = { app.back() }) {
        Box {
        VerdictFx(o.level, Modifier.matchParentSize())
        Glass(Modifier.enter(0), glow = color, padding = 20) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(o.analyte.get(app.lang).uppercase(), color = Palette.muted, fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                Box(contentAlignment = Alignment.BottomCenter) {
                    VerdictGauge(o.level, Modifier.fillMaxWidth(0.8f).height(130.dp))
                }
                Text(o.levelText(app.lang), color = color, fontWeight = FontWeight.Black, fontSize = 40.sp, modifier = Modifier.pulse(o.level == Level.UNSAFE))
                CountUpValue(o)
                Text(o.headline, color = Palette.muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        }
        ConfidenceCard(o, Modifier.enter(1))
        if (o.value != null && com.shuddh.lab.core.TruePrice.applies(o.analyteId, o.unit) && o.value > 0.5) TruePriceCard(app, o.analyteId, o.value)
        TrendCard(app, o)
        Glass(Modifier.enter(1), glow = color) {
            Text(Txt3.what.get(app.lang), color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            o.advice.forEach {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
                    Spacer(Modifier.width(10.dp))
                    Text(it.get(app.lang), color = Palette.text, fontSize = 16.sp, lineHeight = 22.sp)
                }
            }
            if (o.limitNote.isNotBlank()) Note(o.limitNote)
            Btn("🔊  " + Txt3.speak.get(app.lang), primary = false) { speak() }
        }

        val prior = app.store.previousFor(o.analyteId, tag, System.currentTimeMillis())
        val confirmState = saved?.let {
            if (it.confirmed) "CONFIRMED — two consecutive scans of \"${it.sampleTag}\" agree." else null
        } ?: when {
            prior != null && prior.level == o.level && o.level != Level.INCONCLUSIVE ->
                "Will be CONFIRMED on save: matches the previous scan of \"$tag\" (${stamp(prior.time)})."
            o.level == Level.UNSAFE || o.level == Level.CAUTION ->
                "PROVISIONAL — one scan is not proof. Scan the same sample again to confirm."
            else -> "Single scan."
        }
        Section("Evidence ladder") {
            val steps = o.evidence.map { Triple(it.step, it.text, it.ok) } + Triple("CONFIRMATION", confirmState, null as Boolean?)
            steps.forEachIndexed { i, (step, text, ok) ->
                val c = when (ok) { true -> Palette.accent; false -> Palette.red; null -> if (step == "CONFIRMATION") Palette.amber else Palette.blue }
                Row(Modifier.enter(i).height(IntrinsicSize.Min)) {
                    Column(Modifier.width(22.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.copy(alpha = 0.25f)).padding(3.dp)) {
                            Box(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape).background(c))
                        }
                        if (i < steps.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(Palette.line))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.padding(bottom = 12.dp)) {
                        Text(step, color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(text, color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }

        // Corroboration: other instruments' readings of the same sample in the last 24 h.
        val others = if (tag.isBlank()) emptyList() else app.store.records.filter {
            it.sampleTag.equals(tag.trim(), true) && it.instrument != o.instrument && System.currentTimeMillis() - it.time < 86_400_000L
        }.groupBy { it.instrument }.map { it.value.last() }
        if (others.isNotEmpty()) {
            Section("Cross-check with other instruments") {
                others.forEach { r -> Note("• ${r.instrument}: ${r.analyte} ${r.level.name}", Color(r.level.argb)) }
                val agree = others.count { it.level == o.level }
                Note(
                    if (agree > 0) "CORROBORATED by $agree independent instrument${if (agree > 1) "s" else ""}."
                    else "Instruments disagree — retest before acting.",
                    if (agree > 0) Palette.accent else Palette.amber,
                )
            }
        }

        Section("Remember this scan") {
            OutlinedTextField(tag, { tag = it }, label = { Text("Sample (e.g. morning milk, tap water)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = saved == null)
            OutlinedTextField(vendor, { vendor = it }, label = { Text("Vendor / brand / shop") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = saved == null)
            OutlinedTextField(area, { area = it }, label = { Text("Area / ward") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = saved == null)
            if (vendor.isNotBlank()) {
                val mem = app.store.vendorMemory(vendor.trim())
                Note("Memory: " + mem.sentence(), if (mem.failures > 0) Palette.amber else Palette.muted)
            }
            Btn(if (saved == null) "Save to history" else "Saved #${saved!!.id} ✓", Modifier.fillMaxWidth(), enabled = saved == null) { ensureSaved() }
            saved?.let { Mono("sha256 ${it.hash.take(32)}…\nchained to ${it.prevHash.take(16)}…", Palette.muted) }
        }

        Section("Act on it") {
            BtnRow {
                Btn("⬇ Download PDF") {
                    val rec = ensureSaved()
                    runCatching { Passport.saveToDownloads(ctx, Passport.build(ctx, listOf(rec), app.store.verifyChain() == -1, "Verdict: ${rec.analyte}")) }
                        .onSuccess { ctx.toastLong("Saved to $it") }.onFailure { ctx.toastLong("Couldn't save: ${it.message}") }
                }
                Btn("Purity Passport", primary = false) {
                    val rec = ensureSaved()
                    Passport.share(ctx, Passport.build(ctx, listOf(rec), app.store.verifyChain() == -1, "Single scan report"))
                }
                Btn("Tell family", primary = false) {
                    val phone = app.prefs.familyPhone
                    if (phone.isBlank()) {
                        ctx.toastLong("Add a family number in Settings first")
                    } else {
                        val body = "Shuddh: ${o.analyte.en} ${o.valueText()} — ${o.level.name}. ${o.advice.joinToString(" ") { it.en }}" +
                            (if (tag.isNotBlank()) " ($tag)" else "")
                        runCatching { Passport.sms(ctx, phone, body) }.onFailure { ctx.toastLong("No SMS app found") }
                    }
                }
            }
            BtnRow {
                Btn("Share alert QR", primary = false) { qr = CommunityItem.alertFrom(ensureSaved()) }
                if (o.level == Level.UNSAFE || o.level == Level.CAUTION) {
                    Btn("File complaint", primary = false) {
                        val rec = ensureSaved()
                        val pdf = Passport.build(ctx, listOf(rec), app.store.verifyChain() == -1, "Evidence for complaint")
                        Complaint.share(ctx, rec, app.lang, pdf)
                    }
                }
            }
            Note("Haptics: two short pulses = safe, three = caution, one long = danger — readable with the phone in a pocket.")
        }
    }

    qr?.let { item -> QrDialog(item, "Let another Shuddh phone scan this in Community → Scan") { qr = null } }
}

/** Full-screen-ish QR with a share-as-image option. */
@Composable
fun QrDialog(item: CommunityItem, hint: String, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bmp = remember(item) { Qr.encode(item.toPayload()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = {
                val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
                val f = File(dir, "shuddh_${item.kind}_${System.currentTimeMillis()}.png")
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                Complaint.shareFile(ctx, f, "image/png", "Share QR")
            }) { Text("Share image") }
        },
        title = { Text(if (item.kind == "seal") "Purity Seal" else "Alert QR") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(bmp.asImageBitmap(), "QR code", Modifier.size(240.dp).background(Color.White))
                Text(item.describe(), fontSize = 13.sp)
                Spacer(Modifier.size(2.dp))
                Text(hint, fontSize = 12.sp, color = Palette.muted)
            }
        },
    )
}

private object Txt3 {
    val what = com.shuddh.lab.core.Txt("What to do", "क्या करें", "ಏನು ಮಾಡಬೇಕು")
    val speak = com.shuddh.lab.core.Txt("Speak again", "फिर से सुनें", "ಮತ್ತೆ ಕೇಳಿ")
}


/** Celebration burst for SAFE, warning shockwaves for UNSAFE, a ripple for CAUTION. */
@Composable
private fun VerdictFx(level: Level, modifier: Modifier) {
    if (UiPrefs.reduceMotion) return
    val c = Color(level.argb)
    val burst = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(level) { burst.snapTo(0f); burst.animateTo(1f, androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "fx")
    val wave by inf.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.LinearEasing)), label = "w")
    val parts = remember { List(46) { Triple(kotlin.random.Random.nextFloat() * 6.283f, 0.5f + kotlin.random.Random.nextFloat(), kotlin.random.Random.nextInt(3)) } }
    androidx.compose.foundation.Canvas(modifier) {
        val ctr = androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.42f)
        when (level) {
            Level.SAFE -> {
                val t = burst.value
                val cols = listOf(c, Palette.cyan, Color(0xFFFDE047))
                parts.forEach { (ang, sp, ci) ->
                    val d = t * sp * size.minDimension * 0.62f
                    val p = androidx.compose.ui.geometry.Offset(ctr.x + kotlin.math.cos(ang) * d, ctr.y + kotlin.math.sin(ang) * d + t * t * 60f)
                    drawCircle(cols[ci].copy(alpha = (1 - t).coerceIn(0f, 1f)), 4f + 4f * sp, p)
                }
            }
            Level.UNSAFE -> for (k in 0 until 3) {
                val p = (wave + k / 3f) % 1f
                drawCircle(c.copy(alpha = (1 - p) * 0.35f), size.minDimension * (0.15f + 0.6f * p), ctr, style = androidx.compose.ui.graphics.drawscope.Stroke(6f))
            }
            Level.CAUTION -> {
                val p = burst.value
                drawCircle(c.copy(alpha = (1 - p) * 0.4f), size.minDimension * (0.2f + 0.5f * p), ctr, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
            }
            else -> {}
        }
    }
}

/** The measured value counts up from zero. */
@Composable
private fun CountUpValue(o: com.shuddh.lab.core.Outcome) {
    val v = o.value
    if (v == null) { Text(o.valueText(), color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold); return }
    val a = remember(o) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(o) { a.animateTo(v.toFloat(), androidx.compose.animation.core.tween(if (UiPrefs.reduceMotion) 0 else 1200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Text("${com.shuddh.lab.core.fmt(a.value.toDouble())} ${o.unit}".trim(), color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = Display)
}

/** How sure is this result? Animated ring + every check the instrument ran, with fixes for the failed ones. */
@Composable
private fun ConfidenceCard(o: com.shuddh.lab.core.Outcome, modifier: Modifier) {
    val conf = o.confidence()
    val col = when { conf >= 75 -> Palette.accent; conf >= 50 -> Palette.amber; else -> Palette.red }
    val a = remember(o) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(o) { a.animateTo(conf / 100f, androidx.compose.animation.core.tween(1300, delayMillis = 300)) }
    val checks = o.evidence.filter { it.ok != null }
    Glass(modifier, glow = col) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.size(92.dp)) {
                    val st = 12f
                    val tl = androidx.compose.ui.geometry.Offset(st, st); val sz = androidx.compose.ui.geometry.Size(size.width - 2 * st, size.height - 2 * st)
                    drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(st))
                    drawArc(Brush.sweepGradient(listOf(col.copy(alpha = 0.5f), col, col.copy(alpha = 0.5f))), -90f, 360f * a.value, false, tl, sz,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(st, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(a.value * 100).toInt()}%", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text("sure", color = Palette.muted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(when { conf >= 75 -> "High confidence"; conf >= 50 -> "Moderate confidence"; else -> "Low confidence — retest" }, color = col, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("${checks.count { it.ok == true }} of ${checks.size} quality checks passed", color = Palette.muted, fontSize = 12.sp)
            }
        }
        checks.forEachIndexed { i, e ->
            Row(Modifier.enter(i.coerceAtMost(6)), verticalAlignment = Alignment.Top) {
                Text(if (e.ok == true) "✓" else "✗", color = if (e.ok == true) Palette.accent else Palette.red, fontWeight = FontWeight.Black, modifier = Modifier.width(18.dp))
                Text(e.text, color = if (e.ok == true) Palette.muted else Palette.text, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        if (checks.any { it.ok == false }) Note("Fix the ✗ items and scan again — confidence rises with every passed check.", Palette.amber)
    }
}

/** Your past readings of the same test, with today's highlighted. */
@Composable
private fun TrendCard(app: AppState, o: com.shuddh.lab.core.Outcome) {
    val v = o.value ?: return
    val past = app.store.records.filter { it.analyteId == o.analyteId && it.value != null }.takeLast(14)
    if (past.isEmpty()) return
    val ys = (past.map { it.value!!.toFloat() } + v.toFloat())
    val cols = past.map { Color(it.level.argb) } + Color(o.level.argb)
    Section("Your trend · ${o.analyte.get(app.lang)}") {
        val grow = remember(o) { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(o) { grow.animateTo(1f, androidx.compose.animation.core.tween(1200)) }
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val lo = ys.min(); val hi = ys.max().let { if (it - lo < 1e-3f) lo + 1f else it }
            val dx = size.width / (ys.size - 1).coerceAtLeast(1)
            fun pt(i: Int) = androidx.compose.ui.geometry.Offset(i * dx, size.height - 12f - (size.height - 24f) * (ys[i] - lo) / (hi - lo))
            val n = (ys.size * grow.value).toInt().coerceIn(1, ys.size)
            for (i in 1 until n) drawLine(Color.White.copy(alpha = 0.35f), pt(i - 1), pt(i), 4f, androidx.compose.ui.graphics.StrokeCap.Round)
            for (i in 0 until n) {
                val last = i == ys.lastIndex
                drawCircle(cols[i], if (last) 11f else 7f, pt(i))
                if (last) drawCircle(cols[i].copy(alpha = 0.3f), 20f, pt(i))
            }
        }
        val prev = past.last().value!!
        val d = v - prev
        Note("Previous ${com.shuddh.lab.core.fmt(prev)} → now ${com.shuddh.lab.core.fmt(v)} ${o.unit} (${if (d >= 0) "+" else ""}${com.shuddh.lab.core.fmt(d)}) over ${past.size + 1} scans.")
    }
}


/** True Price: what a diluted product really costs you — per genuine litre, and per month. */
@Composable
private fun TruePriceCard(app: AppState, analyteId: String, pct: Double) {
    val (product, unit) = com.shuddh.lab.core.TruePrice.product(analyteId)
    var paid by remember { mutableStateOf(app.prefs.json("price_$product")?.optDouble("p")?.takeIf { !it.isNaN() } ?: if (product == "milk") 60.0 else 400.0) }
    var qty by remember { mutableStateOf(app.prefs.json("price_$product")?.optDouble("q")?.takeIf { !it.isNaN() } ?: if (product == "milk") 1.0 else 0.05) }
    fun save() = app.prefs.putJson("price_$product", org.json.JSONObject().put("p", paid).put("q", qty))
    val real = com.shuddh.lab.core.TruePrice.real(paid, pct)
    val lost = com.shuddh.lab.core.TruePrice.lostPerMonth(paid, pct, qty)
    val a = remember(real) { androidx.compose.animation.core.Animatable(paid.toFloat()) }
    LaunchedEffect(real) { a.animateTo(real.toFloat(), androidx.compose.animation.core.tween(1400)) }
    val lostA = remember(lost) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(lost) { lostA.animateTo(lost.toFloat(), androidx.compose.animation.core.tween(1600, delayMillis = 400)) }
    Glass(Modifier.enter(2), glow = Palette.amber) {
        Text("💸 The true price", color = Palette.amber, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("₹${paid.toInt()}", color = Palette.muted, fontSize = 22.sp, fontWeight = FontWeight.Bold, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
            Text("  →  ", color = Palette.muted, fontSize = 20.sp)
            Text("₹${a.value.toInt()}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 34.sp)
            Text(" per real $unit", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text("You paid for ${pct.toInt()}% ${if (product == "milk") "water" else "sugar syrup"}. That's about ₹${lostA.value.toInt()} lost every month — ₹${(lost * 12).toInt()} a year.",
            color = Palette.text, fontSize = 14.sp)
        // Visual: what fills the "litre" you paid for.
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val f = (1 - pct / 100).toFloat().coerceIn(0f, 1f)
            drawRoundRect(Color(0xFFF8FAFC), size = androidx.compose.ui.geometry.Size(size.width * f, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f))
            drawRoundRect(Color(0xFF60A5FA), topLeft = androidx.compose.ui.geometry.Offset(size.width * f, 0f), size = androidx.compose.ui.geometry.Size(size.width * (1 - f), size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f))
        }
        Row { Text("real $product ${(100 - pct).toInt()}%", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); Text("${if (product == "milk") "water" else "syrup"} ${pct.toInt()}%", color = Palette.blue, fontSize = 11.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(paid.toInt().toString(), { v -> v.toDoubleOrNull()?.let { paid = it; save() } }, label = { Text("₹ per $unit") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            OutlinedTextField(com.shuddh.lab.core.fmt(qty), { v -> v.toDoubleOrNull()?.let { qty = it; save() } }, label = { Text("$unit per day") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
        }
    }
}
