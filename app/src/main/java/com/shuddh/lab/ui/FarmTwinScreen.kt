package com.shuddh.lab.ui

import androidx.compose.ui.graphics.drawscope.rotate
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Agro
import com.shuddh.lab.core.Farm
import com.shuddh.lab.core.FarmData
import com.shuddh.lab.core.FarmTwin
import com.shuddh.lab.core.Irrigation
import com.shuddh.lab.core.Past
import com.shuddh.lab.core.Plan
import com.shuddh.lab.core.Scenario
import com.shuddh.lab.core.Wx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.sin

/** CrispRoots sections. [needsFarm] ones require the farm's location and profile. */
private enum class Section(val emoji: String, val title: String, val sub: String, val color: Long, val needsFarm: Boolean, val sensors: String) {
    TWIN("🛰", "3D Farm Twin", "Watch your crop grow with live weather", 0xFF059669, true, "GPS · gyro"),
    PLAN("🌱", "What to grow", "Every crop simulated & ranked", 0xFF16A34A, true, "GPS"),
    LEAF("🍃", "Leaf Doctor", "Snap a leaf → disease & cure", 0xFF22C55E, false, "camera · AI · flash"),
    HERD("🐄", "Animal health", "Symptoms, breathing rate, herd book", 0xFFF59E0B, false, "camera"),
    FIELD("📡", "Field Kit", "Area, slope, sun, vigour, spray window", 0xFF0EA5E9, false, "GPS · accel · compass · light · camera"),
    MARKET("💰", "Sell smart", "When & where — live mandi + news", 0xFF2563EB, true, "internet"),
    TRAITS("🧬", "Gene Trait Lab", "Design edited variants, CRISP fit score", 0xFF7C3AED, true, "simulation"),
    CALENDAR("📅", "Crop calendar", "Fertiliser doses & task reminders", 0xFF0891B2, true, "simulation"),
    COMBOS("🐟", "Rice + fish & more", "Integrated farming systems", 0xFF0284C7, true, "—"),
    SCHEMES("🏛", "Schemes & forms", "PM-KISAN, PMFBY, KCC… pre-filled", 0xFFD97706, false, "—"),
}

/** Everything the twin needs, held for the screen. */
class TwinState(val ctx: Context) {
    var forecast by mutableStateOf<Map<Long, Wx>>(emptyMap())
    var lastYear by mutableStateOf<Map<Long, Wx>>(emptyMap())
    var syncing by mutableStateOf(false)
    var syncNote by mutableStateOf("")
    var scenario by mutableStateOf(Scenario.LIVE)
    var newsScore by mutableStateOf(0)
    var headlines by mutableStateOf<List<String>>(emptyList())

    fun wxFor(lat: Double): (Long, Int) -> List<Wx> = { from, n -> FarmTwin.series(lat, from, n, forecast, lastYear) }
}

@Composable
fun FarmTwinScreen(app: AppState, asTab: Boolean = false) {
    val prefs = remember { app.ctx.getSharedPreferences("shuddh_farm", Context.MODE_PRIVATE) }
    var farm by remember { mutableStateOf(app.farmStore.farm) }
    val st = remember { TwinState(app.ctx) }
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf<Section?>(null) }
    var editing by remember { mutableStateOf(false) }

    fun save(f: Farm) { farm = f; app.farmStore.save(f) }

    fun sync(force: Boolean) = scope.launch {
        if (!farm.located) return@launch
        st.syncing = true; st.syncNote = "Fetching 16-day forecast and last year's weather…"
        val (f, h) = FarmData.weather(app.ctx, farm.lat, farm.lon, force)
        st.forecast = f; st.lastYear = h
        prefs.edit().putBoolean("synced", true).apply()
        st.syncNote = if (f.isEmpty() && h.isEmpty()) "Offline — using typical climate for your latitude." else "Live: ${f.size}-day forecast · ${h.size} days of history"
        st.syncing = false
    }

    // Real weather is the twin's backbone: fetch it as soon as the farm has a location (cached 6 h).
    LaunchedEffect(farm.lat, farm.lon) { if (farm.located) sync(false) }
    androidx.activity.compose.BackHandler(enabled = section != null || editing) { if (editing) editing = false else section = null }

    val plan = farm.plans.firstOrNull()
    val sec = section
    ScreenFrame(
        if (sec == null) "CrispRoots" else sec.title,
        if (sec == null) "Farm intelligence on your phone" else sec.sub,
        onBack = if (sec != null || editing) ({ if (editing) editing = false else section = null }) else if (asTab) null else ({ app.back() }),
    ) {
        if (editing) { FarmSetup(app, farm, Modifier.enter(0)) { f -> save(f); editing = false; sync(true) }; return@ScreenFrame }
        if (sec == null) {
            FarmHub(farm, plan, st, onEdit = { editing = true }, onPick = { p -> save(farm.copy(plans = listOf(p.copy(acres = farm.acres)))); section = Section.TWIN }) { s -> section = s }
            return@ScreenFrame
        }
        if (sec.needsFarm && !farm.located) {
            Note("Set up your farm first — it takes a minute.")
            FarmSetup(app, farm, Modifier.enter(0)) { f -> save(f); sync(true) }
            return@ScreenFrame
        }
        if (sec.needsFarm) SyncBar(st, prefs.getBoolean("synced", false)) { sync(true) }
        when (sec) {
            Section.TWIN -> if (plan == null) {
                Glass(glow = Palette.accent, padding = 16) {
                    Text("Pick a crop to simulate", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Note("The twin ranks crops for your soil, water and the coming weather. Choose one to see it grow day by day.")
                    Btn("🌱 See what to grow", Modifier.fillMaxWidth()) { section = Section.PLAN }
                }
            } else TwinView(farm, plan, st) { p -> save(farm.copy(plans = listOf(p))) }
            Section.PLAN -> PlanTab(farm, st) { p -> save(farm.copy(plans = listOf(p))); section = Section.TWIN }
            Section.MARKET -> MarketTab(app, farm, plan, st)
            Section.TRAITS -> TraitTab(farm, plan, st) { p -> save(farm.copy(plans = listOf(p))); section = Section.TWIN }
            Section.COMBOS -> CombosTab(farm, plan)
            Section.LEAF -> LeafDoctorTab(app)
            Section.HERD -> HerdTab(app)
            Section.FIELD -> FieldKitTab(app, farm) { f -> save(f) }
            Section.CALENDAR -> CalendarTab(app, farm, plan, st)
            Section.SCHEMES -> SchemesTab(app, farm)
        }
    }
}

