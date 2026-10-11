package com.shuddh.lab.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The assistant's colours: violet → blue → cyan → mint. */
val AiColors = listOf(Color(0xFF8B5CF6), Color(0xFF6366F1), Color(0xFF22D3EE), Color(0xFF34D399))

/**
 * Living AI orb: a breathing halo, a liquid core whose edge ripples, two counter-rotating rings
 * and orbiting sparks. [level] (0..1) swells it with the voice; [thinking] speeds it up.
 */
@Composable
fun AiOrb(size: Dp, modifier: Modifier = Modifier, level: Float = 0f, thinking: Boolean = false, rings: Boolean = true) {
    val inf = rememberInfiniteTransition(label = "orb")
    val speed = if (thinking) 0.45f else 1f
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween((5200 * speed).toInt(), easing = LinearEasing)), label = "t")
    val breathe by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val lv by animateFloatAsState(level.coerceIn(0f, 1f), tween(120), label = "lv")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        val c = center
        val swell = 1f + 0.06f * breathe + 0.18f * lv
        // Halo
        drawCircle(Brush.radialGradient(listOf(AiColors[0].copy(alpha = 0.35f + 0.25f * lv), AiColors[2].copy(alpha = 0.12f), Color.Transparent), c, r), r)
        if (rings) {
            rotate(360f * t) {
                drawCircle(Brush.sweepGradient(AiColors + AiColors.first(), c), r * 0.86f, style = Stroke(r * 0.035f, cap = StrokeCap.Round))
            }
            rotate(-540f * t) {
                drawCircle(AiColors[2].copy(alpha = 0.55f), r * 0.74f, style = Stroke(r * 0.02f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.06f, r * 0.09f))))
            }
        }
        // Liquid core: radius rippled by three slow harmonics.
        val base = r * 0.5f * swell
        val p = Path()
        val n = 72
        for (i in 0..n) {
            val a = (i.toFloat() / n) * 2 * PI.toFloat()
            val w = 1f + 0.06f * sin(3 * a + 2 * PI.toFloat() * t) + 0.04f * sin(5 * a - 4 * PI.toFloat() * t) + 0.05f * lv * sin(7 * a + 6 * PI.toFloat() * t)
            val x = c.x + base * w * cos(a); val y = c.y + base * w * sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        rotate(120f * t) {
            drawPath(p, Brush.linearGradient(AiColors, Offset(c.x - base, c.y - base), Offset(c.x + base, c.y + base)))
        }
        // Glass glint
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), Offset(c.x - base * 0.35f, c.y - base * 0.4f), base * 0.6f), base * 0.6f, Offset(c.x - base * 0.35f, c.y - base * 0.4f))
        // Orbiting sparks
        if (rings) repeat(3) { k ->
            val a = 2 * PI.toFloat() * (t + k / 3f)
            val rr = r * (0.86f + 0.04f * sin(4 * a))
            val pos = Offset(c.x + rr * cos(a), c.y + rr * sin(a))
            drawCircle(Color.White, r * 0.035f, pos)
            drawCircle(AiColors[(k + 1) % AiColors.size].copy(alpha = 0.45f), r * 0.08f, pos)
        }
    }
}

/** Prompts the Ask bar types out, one after another. */
val AskPrompts = listOf(
    "My milk tastes weird…", "Are these apples waxed?", "Stones in my rice?", "Is this watermelon ripe?",
    "Tap water smells of bleach", "What's running out at home?", "Set a 10 min timer",
)

