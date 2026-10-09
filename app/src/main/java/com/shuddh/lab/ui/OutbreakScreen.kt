package com.shuddh.lab.ui

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.CommunityItem
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.MeshProto
import com.shuddh.lab.core.OutbreakWatch
import com.shuddh.lab.core.OutbreakWatch.Status
import com.shuddh.lab.core.OutbreakWatch.Symptom

private val symColor = mapOf(
    Symptom.DIARRHOEA to Color(0xFFF97316), Symptom.VOMITING to Color(0xFFA78BFA),
    Symptom.FEVER to Color(0xFFF43F5E), Symptom.JAUNDICE to Color(0xFFFDE047),
)

/** Outbreak Watch — anonymous illness reports + unsafe-water reports → an early warning for the area, offline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutbreakScreen(app: AppState) {
    val ctx = app.ctx
    var area by remember { mutableStateOf(app.prefs.area) }
    val picked = remember { mutableStateListOf<Symptom>() }
    var people by remember { mutableIntStateOf(1) }
    var msg by remember { mutableStateOf<String?>(null) }
    val home = remember {
        app.prefs.json("household")?.optString("id") ?: "home-${java.util.UUID.randomUUID().toString().take(8)}".also {
            app.prefs.putJson("household", org.json.JSONObject().put("id", it))
        }
    }
    val a = OutbreakWatch.assess(app.community.items.toList(), area)
    val col = Color(a.status.argb)

    LaunchedEffect(a.status) {
        if (a.status == Status.ALERT) { Haptics.alarm(ctx); app.voice.speak("Outbreak alert in your area. ${OutbreakWatch.advice(a).first()}", app.lang) }
    }

    fun report() {
        if (picked.isEmpty()) { msg = "Pick at least one symptom."; return }
        if (area.isBlank()) { msg = "Enter your area or ward so reports can be grouped."; return }
        app.prefs.area = area
        picked.forEach { s ->
            val item = CommunityItem("case", "", area.trim(), s.label, Level.CAUTION, "", System.currentTimeMillis(), people, 0, home)
            app.community.add(item)
            if (app.mesh.running) app.mesh.send(MeshProto.ALERT, item.compact())
        }
        Haptics.click(ctx)
        msg = "Reported anonymously" + if (app.mesh.running) " and shared with ${app.mesh.peers.size} nearby phone(s)." else ". Join the mesh in Hive to share it with neighbours."
        picked.clear(); people = 1
    }

    ScreenFrame("Outbreak Watch", "Neighbours' illness reports + water tests → early warning", onBack = { app.back() }) {
        Glass(Modifier.enter(0), glow = col, padding = 14) {
            StatusRadar(a, area)
            Text(a.status.label, color = col, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 26.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("${a.recentCases} sick in the last 3 days · ${a.households} home${if (a.households == 1) "" else "s"} · usual ≈ ${"%.1f".format(a.baselinePerWindow)}",
                color = Palette.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            a.reasons.forEach { Text("• $it", color = Palette.text, fontSize = 13.sp) }
            a.dominant?.let { if (a.status != Status.CLEAR) Note("Likely cause of ${it.label.lowercase()}: ${it.likely}.", Palette.amber) }
        }

        Section("Is anyone at home unwell?") {
            Note("Anonymous: only the symptom, number of people and your area are shared — no names or numbers.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Symptom.entries.forEach { s ->
                    val on = s in picked
                    val c = symColor[s]!!
                    Text("${s.emoji} ${s.label}", color = if (on) Color.Black else Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c else c.copy(alpha = 0.14f))
                            .clickable { if (on) picked.remove(s) else picked.add(s); Haptics.tick(ctx) }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("People affected", color = Palette.text, modifier = Modifier.weight(1f))
                Text("−", color = Palette.cyan, fontSize = 24.sp, modifier = Modifier.clickable { people = (people - 1).coerceAtLeast(1) }.padding(horizontal = 14.dp))
                Text("$people", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("+", color = Palette.cyan, fontSize = 24.sp, modifier = Modifier.clickable { people = (people + 1).coerceAtMost(12) }.padding(horizontal = 14.dp))
            }
            OutlinedTextField(area, { area = it }, label = { Text("Area / ward / village") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Btn("Report anonymously", Modifier.fillMaxWidth(), enabled = picked.isNotEmpty()) { report() }
            msg?.let { Note(it, Palette.accent) }
        }

        Section("Illness in ${area.ifBlank { "your area" }} · last 14 days") {
            EpiCurve(a.perDay)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Symptom.entries.forEach { s -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(symColor[s]!!)); Spacer(Modifier.width(4.dp))
                    Text(s.emoji, fontSize = 11.sp)
                } }
            }
            Note("A sudden rise in one symptom across several homes is the classic sign of a shared contaminated source.")
        }

        Section("What to do") {
            OutbreakWatch.advice(a).forEachIndexed { i, t -> Row(Modifier.enter(i)) { Text("✓ ", color = col, fontWeight = FontWeight.Black); Text(t, color = Palette.text, fontSize = 14.sp) } }
            BtnRow {
                Btn("📨 Tell health worker") {
                    val text = "Shuddh Outbreak Watch — ${area.ifBlank { "my area" }}: ${a.status.label}. ${a.recentCases} people sick in 3 days across ${a.households} homes" +
                        (a.dominant?.let { " (mostly ${it.label.lowercase()})" } ?: "") + (if (a.waterLinked) ". Unsafe water also reported nearby." else ".") + " Please test the water source."
                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send to ASHA / health worker").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                Btn("♨️ Boil Guard", primary = false) { app.go(Screen.BOIL) }
                Btn("🧪 Test water", primary = false) { app.go(Screen.SPECTRUM) }
            }
        }
        if (app.community.items.none { it.kind == "case" }) Btn("✨ Load sample outbreak (demo)", Modifier.fillMaxWidth(), primary = false) {
            area = area.ifBlank { "Ward 12" }; app.prefs.area = area
            sampleOutbreak(area).forEach { app.community.add(it) }
        }
        HowItWorks(listOf(
            "Families report illness at home anonymously — symptom, number of people, area. Reports spread phone-to-phone over the Bluetooth mesh or QR, no internet needed.",
            "For your area, Shuddh compares the last 3 days with the area's own usual rate over the previous 2 weeks.",
            "It raises a Watch when cases climb above normal, and an Outbreak alert when many homes are affected, jaundice clusters, or stomach illness coincides with an unsafe-water test nearby.",
            "This mirrors how district surveillance teams spot outbreaks early — but starts in the neighbourhood, days sooner.",
        ))
    }
}

/** Area status: concentric rings pulse faster and redder as the risk rises; dots are recent cases. */
@Composable
private fun StatusRadar(a: OutbreakWatch.Assessment, area: String) {
    val inf = rememberInfiniteTransition(label = "radar")
    val speed = when (a.status) { Status.ALERT -> 900; Status.WATCH -> 1600; Status.CLEAR -> 3000 }
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(speed, easing = LinearEasing)), label = "t")
    val col = Color(a.status.argb)
    val dots = remember(a.recentCases) { List(a.recentCases.coerceAtMost(24)) { Pair(Math.random().toFloat() * 6.283f, 0.25f + Math.random().toFloat() * 0.7f) } }
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension / 2 - 6f
        for (k in 1..3) drawCircle(Color.White.copy(alpha = 0.06f), r * k / 3, c, style = Stroke(2f))
        for (k in 0 until 3) { val p = (t + k / 3f) % 1f; drawCircle(col.copy(alpha = (1 - p) * 0.45f), r * p, c, style = Stroke(5f)) }
        dots.forEach { (ang, d) -> drawCircle(col, 6f, Offset(c.x + kotlin.math.cos(ang) * r * d, c.y + kotlin.math.sin(ang) * r * d)) }
        drawCircle(Brush.radialGradient(listOf(col, col.copy(alpha = 0.1f)), c, 34f), 30f, c)
        drawContext.canvas.nativeCanvas.drawText(area.ifBlank { "Your area" }.take(18), c.x, c.y + r + 2f,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 30f; textAlign = android.graphics.Paint.Align.CENTER })
    }
}

