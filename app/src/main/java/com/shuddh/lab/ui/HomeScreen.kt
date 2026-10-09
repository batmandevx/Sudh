package com.shuddh.lab.ui

import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Insights
import com.shuddh.lab.core.Lang
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.ScanRecord
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.fmt
import com.shuddh.lab.core.stamp

private data class Tile(val screen: Screen, val name: String, val hardware: String, val detects: String, val color: Color, val glyph: Glyph)

private val tilesList = listOf(
    Tile(Screen.SPECTRUM, "Spectrum", "Flash · camera · grating", "Water chemistry · milk adulterants · refills", Palette.violet, Glyph.SPECTRUM),
    Tile(Screen.POLAR, "Polar", "Flash · camera · gyro", "Sugar syrup in honey by optical rotation", Palette.amber, Glyph.POLAR),
    Tile(Screen.NIR, "NIR", "IR blaster · camera", "Water dilution at 940 nm (lock-in)", Palette.red, Glyph.NIR),
    Tile(Screen.NAMI, "Nami", "Speaker · mic sonar", "Moisture in soil, walls, grain, cloth", Palette.cyan, Glyph.NAMI),
    Tile(Screen.ECHO, "Echo", "Microphone", "Coconut fill · watermelon · container", Palette.blue, Glyph.ECHO),
    Tile(Screen.STRIP, "Strips", "Torch · camera · white ref", "Any colour strip: pH, hardness, arsenic", Palette.accent, Glyph.STRIP),
    Tile(Screen.SCATTER, "Hawa", "Flash · camera at 90°", "Smoke in air · cloudy water", Color(0xFF9AD0C2), Glyph.SCATTER),
    Tile(Screen.LENS, "Label Lens", "Camera · on-device OCR", "Expiry date, FSSAI licence, MRP check", Palette.amber, Glyph.STRIP),
    Tile(Screen.WHISTLE, "Whistle", "Microphone · FFT", "Counts pressure-cooker whistles for you", Palette.red, Glyph.ECHO),
    Tile(Screen.MAGNETO, "Magneto", "Compass magnetometer", "Is this steel utensil food-grade 304?", Palette.violet, Glyph.POLAR),
    Tile(Screen.FLOAT, "Float", "Lactometer", "Added water in milk (CLR → SNF)", Color(0xFFE8F1EC), Glyph.FLOAT),
)

data class Ready(val ok: Boolean, val text: String)

/** Which instruments are calibrated and ready to give verdicts. */
fun readiness(app: AppState): Map<Screen, Ready> {
    val p = app.prefs
    @Suppress("UNUSED_VARIABLE") val v = p.version // recompose when calibrations change
    val tests = p.keys("acal_").size
    val echo = listOf("coconut", "watermelon", "container").count { p.has("echo2_${it}_good") && p.has("echo2_${it}_bad") }
    val charts = p.keys("strip_").size
    return mapOf(
        Screen.SPECTRUM to if (p.has("wavecal")) Ready(true, "λ ok · $tests std") else Ready(false, "Calibrate λ"),
        Screen.POLAR to if (p.has("polar_pure") && p.has("polar_syrup")) Ready(true, "Refs set") else Ready(false, "Set refs"),
        Screen.NIR to when {
            !app.hasIr -> Ready(false, "No IR blaster")
            p.has("nir_pure") && p.has("nir_dil") -> Ready(true, "Refs set")
            else -> Ready(false, "Set refs")
        },
        Screen.ECHO to if (echo > 0) Ready(true, "$echo trained") else Ready(false, "Tap 2 refs"),
        Screen.STRIP to if (charts > 0) Ready(true, "$charts charts") else Ready(false, "Add chart"),
        Screen.NAMI to listOf("soil", "wall", "grain", "cloth").count { p.has("nami_${it}_dry") && p.has("nami_${it}_wet") }.let {
            if (it > 0) Ready(true, "$it trained") else Ready(false, "Dry + wet refs")
        },
        Screen.SCATTER to Ready(true, "Ready"),
        Screen.MAGNETO to Ready(true, "Ready"),
        Screen.MODELS to (com.shuddh.lab.core.ModelRole.entries.count { app.llm.installed(it) }).let { Ready(app.llm.hasChat() && app.llm.installed(com.shuddh.lab.core.ModelRole.TOOLS), "$it/3 models") },
        Screen.LENS to Ready(true, "Ready"),
        Screen.WHISTLE to if (p.has("whistle_pitch")) Ready(true, "Tuned") else Ready(true, "Ready"),
        Screen.FLOAT to Ready(true, "Ready"),
    )
}