/** Hub: interactive 3D farm, today's status, and the feature grid. */
@Composable
private fun FarmHub(farm: Farm, plan: Plan?, st: TwinState, onEdit: () -> Unit, onPick: (Plan) -> Unit = {}, onOpen: (Section) -> Unit) {
    val crop = plan?.let { Agro.crop(it.crop) }
    var today by remember { mutableStateOf<FarmTwin.DayState?>(null) }
    LaunchedEffect(plan, st.forecast.size) {
        today = if (plan == null || crop == null) null else withContext(Dispatchers.Default) {
            val r = FarmTwin.simulate(farm, plan, st.wxFor(farm.lat)(plan.sowDay, (crop.days * 1.8).toInt()))
            val d = LocalDate.now().toEpochDay()
            r.days.firstOrNull { it.day == d } ?: if (d < plan.sowDay) null else r.days.lastOrNull()
        }
    }
    var cur by remember { mutableStateOf<FarmData.Current?>(null) }
    var picks by remember { mutableStateOf<List<FarmTwin.Pick>>(emptyList()) }
    LaunchedEffect(farm.lat, farm.lon) { if (farm.located) cur = FarmData.current(farm.lat, farm.lon) }
    LaunchedEffect(farm, st.forecast.size) { if (farm.located) picks = withContext(Dispatchers.Default) { FarmTwin.rank(farm, st.wxFor(farm.lat), Scenario.LIVE).take(3) } }
    val inf = rememberInfiniteTransition(label = "hubgrow")
    val idle by inf.animateFloat(0.05f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "idle")
    val d = today
    Box(Modifier.fillMaxWidth().height(260.dp).enter(0)) {
        Farm3D(
            FarmScene(d?.progress?.toFloat() ?: idle, d?.soilWater?.toFloat() ?: 0.6f, (d?.rain ?: 0.0) > 2, d?.hot == true, d?.flooded == true,
                ripe = cropRipe(crop?.key), soil = soilColor(farm.soil), fishPond = plan?.combo == "ricefish" || crop?.key == "rice",
                wind = cur?.wind?.toFloat() ?: 6f, cloud = ((cur?.cloud ?: 20.0) / 100).toFloat(), shape = plantShape(crop?.key)).let { sc -> if ((cur?.rain ?: 0.0) > 0.2) sc.copy(rainy = true) else sc },
            Modifier.fillMaxWidth().height(260.dp),
        )
        Column(Modifier.align(Alignment.TopStart).padding(14.dp)) {
            Text("DIGITAL TWIN · ${if (farm.located) "LIVE" else "DEMO"}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000)).padding(horizontal = 8.dp, vertical = 3.dp))
            Spacer(Modifier.height(6.dp))
            Text(if (farm.located) farm.name else "Your farm in 3D", color = Color.White, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x44000000)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Text(
            when { crop == null -> "Drag to look around · tilt the phone"; d == null -> "${crop.emoji} ${crop.name} · sowing ${FarmTwin.fmtDay(plan!!.sowDay)}"; else -> "${crop.emoji} ${FarmTwin.stage(d.progress)} · ${d.tmax.toInt()}°C · soil ${(d.soilWater * 100).toInt().coerceAtMost(150)}%" },
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(50)).background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 5.dp),
        )
        Text(if (farm.located) "✎ Farm" else "Set up farm", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(50)).background(Color(0x66000000)).clickable(onClick = onEdit).padding(horizontal = 10.dp, vertical = 5.dp))
    }
    if (farm.located) Text("${"%.1f".format(farm.acres)} acres · ${Agro.soil(farm.soil).name} soil · ${farm.irrigation.label}" + (if (farm.district.isNotBlank()) " · ${farm.district}" else ""),
        color = Palette.muted, fontSize = 12.sp)
    ThisWeek(farm, plan, st, cur, Modifier.enter(1))
    cur?.let { LiveWeather(it, Modifier.enter(1)) }
    if (picks.isNotEmpty()) TopPicks(picks, Modifier.enter(2)) { p -> onPick(p) }
    Section.entries.chunked(2).forEachIndexed { r, row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEachIndexed { i, s -> FeatureTile(s, Modifier.weight(1f).enter((r * 2 + i).coerceAtMost(9))) { onOpen(s) } }
        }
    }
}

