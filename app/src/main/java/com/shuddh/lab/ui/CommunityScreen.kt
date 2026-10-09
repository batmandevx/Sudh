package com.shuddh.lab.ui

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.CommunityItem
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Qr
import com.shuddh.lab.core.stamp
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The Hive — your neighbourhood's offline food-safety network. Phones share verdicts and vendor
 * track records by QR or Bluetooth mesh; no server, no accounts, no personal data.
 */
@Composable
fun CommunityScreen(app: AppState) {
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showSeal by remember { mutableStateOf<CommunityItem?>(null) }
    val items = app.community.items
    val ctx = app.ctx

    // Alerts and seals arriving over the Bluetooth mesh are imported automatically.
    LaunchedEffect(app.mesh.messages.size) {
        app.mesh.messages.filter { !it.mine && (it.packet.type == com.shuddh.lab.core.MeshProto.ALERT || it.packet.type == com.shuddh.lab.core.MeshProto.SEAL) }
            .forEach { m -> CommunityItem.parseCompact(m.packet.text, "mesh·${m.packet.name}")?.let { if (app.community.add(it)) Haptics.result(ctx, it.level) } }
    }

    val latestBad = app.store.records.lastOrNull { it.level == Level.UNSAFE || it.level == Level.CAUTION }
    fun warnNeighbours() {
        val r = latestBad ?: run { msg = "No unsafe or caution result to share yet."; return }
        val item = CommunityItem.alertFrom(r)
        if (!app.mesh.running) { app.go(Screen.MESH); msg = "Join the mesh first, then tap Warn neighbours again."; return }
        app.mesh.send(com.shuddh.lab.core.MeshProto.ALERT, item.compact())
        Haptics.thud(ctx); msg = "Alert broadcast to ${app.mesh.peers.size} nearby phone(s) — it hops onward phone-to-phone."
        app.voice.speak("Alert sent to nearby phones.", app.lang)
    }

    ScreenFrame("Hive", "Your neighbourhood's offline food-safety network") {
        Glass(Modifier.enter(0), glow = Palette.cyan, padding = 12) {
            HiveNetwork(app.mesh.peers.size, items.take(12), app.mesh.running)
            Text("Warn each other about bad milk, fake honey and unsafe water — even with no internet.",
                color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
            HowHiveWorks()
        }

        // Actions
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.enter(1)) {
            ActionTile("📷", "Scan a QR", "seal or alert", Palette.cyan, Modifier.weight(1f)) { scanning = !scanning; msg = null }
            ActionTile("📣", "Warn neighbours", latestBad?.let { "${it.analyte} · ${it.level.name}" } ?: "no alert yet", Palette.red, Modifier.weight(1f)) { warnNeighbours() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.enter(2)) {
            ActionTile("🔳", "Share result", "as QR code", Palette.accent, Modifier.weight(1f)) {
                app.store.records.lastOrNull()?.let { showSeal = CommunityItem.alertFrom(it) } ?: run { msg = "Run a test first." }
            }
            ActionTile("📡", "Mesh chat", if (app.mesh.running) "${app.mesh.peers.size} nearby" else "Bluetooth, no internet", Palette.violet, Modifier.weight(1f)) { app.go(Screen.MESH) }
        }
        val ob = com.shuddh.lab.core.OutbreakWatch.assess(items.toList(), app.prefs.area)
        Row(Modifier.enter(3), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile("🚨", "Outbreak Watch", "${ob.status.label} · ${ob.recentCases} sick in 3 days", Color(ob.status.argb), Modifier.weight(1f)) { app.go(Screen.OUTBREAK) }
        }
        msg?.let { Note(it, Palette.accent) }
        if (scanning) Section("Scan a Seal or Alert") {
            QrScanner { text ->
                val item = CommunityItem.parse(text) ?: CommunityItem.parseCompact(text)
                if (item == null) msg = "That QR isn't a Shuddh code."
                else { scanning = false; msg = if (app.community.add(item)) "Imported: ${item.describe()}" else "Already imported earlier."; Haptics.result(ctx, item.level) }
            }
            Btn("Stop scanning", Modifier.fillMaxWidth(), primary = false) { scanning = false }
        }

        val batches = app.community.batchAlerts()
        if (batches.isNotEmpty()) Glass(Modifier.enter(3), glow = Palette.red) {
            Text("⚠ Batch alerts", color = Palette.red, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
            batches.forEach { (what, n) -> Text("$what — flagged UNSAFE by $n independent phones", color = Palette.text, fontSize = 14.sp) }
            Note("Several neighbours found the same problem. Avoid this product until you test it yourself.")
        }

        PulseCard(app)
        TrustBoard(app) { v -> showSeal = CommunityItem.sealFrom(app.store, v, app.store.records.lastOrNull { it.vendor.equals(v, true) }?.area ?: app.prefs.area) }

        Section("Alert feed (${items.size})") {
            if (items.isEmpty()) {
                Note("Nothing received yet. Scan a neighbour's QR, join the mesh — or load a sample neighbourhood to see how it works.")
                Btn("✨ Load sample neighbourhood", Modifier.fillMaxWidth(), primary = false) { sampleNeighbourhood().forEach { app.community.add(it) } }
            }
            items.take(30).forEachIndexed { i, it -> FeedRow(it, Modifier.enter(i.coerceAtMost(6))) { app.community.remove(it) } }
            Note("Reports from other phones are screening results, not lab certificates. Each carries a reference to the sender's tamper-evident scan log.")
        }
    }
    showSeal?.let { QrDialog(it, "Another phone scans this in Hive → Scan a QR.") { showSeal = null } }
}

/** Animated network: your phone in the centre, nearby phones and received reports around it, alerts pulsing in. */
@Composable
private fun HiveNetwork(peers: Int, reports: List<CommunityItem>, live: Boolean) {
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "hive")
    val t by inf.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.LinearEasing)), label = "t")
    val spin by inf.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(40000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin")
    val cyan = Palette.cyan
    val nodes = (List(peers.coerceAtMost(6)) { Color(0xFFA78BFA) to null } + reports.map { Color(it.level.argb) to it }).ifEmpty {
        List(6) { Color.White.copy(alpha = 0.25f) to null }
    }
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val r = size.minDimension * 0.4f
        // Radio ripples from the centre when the mesh is live.
        if (live) for (k in 0 until 3) {
            val p = (t + k / 3f) % 1f
            drawCircle(cyan.copy(alpha = (1 - p) * 0.3f), r * 1.1f * p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        }
        val pos = nodes.indices.map { i ->
            val a = Math.toRadians((spin + 360.0 * i / nodes.size)).toFloat()
            val rr = r * (0.75f + 0.25f * ((i * 37) % 10) / 10f)
            androidx.compose.ui.geometry.Offset(c.x + kotlin.math.cos(a) * rr, c.y + kotlin.math.sin(a) * rr)
        }
        // Links + travelling pulses (unsafe reports pulse red toward you).
        pos.forEachIndexed { i, p ->
            drawLine(Color.White.copy(alpha = 0.12f), c, p, 2f)
            val (col, item) = nodes[i]
            if (item != null || live) {
                val q = (t + i * 0.13f) % 1f
                val dot = androidx.compose.ui.geometry.Offset(p.x + (c.x - p.x) * q, p.y + (c.y - p.y) * q)
                drawCircle(col.copy(alpha = 0.9f), if (item?.level == Level.UNSAFE) 6f else 4f, dot)
            }
        }
        // Mesh between neighbours.
        for (i in pos.indices) { val j = (i + 2) % pos.size; if (pos.size > 3) drawLine(Color.White.copy(alpha = 0.05f), pos[i], pos[j], 1.5f) }
        pos.forEachIndexed { i, p ->
            val col = nodes[i].first
            drawCircle(col.copy(alpha = 0.25f), 18f, p); drawCircle(col, 10f, p)
        }
        drawCircle(Brush.radialGradient(listOf(cyan, cyan.copy(alpha = 0.2f)), c, 42f), 38f, c)
        drawCircle(Color.White, 14f, c)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Legend(Color(0xFFA78BFA), "$peers phones nearby")
        Legend(Color(Level.UNSAFE.argb), "${reports.count { it.level == Level.UNSAFE }} unsafe")
        Legend(Color(Level.SAFE.argb), "${reports.count { it.level == Level.SAFE }} safe")
    }
}

