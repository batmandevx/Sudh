package com.shuddh.lab.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Insights
import com.shuddh.lab.core.ScanRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private const val DAY = 86_400_000L

/** "1 day" / "3 days". */
fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** GitHub-style colour for a day: green intensity by count, shifted to amber/red by failures. */
fun dayColor(t: Insights.Tally?): Color {
    if (t == null || t.total == 0) return Palette.ink.copy(alpha = 0.06f)
    val base = when {
        t.unsafe > 0 && t.unsafe * 2 >= t.total -> Palette.red
        t.unsafe > 0 || t.caution > 0 -> Palette.amber
        else -> Palette.accent
    }
    val intensity = when (t.total) { 1 -> 0.35f; 2 -> 0.55f; 3 -> 0.75f; else -> 1f }
    return base.copy(alpha = intensity)
}

/** Contribution heatmap: one column per week, one row per weekday, cells fade in left to right. */
@Composable
fun ContributionHeatmap(rs: List<ScanRecord>, weeks: Int = 26, onDay: (Long) -> Unit = {}) {
    val byDay = remember(rs) { Insights.byDay(rs) }
    val today = Insights.startOfDay(System.currentTimeMillis())
    val cal = Calendar.getInstance().apply { timeInMillis = today; firstDayOfWeek = Calendar.MONDAY }
    val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
    val firstMonday = today - (dow + (weeks - 1) * 7L) * DAY
    val reveal = remember(rs) { Animatable(0f) }
    LaunchedEffect(rs) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    val fmt = SimpleDateFormat("MMM", Locale.US)
    Column {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(weeks / 7.6f).pointerInput(rs) {
                detectTapGestures { p ->
                    val cell = size.width / weeks.toFloat()
                    val col = (p.x / cell).toInt(); val row = ((p.y - cell * 0.6f) / cell).toInt()
                    if (col in 0 until weeks && row in 0..6) onDay(firstMonday + (col * 7L + row) * DAY)
                }
            },
        ) {
            val cell = size.width / weeks
            val gap = cell * 0.16f
            val top = cell * 0.6f
            val paint = android.graphics.Paint().apply { color = Palette.muted.toArgb(); textSize = cell * 0.55f; isAntiAlias = true }
            var lastMonth = -1
            for (w in 0 until weeks) {
                val weekStart = firstMonday + w * 7L * DAY
                val m = Calendar.getInstance().apply { timeInMillis = weekStart }.get(Calendar.MONTH)
                if (m != lastMonth) {
                    drawContext.canvas.nativeCanvas.drawText(fmt.format(Date(weekStart)), w * cell, top - cell * 0.15f, paint)
                    lastMonth = m
                }
                val a = (reveal.value * (weeks + 4) - w).coerceIn(0f, 1f)
                for (d in 0..6) {
                    val day = weekStart + d * DAY
                    if (day > today) continue
                    val c = dayColor(byDay[day])
                    drawRoundRect(c.copy(alpha = c.alpha * a), Offset(w * cell + gap / 2, top + d * cell + gap / 2), Size(cell - gap, cell - gap), CornerRadius(cell * 0.22f))
                    if (day == today) drawRoundRect(Palette.ink, Offset(w * cell + gap / 2, top + d * cell + gap / 2), Size(cell - gap, cell - gap), CornerRadius(cell * 0.22f), style = Stroke(2f))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("${plural(byDay.keys.count { it >= firstMonday }, "active day")} in $weeks weeks", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Less ", color = Palette.muted, fontSize = 10.sp)
            listOf(0.06f, 0.35f, 0.55f, 0.75f, 1f).forEach { a ->
                Box(Modifier.padding(1.dp).size(10.dp).clip(RoundedCornerShape(2.dp)).background(if (a < 0.1f) Color.White.copy(alpha = a) else Palette.accent.copy(alpha = a)))
            }
            Text(" More", color = Palette.muted, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot(Palette.accent, "all safe"); LegendDot(Palette.amber, "warning"); LegendDot(Palette.red, "mostly unsafe")
        }
    }
}

@Composable
private fun LegendDot(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Palette.muted, fontSize = 10.sp)
    }
}

/** Month calendar; each day shows a ring coloured like the heatmap. Swipe months with the arrows. */
@Composable
fun MonthCalendar(rs: List<ScanRecord>, month: Long, selected: Long?, onMonth: (Long) -> Unit, onSelect: (Long) -> Unit) {
    val byDay = remember(rs) { Insights.byDay(rs) }
    val today = Insights.startOfDay(System.currentTimeMillis())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Palette.text, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable { onMonth(shiftMonth(month, -1)) }.padding(horizontal = 14.dp))
            Text(SimpleDateFormat("MMMM yyyy", Locale.US).format(Date(month)), color = Palette.text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("›", color = Palette.text, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable { onMonth(shiftMonth(month, 1)) }.padding(horizontal = 14.dp))
        }
        Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, color = Palette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        AnimatedContent(month, transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 3 } + fadeOut())
        }, label = "month") { m ->
            val cal = Calendar.getInstance().apply { timeInMillis = m; set(Calendar.DAY_OF_MONTH, 1) }
            val first = Insights.startOfDay(cal.timeInMillis)
            val lead = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (week in 0 until 6) {
                    if (week * 7 - lead >= days) break
                    Row {
                        for (d in 0..6) {
                            val idx = week * 7 + d - lead
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                                if (idx in 0 until days) {
                                    val day = first + idx * DAY
                                    val t = byDay[day]
                                    val c = dayColor(t)
                                    val sel = day == selected
                                    Box(
                                        Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                                            .background(if (sel) Brush.linearGradient(listOf(Palette.accent, Palette.cyan)) else Brush.linearGradient(listOf(c, c)))
                                            .then(if (day == today) Modifier.border(2.dp, Palette.ink, CircleShape) else Modifier)
                                            .clickable { onSelect(day) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("${idx + 1}", color = if (sel) Palette.onAccent else if (day > today) Palette.muted.copy(alpha = 0.4f) else Palette.text,
                                            fontSize = 12.sp, fontWeight = if (t != null) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shiftMonth(t: Long, by: Int) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, by) }.timeInMillis

/** Testing streak with a breathing flame. */
@Composable
fun StreakCard(current: Int, longest: Int, activeDays: Int) {
    val t = rememberInfiniteTransition(label = "flame")
    val s by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    Glass(glow = Palette.amber, padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(64.dp)) {
                    drawCircle(Brush.radialGradient(listOf(Palette.amber.copy(alpha = 0.45f * s), Color.Transparent)), size.minDimension / 2 * s)
                }
                Text("🔥", fontSize = (30 * s).sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUp(current) { Text(it, color = Palette.amber, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 32.sp) }
                    Text("  day streak", color = Palette.text, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text("Longest ${plural(longest, "day")} · ${plural(activeDays, "day")} tested", color = Palette.muted, fontSize = 12.sp)
            }
        }
    }
}

/** 24-hour radial bar chart — when the household tests. */
@Composable
fun HourClock(hours: IntArray, modifier: Modifier = Modifier.size(230.dp)) {
    val grow = remember(hours.toList()) { Animatable(0f) }
    LaunchedEffect(hours.toList()) { grow.snapTo(0f); grow.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    val maxN = (hours.maxOrNull() ?: 1).coerceAtLeast(1)
    Canvas(modifier) {
        val r0 = size.minDimension * 0.18f
        val r1 = size.minDimension * 0.46f
        drawCircle(Palette.ink.copy(alpha = 0.05f), r1)
        drawCircle(Palette.ink.copy(alpha = 0.08f), r0, style = Stroke(2f))
        for (h in 0 until 24) {
            val a = Math.toRadians(-90.0 + h * 15.0)
            val len = (r1 - r0) * hours[h] / maxN * grow.value
            val col = when (h) { in 5..10 -> Palette.amber; in 11..16 -> Palette.accent; in 17..21 -> Palette.violet; else -> Palette.blue }
            val p0 = Offset(center.x + (r0 * cos(a)).toFloat(), center.y + (r0 * sin(a)).toFloat())
            val p1 = Offset(center.x + ((r0 + len.coerceAtLeast(3f)) * cos(a)).toFloat(), center.y + ((r0 + len.coerceAtLeast(3f)) * sin(a)).toFloat())
            drawLine(if (hours[h] == 0) Palette.ink.copy(alpha = 0.1f) else col, p0, p1, size.minDimension * 0.035f, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        val paint = android.graphics.Paint().apply { color = Palette.muted.toArgb(); textSize = size.minDimension * 0.06f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
        listOf(0 to "12a", 6 to "6a", 12 to "12p", 18 to "6p").forEach { (h, s) ->
            val a = Math.toRadians(-90.0 + h * 15.0)
            val rr = r1 + size.minDimension * 0.04f
            drawContext.canvas.nativeCanvas.drawText(s, center.x + (rr * cos(a)).toFloat(), center.y + (rr * sin(a)).toFloat() + paint.textSize / 3, paint)
        }
        val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
        val pp = android.graphics.Paint(paint).apply { color = Palette.text.toArgb(); textSize = size.minDimension * 0.075f; isFakeBoldText = true }
        drawContext.canvas.nativeCanvas.drawText(if (hours.sum() == 0) "—" else "${peak}:00", center.x, center.y + pp.textSize / 3, pp)
    }
}

/** Horizontal bars per instrument, growing in with a stagger. */
@Composable
fun InstrumentMix(mix: List<Pair<String, Int>>) {
    val maxN = (mix.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
    val colors = mapOf(
        "Spectrum" to Palette.violet, "Polar" to Palette.amber, "NIR" to Palette.red, "Nami" to Palette.cyan, "Echo" to Palette.blue,
        "Strips" to Palette.accent, "Hawa" to Palette.tint(Color(0xFF9AD0C2)), "Float" to Palette.tint(Color(0xFFE8F1EC)), "Magneto" to Palette.violet, "Lens" to Palette.amber,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        mix.forEachIndexed { i, (name, n) ->
            val a = remember(mix) { Animatable(0f) }
            LaunchedEffect(mix) { a.animateTo(n.toFloat() / maxN, tween(700, delayMillis = 70 * i, easing = FastOutSlowInEasing)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Palette.text, fontSize = 12.sp, modifier = Modifier.width(78.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Palette.ink.copy(alpha = 0.05f))) {
                    val c = colors[name] ?: Palette.cyan
                    Box(Modifier.fillMaxWidth(a.value.coerceAtLeast(0.01f)).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.6f), c))))
                }
                Text("  $n", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.width(36.dp))
            }
        }
    }
}
