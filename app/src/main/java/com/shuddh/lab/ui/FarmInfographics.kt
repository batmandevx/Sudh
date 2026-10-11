package com.shuddh.lab.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Agro
import com.shuddh.lab.core.FarmTwin
import kotlin.math.cos
import kotlin.math.sin

/** 0→1 once, when [key] changes — drives every infographic's entrance. */
@Composable
fun rememberReveal(key: Any?, ms: Int = 1100): Float {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(ms, easing = FastOutSlowInEasing)) }
    return a.value
}

@Composable
fun InfoCard(title: String, sub: String? = null, glow: Color = Palette.accent, content: @Composable () -> Unit) {
    Glass(glow = glow, padding = 14) {
        Text(title, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        sub?.let { Text(it, color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp) }
        content()
    }
}

/** Waterfall: best possible yield, then a red drop for every limiting factor, ending at the twin's yield. */
@Composable
fun YieldWaterfall(steps: List<Pair<String, Double>>) {
    val rv = rememberReveal(steps)
    val max = steps.maxOf { it.second }.coerceAtLeast(0.1)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val n = steps.size; val gap = 8f; val bw = (size.width - gap * (n - 1)) / n; val h = size.height - 18f
        var prev = 0.0
        steps.forEachIndexed { i, (_, v) ->
            val x = i * (bw + gap)
            val first = i == 0; val last = i == n - 1
            val (top, bottom, col) = when {
                first || last -> Triple(v, 0.0, if (last) Color(0xFF16A34A) else Color(0xFF64748B))
                else -> Triple(prev, v, Color(0xFFEF4444))
            }
            val yT = h - (h * top / max * rv).toFloat(); val yB = h - (h * bottom / max * rv).toFloat()
            drawRoundRect(col, Offset(x, yT), Size(bw, (yB - yT).coerceAtLeast(2f)), CornerRadius(6f))
            if (i > 0 && !last) drawLine(Palette.ink.copy(alpha = 0.25f), Offset(x - gap, h - (h * prev / max * rv).toFloat()), Offset(x, h - (h * prev / max * rv).toFloat()), 2f)
            prev = v
        }
    }
    Row(Modifier.fillMaxWidth()) {
        steps.forEachIndexed { i, (k, v) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (i == 0 || i == steps.size - 1) "%.1f".format(v) else "−%.1f".format(steps[i - 1].second - v), color = if (i == 0 || i == steps.size - 1) Palette.text else Palette.red, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(k.substringBefore(" ("), color = Palette.muted, fontSize = 9.sp, maxLines = 2, lineHeight = 10.sp)
            }
        }
    }
}

/** Low / likely / high band with an animated marker — "how sure is this number?". */
@Composable
fun RangeBar(r: FarmTwin.Range, unit: String = "q/acre") {
    val rv = rememberReveal(r)
    val lo = r.low * 0.85; val hi = (r.high * 1.1).coerceAtLeast(lo + 0.1)
    fun x(v: Double, w: Float) = (w * ((v - lo) / (hi - lo))).toFloat()
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val w = size.width; val cy = size.height / 2
        drawLine(Palette.veil(0x20), Offset(0f, cy), Offset(w, cy), 10f, StrokeCap.Round)
        val a = x(r.low, w); val b = x(r.high, w)
        drawLine(Color(0xFF22C55E).copy(alpha = 0.55f), Offset(a + (b - a) * (1 - rv) / 2, cy), Offset(b - (b - a) * (1 - rv) / 2, cy), 14f, StrokeCap.Round)
        val m = x(r.mid, w)
        drawCircle(Color.White, 11f * rv, Offset(m, cy)); drawCircle(Color(0xFF16A34A), 8f * rv, Offset(m, cy))
    }
    Row(Modifier.fillMaxWidth()) {
        Text("bad year ${"%.1f".format(r.low)}", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text("likely ${"%.1f".format(r.mid)} $unit", color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.4f))
        Text("good year ${"%.1f".format(r.high)}", color = Palette.muted, fontSize = 11.sp)
    }
}