private data class AppIcon(val screen: Screen, val name: String, val color: Color, val glyph: Glyph)

private val foodWater = listOf(
    AppIcon(Screen.SPECTRUM, "Spectrum", Palette.violet, Glyph.SPECTRUM),
    AppIcon(Screen.POLAR, "Honey", Palette.amber, Glyph.POLAR),
    AppIcon(Screen.NIR, "Infrared", Palette.red, Glyph.NIR),
    AppIcon(Screen.FLOAT, "Milk", Color(0xFFE8F1EC), Glyph.FLOAT),
)
private val soundSensors = listOf(
    AppIcon(Screen.ECHO, "Echo", Palette.blue, Glyph.ECHO),
    AppIcon(Screen.NAMI, "Moisture", Palette.cyan, Glyph.NAMI),
    AppIcon(Screen.WHISTLE, "Whistle", Palette.red, Glyph.COOKER),
    AppIcon(Screen.MAGNETO, "Steel", Palette.violet, Glyph.MAGNET),
)
private val moreTools = listOf(
    AppIcon(Screen.STRIP, "Strips", Palette.accent, Glyph.STRIP),
    AppIcon(Screen.SCATTER, "Air", Color(0xFF9AD0C2), Glyph.SCATTER),
    AppIcon(Screen.MODELS, "AI models", Palette.violet, Glyph.SPARK),
    AppIcon(Screen.GUIDE, "Kit guide", Palette.amber, Glyph.KIT),
)

/** A single, useful next step calculated from the user's own on-device lab data. */
private data class LabPlan(
    val kicker: String,
    val title: String,
    val detail: String,
    val action: String,
    val screen: Screen,
    val color: Color,
    val emoji: String,
)

private fun instrumentScreen(record: ScanRecord): Screen? = when {
    record.instrument.contains("Spectrum", true) -> Screen.SPECTRUM
    record.instrument.contains("Polar", true) -> Screen.POLAR
    record.instrument.contains("NIR", true) -> Screen.NIR
    record.instrument.contains("Echo", true) -> Screen.ECHO
    record.instrument.contains("Nami", true) -> Screen.NAMI
    record.instrument.contains("Magneto", true) -> Screen.MAGNETO
    record.instrument.contains("Whistle", true) -> Screen.WHISTLE
    record.instrument.contains("Float", true) -> Screen.FLOAT
    record.instrument.contains("Strip", true) -> Screen.STRIP
    record.instrument.contains("Hawa", true) || record.instrument.contains("Scatter", true) -> Screen.SCATTER
    record.instrument.contains("Lens", true) -> Screen.LENS
    else -> null
}

/**
 * We only ask for a repeat while its 15-minute evidence window is still open. It keeps the
 * home screen useful without claiming that a single screening reading is a final verdict.
 */
