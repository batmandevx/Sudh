package com.shuddh.lab.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Agro
import com.shuddh.lab.core.Farm
import com.shuddh.lab.core.FarmData
import com.shuddh.lab.core.FarmTwin
import com.shuddh.lab.core.Irrigation
import com.shuddh.lab.core.PantrySmart
import com.shuddh.lab.core.Plan
import com.shuddh.lab.core.Scenario
import com.shuddh.lab.core.WebSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin

// ── What to grow ───────────────────────────────────────────────────────────────────────────────

@Composable
fun PlanTab(farm: Farm, st: TwinState, onPick: (Plan) -> Unit) {
    var exotic by remember { mutableStateOf(false) }
    var picks by remember { mutableStateOf<List<FarmTwin.Pick>?>(null) }
    LaunchedEffect(farm, st.scenario, st.forecast.size, st.lastYear.size, exotic) {
        picks = null
        picks = withContext(Dispatchers.Default) { FarmTwin.rank(farm, st.wxFor(farm.lat), st.scenario, exotic = exotic) }
    }
    Glass(glow = Palette.accent, padding = 14) {
        Text("Best crops for your farm, next 4 months", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Each crop is grown in the twin from its sowing window with your soil, water, weather and past seasons — ranked by profit after risk.", color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include exotic crops (dragon fruit, avocado, berries…)", color = Palette.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(exotic, { exotic = it })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Scenario.entries.take(3).forEach { s -> Chip("${s.emoji} ${s.label}", st.scenario == s) { st.scenario = s } }
        }
    }
    val list = picks
    if (list == null) Note("Growing every crop in the twin…")
    else if (list.isEmpty()) Note("No crop's sowing window opens in the next 4 months.")
    else {
        InfoCard("Profit vs risk", "Bigger bubble = closer to the crop's best yield. Top-left is the sweet spot.", Palette.accent) {
            ProfitRiskBubbles(list) { p -> onPick(p.result.plan.copy(acres = farm.acres)) }
        }
        val top = list.first().score.coerceAtLeast(1.0)
        list.forEachIndexed { i, p -> CropRankCard(i, p, top, Modifier.enter(i.coerceAtMost(8))) { onPick(p.result.plan.copy(acres = farm.acres)) } }
    }
}

@Composable
private fun CropRankCard(rank: Int, p: FarmTwin.Pick, top: Double, modifier: Modifier, onClick: () -> Unit) {
    val r = p.result
    val w by animateFloatAsState((p.score / top).toFloat().coerceIn(0.03f, 1f), tween(700), label = "rk")
    val c = when { r.profitPerAcre <= 0 -> Palette.red; rank < 3 -> Palette.accent; else -> Palette.blue }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.10f), Palette.surface)))
            .border(1.dp, c.copy(alpha = if (rank == 0) 0.6f else 0.25f), RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (rank == 0) "🏆" else "${rank + 1}", color = Palette.muted, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(28.dp))
            Text(r.crop.emoji, fontSize = 24.sp); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(r.crop.name, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Sow ${FarmTwin.fmtDay(r.plan.sowDay)} → harvest ${FarmTwin.fmtDay(r.harvestDay)}", color = Palette.muted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(money(if (r.orchard) r.matureProfit else r.profitPerAcre), color = c, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(if (r.orchard) "/acre/yr from yr ${if (r.crop.firstYear == 0.0) 3 else 2}" else "/acre", color = Palette.muted, fontSize = 10.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.veil(0x12))) {
            Box(Modifier.fillMaxWidth(w).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient(listOf(c, c.copy(alpha = 0.6f)))))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag("${"%.1f".format(r.yieldQ)} q/acre", Palette.text)
            Tag("risk ${(r.risk * 100).toInt()}%", if (r.risk > 0.4) Palette.red else if (r.risk > 0.2) Palette.amber else Palette.accent)
            if (r.surge >= 0.1) Tag("📈 surge +${(r.surge * 100).toInt()}%", Palette.accent)
            if (r.crop.exotic) Tag("exotic", Palette.violet)
        }
    }
}

