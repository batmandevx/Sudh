package com.shuddh.lab.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Exposure
import kotlin.math.sin

private val conColor = mapOf("no3" to Color(0xFFA78BFA), "f" to Color(0xFF22D3EE), "as" to Color(0xFFF43F5E))
private fun pctColor(p: Double) = when { p < 50 -> Color(0xFF34D399); p < 100 -> Palette.tint(Color(0xFFFBBF24)); else -> Color(0xFFF43F5E) }

/** Family Exposure Ledger — how much of a safe daily limit each person actually drinks, month by month. */
@Composable
fun ExposureScreen(app: AppState) {
    val family = remember { loadFamily(app) }
    var who by remember { mutableIntStateOf(0) }
    val now = System.currentTimeMillis()
    val real = app.store.records.filter { it.value != null }.map { Exposure.Sample(it.time, it.analyteId, it.value!!) }
    val sample = real.none { r -> Exposure.contaminants.any { r.analyteId in it.ids } }
    val samples = if (sample) sampleData(now) else real
    val m = family[who]
    val results = Exposure.ledger(samples, m, now)
    val worst = results.maxByOrNull { it.avgPct }

    ScreenFrame("Family Exposure", "What your family really drinks — month by month", onBack = { app.back() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            family.forEachIndexed { i, f ->
                val on = i == who
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (on) Palette.accent.copy(alpha = 0.2f) else Palette.ink.copy(alpha = 0.05f))
                    .clickable { who = i }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(f.emoji, fontSize = 28.sp)
                    Text(f.name, color = if (on) Palette.text else Palette.muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${f.weightKg.toInt()} kg · ${com.shuddh.lab.core.fmt(f.waterL)} L/day", color = Palette.muted, fontSize = 10.sp)
                }
            }
        }
        Glass(Modifier.enter(0), glow = worst?.let { pctColor(it.avgPct) } ?: Palette.cyan, padding = 16) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BodyFill(m.emoji == "🧒", (worst?.avgPct ?: 0.0) / 100, worst?.let { pctColor(it.avgPct) } ?: Palette.accent, Modifier.size(120.dp, 190.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val big = remember(worst?.avgPct, who) { Animatable(0f) }
                    LaunchedEffect(worst?.avgPct, who) { big.snapTo(0f); big.animateTo((worst?.avgPct ?: 0.0).toFloat(), tween(1400, easing = FastOutSlowInEasing)) }
                    Text("${big.value.toInt()}%", color = worst?.let { pctColor(it.avgPct) } ?: Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 44.sp)
                    Text(Exposure.headline(m, results), color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    worst?.let { Text("Peak day: ${it.peakPct.toInt()}% of the limit", color = Palette.muted, fontSize = 12.sp) }
                }
            }
            if (sample) Note("Showing SAMPLE data — test your tap water for nitrate, fluoride or arsenic and this becomes your family's real ledger.", Palette.amber)
        }

        results.forEachIndexed { i, r ->
            Section("${r.contaminant.name} · last 30 days") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Gauge(r.avgPct, conColor[r.contaminant.key]!!, Modifier.size(84.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Average ${r.avgPct.toInt()}% · peak ${r.peakPct.toInt()}% of the safe daily limit", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(r.contaminant.basis, color = Palette.muted, fontSize = 11.sp)
                    }
                }
                DailyBars(r.daily, conColor[r.contaminant.key]!!)
            }
        }

        Section("What to do") {
            val tips = buildList {
                results.forEach { r ->
                    if (r.avgPct >= 50) add(when (r.contaminant.key) {
                        "no3" -> "Nitrate: use RO or another source for ${m.name.lowercase()}'s drinking water and infant formula — boiling concentrates nitrate."
                        "f" -> "Fluoride: switch drinking water to RO or a defluoridation filter; avoid fluoride toothpaste for young children in high-fluoride areas."
                        else -> "Arsenic: stop drinking this water — use an arsenic-removal filter or a tested source; tell the gram panchayat / PHED."
                    })
                }
                if (isEmpty()) add("Every contaminant is under half its safe limit. Retest your water every 3 months — levels change with seasons.")
            }
            tips.forEach { Text("• $it", color = Palette.text, fontSize = 14.sp) }
        }
        Section("Your family") {
            Note("Weight and daily water decide the limit. Edit to match your family.")
            family.forEachIndexed { i, f ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.emoji, fontSize = 22.sp)
                    OutlinedTextField(f.weightKg.toInt().toString(), { v -> v.toDoubleOrNull()?.let { family[i] = f.copy(weightKg = it); saveFamily(app, family) } },
                        label = { Text("kg") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(com.shuddh.lab.core.fmt(f.waterL), { v -> v.toDoubleOrNull()?.let { family[i] = f.copy(waterL = it); saveFamily(app, family) } },
                        label = { Text("L water/day") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
        }
        HowItWorks(listOf(
            "Every water test you save (nitrate, fluoride, arsenic) stays in effect until the next one.",
            "Each day, Shuddh multiplies that concentration by how much each person drinks, and divides by a health-based daily limit for their body weight.",
            "Children drink more per kilogram than adults — so the same tap water uses up a much bigger share of a child's safe limit.",
            "Limits: nitrate 3.7 mg/kg/day (WHO/JECFA), fluoride 0.1 mg/kg/day (EFSA), arsenic benchmark from the WHO 10 µg/L guideline.",
        ))
    }
}

private fun loadFamily(app: AppState): androidx.compose.runtime.snapshots.SnapshotStateList<Exposure.Member> {
    val list = androidx.compose.runtime.mutableStateListOf<Exposure.Member>()
    val a = app.prefs.json("family")?.optJSONArray("m")
    if (a != null) for (i in 0 until a.length()) a.getJSONObject(i).let { list += Exposure.Member(it.getString("n"), it.getString("e"), it.getDouble("w"), it.getDouble("l")) }
    else list += Exposure.defaultFamily
    return list
}

private fun saveFamily(app: AppState, f: List<Exposure.Member>) = app.prefs.putJson("family", org.json.JSONObject().put("m",
    org.json.JSONArray(f.map { org.json.JSONObject().put("n", it.name).put("e", it.emoji).put("w", it.weightKg).put("l", it.waterL) })))

private fun sampleData(now: Long): List<Exposure.Sample> {
    val d = 86_400_000L
    return listOf(Exposure.Sample(now - 45 * d, "no3", 28.0), Exposure.Sample(now - 12 * d, "no3", 52.0), Exposure.Sample(now - 40 * d, "f", 1.1), Exposure.Sample(now - 20 * d, "strip_as", 4.0))
}

/** A silhouette that fills with "water" to the share of the safe limit; overflows red past 100 %. */
@Composable
private fun BodyFill(child: Boolean, frac: Double, col: Color, modifier: Modifier) {
    val f by animateFloatAsState(frac.toFloat().coerceIn(0f, 1.2f), tween(1600, easing = FastOutSlowInEasing), label = "fill")
    val inf = rememberInfiniteTransition(label = "wave")
    val ph by inf.animateFloat(0f, 6.283f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "ph")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val s = if (child) 0.82f else 1f
        val cx = w / 2; val top = h * (1 - s)
        val body = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(Offset(cx, top + h * s * 0.11f), h * s * 0.1f))
            addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - w * 0.28f * s, top + h * s * 0.23f, cx + w * 0.28f * s, top + h * s * 0.62f, CornerRadius(30f)))
            addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - w * 0.22f * s, top + h * s * 0.6f, cx - w * 0.03f * s, h, CornerRadius(14f)))
            addRoundRect(androidx.compose.ui.geometry.RoundRect(cx + w * 0.03f * s, top + h * s * 0.6f, cx + w * 0.22f * s, h, CornerRadius(14f)))
            addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - w * 0.42f * s, top + h * s * 0.25f, cx - w * 0.3f * s, top + h * s * 0.58f, CornerRadius(12f)))
            addRoundRect(androidx.compose.ui.geometry.RoundRect(cx + w * 0.3f * s, top + h * s * 0.25f, cx + w * 0.42f * s, top + h * s * 0.58f, CornerRadius(12f)))
        }
        drawPath(body, Palette.ink.copy(alpha = 0.08f))
        clipPath(body) {
            val level = h - (h - top) * f.coerceAtMost(1f)
            val wave = Path().apply {
                moveTo(0f, h); var x = 0f
                while (x <= w) { lineTo(x, level + 5f * sin(x / 18f + ph)); x += 4f }
                lineTo(w, h); close()
            }
            drawPath(wave, Brush.verticalGradient(listOf(col.copy(alpha = 0.7f), col), level, h))
        }
        drawPath(body, Color.White.copy(alpha = 0.5f), style = Stroke(3f))
        if (f > 1f) drawCircle(Color(0xFFF43F5E).copy(alpha = 0.3f), w * 0.5f, Offset(cx, h * 0.45f))
    }
}