private fun nextLabPlan(app: AppState, ready: Map<Screen, Ready>): LabPlan {
    val records = app.store.records
    val latest = records.lastOrNull()
    val canConfirm = latest != null &&
        latest.level in setOf(Level.UNSAFE, Level.CAUTION) && !latest.confirmed &&
        System.currentTimeMillis() - latest.time in 0 until 15 * 60 * 1000L
    if (canConfirm) {
        return LabPlan(
            "EVIDENCE WINDOW OPEN",
            "Confirm ${latest!!.analyte}",
            "A matching second scan within 15 minutes turns a provisional result into stronger evidence.",
            "Repeat this scan",
            instrumentScreen(latest) ?: Screen.HISTORY,
            if (latest.level == Level.UNSAFE) Palette.red else Palette.amber,
            "↻",
        )
    }
    if (records.isEmpty()) {
        return LabPlan(
            "START HERE · ABOUT 30 SECONDS",
            "Check a food label",
            "Point the camera at any packet. Shuddh reads expiry, FSSAI licence and MRP completely on-device.",
            "Open Label Lens",
            Screen.LENS,
            Palette.amber,
            "⌁",
        )
    }
    val calibration = listOf(Screen.SPECTRUM, Screen.POLAR, Screen.NAMI, Screen.STRIP)
        .firstOrNull { ready[it]?.ok == false }
    if (calibration != null) {
        val name = when (calibration) {
            Screen.SPECTRUM -> "Spectrum"
            Screen.POLAR -> "Honey"
            Screen.NAMI -> "Moisture"
            else -> "Strip reader"
        }
        return LabPlan(
            "MAKE YOUR NEXT RESULT STRONGER",
            "Set up $name",
            ready[calibration]?.text?.let { "$it. A saved reference makes later readings more trustworthy." }
                ?: "Save a reference before the next reading.",
            "Set a reference",
            calibration,
            Palette.cyan,
            "◌",
        )
    }
    val lastWeek = latest != null && System.currentTimeMillis() - latest.time > 7 * 86_400_000L
    return if (lastWeek) {
        LabPlan(
            "WEEKLY SAFETY RHYTHM",
            "Refresh your kitchen pulse",
            "Your last saved check was over a week ago. Start with water, milk or a label in the kitchen today.",
            "Test food & water",
            Screen.SPECTRUM,
            Palette.violet,
            "✦",
        )
    } else {
        LabPlan(
            "LAB IS IN GOOD SHAPE",
            "Explore one more check",
            "Try a different instrument to broaden your kitchen snapshot and unlock your next lab badge.",
            "Browse food guide",
            Screen.FOODGUIDE,
            Palette.accent,
            "✦",
        )
    }
}

@Composable
fun HomeScreen(app: AppState) {
    val ready = readiness(app)
    val records = app.store.records
    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Aurora(intensity = 0.8f)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            HomeHeader(app)
            HeroCard(app, Modifier.enter(1))
            LabPulse(app, ready, Modifier.enter(2))
            AskBar(Modifier.enter(3)) { app.go(Screen.ASSISTANT) }
            QuickActions(app, Modifier.enter(4))
            IconSection("Test food & water", foodWater, ready, app, Modifier.enter(5))
            IconSection("Sound & sensors", soundSensors, ready, app, Modifier.enter(6))
            IconSection("More tools", moreTools, ready, app, Modifier.enter(7))
            ExpiringStrip(app)
            DiscoverRow(app, Modifier.enter(8))
            if (records.isNotEmpty()) RecentStrip(app)
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun LabPulse(app: AppState, ready: Map<Screen, Ready>, modifier: Modifier) {
    val plan = nextLabPlan(app, ready)
    val tools = listOf(Screen.SPECTRUM, Screen.POLAR, Screen.NIR, Screen.NAMI, Screen.ECHO, Screen.STRIP, Screen.SCATTER, Screen.LENS, Screen.WHISTLE, Screen.MAGNETO, Screen.FLOAT)
    val readyCount = tools.count { ready[it]?.ok == true }
    val chainIntact = app.store.verifyChain() == -1
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(plan.color.copy(alpha = 0.19f), Color(0x1AFFFFFF), Color(0x08000000))))
            .border(1.dp, plan.color.copy(alpha = 0.42f), RoundedCornerShape(24.dp))
            .clickable { if (plan.screen in tabs) app.tab(plan.screen) else app.go(plan.screen) }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(15.dp))
                    .background(plan.color.copy(alpha = 0.20f)).border(1.dp, plan.color.copy(alpha = 0.42f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(plan.emoji, color = plan.color, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(plan.kicker, color = plan.color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(plan.title, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Text("›", color = plan.color, fontSize = 30.sp)
        }
        Text(plan.detail, color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                plan.action.uppercase(), color = Color(0xFF032016), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(plan.color, plan.color.copy(alpha = 0.75f)))).padding(horizontal = 13.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text("Tap to open", color = Palette.muted, fontSize = 11.sp)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(Color(0x26000000)).padding(horizontal = 11.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PulseMetric("${readyCount}/${tools.size}", "tools ready", Palette.cyan)
            PulseDivider()
            PulseMetric(if (chainIntact) "SEALED" else "CHECK", "scan evidence", if (chainIntact) Palette.accent else Palette.red)
            PulseDivider()
            PulseMetric("LOCAL", "data stays here", Palette.violet)
        }
    }
}

@Composable
private fun PulseMetric(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Palette.muted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun PulseDivider() = Box(Modifier.width(1.dp).height(24.dp).background(Palette.line))

@Composable
private fun HomeHeader(app: AppState) {
    var menu by remember { mutableStateOf(false) }
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greet = when (hour) {
        in 5..11 -> Txt("Good morning", "सुप्रभात", "ಶುಭೋದಯ")
        in 12..16 -> Txt("Good afternoon", "नमस्ते", "ಶುಭ ಮಧ್ಯಾಹ್ನ")
        else -> Txt("Good evening", "शुभ संध्या", "ಶುಭ ಸಂಜೆ")
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.enter(0)) {
        LogoMark(40.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "shuddh",
                style = androidx.compose.ui.text.TextStyle(
                    brush = Brush.linearGradient(listOf(Color(0xFFEFF4FA), Color(0xFF8EF0C8), Color(0xFF7DD3FC))),
                    fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = Display, letterSpacing = (-0.8).sp,
                ),
            )
            Text(greet.get(app.lang) + " 👋", color = Palette.muted, fontSize = 13.sp)
        }
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Palette.glass).border(1.dp, Palette.line, RoundedCornerShape(50))
                    .clickable { menu = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(when (app.lang) { Lang.EN -> "EN"; Lang.HI -> "हि"; Lang.KN -> "ಕ"; Lang.TE -> "తె"; Lang.TA -> "த" }, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(" ▾", color = Palette.muted, fontSize = 11.sp)
            }
            androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                Lang.entries.forEach { l ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(l.label) }, onClick = { app.setLanguage(l); menu = false })
                }
            }
        }
    }
}