/** Where the money goes vs what comes back: cost donut + revenue/profit bars. */
@Composable
fun MoneyInfographic(r: FarmTwin.Result) {
    val costs = FarmTwin.costs(r)
    val total = costs.sumOf { it.second }.coerceAtLeast(1.0)
    val cols = listOf(Color(0xFF6366F1), Color(0xFF22C55E), Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFFEF4444), Color(0xFF0EA5E9))
    val rv = rememberReveal(r)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(120.dp)) {
                val st = 22f; var a = -90f
                costs.forEachIndexed { i, (_, v) ->
                    val sw = (360 * v / total * rv).toFloat()
                    drawArc(cols[i % cols.size], a, (sw - 1.5f).coerceAtLeast(0f), false, Offset(st / 2, st / 2), Size(size.width - st, size.height - st), style = Stroke(st))
                    a += sw
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(money(total), color = Palette.text, fontWeight = FontWeight.Black, fontSize = 14.sp)
                Text("cost", color = Palette.muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            costs.forEachIndexed { i, (k, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(cols[i % cols.size])); Spacer(Modifier.width(6.dp))
                    Text(k, color = Palette.text, fontSize = 11.sp, modifier = Modifier.weight(1f)); Text(money(v), color = Palette.muted, fontSize = 11.sp)
                }
            }
        }
    }
    val maxV = maxOf(r.revenue, r.cost).coerceAtLeast(1.0)
    listOf(Triple("Revenue", r.revenue, Color(0xFF16A34A)), Triple("Cost", r.cost, Color(0xFFF59E0B)), Triple(if (r.profit >= 0) "Profit" else "Loss", kotlin.math.abs(r.profit), if (r.profit >= 0) Color(0xFF0EA5E9) else Color(0xFFEF4444))).forEach { (k, v, c) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(k, color = Palette.text, fontSize = 12.sp, modifier = Modifier.width(64.dp))
            Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.veil(0x10))) {
                Box(Modifier.fillMaxWidth((v / maxV * rv).toFloat().coerceIn(0.01f, 1f)).height(14.dp).clip(RoundedCornerShape(7.dp)).background(c))
            }
            Text(money(v), color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp).padding(start = 6.dp))
        }
    }
}

/** Hexagonal radar of the crop's risks on this farm. */
@Composable
fun RiskRadar(risks: List<Pair<String, Double>>) {
    val rv = rememberReveal(risks)
    Box(Modifier.fillMaxWidth().height(200.dp)) {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val c = center; val R = size.height * 0.38f; val n = risks.size
            fun pt(i: Int, f: Float) = Offset(c.x + R * f * cos(-Math.PI / 2 + 2 * Math.PI * i / n).toFloat(), c.y + R * f * sin(-Math.PI / 2 + 2 * Math.PI * i / n).toFloat())
            for (ring in 1..4) { val p = Path(); for (i in 0 until n) { val q = pt(i, ring / 4f); if (i == 0) p.moveTo(q.x, q.y) else p.lineTo(q.x, q.y) }; p.close(); drawPath(p, Palette.ink.copy(alpha = 0.08f), style = Stroke(1.5f)) }
            for (i in 0 until n) drawLine(Palette.ink.copy(alpha = 0.08f), c, pt(i, 1f), 1.5f)
            val p = Path()
            risks.forEachIndexed { i, (_, v) -> val q = pt(i, (0.06f + 0.94f * v.toFloat()) * rv); if (i == 0) p.moveTo(q.x, q.y) else p.lineTo(q.x, q.y) }
            p.close()
            drawPath(p, Color(0xFFEF4444).copy(alpha = 0.25f)); drawPath(p, Color(0xFFEF4444), style = Stroke(3f))
            risks.forEachIndexed { i, (_, v) -> drawCircle(Color(0xFFEF4444), 5f, pt(i, (0.06f + 0.94f * v.toFloat()) * rv)) }
        }
        // Labels around the hexagon.
        risks.forEachIndexed { i, (k, v) ->
            val ang = -Math.PI / 2 + 2 * Math.PI * i / risks.size
            Text("$k ${(v * 100).toInt()}%", color = if (v > 0.3) Palette.red else Palette.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center).offset((cos(ang) * 120).toFloat().dp, (sin(ang) * 88).toFloat().dp))
        }
    }
}