/** Home entry to the assistant: animated gradient border, living orb, typewriter prompts, mic. */
@Composable
fun AskBarFancy(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "ask")
    val spin by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "spin")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse")
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f), label = "sc")
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        var i = 0
        while (true) {
            val s = AskPrompts[i % AskPrompts.size]
            for (k in 1..s.length) { shown = s.take(k); delay(45) }
            delay(1600)
            for (k in s.length downTo 0) { shown = s.take(k); delay(18) }
            delay(250); i++
        }
    }
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (Palette.light) 14.dp else 0.dp, shape, ambientColor = AiColors[0].copy(alpha = 0.35f), spotColor = AiColors[0].copy(alpha = 0.35f))
            .clip(shape)
            .drawBehind {
                // Rotating conic border: draw the spinning gradient, then cover the inside.
                rotate(360f * spin) {
                    drawCircle(Brush.sweepGradient(AiColors + AiColors.first()), size.maxDimension)
                }
                drawRoundRect(if (Palette.light) Color.White else Color(0xFF0E1522), Offset(2.dp.toPx(), 2.dp.toPx()),
                    androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()), CornerRadius(24.dp.toPx()))
                drawRoundRect(Brush.horizontalGradient(listOf(AiColors[0].copy(alpha = 0.10f), AiColors[2].copy(alpha = 0.06f))), Offset(2.dp.toPx(), 2.dp.toPx()),
                    androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()), CornerRadius(24.dp.toPx()))
            }
            .clickable(src, null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiOrb(46.dp, rings = false)
            Spacer(Modifier.width(10.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("Ask Shuddh AI", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shown, color = Palette.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.padding(start = 1.dp).size(2.dp, 14.dp).background(AiColors[0].copy(alpha = if (pulse < 0.5f) 1f else 0f)))
                }
            }
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(46.dp)) {
                    val r = size.minDimension / 2
                    drawCircle(AiColors[2].copy(alpha = 0.35f * (1 - pulse)), r * (0.6f + 0.4f * pulse))
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(listOf(AiColors[0], AiColors[2]))), contentAlignment = Alignment.Center) {
                    MicGlyph(Color.White, Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Clean line microphone (no emoji, so it renders the same everywhere). */
@Composable
fun MicGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val st = w * 0.11f
        drawRoundRect(color, Offset(w * 0.32f, 0f), androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.62f), CornerRadius(w * 0.18f))
        drawArc(color, 0f, 180f, false, Offset(w * 0.16f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.56f), style = Stroke(st, cap = StrokeCap.Round))
        drawLine(color, Offset(w * 0.5f, h * 0.78f), Offset(w * 0.5f, h * 0.96f), st, StrokeCap.Round)
    }
}

/** Centre tab: raised, glowing CrispRoots button with a sprout that grows and sways. */
@Composable
fun CrispTabButton(selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "crisp")
    val grow by inf.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g")
    val ring by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "r")
    androidx.compose.foundation.layout.Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).graphicsLayer { translationY = -6f }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(46.dp)) {
                val r = size.minDimension / 2
                drawCircle(Color(0xFF22C55E).copy(alpha = 0.35f * (1 - ring)), r * (0.75f + 0.25f * ring))
                drawCircle(Brush.linearGradient(listOf(Color(0xFF16A34A), Color(0xFF0E7490))), r * 0.78f)
                val base = Offset(center.x, center.y + r * 0.42f)
                val top = Offset(center.x, center.y + r * 0.42f - r * 0.75f * grow)
                drawLine(Color.White, base, top, r * 0.1f, StrokeCap.Round)
                val mid = Offset(center.x, base.y - (base.y - top.y) * 0.55f)
                val leaf = Path().apply { moveTo(mid.x, mid.y); quadraticTo(mid.x + r * 0.45f * grow, mid.y - r * 0.35f, mid.x + r * 0.5f * grow, mid.y - r * 0.05f); quadraticTo(mid.x + r * 0.2f, mid.y + r * 0.05f, mid.x, mid.y) }
                drawPath(leaf, Color(0xFFBBF7D0))
                val leaf2 = Path().apply { moveTo(top.x, top.y + r * 0.08f); quadraticTo(top.x - r * 0.4f * grow, top.y - r * 0.2f, top.x - r * 0.42f * grow, top.y + r * 0.1f); quadraticTo(top.x - r * 0.15f, top.y + r * 0.2f, top.x, top.y + r * 0.08f) }
                drawPath(leaf2, Color.White)
            }
        }
        Text("CrispRoots", fontSize = 10.sp, maxLines = 1, softWrap = false, color = if (selected) Color(0xFF16A34A) else Palette.muted, fontWeight = FontWeight.Bold)
    }
}