@Composable
private fun HeroCard(app: AppState, modifier: Modifier) {
    val records = app.store.records
    val score = app.store.kitchenScore()
    val streak = Insights.streaks(records.toList()).first
    val status = when {
        score == null -> Txt("Run your first test to start your kitchen score", "किचन स्कोर के लिए पहला टेस्ट करें", "ಮೊದಲ ಪರೀಕ್ಷೆ ಮಾಡಿ")
        score.first >= 80 -> Txt("Your kitchen looks clean this week", "इस हफ्ते रसोई ठीक है", "ಈ ವಾರ ಅಡುಗೆಮನೆ ಚೆನ್ನಾಗಿದೆ")
        score.first >= 50 -> Txt("A few items need attention", "कुछ चीज़ों पर ध्यान दें", "ಕೆಲವು ವಸ್ತುಗಳಿಗೆ ಗಮನ ಬೇಕು")
        else -> Txt("Several items failed — retest and act", "कई चीज़ें फेल हुईं", "ಹಲವು ವಸ್ತುಗಳು ವಿಫಲವಾಗಿವೆ")
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B2B4A), Color(0xFF12322B), Color(0xFF0F1A2A))))
            .border(1.dp, Brush.linearGradient(listOf(Color(0x5534D399), Color(0x1122D3EE))), RoundedCornerShape(28.dp))
            .clickable { app.tab(Screen.INSIGHTS) }.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpectrumOrb(score?.first, 104.dp, "/100")
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Txt("Kitchen Health", "रसोई स्वास्थ्य", "ಅಡುಗೆಮನೆ ಆರೋಗ್ಯ").get(app.lang), color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                Text(status.get(app.lang), color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                score?.second?.let { Badge(it, if (it.startsWith("No failures")) Palette.accent else Palette.amber) }
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x33000000)).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            HeroStat("${records.size}", "scans", Palette.text, Modifier.weight(1f))
            VDivider()
            HeroStat("${records.count { it.level == Level.UNSAFE }}", "unsafe", Palette.red, Modifier.weight(1f))
            VDivider()
            HeroStat("${app.store.vendors().size}", "vendors", Palette.blue, Modifier.weight(1f))
            VDivider()
            HeroStat("$streak", "🔥 streak", Palette.amber, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroStat(v: String, label: String, c: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CountUp(v.toIntOrNull() ?: 0) { Text(it, color = c, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp) }
        Text(label, color = Palette.muted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun VDivider() = Box(Modifier.width(1.dp).height(28.dp).background(Palette.line))

@Composable
private fun AskBar(modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(Color(0x332A1F5C), Color(0x2222D3EE))))
            .border(1.dp, Brush.horizontalGradient(listOf(Color(0x888B5CF6), Color(0x6622D3EE))), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✨", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Text("Ask Shuddh — \"set a 10 min timer\", \"is my milk safe?\"", color = Palette.muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f),
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Box(Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF22D3EE)))), contentAlignment = Alignment.Center) {
            Text("🎙", fontSize = 15.sp)
        }
    }
}