/** 14-day epidemic curve, bars stacked by symptom, growing in. */
@Composable
private fun EpiCurve(perDay: List<Pair<Long, Map<Symptom, Int>>>) {
    val grow = remember(perDay.sumOf { it.second.values.sum() }) { Animatable(0f) }
    LaunchedEffect(grow) { grow.animateTo(1f, tween(1000)) }
    val maxV = (perDay.maxOfOrNull { it.second.values.sum() } ?: 1).coerceAtLeast(3)
    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val bw = size.width / perDay.size
        perDay.forEachIndexed { i, (_, m) ->
            var y = size.height - 14f
            Symptom.entries.forEach { s ->
                val v = m[s] ?: 0
                if (v > 0) {
                    val hgt = (size.height - 20f) * v / maxV * grow.value
                    drawRoundRect(symColor[s]!!, Offset(i * bw + 3f, y - hgt), Size(bw - 6f, hgt), CornerRadius(4f))
                    y -= hgt
                }
            }
        }
        drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, size.height - 12f), Offset(size.width, size.height - 12f), 2f)
    }
    Row { Text("2 weeks ago", color = Palette.muted, fontSize = 10.sp, modifier = Modifier.weight(1f)); Text("today", color = Palette.muted, fontSize = 10.sp) }
}

private fun sampleOutbreak(area: String): List<CommunityItem> {
    val now = System.currentTimeMillis(); val h = 3_600_000L
    fun c(s: Symptom, ago: Long, n: Int, home: String) = CommunityItem("case", "", area, s.label, Level.CAUTION, "", now - ago, n, 0, home)
    return listOf(
        c(Symptom.FEVER, 200 * h, 1, "a"), c(Symptom.DIARRHOEA, 250 * h, 1, "b"),
        c(Symptom.DIARRHOEA, 50 * h, 2, "c"), c(Symptom.DIARRHOEA, 30 * h, 1, "d"), c(Symptom.VOMITING, 28 * h, 1, "d"),
        c(Symptom.DIARRHOEA, 14 * h, 3, "e"), c(Symptom.DIARRHOEA, 6 * h, 2, "f"),
        CommunityItem("alert", "Borewell, 5th Cross", area, "Nitrate in water", Level.UNSAFE, "62 mg/L", now - 40 * h, 1, 1, "sample"),
    )
}