/** "This week on your farm": the next tasks from the crop calendar, with today's weather call. */
@Composable
private fun ThisWeek(farm: Farm, plan: Plan?, st: TwinState, cur: FarmData.Current?, modifier: Modifier) {
    val crop = plan?.let { Agro.crop(it.crop) } ?: return
    var tasks by remember { mutableStateOf<List<com.shuddh.lab.core.FieldKit.Task>>(emptyList()) }
    LaunchedEffect(plan, st.forecast.size) {
        tasks = withContext(Dispatchers.Default) {
            val r = FarmTwin.simulate(farm, plan, st.wxFor(farm.lat)(plan.sowDay, (crop.days * 1.8).toInt()))
            val today = LocalDate.now().toEpochDay()
            com.shuddh.lab.core.FieldKit.calendar(r).filter { it.day >= today - 1 }.take(3)
        }
    }
    if (tasks.isEmpty()) return
    val today = LocalDate.now().toEpochDay()
    Glass(modifier, glow = Palette.accent, padding = 14) {
        Text("📌 Next on your farm · ${crop.emoji} ${crop.name.substringBefore(" (")}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        tasks.forEachIndexed { i, t ->
            val dd = t.day - today
            Row(Modifier.fillMaxWidth().enter(i).clip(RoundedCornerShape(12.dp)).background(if (dd <= 7) Palette.accent.copy(alpha = 0.10f) else Palette.veil(0x08)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.emoji, fontSize = 18.sp); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(t.detail, color = Palette.muted, fontSize = 11.sp, maxLines = 2, lineHeight = 14.sp)
                }
                Text(when { dd < 0 -> "now"; dd == 0L -> "today"; dd == 1L -> "tomorrow"; else -> "in $dd d" }, color = if (dd <= 7) Palette.accent else Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        cur?.let { c -> FarmData.advice(c).firstOrNull()?.let { Text(it, color = Palette.text, fontSize = 12.sp) } }
    }
}

/** Live weather at the farm with an animated wind arrow, plus what to do about it. */
@Composable
private fun LiveWeather(c: FarmData.Current, modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "lw")
    val blow by inf.animateFloat(0f, 1f, infiniteRepeatable(tween((2400 - c.wind * 60).toInt().coerceIn(500, 2400), easing = LinearEasing)), label = "b")
    val dir by animateFloatAsState(c.windDir.toFloat(), tween(800), label = "d")
    Glass(modifier, glow = Palette.blue, padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(64.dp)) {
                drawCircle(Palette.veil(0x14), size.minDimension / 2)
                // Arrow points where the wind blows TO (meteorological direction + 180°).
                rotate(dir + 180f) {
                    val l = size.height * 0.36f
                    drawLine(Palette.blue, Offset(center.x, center.y + l), Offset(center.x, center.y - l), 5f, StrokeCap.Round)
                    drawLine(Palette.blue, Offset(center.x, center.y - l), Offset(center.x - 9f, center.y - l + 12f), 5f, StrokeCap.Round)
                    drawLine(Palette.blue, Offset(center.x, center.y - l), Offset(center.x + 9f, center.y - l + 12f), 5f, StrokeCap.Round)
                    for (k in 0..2) { val y = center.y + l - ((blow + k / 3f) % 1f) * 2 * l; drawCircle(Palette.cyan.copy(alpha = 0.7f), 3f, Offset(center.x + 10f, y)) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${c.temp.toInt()}°C · wind ${c.wind.toInt()} km/h${if (c.gust > c.wind + 5) " (gusts ${c.gust.toInt()})" else ""}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Humidity ${c.rh.toInt()}% · clouds ${c.cloud.toInt()}% · rain next 3 days ${"%.0f".format(c.rain3d)} mm", color = Palette.muted, fontSize = 11.sp)
            }
        }
        FarmData.advice(c).forEach { Text(it, color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp) }
    }
}

/** The twin's three best crops right now, as tappable cards. */
@Composable
private fun TopPicks(picks: List<FarmTwin.Pick>, modifier: Modifier, onPick: (Plan) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🌱 Best crops for your farm now", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            picks.forEachIndexed { i, p ->
                val r = p.result
                val float by rememberInfiniteTransition(label = "tp$i").animateFloat(-2f, 2f, infiniteRepeatable(tween(1500 + i * 200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "f")
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(Palette.accent.copy(alpha = 0.14f), Palette.surface)))
                    .border(1.dp, Palette.accent.copy(alpha = if (i == 0) 0.6f else 0.25f), RoundedCornerShape(18.dp)).clickable { onPick(r.plan) }.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.crop.emoji, fontSize = 28.sp, modifier = Modifier.graphicsLayer { translationY = float })
                    Text(r.crop.name.substringBefore(" ("), color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    Text(money(if (r.orchard) r.matureProfit else r.profitPerAcre) + "/ac", color = if (r.profitPerAcre > 0 || r.orchard) Palette.accent else Palette.red, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Text("sow ${FarmTwin.fmtDay(r.plan.sowDay)}", color = Palette.muted, fontSize = 10.sp)
                    if (r.surge >= 0.1) Text("📈 +${(r.surge * 100).toInt()}%", color = Palette.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FeatureTile(s: Section, modifier: Modifier, onClick: () -> Unit) {
    val c = Color(s.color)
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.45f), label = "ft")
    val inf = rememberInfiniteTransition(label = "ft${s.ordinal}")
    val float by inf.animateFloat(-3f, 3f, infiniteRepeatable(tween(1600 + s.ordinal * 130, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "fl")
    val shine by inf.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(3200 + s.ordinal * 170, easing = LinearEasing)), label = "sh")
    Column(
        modifier.androidx_graphics(scale).clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(c.copy(alpha = 0.22f), Palette.surface)))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .clickable(src, null, onClick = onClick)
            .drawBehindShine(shine, c)
            .height(150.dp).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(44.dp).androidx_offsetY(float).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.6f)))), contentAlignment = Alignment.Center) {
            Text(s.emoji, fontSize = 22.sp)
        }
        Text(s.title, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
        Text(s.sub, color = Palette.muted, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2)
        Spacer(Modifier.weight(1f))
        Text("⚙ ${s.sensors}", color = c, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun Modifier.androidx_graphics(scale: Float) = this.then(Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
private fun Modifier.androidx_offsetY(dy: Float) = this.then(Modifier.graphicsLayer { translationY = dy })
private fun Modifier.drawBehindShine(x: Float, c: Color) = this.then(Modifier.drawBehind {
    val sx = size.width * x
    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.18f), Color.Transparent), Offset(sx - 60f, 0f), Offset(sx + 60f, size.height)))
})

