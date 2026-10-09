package com.shuddh.lab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Insights
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.fmt

private val months = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

@Composable
fun InsightsScreen(app: AppState) {
    var sample by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val real = app.store.records.toList()
    val rs = if (sample) remember { com.shuddh.lab.core.Demo.records() } else real
    var month by remember { mutableStateOf(Insights.startOfDay(System.currentTimeMillis())) }
    var selectedDay by remember { mutableStateOf<Long?>(null) }
    ScreenFrame("Insights", if (sample) "SAMPLE DATA — preview only, nothing saved" else "Computed on this phone from your own scans") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (sample) "Showing generated sample data" else "Your data · ${plural(real.size, "scan")}", color = if (sample) Palette.amber else Palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(sample, { sample = it; selectedDay = null })
            Text("  Sample", color = Palette.muted, fontSize = 12.sp)
        }
        if (rs.isEmpty()) {
            Section { Note("No scans yet. Insights appear after you save a few verdicts — or flip on Sample to preview the charts.") }
            return@ScreenFrame
        }

        val (cur, longest) = Insights.streaks(rs)
        StreakCard(cur, longest, Insights.byDay(rs).size)

        Section("Testing heatmap · last 26 weeks") {
            ContributionHeatmap(rs) { d -> selectedDay = d; month = d }
        }

        Section("Calendar") {
            MonthCalendar(rs, month, selectedDay, { month = it }) { selectedDay = if (selectedDay == it) null else it }
            selectedDay?.let { d ->
                val dayRecs = rs.filter { Insights.startOfDay(it.time) == d }
                Text(java.text.SimpleDateFormat("EEEE, d MMM", java.util.Locale.US).format(java.util.Date(d)), color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (dayRecs.isEmpty()) Note("No scans this day.")
                dayRecs.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(r.level.argb), androidx.compose.foundation.shape.CircleShape))
                        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                        Text("${r.analyte} · ${r.value?.let { com.shuddh.lab.core.fmt(it) + " " + r.unit } ?: "—"}", color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Badge(r.level.name, Color(r.level.argb))
                    }
                }
            }
        }

        Section("When you test · 24-hour clock") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { HourClock(Insights.hours(rs)) }
            Note("Bars show scans per hour; the centre shows your busiest hour. Morning = amber, day = green, evening = violet, night = blue.")
        }

        Section("Instrument mix") { InstrumentMix(Insights.instrumentMix(rs)) }

        Section("💸 What adulteration costs you") { CostMeter(rs) }
        Section("🏆 Vendor safety leaderboard") { VendorBoard(rs) }

        val all = Insights.byTest(rs).fold(Triple(0, 0, 0)) { acc, (_, t) -> Triple(acc.first + t.safe, acc.second + t.caution, acc.third + t.unsafe) }
        Glass(Modifier.enter(0), glow = Palette.accent, padding = 18) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Donut(all.first, all.second, all.third, Modifier.size(132.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CountUp(all.first + all.second + all.third) { Text(it, color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Black) }
                        Text("verdicts", color = Palette.muted, fontSize = 11.sp)
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Legend(Palette.accent, "Safe", all.first)
                    Legend(Palette.amber, "Caution", all.second)
                    Legend(Palette.red, "Unsafe", all.third)
                }
            }
            Note(Insights.digest(rs) ?: "No scans in the last 7 days.", Palette.text)
        }

        Section("Safety radar · by category") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RadarChart(Insights.categories, Insights.categoryScores(rs), Modifier.size(280.dp))
            }
            Note("Each spoke = share of safe verdicts in that category. Hollow spokes have no scans yet.")
        }

        Section("Activity · last 14 days") {
            ActivityBars(Insights.activity(rs))
            Row { Text("14 days ago", color = Palette.muted, fontSize = 10.sp, modifier = Modifier.weight(1f)); Text("today", color = Palette.muted, fontSize = 10.sp) }
        }

        Btn("⬇ Download full report (PDF)", Modifier.fillMaxWidth()) {
            runCatching {
                com.shuddh.lab.core.Passport.saveToDownloads(app.ctx, com.shuddh.lab.core.Passport.build(app.ctx, rs, app.store.verifyChain() == -1, "Full kitchen report"))
            }.onSuccess { app.ctx.toastLong("Saved to $it") }.onFailure { app.ctx.toastLong("Couldn't save: ${it.message}") }
        }

        val daily = Insights.daily(rs)
        val pts = daily.mapIndexedNotNull { i, v -> v?.let { i.toFloat() to it.toFloat() } }
        Section("Kitchen score · last 30 days") {
            if (pts.size < 2) {
                Note("Scan on at least two different days to see a trend.")
            } else {
                LineChart(
                    listOf(
                        Series(pts.map { it.first }.toFloatArray(), pts.map { it.second }.toFloatArray(), Palette.accent, fill = true),
                        Series(pts.map { it.first }.toFloatArray(), pts.map { it.second }.toFloatArray(), Palette.accent, dots = true),
                    ),
                    xMin = 0f, xMax = 29f, yMin = 0f, yMax = 100f, xLabel = "days",
                )
            }
        }

        Section("By test") {
            Insights.byTest(rs).forEach { (name, t) ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row {
                        Text(name, color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("${t.unsafe} fail · ${t.caution} warn · ${t.safe} ok", color = Palette.muted, fontSize = 11.sp)
                    }
                    TallyBar(t.safe, t.caution, t.unsafe)
                }
            }
        }

        val dossiers = Insights.dossiers(rs)
        Section("Corroboration · same sample, several instruments (24 h)") {
            if (dossiers.isEmpty()) {
                Note("Tag the same sample name in two instruments (e.g. \"honey jar\" in Polar and NIR) and the verdicts are cross-checked here.")
            }
            dossiers.forEach { d ->
                Text(
                    "\"${d.tag}\" → ${d.consensus.name}" + when {
                        d.conflict -> " · CONFLICT, retest"
                        d.agree >= 2 -> " · corroborated ${d.agree}/${d.items.size}"
                        else -> " · single instrument"
                    },
                    color = if (d.conflict) Palette.amber else Color(d.consensus.argb), fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                )
                d.items.forEach { Note("   ${it.instrument}: ${it.analyte} ${it.level.name}") }
            }
        }

        val monthly = Insights.monthly(rs)
        Section("Seasonal watch · failure rate by month") {
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                monthly.forEachIndexed { i, v ->
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        val frac = (v ?: 0.0).toFloat().coerceIn(0.02f, 1f)
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight(frac * 0.8f)
                                .background(if (v == null) Palette.line else if (i in 5..8) Palette.blue else Palette.amber, RoundedCornerShape(3.dp)),
                        )
                        Text(months[i], color = Palette.muted, fontSize = 10.sp)
                    }
                }
            }
            Note(Insights.monsoonNote(rs) ?: "Needs 5+ scans both inside and outside the monsoon (Jun–Sep) to compare.")
        }

        val board = Insights.areaBoard(rs, app.community.items)
        Section("Area purity board") {
            if (board.isEmpty()) Note("Add your locality in Settings (and on each scan) to build the board. Imported community seals and alerts count too.")
            board.forEach { a ->
                val c = when {
                    a.total == 0 -> Palette.muted
                    a.rate < 0.1 -> Palette.accent
                    a.rate < 0.3 -> Palette.amber
                    else -> Palette.red
                }
                Row(
                    Modifier.fillMaxWidth().background(c.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.area, color = Palette.text, fontWeight = FontWeight.SemiBold)
                        Text("${a.fails} of ${a.total} failed" + if (a.community > 0) " · ${a.community} community reports" else "", color = Palette.muted, fontSize = 11.sp)
                    }
                    Text("${fmt(a.rate * 100)}%", color = c, fontWeight = FontWeight.Bold)
                }
            }
        }

        Section("Vendors") {
            val vs = app.store.vendors().map { app.store.vendorMemory(it) }.sortedByDescending { it.failures }
            if (vs.isEmpty()) Note("Name the vendor when you save a scan to build each shop's track record.")
            vs.forEach { m ->
                Row {
                    Text(m.vendor, color = Palette.text, modifier = Modifier.weight(1f))
                    Text("${m.failures}/${m.total} failed · ${m.trend}", color = if (m.failures > 0) Palette.amber else Palette.muted, fontSize = 12.sp)
                }
            }
        }
        Note("Unsafe this month: ${rs.count { it.level == Level.UNSAFE && System.currentTimeMillis() - it.time < 30L * 86_400_000 }}")
    }
}

