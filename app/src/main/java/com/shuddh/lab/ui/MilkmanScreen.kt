package com.shuddh.lab.ui

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Milkman
import java.util.Calendar

/** Milkman Ledger — daily litres + purity tests → what the month's milk was really worth. */
@Composable
fun MilkmanScreen(app: AppState) {
    val ctx = app.ctx
    val days = remember { mutableStateMapOf<Int, Double>().also { m -> app.prefs.json("milk_days")?.let { o -> o.keys().forEach { k -> m[k.toInt()] = o.getDouble(k) } } } }
    var price by remember { mutableStateOf(app.prefs.json("price_milk")?.optDouble("p")?.takeIf { !it.isNaN() } ?: 60.0) }
    var monthOffset by remember { mutableIntStateOf(0) }
    fun save() = app.prefs.putJson("milk_days", org.json.JSONObject().apply { days.forEach { (k, v) -> put(k.toString(), v) } })
    val cal = Calendar.getInstance().apply { add(Calendar.MONTH, monthOffset); set(Calendar.DAY_OF_MONTH, 1) }
    val monthKey = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + 1
    val monthName = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(cal.time)
    val tests = app.store.records.filter { (it.analyteId == "milk_water" || it.analyteId == "nir_water") && it.value != null }.map { Milkman.Test(it.time, it.value!!) }
    val m = Milkman.month(days.map { Milkman.Day(it.key, it.value) }, tests, price, monthKey)
    val todayKey = Milkman.key(System.currentTimeMillis())

    ScreenFrame("Milkman Ledger", "Daily milk + purity tests → a fair monthly bill", onBack = { app.back() }) {
        Glass(Modifier.enter(0), glow = if (m.overpaid > 1) Palette.amber else Palette.accent, padding = 16) {
            val a = remember(m.overpaid) { Animatable(0f) }
            LaunchedEffect(m.overpaid) { a.animateTo(m.overpaid.toFloat(), tween(1400, easing = FastOutSlowInEasing)) }
            Text(monthName, color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (m.overpaid > 1) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Pay ₹${a.value.toInt()} less", color = Palette.amber, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 32.sp)
                }
                Text("${m.wateredDays} days of watered milk · ≈${com.shuddh.lab.core.fmt(m.waterLitres)} L of water you paid for", color = Palette.text, fontSize = 14.sp)
            } else Text(if (m.litres > 0) "Fair bill ✓ — no watered milk found" else "Log today's milk to start", color = Palette.accent, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 24.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("🥛", "${com.shuddh.lab.core.fmt(m.litres)} L", "delivered", Modifier.weight(1f))
                Stat("🧾", "₹${m.billed.toInt()}", "billed", Modifier.weight(1f))
                Stat("✅", "₹${m.fair.toInt()}", "fair value", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("📨 Send bill to milkman", Modifier.weight(1f), enabled = m.litres > 0) {
                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Milkman.message(m, price, monthName)), "Send bill").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }

        if (monthOffset == 0) Section("Today's milk") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.0, 0.5, 1.0, 1.5, 2.0).forEach { l ->
                    val on = (days[todayKey] ?: -1.0) == l
                    Text(if (l == 0.0) "None" else "${com.shuddh.lab.core.fmt(l)} L", color = if (on) Color.Black else Palette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) Palette.accent else Color.White.copy(alpha = 0.06f))
                            .clickable { if (l == 0.0) days.remove(todayKey) else days[todayKey] = l; save(); Haptics.tick(ctx) }.padding(vertical = 12.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(price.toInt().toString(), { v -> v.toDoubleOrNull()?.let { price = it; app.prefs.putJson("price_milk", org.json.JSONObject().put("p", it).put("q", 1.0)) } },
                    label = { Text("₹ per litre") }, singleLine = true, modifier = Modifier.weight(1f))
                Btn("🧪 Test today's milk", primary = false) { app.go(Screen.FLOAT) }
            }
            Note("Any water-in-milk test (Float lactometer or NIR) automatically marks that day — and the next 3 — as watered by the measured %.")
        }

        Section("Calendar") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Palette.cyan, fontSize = 26.sp, modifier = Modifier.clickable { monthOffset-- }.padding(horizontal = 12.dp))
                Text(monthName, color = Palette.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("›", color = if (monthOffset < 0) Palette.cyan else Palette.muted, fontSize = 26.sp, modifier = Modifier.clickable { if (monthOffset < 0) monthOffset++ }.padding(horizontal = 12.dp))
            }
            MonthGrid(cal, m.days.associateBy { it.dayKey }, todayKey) { k ->
                days[k] = if ((days[k] ?: 0.0) > 0) 0.0.also { days.remove(k) } else 1.0; if (days[k] == 0.0) days.remove(k); save(); Haptics.tick(ctx)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legend(Palette.accent, "pure"); Legend(Palette.amber, "watered"); Legend(Color.White.copy(alpha = 0.25f), "delivered, untested")
            }
            DailyLitres(m.days)
        }
        HowItWorks(listOf(
            "Tap today's litres each morning (or tap a calendar day to toggle 1 L).",
            "When you test the milk with Float or NIR, the measured % of added water marks that day and the next three.",
            "Month end: litres × price = the bill; litres × water % = the water you paid for. The difference is a fair, evidence-based deduction you can send to your milkman.",
        ))
    }
}