@Composable
private fun Legend(c: Color, t: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(c, CircleShape)); Spacer(Modifier.width(5.dp)); Text(t, color = Palette.muted, fontSize = 11.sp)
    }
}

@Composable
private fun HowHiveWorks() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf("🧪" to "You test", "📲" to "Share by QR / mesh", "🛡" to "Neighbours stay safe").forEachIndexed { i, (e, t) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(e, fontSize = 24.sp)
                Text(t, color = Palette.muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (i < 2) Text("→", color = Palette.cyan, fontSize = 18.sp)
        }
    }
}

@Composable
private fun ActionTile(icon: String, title: String, sub: String, c: Color, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.95f else 1f, label = "tile")
    Column(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(c.copy(alpha = 0.25f), c.copy(alpha = 0.06f))))
            .clickable(src, null, onClick = onClick).padding(14.dp),
    ) {
        Text(icon, fontSize = 26.sp)
        Text(tr(title), color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(sub, color = Palette.muted, fontSize = 11.sp, maxLines = 1)
    }
}

/** Neighbourhood safety pulse: donut of everything received + headline stats. */
@Composable
private fun PulseCard(app: AppState) {
    val items = app.community.items.filter { it.kind != "case" }
    if (items.isEmpty()) return
    val safe = items.count { it.level == Level.SAFE }; val caution = items.count { it.level == Level.CAUTION }; val unsafe = items.count { it.level == Level.UNSAFE }
    val week = items.count { System.currentTimeMillis() - it.time < 7 * 86_400_000L }
    val score = (100.0 * (safe + 0.5 * caution) / (safe + caution + unsafe).coerceAtLeast(1)).toInt()
    Section("Neighbourhood safety pulse") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Donut(safe, caution, unsafe, Modifier.size(110.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CountUp(score) { Text(it, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 24.sp) }
                    Text("area score", color = Palette.muted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("📥", "${items.size}", "reports received")
                Stat("⚠️", "$unsafe", "unsafe found")
                Stat("🏪", "${items.map { it.vendor }.filter { it.isNotBlank() }.distinct().size}", "vendors covered")
                Stat("🗓", "$week", "this week")
            }
        }
    }
}