/** Season timeline: coloured stage bands with hot / dry / flood markers underneath. */
@Composable
fun SeasonGantt(r: FarmTwin.Result) {
    val rv = rememberReveal(r)
    val stages = listOf("Germination" to Color(0xFFA3E635), "Vegetative" to Color(0xFF22C55E), "Flowering" to Color(0xFFF59E0B), "Grain / fruit fill" to Color(0xFFEAB308), "Ready to harvest" to Color(0xFFB45309))
    val n = r.days.size.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(58.dp)) {
        val w = size.width
        r.days.forEachIndexed { i, d ->
            if (i.toFloat() / n > rv) return@forEachIndexed
            val col = stages.first { it.first == FarmTwin.stage(d.progress) }.second
            drawRect(col, Offset(w * i / n, 6f), Size(w / n + 1f, 22f))
            when { d.flooded -> drawCircle(Color(0xFF3B82F6), 3.5f, Offset(w * i / n, 40f)); d.hot -> drawCircle(Color(0xFFEF4444), 3.5f, Offset(w * i / n, 40f)); d.waterStress > 0.6 -> drawCircle(Color(0xFFF59E0B), 3f, Offset(w * i / n, 50f)) }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("🌱 ${FarmTwin.fmtDay(r.plan.sowDay)}", color = Palette.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text("🔴 hot  🟠 dry  🔵 flood", color = Palette.muted, fontSize = 10.sp, modifier = Modifier.weight(1.4f))
        Text("🧺 ${FarmTwin.fmtDay(r.harvestDay)}", color = Palette.muted, fontSize = 10.sp)
    }
}

/** Profit (y) vs risk (x) bubble chart for the ranked crops. */
@Composable
fun ProfitRiskBubbles(picks: List<FarmTwin.Pick>, onPick: (FarmTwin.Pick) -> Unit) {
    val rv = rememberReveal(picks.size)
    val list = picks.take(10)
    if (list.isEmpty()) return
    val maxP = list.maxOf { maxOf(it.result.profitPerAcre, it.result.matureProfit / it.result.plan.acres.coerceAtLeast(0.1)) }.coerceAtLeast(1.0)
    val minP = list.minOf { it.result.profitPerAcre }.coerceAtMost(0.0)
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(Palette.well(0x18))) {
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val w = size.width; val h = size.height; val pad = 24f
            // Quadrant shading: top-left is the sweet spot (high profit, low risk).
            drawRect(Color(0xFF22C55E).copy(alpha = 0.08f), Offset(pad, pad), Size((w - 2 * pad) / 2, (h - 2 * pad) / 2))
            val y0 = h - pad - (h - 2 * pad) * ((0 - minP) / (maxP - minP)).toFloat()
            drawLine(Palette.ink.copy(alpha = 0.2f), Offset(pad, y0), Offset(w - pad, y0), 1.5f)
            list.forEach { p ->
                val r = p.result
                val x = pad + (w - 2 * pad) * r.risk.toFloat()
                val y = h - pad - (h - 2 * pad) * ((r.profitPerAcre - minP) / (maxP - minP)).toFloat()
                val rad = (10f + 14f * (r.yieldQ / (r.crop.yieldQ.coerceAtLeast(0.1))).toFloat()) * rv
                drawCircle((if (r.profitPerAcre > 0) Color(0xFF22C55E) else Color(0xFFEF4444)).copy(alpha = 0.3f), rad, Offset(x, y))
                drawCircle(if (r.profitPerAcre > 0) Color(0xFF16A34A) else Color(0xFFDC2626), rad, Offset(x, y), style = Stroke(2f))
            }
        }
        list.forEach { p ->
            val r = p.result
            Text(r.crop.emoji, fontSize = 15.sp, modifier = Modifier.chartPos(r.risk.toFloat(), ((r.profitPerAcre - minP) / (maxP - minP)).toFloat()))
        }
        Text("profit ↑", color = Palette.muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
        Text("risk →", color = Palette.muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
        Text("sweet spot", color = Color(0xFF16A34A), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 26.dp))
    }
}

/** Places a child at chart fractions (x right, y up) inside the 24 px-padded plot area. */
private fun Modifier.chartPos(fx: Float, fy: Float): Modifier = this.then(
    Modifier.layout { m, c ->
        val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
        val w = c.maxWidth; val h = c.maxHeight.takeIf { it < 100000 } ?: 600
        val pad = 24
        val x = (pad + (w - 2 * pad) * fx).toInt() - p.width / 2
        val y = (h - pad - (h - 2 * pad) * fy).toInt() - p.height / 2
        layout(p.width, p.height) { p.place(x.coerceIn(0, (w - p.width).coerceAtLeast(0)), y.coerceIn(0, (h - p.height).coerceAtLeast(0))) }
    },
)

/** Twin vs the farmer's real harvests (re-simulated with that season's archived weather). */
@Composable
fun BacktestChart(checks: List<Pair<Agro.Crop, FarmTwin.Check>>) {
    val rv = rememberReveal(checks.size)
    val max = checks.maxOf { maxOf(it.second.actual, it.second.twin) }.coerceAtLeast(1.0)
    checks.forEach { (c, k) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${c.emoji} ${k.year}", color = Palette.text, fontSize = 12.sp, modifier = Modifier.width(72.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.fillMaxWidth((k.actual / max * rv).toFloat().coerceIn(0.02f, 1f)).height(9.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0EA5E9)))
                Box(Modifier.fillMaxWidth((k.twin / max * rv).toFloat().coerceIn(0.02f, 1f)).height(9.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF22C55E)))
            }
            val err = (k.twin / k.actual - 1) * 100
            Text("${if (err >= 0) "+" else ""}${err.toInt()}%", color = if (kotlin.math.abs(err) <= 20) Palette.accent else Palette.amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp).padding(start = 6.dp))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(Color(0xFF0EA5E9))); Text(" you harvested", color = Palette.muted, fontSize = 10.sp) }
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(Color(0xFF22C55E))); Text(" twin predicted", color = Palette.muted, fontSize = 10.sp) }
    }
}
