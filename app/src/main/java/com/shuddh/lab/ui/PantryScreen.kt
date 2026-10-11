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
import androidx.compose.ui.graphics.graphicsLayer
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

fun pantryColor(days: Long): Color = when { days < 0 -> Palette.red; days <= 3 -> Palette.amber; days <= 7 -> Palette.tint(Color(0xFFFDE047)); else -> Palette.accent }

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
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.well(0x22)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("♻️", fontSize = 20.sp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Food saved score: $s%", color = Palette.text, fontWeight = FontWeight.Bold)
                        Text("${pantry.usedInTime} eaten in time · ${pantry.wasted} wasted", color = Palette.muted, fontSize = 11.sp)
                    }
                }
            }
        }

        SmartRestock(app, Modifier.enter(1))
        ShoppingCard(app, Modifier.enter(2))
        BestTimeToBuy(Modifier.enter(3))

        Section("Add an item") {
            BasicTextField(
                name, { name = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.well(0x22)).border(1.dp, Palette.line, RoundedCornerShape(14.dp)).padding(14.dp),
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
            drawArc(Palette.ink.copy(alpha = 0.08f), 0f, 360f, false, Offset(st, st), Size(this.size.width - 2 * st, this.size.height - 2 * st), style = Stroke(st))
            drawArc(c, -90f, 360f * a.value, false, Offset(st, st), Size(this.size.width - 2 * st, this.size.height - 2 * st), style = Stroke(st, cap = StrokeCap.Round))
        }
        Text(item.emoji, fontSize = (size.value / 2.6f).sp)
    }
}

@Composable
private fun PantryCard(it: PantryItem, modifier: Modifier, onUsed: () -> Unit, onWaste: () -> Unit) {
    val c = pantryColor(it.daysLeft)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.12f), Palette.veil(0x0A))))
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

/** Predicted run-outs from the household's own buying rhythm, plus one-tap "bought" logging. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SmartRestock(app: AppState, modifier: Modifier) {
    val pantry = app.pantry
    val due = pantry.restock()
    var remind by remember { mutableStateOf(com.shuddh.lab.core.PantryReminder.enabled(app.ctx)) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    Glass(modifier, glow = Palette.blue, padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Smart restock"), color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(tr("Learns how fast you use each staple"), color = Palette.muted, fontSize = 11.sp)
            }
            Text("🔔", fontSize = 16.sp)
            androidx.compose.material3.Switch(remind, {
                remind = it; com.shuddh.lab.core.PantryReminder.set(app.ctx, it)
                if (it && android.os.Build.VERSION.SDK_INT >= 33) activity?.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
            })
        }
        if (remind) Text(tr("Daily 9 am reminder is on"), color = Palette.blue, fontSize = 11.sp)
        if (due.isEmpty()) {
            Note(if (pantry.history.isEmpty()) "Tap what you bought today — after two purchases Shuddh learns your rhythm and reminds you before you run out." else "Nothing is running out in the next 3 days. 👍")
        }
        due.forEachIndexed { i, r ->
            val c = if (r.dueInDays <= 0.5) Palette.red else if (r.dueInDays < 1.5) Palette.amber else Palette.blue
            Row(Modifier.fillMaxWidth().enter(i.coerceAtMost(5)).clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = 0.10f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.emoji, fontSize = 22.sp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.label, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(r.words + " · " + (if (r.learned) "you buy every ~${"%.1f".format(r.everyDays).removeSuffix(".0")} days" else "typical home rhythm"), color = c, fontSize = 11.sp)
                }
                Text("+ List", color = Palette.blue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { addToList(app, r.label) }.padding(horizontal = 8.dp, vertical = 5.dp))
                Text("✓ Bought", color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.accent.copy(alpha = 0.15f)).clickable { pantry.bought(r.key) }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        Text(tr("Bought today"), color = Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            com.shuddh.lab.core.PantrySmart.staples.forEach { st ->
                var pop by remember { mutableStateOf(false) }
                val sc by androidx.compose.animation.core.animateFloatAsState(if (pop) 1.15f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f), finishedListener = { pop = false }, label = "pop")
                val n = pantry.history[st.key]?.size ?: 0
                Text("${st.emoji} ${st.label}" + if (n > 0) " ·$n" else "", color = Palette.text, fontSize = 12.sp,
                    modifier = Modifier.graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(50)).background(Palette.veil(0x0E))
                        .clickable { pantry.bought(st.key); pop = true; com.shuddh.lab.core.Haptics.click(app.ctx) }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

/** Bumped whenever the shopping list changes so every card re-reads it. */
private var listVersion by mutableStateOf(0)