@Composable
fun Tag(text: String, c: Color) {
    Text(text, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

// ── Where & when to sell ───────────────────────────────────────────────────────────────────────

@Composable
fun MarketTab(app: AppState, farm: Farm, plan: Plan?, st: TwinState) {
    val crop = Agro.crop(plan?.crop ?: "") ?: run { Note("Pick a crop in \"What to grow\" first."); return }
    val scope = rememberCoroutineScope()
    var mandis by remember(crop.key) { mutableStateOf<List<FarmData.Mandi>?>(null) }
    var mandiNote by remember(crop.key) { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val harvest = remember(plan, st.scenario) { LocalDate.ofEpochDay(FarmTwin.simulate(farm, plan!!, st.wxFor(farm.lat)(plan.sowDay, (crop.days * 1.8).toInt()), st.scenario).harvestDay) }
    val (sell, _) = FarmTwin.bestSellMonth(crop, harvest.monthValue)

    Glass(glow = Palette.accent, padding = 14) {
        Text("When to sell ${crop.emoji} ${crop.name}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        SeasonBars(crop, harvest.monthValue, sell)
        Text(
            if (sell == harvest.monthValue) "Sell at harvest (${PantrySmart.monthName(sell)}) — ${if (crop.storable) "prices don't improve enough to pay for storage" else "it doesn't store well"}."
            else "Harvest in ${PantrySmart.monthName(harvest.monthValue)}, sell in ${PantrySmart.monthName(sell)}: prices usually recover ${((FarmTwin.seasonal(crop, sell) / FarmTwin.seasonal(crop, harvest.monthValue) - 1) * 100).toInt()}% (storage losses counted). Warehouse receipts (WDRA) let you borrow against stored crop meanwhile.",
            color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp,
        )
        if (crop.key in setOf("rice", "wheat")) Text("MSP ₹${"%,.0f".format(crop.price)}/q is guaranteed at government procurement centres — never sell below it.", color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }

    Glass(glow = Palette.blue, padding = 14) {
        Text("Where to sell — today's mandi prices", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Live AGMARKNET prices${if (farm.state.isNotBlank()) " in ${farm.state}" else ""}. Needs internet.", color = Palette.muted, fontSize = 11.sp)
        Btn(if (loading) "Fetching…" else "📡 Fetch today's prices", Modifier.fillMaxWidth(), primary = false, enabled = !loading) {
            loading = true
            scope.launch {
                val name = FarmData.mandiName[crop.key] ?: crop.name
                var m = FarmData.mandi(name, farm.state)
                if (m.isEmpty() && farm.state.isNotBlank()) m = FarmData.mandi(name, "")
                mandis = m
                mandiNote = if (m.isEmpty()) "No prices came back (offline, or no arrivals reported today). Try eNAM or your nearest APMC." else ""
                loading = false
            }
        }
        if (mandiNote.isNotBlank()) Note(mandiNote)
        mandis?.takeIf { it.isNotEmpty() }?.let { list ->
            val median = list.map { it.modal }.sorted()[list.size / 2]
            list.take(8).forEachIndexed { i, m ->
                val diff = m.modal - median
                Row(Modifier.fillMaxWidth().enter(i).clip(RoundedCornerShape(12.dp)).background(if (i == 0) Palette.accent.copy(alpha = 0.1f) else Palette.veil(0x08)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (i == 0) "🏆" else "📍", fontSize = 16.sp); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.market, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${m.district}, ${m.state} · ${m.date}", color = Palette.muted, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("₹${"%,.0f".format(m.modal)}/q", color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(if (diff >= 0) "+₹${"%,.0f".format(diff)} vs median" else "−₹${"%,.0f".format(-diff)}", color = if (diff >= 0) Palette.accent else Palette.red, fontSize = 10.sp)
                    }
                }
            }
            Note("A better price far away can be eaten by transport: roughly ₹2–4 per quintal per km by tractor-trolley.")
        }
    }

    Glass(glow = Palette.violet, padding = 14) {
        Text("📰 News that moves the price", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Scans current headlines for shortage, export, bumper-crop signals and nudges the twin's harvest price (±10% max).", color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp)
        var newsLoading by remember { mutableStateOf(false) }
        var newsErr by remember { mutableStateOf("") }
        Btn(if (newsLoading) "Reading news…" else "Scan news for ${crop.name.lowercase()}", Modifier.fillMaxWidth(), primary = false, enabled = !newsLoading) {
            newsLoading = true; newsErr = ""
            scope.launch {
                runCatching { WebSearch.search("${crop.name.substringBefore(" (")} price mandi India news ${farm.state}", true) }
                    .onSuccess { r -> st.headlines = r.sources.map { it.title }.take(8); st.newsScore = FarmTwin.newsScore(st.headlines) }
                    .onFailure { newsErr = "Couldn't reach the news feed — check internet." }
                newsLoading = false
            }
        }
        if (newsErr.isNotBlank()) Note(newsErr)
        if (st.headlines.isNotEmpty()) {
            val s = st.newsScore
            Text(when { s > 0 -> "📈 Headlines lean bullish → twin price +${s * 5}%"; s < 0 -> "📉 Headlines lean bearish → twin price ${s * 5}%"; else -> "➖ Mixed headlines → no change" },
                color = if (s > 0) Palette.accent else if (s < 0) Palette.red else Palette.muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            st.headlines.forEach { Text("• $it", color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp) }
        }
    }
}

@Composable
private fun SeasonBars(crop: Agro.Crop, harvest: Int, sell: Int) {
    val grow by animateFloatAsState(1f, tween(900), label = "sb")
    Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        (1..12).forEach { m ->
            val v = FarmTwin.seasonal(crop, m)
            val c = when (m) { sell -> Palette.accent; harvest -> Palette.amber; else -> Palette.veil(0x30) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height((70 * (v - 0.7) / 0.5 * grow).dp.coerceAtLeast(6.dp)).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(c))
                Text(PantrySmart.monthName(m).take(1), color = if (m == sell || m == harvest) Palette.text else Palette.muted, fontSize = 9.sp)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(Palette.amber)); Text(" harvest", color = Palette.muted, fontSize = 10.sp) }
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(Palette.accent)); Text(" best to sell", color = Palette.muted, fontSize = 10.sp) }
        Text("bar = typical price level", color = Palette.muted, fontSize = 10.sp)
    }
}

// ── Trait Lab (gene editing & breeding, simulated) ─────────────────────────────────────────────

@Composable
fun TraitTab(farm: Farm, plan: Plan?, st: TwinState, onUse: (Plan) -> Unit) {
    var cropKey by remember { mutableStateOf(plan?.crop ?: "rice") }
    val crop = Agro.crop(cropKey) ?: return
    var chosen by remember(cropKey) { mutableStateOf(setOf<String>()) }
    val sow = remember(cropKey) { (plan?.takeIf { it.crop == cropKey }?.sowDay) ?: FarmTwin.nextSow(crop, LocalDate.now()).toEpochDay() }
    val base = Plan(cropKey, 1.0, sow)
    var table by remember { mutableStateOf<List<Triple<Scenario, Double, Double>>>(emptyList()) }
    LaunchedEffect(cropKey, chosen, farm, st.forecast.size) {
        table = withContext(Dispatchers.Default) {
            val wx = st.wxFor(farm.lat)(sow, (crop.days * 1.8).toInt())
            Scenario.entries.map { s -> Triple(s, FarmTwin.simulate(farm, base, wx, s).yieldQ, FarmTwin.simulate(farm, base.copy(traits = chosen), wx, s).yieldQ) }
        }
    }

    Glass(glow = Palette.violet, padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DnaHelix(Modifier.size(64.dp, 90.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Trait Lab", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text("Design a variant: add a gene trait and watch your farm's twin grow it through drought, heat and floods.", color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Agro.crops.forEach { c -> Chip("${c.emoji} ${c.name.substringBefore(" (")}", c.key == cropKey) { cropKey = c.key } }
        }
    }

    // CRISP compatibility: each trait's yield gain across scenarios, weighted by this farm's own stresses.
    var fit by remember(cropKey) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var stressNote by remember(cropKey) { mutableStateOf("") }
    LaunchedEffect(cropKey, farm, st.forecast.size) {
        val r = withContext(Dispatchers.Default) {
            val wx = st.wxFor(farm.lat)(sow, (crop.days * 1.8).toInt())
            val baseRuns = Scenario.entries.associateWith { FarmTwin.simulate(farm, base, wx, it) }
            val w = com.shuddh.lab.core.FieldKit.stressWeights(baseRuns[Scenario.LIVE]!!)
            val scores = Agro.traitsFor(cropKey).associate { t ->
                val gains = Scenario.entries.associateWith { s0 ->
                    val b0 = baseRuns[s0]!!.yieldQ.coerceAtLeast(0.01)
                    val e = FarmTwin.simulate(farm, base.copy(traits = setOf(t.key)), wx, s0)
                    (e.yieldQ - b0) / b0 + (if (e.harvestDay < baseRuns[s0]!!.harvestDay) 0.03 else 0.0) + t.pestCut * 0.1
                }
                t.key to com.shuddh.lab.core.FieldKit.compatibility(gains, w)
            }
            val live = baseRuns[Scenario.LIVE]!!
            scores to "Your farm's main yield losses: water ${(100 * (1 - live.factors["Water"]!!)).toInt()}% · heat ${(100 * (1 - live.factors["Heat"]!!)).toInt()}% · flood ${(100 * (1 - live.factors["Flood"]!!)).toInt()}% · pests ${(100 * (1 - live.factors["Pests"]!!)).toInt()}%"
        }
        fit = r.first; stressNote = r.second
    }
    if (stressNote.isNotBlank()) Text(stressNote, color = Palette.muted, fontSize = 11.sp)
    Agro.traitsFor(cropKey).sortedByDescending { fit[it.key] ?: 0 }.forEachIndexed { i, t ->
        val on = t.key in chosen
        val research = t.kind == "Research target"
        Column(
            Modifier.fillMaxWidth().enter(i.coerceAtMost(6)).clip(RoundedCornerShape(18.dp))
                .background(Brush.horizontalGradient(listOf((if (research) Palette.violet else Palette.accent).copy(alpha = if (on) 0.18f else 0.07f), Palette.surface)))
                .border(if (on) 2.dp else 1.dp, (if (research) Palette.violet else Palette.accent).copy(alpha = if (on) 0.8f else 0.25f), RoundedCornerShape(18.dp))
                .clickable { chosen = if (on) chosen - t.key else chosen + t.key }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (on) "✓" else "+", color = if (research) Palette.violet else Palette.accent, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("${t.gene} · ${t.kind}", color = if (research) Palette.violet else Palette.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            fit[t.key]?.let { sc -> ConfidenceBar("CRISP fit for your farm", sc / 100f, if (sc >= 60) Palette.accent else if (sc >= 30) Palette.amber else Palette.muted) }
            Text(t.what, color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp)
            Text((if (research) "🔬 " else "🌱 Seed you can buy: ") + t.real, color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }

    if (table.isNotEmpty()) Glass(glow = Palette.violet, padding = 14) {
        Text(if (chosen.isEmpty()) "Your ${crop.name.lowercase()} in every scenario" else "Edited variant vs today's ${crop.name.lowercase()}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        table.forEach { (s, a, b) ->
            val gain = if (a > 0.01) (b / a - 1) else 0.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${s.emoji} ${s.label}", color = Palette.text, fontSize = 12.sp, modifier = Modifier.width(140.dp))
                Box(Modifier.weight(1f).height(16.dp)) {
                    val max = table.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(0.1)
                    val wa by animateFloatAsState((a / max).toFloat(), tween(700), label = "a")
                    val wb by animateFloatAsState((b / max).toFloat(), tween(900), label = "b")
                    Box(Modifier.fillMaxWidth(wb.coerceIn(0.01f, 1f)).height(16.dp).clip(RoundedCornerShape(4.dp)).background(Palette.violet.copy(alpha = 0.55f)))
                    Box(Modifier.fillMaxWidth(wa.coerceIn(0.01f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.ink.copy(alpha = 0.35f)))
                }
                Text(if (chosen.isEmpty()) "${"%.1f".format(a)} q" else (if (gain >= 0) "+" else "") + "${(gain * 100).toInt()}%",
                    color = if (gain > 0.02) Palette.accent else Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp).padding(start = 6.dp))
            }
        }
        if (chosen.isNotEmpty()) Btn("🛰 Grow this variant in my twin", Modifier.fillMaxWidth()) { onUse(base.copy(acres = farm.acres, traits = chosen)) }
    }

    ExoticMatcher(farm, st)

    Glass(glow = Palette.amber, padding = 14) {
        Text("The honest part", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Note("A phone can't edit genes. The Trait Lab simulates what a trait would do on your farm so you know which seed is worth asking for. India exempts SDN-1/SDN-2 genome-edited plants with no foreign DNA from GM rules (2022) and released its first two edited rice varieties in 2025. Buy only certified seed; ask your KVK or agricultural university for trial lines.")
    }
}

/** Can a foreign crop work here — and what trait would it need? */
@Composable
private fun ExoticMatcher(farm: Farm, st: TwinState) {
    var rows by remember { mutableStateOf<List<Triple<Agro.Crop, FarmTwin.Result, FarmTwin.Result>>>(emptyList()) }
    LaunchedEffect(farm, st.forecast.size) {
        rows = withContext(Dispatchers.Default) {
            Agro.crops.filter { it.exotic }.map { c ->
                val sow = FarmTwin.nextSow(c, LocalDate.now()).toEpochDay()
                val wx = st.wxFor(farm.lat)(sow, (c.days * 1.8).toInt())
                val p = Plan(c.key, 1.0, sow)
                Triple(c, FarmTwin.simulate(farm, p, wx), FarmTwin.simulate(farm, p.copy(traits = setOf("heat2", "drought")), wx))
            }.sortedByDescending { it.third.matureProfit }
        }
    }
    if (rows.isEmpty()) return
    Glass(glow = Palette.cyan, padding = 14) {
        Text("🌍 Foreign crops on your farm", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Grown in the twin as-is, and as a variant edited for +2 °C heat and drought tolerance.", color = Palette.muted, fontSize = 11.sp)
        rows.forEach { (c, a, b) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(c.emoji, fontSize = 22.sp); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(c.note, color = Palette.muted, fontSize = 10.sp, lineHeight = 13.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(money(a.matureProfit), color = if (a.matureProfit > 0) Palette.accent else Palette.red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("edited " + money(b.matureProfit), color = Palette.violet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("Profit per acre per year once bearing. Red = the climate here doesn't suit it today.", color = Palette.muted, fontSize = 10.sp)
    }
}

/** Rotating double helix with base-pair rungs. */
@Composable
fun DnaHelix(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "dna")
    val ph by inf.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "ph")
    Canvas(modifier) {
        val w = size.width; val h = size.height; val n = 14
        for (i in 0..n) {
            val y = h * i / n
            val a = ph + i * 0.55f
            val x1 = w / 2 + w * 0.38f * sin(a); val x2 = w / 2 - w * 0.38f * sin(a)
            val front = cos(a) > 0
            drawLine(Palette.ink.copy(alpha = 0.25f), Offset(x1, y), Offset(x2, y), 3f, StrokeCap.Round)
            drawCircle(if (front) Color(0xFF8B5CF6) else Color(0xFF8B5CF6).copy(alpha = 0.4f), 5f, Offset(x1, y))
            drawCircle(if (!front) Color(0xFF22D3EE) else Color(0xFF22D3EE).copy(alpha = 0.4f), 5f, Offset(x2, y))
        }
    }
}

// ── Integrated farming ─────────────────────────────────────────────────────────────────────────

@Composable
fun CombosTab(farm: Farm, plan: Plan?) {
    val fit = Agro.combos.map { c ->
        val soilOk = farm.soil in c.soils
        val waterOk = !c.needsWater || farm.irrigation != Irrigation.RAINFED
        val cropOk = c.crops.isEmpty() || plan?.crop in c.crops
        Triple(c, soilOk && waterOk, cropOk)
    }.sortedWith(compareByDescending<Triple<Agro.Combo, Boolean, Boolean>> { it.second && it.third }.thenByDescending { it.second })
    Glass(glow = Palette.cyan, padding = 14) {
        Text("Grow more from the same land", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Integrated systems where one part feeds another — matched to your soil, water and crop.", color = Palette.muted, fontSize = 12.sp)
    }
    fit.forEachIndexed { i, (c, ok, cropOk) ->
        var open by remember { mutableStateOf(i == 0) }
        val inf = rememberInfiniteTransition(label = "cb$i")
        val bob by inf.animateFloat(-3f, 3f, androidx.compose.animation.core.infiniteRepeatable(tween(1400 + i * 120), androidx.compose.animation.core.RepeatMode.Reverse), label = "b")
        Column(
            Modifier.fillMaxWidth().enter(i.coerceAtMost(7)).clip(RoundedCornerShape(18.dp))
                .background(Brush.horizontalGradient(listOf((if (ok) Palette.cyan else Palette.muted).copy(alpha = 0.10f), Palette.surface)))
                .border(1.dp, (if (ok && cropOk) Palette.accent else Palette.line), RoundedCornerShape(18.dp))
                .clickable { open = !open }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.emoji, fontSize = 26.sp, modifier = Modifier.padding(end = 10.dp).graphicsLayer { translationY = bob })
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("+₹${c.extraPerAcre.first / 1000}k–${c.extraPerAcre.last / 1000}k per acre a year", color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Tag(if (ok && cropOk) "fits your farm" else if (ok) "fits soil" else "needs changes", if (ok && cropOk) Palette.accent else if (ok) Palette.blue else Palette.muted)
            }
            Text(c.why, color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp)
            if (open) c.how.forEachIndexed { k, s -> Text("${k + 1}. $s", color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp) }
        }
    }
    Note("Income ranges are typical figures from ICAR and state agriculture departments; your result depends on management and local prices.")
}