@Composable
private fun Stat(icon: String, v: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.05f)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 16.sp)
        Text(v, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 15.sp, maxLines = 1)
        Text(label, color = Palette.muted, fontSize = 10.sp)
    }
}

@Composable
private fun Legend(c: Color, t: String) {
    Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c)); Spacer(Modifier.width(4.dp)); Text(t, color = Palette.muted, fontSize = 11.sp) }
}

@Composable
private fun MonthGrid(cal: Calendar, views: Map<Int, Milkman.DayView>, todayKey: Int, onTap: (Int) -> Unit) {
    val first = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
    val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday-first
    val n = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val base = first.get(Calendar.YEAR) * 10000 + (first.get(Calendar.MONTH) + 1) * 100
    Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, color = Palette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center) } }
    (0 until (lead + n + 6) / 7).forEach { w ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 2.dp)) {
            (0 until 7).forEach { d ->
                val day = w * 7 + d - lead + 1
                if (day !in 1..n) Spacer(Modifier.weight(1f).aspectRatio(1f)) else {
                    val k = base + day; val v = views[k]
                    val c = when { v == null -> Color.Transparent; (v.waterPct ?: 0.0) >= 3 -> Palette.amber; v.waterPct != null -> Palette.accent; else -> Color.White.copy(alpha = 0.18f) }
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(9.dp)).background(c.copy(alpha = if (c == Color.Transparent) 0f else 0.85f))
                        .border(if (k == todayKey) 2.dp else 1.dp, if (k == todayKey) Palette.cyan else Color.White.copy(alpha = 0.08f), RoundedCornerShape(9.dp))
                        .clickable { if (k <= todayKey) onTap(k) }, contentAlignment = Alignment.Center) {
                        Text("$day", color = if (v != null && c != Color.Transparent) Color.Black else Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyLitres(views: List<Milkman.DayView>) {
    if (views.isEmpty()) return
    val grow = remember(views.size) { Animatable(0f) }
    LaunchedEffect(views.size) { grow.animateTo(1f, tween(900)) }
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val maxL = views.maxOf { it.litres }.coerceAtLeast(1.0)
        val bw = size.width / 31f
        views.forEach { v ->
            val i = (v.dayKey % 100) - 1
            val h = (size.height * v.litres / maxL).toFloat() * grow.value
            val water = ((v.waterPct ?: 0.0) / 100).toFloat()
            drawRoundRect(Color(0xFFF8FAFC).copy(alpha = 0.85f), Offset(i * bw + 1f, size.height - h), Size(bw - 2f, h), CornerRadius(3f))
            if (water > 0f) drawRoundRect(Color(0xFF60A5FA), Offset(i * bw + 1f, size.height - h), Size(bw - 2f, h * water), CornerRadius(3f))
        }
    }
    Text("Daily litres — blue part = added water", color = Palette.muted, fontSize = 10.sp)
}
