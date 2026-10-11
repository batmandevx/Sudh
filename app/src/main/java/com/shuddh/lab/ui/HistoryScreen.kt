package com.shuddh.lab.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.CommunityItem
import com.shuddh.lab.core.Complaint
import com.shuddh.lab.core.Insights
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Passport
import com.shuddh.lab.core.ScanRecord
import com.shuddh.lab.core.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Instrument name → icon + colour used across History. */
fun instrumentLook(instrument: String): Pair<Glyph, Color> = when (instrument.removePrefix("Shuddh ")) {
    "Spectrum" -> Glyph.SPECTRUM to Palette.violet
    "Polar" -> Glyph.POLAR to Palette.amber
    "NIR" -> Glyph.NIR to Palette.red
    "Echo" -> Glyph.ECHO to Palette.blue
    "Nami" -> Glyph.NAMI to Palette.cyan
    "Strips" -> Glyph.STRIP to Palette.accent
    "Hawa" -> Glyph.SCATTER to Palette.tint(Color(0xFF9AD0C2))
    "Float" -> Glyph.FLOAT to Palette.tint(Color(0xFFE8F1EC))
    "Magneto" -> Glyph.MAGNET to Palette.violet
    "Lens" -> Glyph.STRIP to Palette.amber
    else -> Glyph.SPARK to Palette.cyan
}

private enum class HFilter(val label: String) { ALL("All"), UNSAFE("Unsafe"), CAUTION("Caution"), SAFE("Safe"), CONFIRMED("Confirmed ✓"), GPS("📍 GPS") }

private fun dayLabel(t: Long): String {
    val today = Insights.startOfDay(System.currentTimeMillis())
    return when (Insights.startOfDay(t)) {
        today -> "Today"
        today - 86_400_000L -> "Yesterday"
        else -> SimpleDateFormat("EEE, d MMM yyyy", Locale.US).format(Date(t))
    }
}

@Composable
fun HistoryScreen(app: AppState) {
    val store = app.store
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(HFilter.ALL) }
    var vendor by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var qr by remember { mutableStateOf<CommunityItem?>(null) }
    val broken = remember(store.records.size) { store.verifyChain() }
    val all = store.records.toList()
    val shown = all.filter { r ->
        (vendor == null || r.vendor.equals(vendor, true)) &&
            when (filter) {
                HFilter.ALL -> true
                HFilter.UNSAFE -> r.level == Level.UNSAFE
                HFilter.CAUTION -> r.level == Level.CAUTION
                HFilter.SAFE -> r.level == Level.SAFE
                HFilter.CONFIRMED -> r.confirmed
                HFilter.GPS -> r.lat != null
            } &&
            (query.isBlank() || listOf(r.analyte, r.vendor, r.area, r.sampleTag, r.instrument, r.headline).any { it.contains(query.trim(), true) })
    }.reversed()
    val groups = shown.groupBy { Insights.startOfDay(it.time) }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Aurora(intensity = 0.5f)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("History", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Palette.text)
                        Text("${plural(all.size, "scan")} · stored only on this phone", color = Palette.muted, fontSize = 12.sp)
                    }
                    IconAction("⬇", "PDF", enabled = shown.isNotEmpty()) {
                        runCatching { Passport.saveToDownloads(app.ctx, Passport.build(app.ctx, shown.reversed(), broken == -1, vendor?.let { "Vendor: $it" } ?: "Scan history")) }
                            .onSuccess { app.ctx.toastLong("Saved to $it") }.onFailure { app.ctx.toastLong("Couldn't save: ${it.message}") }
                    }
                    Spacer(Modifier.width(8.dp))
                    IconAction("⇪", "CSV", enabled = shown.isNotEmpty()) { Complaint.shareFile(app.ctx, Complaint.csv(app.ctx, shown.reversed()), "text/csv", "Export scans") }
                }
            }
            item { SummaryCard(all, broken) }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Palette.glass).border(1.dp, Palette.line, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔎", fontSize = 15.sp)
                    BasicTextField(
                        query, { query = it }, singleLine = true, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        textStyle = TextStyle(color = Palette.text, fontSize = 15.sp, fontFamily = Body), cursorBrush = SolidColor(Palette.cyan),
                        decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search tests, vendors, areas…", color = Palette.muted, fontSize = 15.sp); inner() } },
                    )
                    if (query.isNotEmpty()) Text("✕", color = Palette.muted, modifier = Modifier.clickable { query = "" })
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(HFilter.entries) { f ->
                        val n = when (f) {
                            HFilter.ALL -> all.size; HFilter.UNSAFE -> all.count { it.level == Level.UNSAFE }; HFilter.CAUTION -> all.count { it.level == Level.CAUTION }
                            HFilter.SAFE -> all.count { it.level == Level.SAFE }; HFilter.CONFIRMED -> all.count { it.confirmed }; HFilter.GPS -> all.count { it.lat != null }
                        }
                        FilterPill("${f.label}  $n", filter == f) { filter = f }
                    }
                }
            }
            val vendors = store.vendors()
            if (vendors.isNotEmpty()) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf<String?>(null) + vendors) { v -> FilterPill(v?.let { "🏪 $it" } ?: "All vendors", vendor == v) { vendor = v } }
                }
            }
            vendor?.let { v ->
                item {
                    val m = store.vendorMemory(v)
                    Glass(glow = if (m.failures > 0) Palette.amber else Palette.accent, padding = 14) {
                        Text(m.sentence(), color = Palette.text, fontSize = 14.sp)
                        BtnRow {
                            Btn("Vendor seal QR", primary = false) { qr = CommunityItem.sealFrom(store, v, all.lastOrNull { it.vendor.equals(v, true) }?.area ?: app.prefs.area) }
                        }
                    }
                }
            }
            if (shown.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LogoMark(70.dp, animated = false)
                    Text(if (all.isEmpty()) "No scans yet" else "Nothing matches", color = Palette.text, fontWeight = FontWeight.SemiBold)
                    Text(if (all.isEmpty()) "Run any instrument and tap Save on the verdict." else "Try another filter or search.", color = Palette.muted, fontSize = 13.sp)
                }
            }
            groups.forEach { (day, recs) ->
                item(key = "h$day") {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dayLabel(day), color = Palette.text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f).height(1.dp).background(Palette.line))
                        Spacer(Modifier.width(8.dp))
                        Text("${recs.size}", color = Palette.muted, fontSize = 12.sp)
                    }
                }
                items(recs, key = { it.id }) { r ->
                    HistoryCard(app, r, expanded == r.id, broken, onToggle = { expanded = if (expanded == r.id) null else r.id }, onQr = { qr = CommunityItem.alertFrom(r) })
                }
            }
        }
    }
    qr?.let { QrDialog(it, "Show this to another Shuddh phone (Hive → Scan QR) or print it for the counter.") { qr = null } }
}