fun soilColor(key: String) = Color(when (key) { "black" -> 0xFF4A4038; "red" -> 0xFFB45309; "laterite" -> 0xFF9A3412; "sandy" -> 0xFFD6B98C; "clay" -> 0xFF78716C; else -> 0xFFA16207 })
fun cropRipe(key: String?) = when (key) { "rice", "wheat", "mustard", "quinoa" -> Color(0xFFEAB308); "cotton" -> Color(0xFFF8FAFC); "tomato", "chilli", "strawberry" -> Color(0xFFDC2626); "dragonfruit" -> Color(0xFFDB2777); else -> Color(0xFF15803D) }

// ── Hero & chrome ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun TwinHero(farm: Farm, st: TwinState, modifier: Modifier, onEdit: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "hero")
    val sweep by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "sw")
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF065F46), Color(0xFF0E7490), Color(0xFF1E3A8A))))
            .padding(16.dp),
    ) {
        // Satellite scan line sweeping over a field grid.
        Canvas(Modifier.matchParentSize()) {
            val step = 26.dp.toPx()
            var x = 0f; while (x < size.width) { drawLine(Color.White.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = 0f; while (y < size.height) { drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
            val sx = size.width * sweep
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x5534D399), Color.Transparent), sx - 80f, sx + 20f), Offset(sx - 80f, 0f), Size(100f, size.height))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DIGITAL TWIN · ${if (farm.located) "LIVE" else "SET UP"}", color = Color(0xFFA7F3D0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
                Text(if (farm.located) "✎ Edit farm" else "", color = Color.White, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onEdit).padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Text(if (farm.located) farm.name else "Build a living copy of your farm", color = Color.White, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 21.sp)
            Text(
                if (farm.located) "${"%.1f".format(farm.acres)} acres · ${Agro.soil(farm.soil).name} soil · ${farm.irrigation.label}" + (if (farm.district.isNotBlank()) " · ${farm.district}" else "")
                else "Weather, soil, your past crops and the market — simulated before you sow.",
                color = Color(0xFFE0F2FE), fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun SyncBar(st: TwinState, synced: Boolean, onSync: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.well(0x22)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val inf = rememberInfiniteTransition(label = "sync")
        val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "r")
        Text("🛰", fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp).let { if (st.syncing) it.then(Modifier.size(20.dp)) else it })
        Text(
            if (st.syncing) st.syncNote else if (!synced) "Using typical climate. Sync to add the real forecast (sends only your farm's location)." else st.syncNote.ifBlank { "Weather cached" },
            color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f),
        )
        if (st.syncing) Canvas(Modifier.size(18.dp)) { drawArc(Palette.cyan, rot, 270f, false, style = Stroke(4f, cap = StrokeCap.Round)) }
        else Text(if (synced) "↻ Refresh" else "Sync weather", color = Palette.cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onSync).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