@Composable
private fun Gauge(pct: Double, c: Color, modifier: Modifier) {
    val a = remember(pct) { Animatable(0f) }
    LaunchedEffect(pct) { a.animateTo((pct / 100).toFloat().coerceIn(0f, 1f), tween(1200)) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(84.dp)) {
            val st = 10f
            drawArc(Palette.ink.copy(alpha = 0.07f), 135f, 270f, false, Offset(st, st), Size(size.width - 2 * st, size.height - 2 * st), style = Stroke(st, cap = StrokeCap.Round))
            drawArc(c, 135f, 270f * a.value, false, Offset(st, st), Size(size.width - 2 * st, size.height - 2 * st), style = Stroke(st, cap = StrokeCap.Round))
        }
        Text("${pct.toInt()}%", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DailyBars(daily: List<Double?>, c: Color) {
    val grow = remember(daily) { Animatable(0f) }
    LaunchedEffect(daily) { grow.animateTo(1f, tween(1000)) }
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val maxV = maxOf(110.0, daily.filterNotNull().maxOrNull() ?: 0.0)
        val bw = size.width / daily.size
        val y100 = size.height * (1 - (100 / maxV)).toFloat()
        drawLine(Color(0xFFF43F5E).copy(alpha = 0.6f), Offset(0f, y100), Offset(size.width, y100), 2f)
        daily.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val hh = (size.height * (v / maxV)).toFloat() * grow.value
            drawRoundRect(if (v >= 100) Color(0xFFF43F5E) else c, Offset(i * bw + 1f, size.height - hh), Size(bw - 2f, hh), CornerRadius(3f))
        }
    }
    Row { Text("30 days ago", color = Palette.muted, fontSize = 10.sp, modifier = Modifier.weight(1f)); Text("— safe limit", color = Color(0xFFF43F5E), fontSize = 10.sp, modifier = Modifier.weight(1f)); Text("today", color = Palette.muted, fontSize = 10.sp) }
}
