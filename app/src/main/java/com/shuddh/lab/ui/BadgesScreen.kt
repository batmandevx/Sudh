package com.shuddh.lab.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Badge
import com.shuddh.lab.core.Badges

@Composable
fun BadgesScreen(app: AppState) {
    val badges = Badges.all(app.store, app.prefs)
    val (level, title, toNext) = Badges.level(badges)
    var focus by remember { mutableStateOf<Badge?>(null) }
    ScreenFrame("Badges", "${badges.count { it.unlocked }} of ${badges.size} earned", onBack = { app.back() }) {
        Glass(glow = Palette.amber, padding = 18) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(78.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Palette.amber, Palette.red))), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("LV", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        CountUp(level) { Text(it, color = Color.White, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 30.sp) }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    XpBar(toNext)
                    Text("Earn 2 badges to level up", color = Palette.muted, fontSize = 11.sp)
                }
            }
        }
        badges.chunked(3).forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { ci, b -> Medal(b, Modifier.weight(1f).enter(ri * 3 + ci)) { focus = b } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        focus?.let { b ->
            Glass(glow = if (b.unlocked) Palette.accent else Palette.line) {
                Text("${b.emoji}  ${b.title}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(b.how, color = Palette.muted, fontSize = 13.sp)
                Text(if (b.unlocked) "Unlocked ✓" else "Progress ${b.detail}", color = if (b.unlocked) Palette.accent else Palette.amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Note("Badges are computed from what you actually do in Shuddh — nothing is pre-unlocked.")
    }
}

@Composable
private fun XpBar(p: Float) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(p) { a.animateTo(p, tween(1200, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Palette.line)) {
        Box(Modifier.fillMaxWidth(a.value.coerceAtLeast(0.02f)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient(listOf(Palette.amber, Palette.red))))
    }
}

/** Medallion with a progress ring; unlocked ones glow and get a moving shine. */
@Composable
private fun Medal(b: Badge, modifier: Modifier, onClick: () -> Unit) {
    val ring = remember { Animatable(0f) }
    LaunchedEffect(b.progress) { ring.animateTo(b.progress, tween(1100, easing = FastOutSlowInEasing)) }
    val t = rememberInfiniteTransition(label = "shine")
    val shine by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "s")
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(if (b.unlocked) Palette.amber.copy(alpha = 0.08f) else Palette.glass).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(78.dp)) {
                val st = 7f
                if (b.unlocked) drawCircle(Brush.radialGradient(listOf(Palette.amber.copy(alpha = 0.45f), Color.Transparent)), size.minDimension / 2)
                drawCircle(Palette.ink.copy(alpha = 0.08f), size.minDimension / 2 - st, style = Stroke(st))
                drawArc(
                    Brush.sweepGradient(listOf(Palette.amber, Palette.red, Palette.amber)), -90f, 360f * ring.value, false,
                    Offset(st, st), androidx.compose.ui.geometry.Size(size.width - 2 * st, size.height - 2 * st), style = Stroke(st, cap = StrokeCap.Round),
                )
                drawCircle(if (UiPrefs.light) (if (b.unlocked) Color(0xFFFFF7E6) else Color(0xFFEEF2F8)) else if (b.unlocked) Color(0xFF2A1F0E) else Color(0xFF141B28), size.minDimension / 2 - st * 2.4f)
                if (b.unlocked && !UiPrefs.reduceMotion) {
                    val x = size.width * shine
                    drawLine(Palette.ink.copy(alpha = 0.25f), Offset(x - 20f, 0f), Offset(x + 20f, size.height), 10f)
                }
            }
            Text(b.emoji, fontSize = 28.sp, modifier = Modifier.alpha(if (b.unlocked) 1f else 0.35f))
        }
        Spacer(Modifier.height(6.dp))
        Text(b.title, color = if (b.unlocked) Palette.text else Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
        Text(if (b.unlocked) "Unlocked" else b.detail, color = if (b.unlocked) Palette.amber else Palette.muted, fontSize = 10.sp)
    }
}