// ── Farm set-up ────────────────────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FarmSetup(app: AppState, farm0: Farm, modifier: Modifier, onDone: (Farm) -> Unit) {
    var f by remember { mutableStateOf(farm0) }
    var soilNote by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    Glass(modifier, glow = Palette.accent, padding = 16) {
        Text("① Where is the farm?", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (f.located) "📍 ${"%.3f".format(f.lat)}, ${"%.3f".format(f.lon)}" else "Stand in your field and tap →", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Btn("Use GPS", primary = !f.located) {
                if (!app.geo.hasPermission()) activity?.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 11)
                app.geo.start()
                scope.launch {
                    soilNote = "Finding your field…"
                    var fx: com.shuddh.lab.core.Fix? = null
                    repeat(20) { if (fx == null) { fx = app.geo.lastKnown(); if (fx == null) delay(500) } }
                    val p = fx ?: run { soilNote = "No GPS fix yet — step outside and try again."; return@launch }
                    f = f.copy(lat = p.lat, lon = p.lon)
                    busy = true; soilNote = "Looking up your village and soil (OpenStreetMap + ISRIC soil map)…"
                    FarmData.place(p.lat, p.lon)?.let { pl -> f = f.copy(name = if (f.name == "My farm" && pl.village.isNotBlank()) "Farm, ${pl.village}" else f.name, district = pl.district.ifBlank { f.district }, state = pl.state.ifBlank { f.state }) }
                    val si = FarmData.soilAuto(p.lat, p.lon, f.state)
                    if (si != null) { f = f.copy(soil = si.key, ph = si.ph); soilNote = si.source } else soilNote = "Soil map didn't answer — pick the closest soil below."
                    busy = false
                }
            }
        }
        LabeledField("Farm name", f.name) { f = f.copy(name = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { LabeledField("State", f.state) { f = f.copy(state = it) } }
            Box(Modifier.weight(1f)) { LabeledField("District", f.district) { f = f.copy(district = it) } }
        }
        Text("Area: ${"%.1f".format(f.acres)} acres", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Slider(f.acres.toFloat(), { f = f.copy(acres = (it * 2).toInt() / 2.0) }, valueRange = 0.5f..20f)

        Text("② Soil", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Agro.soils.forEach { s -> Chip(s.name, f.soil == s.key) { f = f.copy(soil = s.key, ph = null) } }
        }
        Text(Agro.soil(f.soil).note, color = Palette.muted, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(soilNote.ifBlank { "Not sure? Read it from the global soil map (ISRIC SoilGrids)." }, color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
            Btn(if (busy) "Reading…" else "🛰 Detect", primary = false, enabled = f.located && !busy) {
                busy = true
                scope.launch {
                    val r = FarmData.soilAuto(f.lat, f.lon, f.state)
                    if (r != null) { f = f.copy(soil = r.key, ph = r.ph ?: f.ph); soilNote = r.source }
                    else soilNote = "Soil map didn't answer — pick the closest soil above."
                    busy = false
                }
            }
        }

        Text("③ Water", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Irrigation.entries.forEach { i -> Chip(i.label, f.irrigation == i) { f = f.copy(irrigation = i) } } }

        Text("④ Past seasons (makes the twin match your farm)", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        HistoryEditor(f.history) { f = f.copy(history = it) }

        Btn(if (farm0.located) "Save farm" else "🛰 Create my farm twin", Modifier.fillMaxWidth(), enabled = f.located) { onDone(f) }
        if (!farm0.located) Btn("Try a demo farm (Nashik · 3 acres · black soil)", Modifier.fillMaxWidth(), primary = false) {
            val y = LocalDate.now().year
            onDone(Farm("Demo farm, Niphad", 20.08, 74.11, "Maharashtra", "Nashik", 3.0, "black", 7.8, Irrigation.LIMITED,
                listOf(Past(y - 1, "onion", 82.0), Past(y - 1, "soybean", 8.0), Past(y - 2, "onion", 90.0), Past(y - 2, "soybean", 9.0))))
        }
        if (!f.located) Note("Location is needed for weather. It stays on this phone; only rounded coordinates go to the weather service when you sync.")
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HistoryEditor(history: List<Past>, onChange: (List<Past>) -> Unit) {
    var crop by remember { mutableStateOf("wheat") }
    var year by remember { mutableStateOf(LocalDate.now().year - 1) }
    var q by remember { mutableFloatStateOf(15f) }
    history.sortedByDescending { it.year }.forEach { p ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.veil(0x0A)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${Agro.crop(p.crop)?.emoji ?: "🌱"} ${p.year} · ${Agro.crop(p.crop)?.name ?: p.crop}", color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(if (p.yieldQ > 0) "${"%.0f".format(p.yieldQ)} q/acre" else "yield ?", color = Palette.muted, fontSize = 12.sp)
            Text("✕", color = Palette.muted, modifier = Modifier.clickable { onChange(history - p) }.padding(horizontal = 8.dp))
        }
    }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Agro.crops.filter { !it.exotic }.forEach { c -> Chip("${c.emoji} ${c.name.substringBefore(" (")}", crop == c.key) { crop = c.key; q = c.yieldQ.toFloat() * 0.8f } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("‹", color = Palette.cyan, fontSize = 22.sp, modifier = Modifier.clickable { year-- }.padding(horizontal = 8.dp))
        Text("$year", color = Palette.text, fontWeight = FontWeight.Bold)
        Text("›", color = Palette.cyan, fontSize = 22.sp, modifier = Modifier.clickable { if (year < LocalDate.now().year) year++ }.padding(horizontal = 8.dp))
        Text("Yield ${q.toInt()} q/acre", color = Palette.text, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
        Btn("+ Add", primary = false) { onChange(history + Past(year, crop, q.toDouble())) }
    }
    val c = Agro.crop(crop)
    Slider(q, { q = it }, valueRange = 0f..((c?.yieldQ ?: 30.0) * 1.6).toFloat())
}

@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = Palette.muted, fontSize = 11.sp)
        androidx.compose.foundation.text.BasicTextField(value, onChange, singleLine = true,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.veil(0x0C)).border(1.dp, Palette.line, RoundedCornerShape(12.dp)).padding(10.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 14.sp, fontFamily = Body), cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.cyan))
    }
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(if (on) Palette.accent else Palette.veil(0x0E), tween(200), label = "ch")
    Text(text, color = if (on) Palette.onAccent else Palette.text, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

// ── Twin view: animated field + day scrubber + results ─────────────────────────────────────────

@Composable
private fun TwinView(farm: Farm, plan: Plan, st: TwinState, onPlan: (Plan) -> Unit) {
    val crop = Agro.crop(plan.crop) ?: return
    val wxFor = st.wxFor(farm.lat)
    var result by remember { mutableStateOf<FarmTwin.Result?>(null) }
    var best by remember { mutableStateOf<Pair<Long, Double>?>(null) }
    LaunchedEffect(plan, st.scenario, st.forecast.size, st.lastYear.size, st.newsScore, farm) {
        val r = withContext(Dispatchers.Default) {
            FarmTwin.simulate(farm, plan, wxFor(plan.sowDay, (crop.days * 1.8).toInt()), st.scenario, FarmTwin.newsAdj(st.newsScore)) to
                FarmTwin.bestSowing(farm, plan, wxFor, st.scenario)
        }
        result = r.first; best = r.second
    }
    val r = result ?: run { Note("Simulating…"); return }
    var dayIdx by remember(r) { mutableFloatStateOf(0f) }
    var playing by remember(r) { mutableStateOf(true) }
    LaunchedEffect(r, playing) {
        while (playing && dayIdx < r.days.size - 1) { delay(40); dayIdx = (dayIdx + 1).coerceAtMost((r.days.size - 1).toFloat()) }
        playing = false
    }
    val d = r.days.getOrNull(dayIdx.toInt()) ?: r.days.lastOrNull() ?: return

    // Scenario chips
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Scenario.entries.forEach { s -> Chip("${s.emoji} ${s.label}", st.scenario == s) { st.scenario = s } }
    }

    Glass(glow = Palette.accent, padding = 12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${crop.emoji} ${crop.name}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(FarmTwin.fmtDay(d.day), color = Palette.cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Farm3D(FarmScene(d.progress.toFloat(), d.soilWater.toFloat(), d.rain > 2, d.hot, d.flooded, ripe = cropRipe(crop.key), soil = soilColor(farm.soil), fishPond = plan.combo == "ricefish",
            cloud = if (d.rain > 2) 0.9f else 0.2f, shape = plantShape(crop.key)),
            Modifier.fillMaxWidth().height(240.dp), autoSpin = false)
        Text("Drag to orbit · tilt the phone to look around", color = Palette.muted, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (playing) "⏸" else "▶", color = Palette.accent, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable {
                if (!playing && dayIdx >= r.days.size - 1) dayIdx = 0f; playing = !playing
            }.padding(8.dp))
            Slider(dayIdx, { dayIdx = it; playing = false }, valueRange = 0f..(r.days.size - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniStat("Stage", FarmTwin.stage(d.progress), Palette.accent, Modifier.weight(1.4f))
            MiniStat("Temp", "${d.tmax.toInt()}°C", if (d.hot) Palette.red else Palette.text, Modifier.weight(0.8f))
            MiniStat("Rain", "${d.rain.toInt()} mm", Palette.blue, Modifier.weight(0.8f))
            MiniStat("Soil water", "${(d.soilWater * 100).toInt().coerceAtMost(150)}%", if (d.waterStress > 0.5) Palette.amber else Palette.cyan, Modifier.weight(1f))
        }
        GrowthChart(r, dayIdx.toInt(), Modifier.fillMaxWidth().height(90.dp))
        WeatherSourceNote(r, st)
    }

    ResultCard(r, plan)
    var range by remember(r) { mutableStateOf<FarmTwin.Range?>(null) }
    LaunchedEffect(r) { range = withContext(Dispatchers.Default) { FarmTwin.range(farm, plan, st.forecast, st.lastYear, st.scenario, FarmTwin.newsAdj(st.newsScore)) } }
    range?.let { rg ->
        InfoCard("🎯 How sure is this?", "The same season grown under 7 weather years (this year, last year, normal climate, drier, wetter, hotter).", Palette.accent) {
            RangeBar(rg)
            Text("Profit: ${money(rg.profitLow)} in a bad year · ${money(rg.profitMid)} likely · ${money(rg.profitHigh)} in a good year", color = Palette.text, fontSize = 12.sp)
        }
    }
    InfoCard("📉 Where the yield goes", "From the best this crop can do on your farm to what the twin expects — each red drop is one cause.", Palette.red) {
        YieldWaterfall(FarmTwin.waterfall(farm, r))
    }
    InfoCard("🗓 Season at a glance", null, Palette.cyan) { SeasonGantt(r) }
    InfoCard("💰 Money in, money out", "For ${"%.1f".format(plan.acres)} acres at ₹${"%,.0f".format(r.price)}/q.", Palette.blue) { MoneyInfographic(r) }
    InfoCard("⚠️ Risk map", "How much each risk could cost this crop on your farm.", Palette.amber) { RiskRadar(FarmTwin.risks(r)) }
    val checks = remember(farm.history, st.lastYear.size) { FarmTwin.backtest(farm, st.lastYear) }
    if (checks.isNotEmpty()) {
        val mape = checks.map { kotlin.math.abs(it.second.twin / it.second.actual - 1) }.average() * 100
        InfoCard("✅ Twin vs your real harvests", "Your past seasons re-run with that year's actual weather — without using your yields to tune it. Average error ${mape.toInt()}%.", Palette.accent) {
            BacktestChart(checks)
        }
    } else Note("Add past seasons (✎ Farm) and sync weather — the twin will then re-run them and show how close it gets to your real harvests.")
    Glass(glow = Palette.amber, padding = 14) {
        Text("How to get the most out of it", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        FarmTwin.insights(farm, r, best).forEachIndexed { i, s ->
            Text(s, color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.enter(i.coerceAtMost(6)))
        }
        best?.let { (bd, by) ->
            if (bd != plan.sowDay && by > r.yieldQ * 1.04) Btn("📅 Move sowing to ${FarmTwin.fmtDay(bd)}", Modifier.fillMaxWidth(), primary = false) { onPlan(plan.copy(sowDay = bd)) }
        }
    }
}

@Composable
private fun WeatherSourceNote(r: FarmTwin.Result, st: TwinState) {
    val n = r.days.size
    val f = r.days.count { st.forecast.containsKey(it.day) }
    val h = r.days.count { !st.forecast.containsKey(it.day) && (st.lastYear.containsKey(it.day - 365) || st.lastYear.containsKey(it.day - 730)) }
    Text("Weather used: $f days forecast · $h days from your farm's own past weather · ${n - f - h} days typical climate", color = Palette.muted, fontSize = 10.sp)
}

@Composable
private fun MiniStat(label: String, v: String, c: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.well(0x22)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(v, color = c, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
        Text(label, color = Palette.muted, fontSize = 9.sp, maxLines = 1)
    }
}

/** Growth (green) and water stress (amber) over the season with a cursor at the shown day. */
@Composable
private fun GrowthChart(r: FarmTwin.Result, cursor: Int, modifier: Modifier) {
    val reveal by animateFloatAsState(1f, tween(900), label = "rv")
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.well(0x22))) {
        val n = r.days.size.coerceAtLeast(2); val w = size.width; val h = size.height
        val stress = Path(); val grow = Path()
        stress.moveTo(0f, h)
        r.days.forEachIndexed { i, d ->
            val x = w * i / (n - 1) * reveal
            stress.lineTo(x, h - h * d.waterStress.toFloat() * 0.6f)
            val y = h - h * d.progress.toFloat() * 0.92f
            if (i == 0) grow.moveTo(x, y) else grow.lineTo(x, y)
        }
        stress.lineTo(w * reveal, h); stress.close()
        drawPath(stress, Palette.amber.copy(alpha = 0.3f))
        drawPath(grow, Palette.accent, style = Stroke(4f, cap = StrokeCap.Round))
        r.days.forEachIndexed { i, d -> if (d.rain > 20) { val x = w * i / (n - 1); drawLine(Palette.blue.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, (d.rain / 150).toFloat().coerceAtMost(1f) * h * 0.4f), 3f) } }
        val cx = w * cursor / (n - 1)
        drawLine(Palette.ink.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, h), 2f)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Legend(Palette.accent, "growth"); Legend(Palette.amber, "water stress"); Legend(Palette.blue, "heavy rain")
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(4.dp)); Text(label, color = Palette.muted, fontSize = 10.sp)
    }
}