private fun addToList(app: AppState, item: String) {
    listVersion++
    val list = (app.memoryGet("shopping").lines().filter { it.isNotBlank() } + item).distinctBy { it.lowercase() }
    app.memoryPut("shopping", list.joinToString("\n"))
}

/** The assistant's shopping list, shareable with the local kirana or opened in a shop. */
@Composable
private fun ShoppingCard(app: AppState, modifier: Modifier) {
    val list = remember(listVersion) { app.memoryGet("shopping").lines().filter { it.isNotBlank() } }
    var input by remember { mutableStateOf("") }
    Glass(modifier, glow = Palette.accent, padding = 16) {
        Text(tr("Shopping list"), color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(input, { input = it }, singleLine = true,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Palette.veil(0x0C)).padding(12.dp),
                textStyle = TextStyle(color = Palette.text, fontSize = 14.sp, fontFamily = Body), cursorBrush = SolidColor(Palette.cyan),
                decorationBox = { inner -> Box { if (input.isEmpty()) Text("Add item…", color = Palette.muted, fontSize = 14.sp); inner() } })
            Spacer(Modifier.width(8.dp))
            Btn("Add", enabled = input.isNotBlank()) { addToList(app, input.trim()); input = "" }
        }
        if (list.isEmpty()) Note("Empty. Add from Smart restock, or tell the assistant \"add milk and atta to my list\".")
        list.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${com.shuddh.lab.core.PantryItem.emojiFor(item)}  $item", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("✓", color = Palette.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                    app.pantry.bought(item)
                    app.memoryPut("shopping", list.filterNot { it == item }.joinToString("\n")); listVersion++
                }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        if (list.isNotEmpty()) BtnRow {
            Btn("📤 Send to shop", primary = false) {
                val text = "Shopping list:\n" + list.joinToString("\n") { "• $it" }
                app.ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), "Send list").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Btn("🛒 Order online", primary = false) {
                runCatching { app.ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, "buy " + list.joinToString(", ") + " online").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
}

/** Seasonal price calendar: what to stock up on now, what to avoid this month. */
@Composable
private fun BestTimeToBuy(modifier: Modifier) {
    val month = Calendar.getInstance().get(Calendar.MONTH) + 1
    val tips = com.shuddh.lab.core.PantrySmart.tips(month)
    var all by remember { mutableStateOf(false) }
    Glass(modifier, glow = Palette.amber, padding = 16) {
        Text(tr("Best time to buy") + " · " + com.shuddh.lab.core.PantrySmart.monthName(month), color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(tr("From India's yearly mandi price cycle — typical pattern, not live prices"), color = Palette.muted, fontSize = 11.sp)
        (if (all) tips else tips.filter { it.advice != com.shuddh.lab.core.PantrySmart.Advice.GOOD }.ifEmpty { tips.take(3) }).forEachIndexed { i, t ->
            val (c, tag) = when (t.advice) {
                com.shuddh.lab.core.PantrySmart.Advice.STOCK_UP -> Palette.accent to "STOCK UP"
                com.shuddh.lab.core.PantrySmart.Advice.GOOD -> Palette.blue to "GOOD"
                com.shuddh.lab.core.PantrySmart.Advice.AVOID -> Palette.red to "COSTLY"
            }
            Row(Modifier.fillMaxWidth().enter(i.coerceAtMost(6)).clip(RoundedCornerShape(14.dp)).background(c.copy(alpha = 0.08f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.season.emoji, fontSize = 22.sp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.season.label, color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(tag, color = c, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                    Text(t.text, color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp)
                    MonthStrip(t.season, month)
                }
            }
        }
        Text(if (all) "Show less" else "Show all ${tips.size}", color = Palette.amber, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { all = !all })
    }
}

@Composable
private fun MonthStrip(s: com.shuddh.lab.core.PantrySmart.Season, now: Int) {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (1..12).forEach { m ->
            val c = when (m) { in s.cheap -> Palette.accent; in s.costly -> Palette.red; else -> Palette.veil(0x22) }
            Box(Modifier.weight(1f).height(if (m == now) 8.dp else 5.dp).clip(RoundedCornerShape(3.dp)).background(c.copy(alpha = if (m == now) 1f else 0.55f)))
        }
    }
}