@Composable
private fun Stat(icon: String, v: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 14.sp); Spacer(Modifier.width(6.dp))
        Text(v, color = Palette.text, fontWeight = FontWeight.Black, fontSize = 15.sp); Spacer(Modifier.width(5.dp))
        Text(label, color = Palette.muted, fontSize = 12.sp)
    }
}

/** Vendor trust: your own scans + neighbours' reports → a 0–100 score per shop (smoothed so 1 report can't dominate). */
@Composable
private fun TrustBoard(app: AppState, onSeal: (String) -> Unit) {
    data class V(val name: String, var fails: Double = 0.0, var total: Double = 0.0, var own: Int = 0)
    val map = linkedMapOf<String, V>()
    app.store.vendors().forEach { v -> val m = app.store.vendorMemory(v); map.getOrPut(v.lowercase()) { V(v) }.apply { fails += m.failures; total += m.total; own = m.total } }
    app.community.items.filter { it.vendor.isNotBlank() }.forEach { i ->
        map.getOrPut(i.vendor.lowercase()) { V(i.vendor) }.apply {
            if (i.kind == "seal") { fails += i.fails; total += i.total } else { total += 1; if (i.level == Level.UNSAFE) fails += 1 else if (i.level == Level.CAUTION) fails += 0.5 }
        }
    }
    if (map.isEmpty()) return
    val rows = map.values.map { v -> v to (100 * (1 - (v.fails + 0.5) / (v.total + 1))).toInt().coerceIn(0, 100) }.sortedByDescending { it.second }
    Section("Vendor trust board") {
        rows.take(8).forEachIndexed { i, (v, score) ->
            val c = when { score >= 75 -> Palette.accent; score >= 50 -> Palette.amber; else -> Palette.red }
            val a by androidx.compose.animation.core.animateFloatAsState(score / 100f, androidx.compose.animation.core.tween(900, delayMillis = i * 80), label = "trust$i")
            Row(Modifier.enter(i.coerceAtMost(6)), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(v.name, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Badge(when { score >= 75 -> "TRUSTED"; score >= 50 -> "WATCH"; else -> "AVOID" }, c)
                    }
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.06f))) {
                        Box(Modifier.fillMaxWidth(a).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.5f), c))))
                    }
                    Text("$score/100 · ${v.total.toInt()} results" + if (v.own > 0) " · ${v.own} yours" else "", color = Palette.muted, fontSize = 11.sp)
                }
                if (v.own > 0) TextButton(onClick = { onSeal(v.name) }) { Text("Seal QR", fontSize = 12.sp) }
            }
        }
        Note("Shops can print their Seal QR for the counter — customers scan before they pay.")
    }
}