@Composable
private fun ResultCard(r: FarmTwin.Result, plan: Plan) {
    val profitColor = if (r.profit >= 0) Palette.accent else Palette.red
    Glass(glow = profitColor, padding = 16) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigStat("${"%.1f".format(r.yieldQ)} q", "per acre", Palette.text, Modifier.weight(1f))
            BigStat(money(r.profit), "profit · ${"%.1f".format(plan.acres)} ac", profitColor, Modifier.weight(1f))
            BigStat(FarmTwin.fmtDay(r.harvestDay), "harvest", Palette.cyan, Modifier.weight(1f))
        }
        if (r.orchard) Text("Orchard: year 1 bears ${(r.crop.firstYear * 100).toInt()}% — from year ${if (r.crop.firstYear == 0.0) 3 else 2}, ~${"%.0f".format(r.matureQ)} q/acre and ${money(r.matureProfit)} a year.", color = Palette.muted, fontSize = 12.sp)
        Text("Price at harvest ₹${"%,.0f".format(r.price)}/q" + if (r.surge >= 0.1) "  ·  📈 surge +${(r.surge * 100).toInt()}%" else if (r.surge <= -0.08) "  ·  📉 harvest glut ${(r.surge * 100).toInt()}%" else "",
            color = if (r.surge >= 0.1) Palette.accent else Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text("What limits this crop", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        r.factors.forEach { (k, v) -> FactorBar(k, v) }
        Text("Risk ${(r.risk * 100).toInt()}% · ${r.heatDays} hot days · ${r.floodDays} flood days · ${r.irrigationMm.toInt()} mm irrigation", color = Palette.muted, fontSize = 11.sp)
    }
}

@Composable
private fun BigStat(v: String, label: String, c: Color, modifier: Modifier) {
    Column(modifier) {
        Text(v, color = c, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1)
        Text(label, color = Palette.muted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
fun FactorBar(label: String, v: Double) {
    val a by animateFloatAsState(v.toFloat(), tween(800, easing = FastOutSlowInEasing), label = "fb")
    val c = when { v >= 0.95 -> Palette.accent; v >= 0.8 -> Palette.amber; else -> Palette.red }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.veil(0x14))) {
            Box(Modifier.fillMaxWidth(a.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
        }
        Text("${(v * 100).toInt()}%", color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp).padding(start = 6.dp))
    }
}

fun money(x: Double): String {
    val a = kotlin.math.abs(x); val s = if (x < 0) "−" else ""
    return when { a >= 1e7 -> "$s₹${"%.1f".format(a / 1e7)} Cr"; a >= 1e5 -> "$s₹${"%.1f".format(a / 1e5)} L"; a >= 1e3 -> "$s₹${"%.0f".format(a / 1e3)}k"; else -> "$s₹${a.toInt()}" }
}