private enum class QI { LENS, SEARCH, VIDEO, MESH, REPORT, MAP }

@Composable
private fun QuickActions(app: AppState, modifier: Modifier) {
    val items = listOf(
        Triple(QI.LENS, "Label", Screen.LENS), Triple(QI.SEARCH, "Photos", Screen.MEDIA), Triple(QI.VIDEO, "Video", Screen.VIDEO),
        Triple(QI.MESH, "Mesh", Screen.MESH), Triple(QI.MAP, "Map", Screen.MAP),
    )
    val colors = listOf(Palette.amber, Palette.violet, Palette.cyan, Palette.blue, Palette.accent)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEachIndexed { i, (kind, label, screen) ->
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).clickable { if (screen == Screen.HISTORY) app.tab(screen) else app.go(screen) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(colors[i].copy(alpha = 0.16f)).border(1.dp, colors[i].copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { LineIcon(kind, colors[i], Modifier.size(26.dp)) }
                Spacer(Modifier.height(6.dp))
                Text(label, color = Palette.text, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** Minimal stroked icons in one consistent style. */
@Composable
private fun LineIcon(k: QI, c: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val st = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when (k) {
            QI.LENS -> { // viewfinder corners + dot
                val l = w * 0.28f
                listOf(0f to 0f, w to 0f, 0f to h, w to h).forEach { (x, y) ->
                    val dx = if (x == 0f) l else -l; val dy = if (y == 0f) l else -l
                    drawLine(c, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x + dx, y), st.width, st.cap)
                    drawLine(c, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x, y + dy), st.width, st.cap)
                }
                drawCircle(c, w * 0.12f, center)
            }
            QI.SEARCH -> {
                drawCircle(c, w * 0.3f, androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.42f), style = st)
                drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.64f, h * 0.64f), androidx.compose.ui.geometry.Offset(w * 0.92f, h * 0.92f), st.width, st.cap)
            }
            QI.VIDEO -> {
                drawRoundRect(c, androidx.compose.ui.geometry.Offset(w * 0.04f, h * 0.18f), androidx.compose.ui.geometry.Size(w * 0.92f, h * 0.64f), androidx.compose.ui.geometry.CornerRadius(w * 0.14f), style = st)
                val tri = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.42f, h * 0.36f); lineTo(w * 0.66f, h * 0.5f); lineTo(w * 0.42f, h * 0.64f); close() }
                drawPath(tri, c)
            }
            QI.MESH -> {
                drawCircle(c, w * 0.09f, center)
                listOf(0.28f, 0.46f).forEach { r ->
                    drawArc(c, -50f, 100f, false, androidx.compose.ui.geometry.Offset(center.x - w * r, center.y - w * r), androidx.compose.ui.geometry.Size(w * r * 2, w * r * 2), style = st)
                    drawArc(c, 130f, 100f, false, androidx.compose.ui.geometry.Offset(center.x - w * r, center.y - w * r), androidx.compose.ui.geometry.Size(w * r * 2, w * r * 2), style = st)
                }
            }
            QI.MAP -> { // map pin over folded map
                val fold = androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.05f, h * 0.3f); lineTo(w * 0.35f, h * 0.18f); lineTo(w * 0.65f, h * 0.3f); lineTo(w * 0.95f, h * 0.18f)
                    lineTo(w * 0.95f, h * 0.82f); lineTo(w * 0.65f, h * 0.94f); lineTo(w * 0.35f, h * 0.82f); lineTo(w * 0.05f, h * 0.94f); close() }
                drawPath(fold, c.copy(alpha = 0.35f), style = st)
                drawCircle(c, w * 0.17f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.42f))
                drawCircle(Color(0xFF0E1522), w * 0.07f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.42f))
                drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.58f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.74f), st.width, st.cap)
            }
            QI.REPORT -> {
                drawRoundRect(c, androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.06f), androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.88f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f), style = st)
                listOf(0.32f, 0.5f, 0.68f).forEach { y -> drawLine(c, androidx.compose.ui.geometry.Offset(w * 0.34f, h * y), androidx.compose.ui.geometry.Offset(w * 0.66f, h * y), st.width * 0.8f, st.cap) }
            }
        }
    }
}