@Composable
private fun Legend(c: Color, label: String, n: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(c, RoundedCornerShape(3.dp)))
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text("$label  ", color = Palette.muted, fontSize = 13.sp)
        Text("$n", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Converts measured milk dilution into rupees lost — the economic face of adulteration. */
@Composable
private fun CostMeter(rs: List<com.shuddh.lab.core.ScanRecord>) {
    val water = rs.filter { it.analyteId == "milk_water" && it.value != null }.map { it.value!! }
    var litres by remember { mutableStateOf(1.0) }
    var price by remember { mutableStateOf(60.0) }
    if (water.isEmpty()) {
        Note("Run Shuddh Float (lactometer) on your milk to see how much money added water costs you each month.")
        return
    }
    val pct = water.average()
    val perMonth = litres * 30 * price * pct / 100
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(perMonth) { anim.snapTo(0f); anim.animateTo(perMonth.toFloat(), androidx.compose.animation.core.tween(1200)) }
    Row(verticalAlignment = Alignment.Bottom) {
        Text("₹${anim.value.toInt()}", color = Palette.red, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 38.sp)
        Text("  / month", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
    }
    Text("≈ ₹${(perMonth * 12).toInt()} a year paid for water, at ${com.shuddh.lab.core.fmt(pct)}% average dilution across ${water.size} milk test${if (water.size == 1) "" else "s"}.", color = Palette.text, fontSize = 13.sp)
    Box(Modifier.fillMaxWidth().height(12.dp).background(Palette.line, RoundedCornerShape(6.dp))) {
        Box(Modifier.fillMaxWidth((pct / 100).toFloat().coerceIn(0.01f, 1f)).height(12.dp).background(Brush.horizontalGradient(listOf(Palette.amber, Palette.red)), RoundedCornerShape(6.dp)))
    }
    Text("You buy per day", color = Palette.muted, fontSize = 12.sp)
    Chips(listOf(0.5, 1.0, 2.0, 3.0), litres, { "${com.shuddh.lab.core.fmt(it)} L" }) { litres = it }
    Text("Price per litre", color = Palette.muted, fontSize = 12.sp)
    Chips(listOf(50.0, 60.0, 70.0, 80.0), price, { "₹${it.toInt()}" }) { price = it }
}

/** Vendors ranked by share of safe verdicts, bars growing in. */
@Composable
private fun VendorBoard(rs: List<com.shuddh.lab.core.ScanRecord>) {
    val rows = rs.filter { it.vendor.isNotBlank() && it.level != Level.INCONCLUSIVE }.groupBy { it.vendor }
        .map { (v, l) -> Triple(v, l.count { it.level == Level.SAFE }.toFloat() / l.size, l.size) }.sortedByDescending { it.second }
    if (rows.isEmpty()) { Note("Name the vendor when you save a verdict to rank your shops."); return }
    rows.take(8).forEachIndexed { i, (v, safe, n) ->
        val a = remember(v, safe) { androidx.compose.animation.core.Animatable(0f) }
        androidx.compose.runtime.LaunchedEffect(v, safe) { a.animateTo(safe, androidx.compose.animation.core.tween(900, delayMillis = 90 * i)) }
        val c = when { safe >= 0.8f -> Palette.accent; safe >= 0.5f -> Palette.amber; else -> Palette.red }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOf("🥇", "🥈", "🥉").getOrElse(i) { "  ${i + 1}" }, fontSize = 16.sp, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(v, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${(safe * 100).toInt()}% safe · $n", color = c, fontSize = 11.sp)
                }
                Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(8.dp).background(Palette.line, RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth(a.value.coerceAtLeast(0.02f)).height(8.dp).background(c, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}