@Composable
private fun IconAction(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick).padding(4.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.glass).border(1.dp, Palette.line, CircleShape), contentAlignment = Alignment.Center) {
            Text(glyph, color = if (enabled) Palette.text else Palette.muted, fontSize = 17.sp)
        }
        Text(label, color = Palette.muted, fontSize = 10.sp)
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, fontSize = 12.sp, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) Palette.onAccent else Palette.text,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Brush.horizontalGradient(listOf(Palette.accent, Palette.cyan)) else Brush.horizontalGradient(listOf(Palette.glass, Palette.glass)))
            .border(1.dp, if (selected) Color.Transparent else Palette.line, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SummaryCard(all: List<ScanRecord>, broken: Int) {
    val safe = all.count { it.level == Level.SAFE }; val caution = all.count { it.level == Level.CAUTION }; val unsafe = all.count { it.level == Level.UNSAFE }
    Glass(glow = Palette.accent, padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Donut(safe, caution, unsafe, Modifier.size(76.dp)) {
                Text("${safe + caution + unsafe}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniCount(safe, "safe", Palette.accent); MiniCount(caution, "caution", Palette.amber); MiniCount(unsafe, "unsafe", Palette.red)
                }
                Badge(if (broken == -1) "🔒 HASH CHAIN INTACT" else "⚠ CHAIN BROKEN AT #${all.getOrNull(broken)?.id}", if (broken == -1) Palette.accent else Palette.red)
                Text("${all.count { it.confirmed }} confirmed · ${all.count { it.lat != null }} with GPS", color = Palette.muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MiniCount(n: Int, label: String, c: Color) {
    Column {
        Text("$n", color = c, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 17.sp)
        Text(label, color = Palette.muted, fontSize = 10.sp)
    }
}

@Composable
private fun HistoryCard(app: AppState, r: ScanRecord, open: Boolean, broken: Int, onToggle: () -> Unit, onQr: () -> Unit) {
    val (glyph, gc) = instrumentLook(r.instrument)
    val lc = Color(r.level.argb)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(lc.copy(alpha = 0.10f), Palette.veil(0x0F))))
            .border(1.dp, if (open) lc.copy(alpha = 0.6f) else Palette.line, RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle),
    ) {
        Row(Modifier.height(IntrinsicSize.Min).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(lc))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(gc.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                InstrumentGlyph(glyph, gc, Modifier.size(28.dp), animated = false)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.analyte, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                Text(
                    listOfNotNull(SimpleDateFormat("HH:mm", Locale.US).format(Date(r.time)), r.vendor.ifBlank { null }, r.area.ifBlank { null }, if (r.lat != null) "📍" else null).joinToString(" · "),
                    color = Palette.muted, fontSize = 11.sp, maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(r.value?.let { fmt(it) } ?: "—", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(r.unit, color = Palette.muted, fontSize = 10.sp)
            }
            Spacer(Modifier.width(8.dp))
            Badge(r.level.name.take(7) + if (r.confirmed) " ✓" else "", lc)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.headline, color = Palette.text, fontSize = 13.sp)
                if (r.sampleTag.isNotBlank()) Text("Sample: ${r.sampleTag}", color = Palette.muted, fontSize = 12.sp)
                r.evidence.forEach { line ->
                    val step = line.substringBefore(":"); val text = line.substringAfter(": ", line)
                    Row {
                        Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape).background(Palette.cyan))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(step, color = Palette.cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                            Text(text, color = Palette.text, fontSize = 12.sp)
                        }
                    }
                }
                Text("#${r.id} · sha256 ${r.hash.take(20)}…", color = Palette.muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                BtnRow {
                    Btn("⬇ PDF", primary = false) {
                        runCatching { Passport.saveToDownloads(app.ctx, Passport.build(app.ctx, listOf(r), broken == -1, r.analyte)) }
                            .onSuccess { app.ctx.toastLong("Saved to $it") }
                    }
                    Btn("Alert QR", primary = false) { onQr() }
                    if (r.lat != null) Btn("📍 Map", primary = false) { app.go(Screen.MAP) }
                    if (r.level == Level.UNSAFE || r.level == Level.CAUTION) Btn("Complaint", primary = false) {
                        Complaint.share(app.ctx, r, app.lang, Passport.build(app.ctx, listOf(r), broken == -1, "Evidence for complaint"))
                    }
                }
            }
        }
    }
}