@Composable
private fun IconSection(title: String, icons: List<AppIcon>, ready: Map<Screen, Ready>, app: AppState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("${icons.count { ready[it.screen]?.ok == true }}/${icons.size} ready", color = Palette.muted, fontSize = 12.sp)
        }
        icons.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { ic -> AppIconTile(ic, ready[ic.screen], Modifier.weight(1f)) { app.go(ic.screen) } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppIconTile(ic: AppIcon, r: Ready?, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.size(62.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(ic.color.copy(alpha = 0.32f), ic.color.copy(alpha = 0.08f))))
                    .border(1.dp, ic.color.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) { InstrumentGlyph(ic.glyph, ic.color, Modifier.size(36.dp), animated = false) }
            r?.takeIf { ic.screen != Screen.GUIDE }?.let {
                Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(12.dp).clip(CircleShape).background(Palette.bg).padding(2.dp).clip(CircleShape)
                    .background(if (it.ok) Palette.accent else Palette.amber))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(ic.name, color = Palette.text, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun RecentStrip(app: AppState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recent", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("See all", color = Palette.cyan, fontSize = 12.sp, modifier = Modifier.clickable { app.tab(Screen.HISTORY) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            app.store.records.takeLast(8).reversed().forEach { r ->
                Column(
                    Modifier.width(150.dp).clip(RoundedCornerShape(20.dp)).background(Palette.glass).border(1.dp, Color(r.level.argb).copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .clickable { app.tab(Screen.HISTORY) }.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LevelRing(r.level, 30.dp)
                    Text(r.analyte, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                    Text(r.value?.let { "${fmt(it)} ${r.unit}" } ?: "—", color = Palette.muted, fontSize = 12.sp)
                    Text(stamp(r.time).substringBefore(","), color = Palette.muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun OnboardingScreen(app: AppState, requestPermissions: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var area by remember { mutableStateOf(app.prefs.area) }
    var phone by remember { mutableStateOf(app.prefs.familyPhone) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i -> Box(Modifier.weight(1f).height(4.dp).background(if (i <= step) Palette.accent else Palette.line, RoundedCornerShape(2.dp))) }
        }
        when (step) {
            0 -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LogoMark(150.dp) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GradientTitle("shuddh", 46) }
                Text("Your phone is already a laboratory.", fontSize = 22.sp, color = Palette.text, fontWeight = FontWeight.SemiBold)
                listOf(
                    "🔬  The flash, camera, IR blaster, gyroscope and microphone become a spectrometer, polarimeter, infrared probe and acoustic sensor.",
                    "🥛  Test milk, water, honey, air and produce with ₹10 reagents and a ₹50 kit.",
                    "🗣  Verdicts spoken in English, हिन्दी and ಕನ್ನಡ — with the evidence behind every one.",
                    "🔒  Tests run on your phone. Optional web search sends only the search text you enter.",
                ).forEach { Text(it, color = Palette.text, fontSize = 15.sp) }
                Btn("Get started", Modifier.fillMaxWidth()) { step = 1 }
            }
            1 -> {
                Text("Your language and area", fontSize = 22.sp, color = Palette.text, fontWeight = FontWeight.SemiBold)
                Chips(Lang.entries, app.lang, { it.label }) { app.setLanguage(it) }
                Btn("Hear a sample", primary = false) {
                    app.voice.speak(Txt("Shuddh is ready.", "शुद्ध तैयार है।", "ಶುದ್ಧ ಸಿದ್ಧವಾಗಿದೆ.").get(app.lang), app.lang)?.let { app.ctx.toastLong(it) }
                }
                OutlinedTextField(area, { area = it }, label = { Text("Your locality / ward (for the area purity board)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("Family member's phone (optional, for SMS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Btn("Continue", Modifier.fillMaxWidth()) {
                    app.prefs.area = area.trim(); app.prefs.familyPhone = phone.trim(); step = 2
                }
            }
            else -> {
                Text("Two permissions", fontSize = 22.sp, color = Palette.text, fontWeight = FontWeight.SemiBold)
                Text("Camera — every optical instrument reads light through it.", color = Palette.text)
                Text("Microphone — only for Echo (tap acoustics), only while listening.", color = Palette.text)
                Text("Camera and microphone processing runs on this phone. Web search and online maps are optional.", color = Palette.muted, fontSize = 13.sp)
                Btn("Allow and open the lab", Modifier.fillMaxWidth()) { requestPermissions(); app.finishOnboarding() }
            }
        }
        Text("Screening-grade results. Confirm critical findings with an accredited lab.", color = Palette.muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun GuideScreen(app: AppState) {
    ScreenFrame("Build the kit", "≈ ₹50 of parts, 20 minutes", onBack = { app.back() }) {
        guide.forEach { (title, body) ->
            Section(title) { body.forEach { Note("• $it", Palette.text) } }
        }
    }
}

private val guide = listOf(
    "General rules" to listOf(
        "Work inside a black cardboard box: ambient light is the enemy of every optical reading.",
        "Keep geometry fixed: tape the phone, light path and vial holder so nothing moves between reference and sample.",
        "Always capture a reference (blank) right before the sample. The app locks exposure and white balance while measuring.",
    ),
    "Spectrum (CD-grating spectrometer)" to listOf(
        "Cut a 1×2 cm piece of a CD/DVD (peel the label layer off a DVD for a clear grating).",
        "Make a slit from two razor blades ~0.3 mm apart on black card at the far end of a 15–20 cm tube/box.",
        "Light source → vial → slit → tube → grating taped over the camera at ~45–60°. Rotate until a rainbow band appears horizontally.",
        "Calibrate wavelengths once by pointing the slit at a CFL tube: tap Auto-calibrate to lock the 436 nm and 546 nm mercury lines.",
        "Quantitative tests: run 2–4 standards from your reagent kit (e.g. 0, 0.5, 1, 2 mg/L) to fit the Beer–Lambert line.",
        "Milk adulterant tests: use pure milk run through the same reagent as the reference — then any extra colour is the adulterant.",
        "Fingerprints: capture a trusted product once and save it; later bottles are compared against it to catch refills.",
    ),
    "Polar (honey polarimeter)" to listOf(
        "Two linear polariser films (old 3D-cinema glasses or polarised sunglass lenses).",
        "Lamp → polariser 1 → vial (2–4 cm path) → polariser 2 taped on the camera lens.",
        "Hold the vial setup fixed and rotate the PHONE about its camera axis through 180°+; the gyroscope logs the angle.",
        "Sweep once with an empty/water vial (blank), then honey. Save a known-pure honey and a sugar syrup as references.",
    ),
    "NIR (IR blaster)" to listOf(
        "Phones with an IR blaster emit ~940 nm. Many rear cameras block IR — test by pointing a TV remote at each camera.",
        "Blaster → vial → camera, inside a dark box. The app pulses the blaster on/off and subtracts the 'off' frames (lock-in).",
        "Optional: a piece of exposed, developed film negative in front of the camera blocks visible light and passes IR.",
    ),
    "Echo (tap acoustics)" to listOf(
        "Hold the phone mic 5–10 cm from the coconut/watermelon in a quiet room and tap firmly with a knuckle.",
        "Save one reference you know is full/ripe and one that is empty/unripe; later taps are placed between them.",
    ),
    "Strips" to listOf(
        "Put a piece of plain white paper next to the strip pad. Torch on. Align the W box on the paper and the S box on the pad.",
        "Capture each colour block of the kit's printed chart once (with its value) — then read any strip against it.",
    ),
    "Hawa / Turbidity" to listOf(
        "Dark box with a narrow flash beam across it; camera looks at the beam from the side (90°).",
        "Capture a clean-air (or clear-water) baseline, then the sample. Scatter rises with particles.",
    ),
)

/** Packets expiring within a week, with animated countdown rings. */
@Composable
private fun ExpiringStrip(app: AppState) {
    val soon = app.pantry.sorted().filter { it.daysLeft <= 7 }
    if (soon.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use soon", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("Pantry ›", color = Palette.cyan, fontSize = 12.sp, modifier = Modifier.clickable { app.go(Screen.PANTRY) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            soon.take(8).forEach { it ->
                Column(
                    Modifier.width(108.dp).clip(RoundedCornerShape(18.dp)).background(Palette.glass).border(1.dp, pantryColor(it.daysLeft).copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                        .clickable { app.go(Screen.PANTRY) }.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ExpiryRing(it, 48.dp)
                    Text(it.name, color = Palette.text, fontSize = 11.sp, maxLines = 1)
                    Text(if (it.daysLeft < 0) "expired" else "${it.daysLeft}d left", color = pantryColor(it.daysLeft), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Discover: guide, pantry, badges, share card — the "life around the lab" features. */
@Composable
private fun DiscoverRow(app: AppState, modifier: Modifier) {
    val badges = com.shuddh.lab.core.Badges.all(app.store, app.prefs)
    val items = listOf(
        Triple("📖", "Food guide", "16 home tests") to { app.go(Screen.FOODGUIDE) },
        Triple("🥫", "Pantry", "${app.pantry.items.size} tracked") to { app.go(Screen.PANTRY) },
        Triple("🏅", "Badges", "${badges.count { it.unlocked }}/${badges.size}") to { app.go(Screen.BADGES) },
        Triple("❤️", "Pulse", "heart rate") to { app.go(Screen.PULSE) },
        Triple("👁️", "Vision Lab", "hands · face · objects") to { app.go(Screen.VISION) },
        Triple("🤖", "Ask Shuddh", "recipes · tasks · AI") to { app.go(Screen.ASSISTANT) },
        Triple("♨️", "Boil Guard", "safe drinking water") to { app.go(Screen.BOIL) },
        Triple("🍳", "Oil Check", "frying-oil reuse") to { app.go(Screen.OIL) },
        Triple("🌾", "Grain Scan", "stones · insects · broken") to { app.go(Screen.GRAIN) },
        Triple("🌐", "Hive", "warn your neighbours") to { app.tab(Screen.COMMUNITY) },
        Triple("🚨", "Outbreak Watch", "early warning for your area") to { app.go(Screen.OUTBREAK) },
        Triple("🩸", "Anaemia Screen", "pallor check · free Hb test") to { app.go(Screen.ANAEMIA) },
    )
    val colors = listOf(Palette.cyan, Palette.accent, Palette.amber, Palette.red, Color(0xFFA78BFA), Palette.blue, Color(0xFFF97316), Color(0xFFFDE047), Color(0xFFD9F99D), Palette.violet, Palette.red, Color(0xFFF472B6))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Discover", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        items.chunked(2).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { c, (t, go) ->
                    val col = colors[r * 2 + c]
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(col.copy(alpha = 0.22f), col.copy(alpha = 0.05f))))
                            .border(1.dp, col.copy(alpha = 0.35f), RoundedCornerShape(20.dp)).clickable { go() }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t.first, fontSize = 24.sp); Spacer(Modifier.width(10.dp))
                        Column {
                            Text(t.second, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(t.third, color = Palette.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