@Composable
private fun FeedRow(it: CommunityItem, modifier: Modifier, onRemove: () -> Unit) {
    val c = Color(it.level.argb)
    val ago = ((System.currentTimeMillis() - it.time) / 60000).let { m -> when { m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" } }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.16f), Color(0x08FFFFFF)))), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).height(58.dp).background(c))
        Column(Modifier.weight(1f).padding(10.dp)) {
            Text(it.describe(), color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text("$ago · ${if (it.ref.startsWith("mesh")) "via Bluetooth mesh" else "via QR"}", color = Palette.muted, fontSize = 11.sp)
        }
        Badge(when (it.kind) { "seal" -> "SEAL"; "case" -> "ILLNESS"; else -> it.level.name }, c)
        TextButton(onClick = onRemove) { Text("✕", color = Palette.muted) }
    }
}

/** A believable sample neighbourhood so the Hive can be explored before real reports arrive. */
private fun sampleNeighbourhood(): List<CommunityItem> {
    val now = System.currentTimeMillis(); val h = 3_600_000L
    fun a(v: String, t: String, l: Level, x: String, ago: Long, area: String = "Koramangala") = CommunityItem("alert", v, area, t, l, x, now - ago, 1, if (l == Level.UNSAFE) 1 else 0, "sample")
    return listOf(
        a("Sri Ram Dairy", "Detergent in milk", Level.UNSAFE, "", 2 * h),
        a("Sri Ram Dairy", "Detergent in milk", Level.UNSAFE, "", 9 * h, "HSR Layout"),
        a("Nandini Booth 14", "Water in milk", Level.SAFE, "1 %", 5 * h),
        a("Ganesh Stores", "Honey purity", Level.CAUTION, "", 20 * h),
        a("Ward 151 tap", "Free chlorine", Level.SAFE, "0.6 mg/L", 26 * h),
        a("Borewell, 5th Cross", "Nitrate in water", Level.UNSAFE, "62 mg/L", 30 * h),
        CommunityItem("seal", "Fresh Mart", "Koramangala", "Vendor record", Level.SAFE, "steady", now - 40 * h, 12, 0, "sample"),
        a("Fresh Mart", "Starch in milk", Level.SAFE, "", 50 * h),
    )
}

@Composable
private fun ItemRow(it: CommunityItem) {
    Row(
        Modifier.fillMaxWidth().background(Palette.surface2, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(Color(it.level.argb), CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(it.describe(), color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Tested ${stamp(it.time)} · ref ${it.ref}", color = Palette.muted, fontSize = 11.sp)
        }
        Badge(if (it.kind == "seal") "SEAL" else it.level.name, if (it.level == Level.INCONCLUSIVE) Palette.muted else Color(it.level.argb))
    }
}

/** Camera view that decodes QR codes (every 3rd frame) and reports the first hit on the main thread. */
@Composable
fun QrScanner(onResult: (String) -> Unit) {
    val cam = remember { CameraHandle() }
    val counter = remember { AtomicInteger() }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var lastText by remember { mutableStateOf("") }
    CameraView(cam, Modifier.fillMaxWidth(), overlay = { roi(RectF(0.15f, 0.25f, 0.85f, 0.75f), Color(0xFF3DDC97), 5f) }) { bmp ->
        if (counter.incrementAndGet() % 3 != 0) return@CameraView
        Qr.decode(bmp)?.let { text ->
            main.post {
                if (text != lastText) { lastText = text; onResult(text) }
            }
        }
    }
}
