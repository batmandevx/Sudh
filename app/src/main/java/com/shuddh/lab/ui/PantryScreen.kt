package com.shuddh.lab.ui

import android.app.DatePickerDialog
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.PantryItem
import com.shuddh.lab.core.stamp
import java.util.Calendar

fun pantryColor(days: Long): Color = when { days < 0 -> Palette.red; days <= 3 -> Palette.amber; days <= 7 -> Color(0xFFFDE047); else -> Palette.accent }

@Composable
fun PantryScreen(app: AppState) {
    val pantry = app.pantry
    val ctx = app.ctx
    var name by remember { mutableStateOf(app.pendingPantryName ?: "") }
    var expiry by remember { mutableStateOf(app.pendingPantryExpiry) }
    val items = pantry.sorted()
    val expired = items.count { it.daysLeft < 0 }
    val soon = items.count { it.daysLeft in 0..7 }
    val fresh = items.size - expired - soon
    LaunchedEffect(Unit) { app.pendingPantryName = null; app.pendingPantryExpiry = null }

    ScreenFrame("Pantry", "Expiry tracker · fight food waste", onBack = { app.back() }) {
        Glass(glow = Palette.accent, padding = 16) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Donut(fresh, soon, expired, Modifier.size(92.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CountUp(items.size) { Text(it, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp) }
                        Text("items", color = Palette.muted, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LegendRow(Palette.accent, "Fresh", fresh); LegendRow(Palette.amber, "Use within a week", soon); LegendRow(Palette.red, "Expired", expired)
                }
            }
            pantry.wasteScore()?.let { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22000000)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("♻️", fontSize = 20.sp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Food saved score: $s%", color = Palette.text, fontWeight = FontWeight.Bold)
                        Text("${pantry.usedInTime} eaten in time · ${pantry.wasted} wasted", color = Palette.muted, fontSize = 11.sp)
                    }
                }
            }
        }

        Section("Add an item") {
            BasicTextField(
                name, { name = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22000000)).border(1.dp, Palette.line, RoundedCornerShape(14.dp)).padding(14.dp),
                textStyle = TextStyle(color = Palette.text, fontSize = 15.sp, fontFamily = Body), cursorBrush = SolidColor(Palette.cyan),
                decorationBox = { inner -> Box { if (name.isEmpty()) Text("e.g. Toned milk 500 ml", color = Palette.muted, fontSize = 15.sp); inner() } },
            )
            Text("Shelf life", color = Palette.muted, fontSize = 12.sp)
            BtnRow {
                listOf(2 to "2 days", 5 to "5 days", 7 to "1 week", 30 to "1 month", 90 to "3 months", 180 to "6 months").forEach { (d, label) ->
                    Btn(label, primary = false) { expiry = System.currentTimeMillis() + d * 86_400_000L }
                }
                Btn("📅 Pick date", primary = false) {
                    val c = Calendar.getInstance()
                    DatePickerDialog(ctx, { _, y, m, d -> expiry = Calendar.getInstance().apply { set(y, m, d, 23, 59) }.timeInMillis },
                        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }
            }
            expiry?.let { Text("Expires ${stamp(it).substringBefore(",")}", color = Palette.cyan, fontSize = 13.sp) }
            BtnRow {
                Btn("Add to pantry", enabled = name.isNotBlank() && expiry != null) { pantry.add(name, expiry!!); name = ""; expiry = null }
                Btn("🔎 Scan a label", primary = false) { app.go(Screen.LENS) }
            }
        }

        if (items.isEmpty()) Note("Nothing tracked yet. Scan a packet with Label Lens and tap \"Add to pantry\", or add one above.")
        items.forEachIndexed { i, it -> PantryCard(it, Modifier.enter(i.coerceAtMost(6)), onUsed = { pantry.finish(it, true) }, onWaste = { pantry.finish(it, false) }) }
    }
}

@Composable
private fun LegendRow(c: Color, label: String, n: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c)); Spacer(Modifier.width(8.dp))
        Text(label, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("$n", color = Palette.text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ExpiryRing(item: PantryItem, size: androidx.compose.ui.unit.Dp) {
    val c = pantryColor(item.daysLeft)
    val a = remember { Animatable(0f) }
    LaunchedEffect(item.id) { a.animateTo(item.freshness, tween(1000, easing = FastOutSlowInEasing)) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val st = this.size.minDimension * 0.1f
            drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, Offset(st, st), Size(this.size.width - 2 * st, this.size.height - 2 * st), style = Stroke(st))
            drawArc(c, -90f, 360f * a.value, false, Offset(st, st), Size(this.size.width - 2 * st, this.size.height - 2 * st), style = Stroke(st, cap = StrokeCap.Round))
        }
        Text(item.emoji, fontSize = (size.value / 2.6f).sp)
    }
}

@Composable
private fun PantryCard(it: PantryItem, modifier: Modifier, onUsed: () -> Unit, onWaste: () -> Unit) {
    val c = pantryColor(it.daysLeft)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.12f), Color(0x0AFFFFFF))))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(20.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpiryRing(it, 54.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(it.name, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            Text(
                when { it.daysLeft < 0 -> "Expired ${-it.daysLeft} day${if (-it.daysLeft == 1L) "" else "s"} ago"; it.daysLeft == 0L -> "Expires today"; else -> "${it.daysLeft} days left" } +
                    (if (it.source == "lens") " · from label" else ""),
                color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            it.fssai?.let { f -> Text("FSSAI $f", color = Palette.muted, fontSize = 10.sp) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("✓ Used", color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.accent.copy(alpha = 0.15f)).clickable(onClick = onUsed).padding(horizontal = 10.dp, vertical = 5.dp))
            Text("🗑 Wasted", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onWaste).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}
