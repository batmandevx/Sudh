package com.shuddh.lab.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Level
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The spectrum gradient — Shuddh's signature: violet → blue → cyan → green → amber → rose. */
val SpectrumColors = listOf(
    Color(0xFF8B5CF6), Color(0xFF3B82F6), Color(0xFF22D3EE), Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFF43F5E),
)
val SpectrumBrush = Brush.horizontalGradient(SpectrumColors)

/** Slow-drifting aurora blobs behind content. Cheap: three radial gradients on one canvas. */
@Composable
fun Aurora(modifier: Modifier = Modifier, intensity: Float = 1f) {
    val t = rememberInfiniteTransition(label = "aurora")
    val anim by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "a")
    val a = if (UiPrefs.reduceMotion) 0.8f else anim
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        fun blob(cx: Float, cy: Float, r: Float, c: Color) = drawCircle(
            Brush.radialGradient(listOf(c.copy(alpha = 0.22f * intensity), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy),
        )
        blob(w * (0.2f + 0.15f * cos(a)), h * (0.12f + 0.05f * sin(a)), w * 0.75f, Color(0xFF8B5CF6))
        blob(w * (0.85f + 0.1f * sin(a * 1.3f)), h * (0.3f + 0.08f * cos(a)), w * 0.7f, Color(0xFF22D3EE))
        blob(w * (0.5f + 0.2f * cos(a * 0.7f)), h * (0.8f + 0.05f * sin(a * 1.1f)), w * 0.8f, Color(0xFF34D399))
    }
}

/** Fades and slides a child in, delayed by its [index] — for staggered lists. */
fun Modifier.enter(index: Int = 0): Modifier = composed {
    if (UiPrefs.reduceMotion) return@composed this
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(520, delayMillis = 60 * index, easing = FastOutSlowInEasing)) }
    graphicsLayer {
        alpha = anim.value
        translationY = (1f - anim.value) * 60f
        scaleX = 0.96f + 0.04f * anim.value; scaleY = scaleX
    }
}

/** Gentle press-free "breathing" scale for live indicators. */
fun Modifier.pulse(enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed this
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    graphicsLayer { scaleX = s; scaleY = s }
}

@Composable
fun CountUp(value: Int, style: @Composable (String) -> Unit) {
    val v by animateFloatAsState(value.toFloat(), tween(1100, easing = FastOutSlowInEasing), label = "count")
    style(v.toInt().toString())
}

/** Rotating spectrum halo around a big animated number — the home hero. */
@Composable
fun SpectrumOrb(score: Int?, size: Dp = 150.dp, caption: String) {
    val t = rememberInfiniteTransition(label = "orb")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "rot")
    val glow by t.animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "glow")
    val fill by animateFloatAsState((score ?: 0) / 100f, tween(1400, easing = FastOutSlowInEasing), label = "fill")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.toPx() * 0.07f
            val sweep = Brush.sweepGradient(SpectrumColors + SpectrumColors.first())
            drawCircle(Brush.radialGradient(listOf(Color(0xFF34D399).copy(alpha = glow * 0.35f), Color.Transparent)), this.size.minDimension / 1.6f)
            rotate(rot) { drawCircle(sweep, this.size.minDimension / 2 - stroke, style = Stroke(stroke * 0.35f)) }
            drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, Offset(stroke * 1.6f, stroke * 1.6f),
                Size(this.size.width - stroke * 3.2f, this.size.height - stroke * 3.2f), style = Stroke(stroke))
            rotate(-90f) {
                drawArc(sweep, 0f, 360f * fill, false, Offset(stroke * 1.6f, stroke * 1.6f),
                    Size(this.size.width - stroke * 3.2f, this.size.height - stroke * 3.2f), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (score == null) {
                Text("—", color = Palette.text, fontSize = (size.value / 4).sp, fontWeight = FontWeight.Black)
            } else {
                CountUp(score) { Text(it, color = Palette.text, fontSize = (size.value / 3.6f).sp, fontWeight = FontWeight.Black) }
            }
            Text(caption, color = Palette.muted, fontSize = 11.sp)
        }
    }
}

/** Semicircle gauge from safe (left) to unsafe (right) with a springy needle. */
@Composable
fun VerdictGauge(level: Level, modifier: Modifier = Modifier) {
    val target = when (level) { Level.SAFE -> 0.15f; Level.CAUTION -> 0.5f; Level.UNSAFE -> 0.87f; Level.INCONCLUSIVE -> 0.5f }
    val anim = remember { Animatable(0f) }
    LaunchedEffect(level) { anim.animateTo(target, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessVeryLow)) }
    Canvas(modifier) {
        val stroke = size.width * 0.07f
        val r = size.width / 2 - stroke
        val c = Offset(size.width / 2, size.height - stroke / 2)
        val arcTopLeft = Offset(c.x - r, c.y - r)
        val gradient = Brush.sweepGradient(
            0f to Color(0xFFF43F5E), 0.5f to Color(0xFF34D399), 0.75f to Color(0xFFFBBF24), 0.999f to Color(0xFFF43F5E), center = c,
        )
        drawArc(Color.White.copy(alpha = 0.06f), 180f, 180f, false, arcTopLeft, Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
        if (level != Level.INCONCLUSIVE) {
            drawArc(gradient, 180f, 180f, false, arcTopLeft, Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        val ang = PI + PI * anim.value
        val tip = Offset(c.x + (r * 0.82f) * cos(ang).toFloat(), c.y + (r * 0.82f) * sin(ang).toFloat())
        drawLine(Color.White, c, tip, stroke * 0.28f, StrokeCap.Round)
        drawCircle(Color.White, stroke * 0.45f, c)
        drawCircle(Color(level.argb), stroke * 0.25f, c)
    }
}

/** Animated donut of safe / caution / unsafe counts. */
@Composable
fun Donut(safe: Int, caution: Int, unsafe: Int, modifier: Modifier = Modifier.size(120.dp), center: @Composable BoxScope.() -> Unit = {}) {
    val total = (safe + caution + unsafe).coerceAtLeast(1).toFloat()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    val p = anim.value
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.13f
            val tl = Offset(stroke / 2, stroke / 2)
            val sz = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.06f), 0f, 360f, false, tl, sz, style = Stroke(stroke))
            var start = -90f
            listOf(safe to Palette.accent, caution to Palette.amber, unsafe to Palette.red).forEach { (n, c) ->
                val sweep = 360f * n / total * p
                if (n > 0) drawArc(c, start, (sweep - 2f).coerceAtLeast(0.5f), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        center()
    }
}

enum class Glyph { SPECTRUM, POLAR, NIR, ECHO, STRIP, SCATTER, FLOAT, NAMI, MAGNET, COOKER, KIT, SPARK }

/** Tiny animated infographic for each instrument — the physics, drawn. */
@Composable
fun InstrumentGlyph(g: Glyph, color: Color, modifier: Modifier = Modifier.size(56.dp), animated: Boolean = true) {
    val t = rememberInfiniteTransition(label = "glyph")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p")
    Canvas(modifier) { drawGlyph(g, color, if (animated) p else 0.3f) }
}

private fun DrawScope.drawGlyph(g: Glyph, color: Color, p: Float) {
    val w = size.width; val h = size.height; val c = Offset(w / 2, h / 2)
    val s = Stroke(w * 0.05f, cap = StrokeCap.Round)
    when (g) {
        Glyph.SPECTRUM -> {
            val tri = Path().apply { moveTo(w * 0.18f, h * 0.78f); lineTo(w * 0.42f, h * 0.22f); lineTo(w * 0.62f, h * 0.78f); close() }
            drawPath(tri, Color.White.copy(alpha = 0.85f), style = s)
            drawLine(Color.White, Offset(0f, h * 0.55f), Offset(w * 0.32f, h * 0.5f), w * 0.04f)
            SpectrumColors.forEachIndexed { i, col ->
                val spread = (i - 2.5f) * 0.07f * (0.7f + 0.3f * sin(p * 2 * PI.toFloat()))
                drawLine(col, Offset(w * 0.52f, h * 0.5f), Offset(w, h * (0.5f + spread * 2.2f)), w * 0.045f, StrokeCap.Round)
            }
        }
        Glyph.POLAR -> {
            drawCircle(color.copy(alpha = 0.3f), w * 0.42f, c, style = s)
            rotate(p * 360f) {
                drawLine(color, Offset(c.x, h * 0.14f), Offset(c.x, h * 0.86f), w * 0.07f, StrokeCap.Round)
                drawLine(color.copy(alpha = 0.5f), Offset(w * 0.14f, c.y), Offset(w * 0.86f, c.y), w * 0.03f, StrokeCap.Round)
            }
        }
        Glyph.NIR -> {
            drawCircle(color, w * 0.08f, Offset(w * 0.15f, c.y))
            for (k in 0..2) {
                val r = ((p + k / 3f) % 1f) * w * 0.8f
                drawArc(color.copy(alpha = 1f - (p + k / 3f) % 1f), -40f, 80f, false,
                    Offset(w * 0.15f - r, c.y - r), Size(r * 2, r * 2), style = s)
            }
        }
        Glyph.ECHO -> {
            for (i in 0 until 7) {
                val amp = 0.15f + 0.35f * kotlin.math.abs(sin((p * 2 * PI + i * 0.9).toFloat()))
                val x = w * (0.12f + i * 0.127f)
                drawLine(color, Offset(x, c.y - h * amp), Offset(x, c.y + h * amp), w * 0.07f, StrokeCap.Round)
            }
        }
        Glyph.STRIP -> {
            drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(w * 0.38f, h * 0.06f), Size(w * 0.24f, h * 0.88f), CornerRadius(w * 0.05f))
            val shift = (p * SpectrumColors.size).toInt()
            for (i in 0..3) {
                drawRoundRect(SpectrumColors[(i + shift) % SpectrumColors.size], Offset(w * 0.41f, h * (0.12f + i * 0.2f)), Size(w * 0.18f, h * 0.14f), CornerRadius(w * 0.03f))
            }
        }
        Glyph.SCATTER -> {
            drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, c.y), Offset(w, c.y), h * 0.22f)
            for (i in 0 until 9) {
                val x = ((i * 0.13f + p) % 1f) * w
                val y = c.y + sin((i * 1.7f + p * 6f)) * h * 0.28f
                drawCircle(color.copy(alpha = 0.9f), w * (0.025f + (i % 3) * 0.012f), Offset(x, y))
            }
        }
        Glyph.FLOAT -> {
            val bob = sin(p * 2 * PI.toFloat()) * h * 0.06f
            val wave = Path().apply {
                moveTo(0f, h * 0.62f)
                for (x in 0..20) lineTo(w * x / 20f, h * 0.62f + sin(x / 3f + p * 2 * PI.toFloat()) * h * 0.03f)
                lineTo(w, h); lineTo(0f, h); close()
            }
            drawPath(wave, Brush.verticalGradient(listOf(color.copy(alpha = 0.5f), color.copy(alpha = 0.1f))))
            drawRoundRect(Color.White, Offset(w * 0.44f, h * 0.08f + bob), Size(w * 0.12f, h * 0.6f), CornerRadius(w * 0.06f), style = s)
            drawCircle(Color.White, w * 0.1f, Offset(c.x, h * 0.7f + bob))
        }
        Glyph.MAGNET -> { // U-magnet with field arcs
            val u = Path().apply {
                moveTo(w * 0.25f, h * 0.2f); lineTo(w * 0.25f, h * 0.55f)
                cubicTo(w * 0.25f, h * 0.92f, w * 0.75f, h * 0.92f, w * 0.75f, h * 0.55f); lineTo(w * 0.75f, h * 0.2f)
            }
            drawPath(u, color, style = Stroke(w * 0.13f))
            drawLine(Color.White, Offset(w * 0.25f, h * 0.18f), Offset(w * 0.25f, h * 0.3f), w * 0.13f)
            drawLine(Color.White, Offset(w * 0.75f, h * 0.18f), Offset(w * 0.75f, h * 0.3f), w * 0.13f)
            val a = 0.4f + 0.6f * kotlin.math.abs(sin(p * PI.toFloat()))
            drawArc(color.copy(alpha = a), 200f, 140f, false, Offset(w * 0.1f, -h * 0.25f), Size(w * 0.8f, h * 0.6f), style = Stroke(w * 0.04f))
        }
        Glyph.COOKER -> { // pressure cooker with puffing steam
            drawRoundRect(color, Offset(w * 0.14f, h * 0.48f), Size(w * 0.72f, h * 0.42f), CornerRadius(w * 0.12f))
            drawRoundRect(color.copy(alpha = 0.7f), Offset(w * 0.08f, h * 0.42f), Size(w * 0.84f, h * 0.1f), CornerRadius(w * 0.05f))
            drawRect(Color.White, Offset(w * 0.46f, h * 0.3f), Size(w * 0.08f, h * 0.13f))
            for (k in 0..2) {
                val q = (p + k / 3f) % 1f
                drawCircle(Color.White.copy(alpha = (1f - q) * 0.8f), w * (0.05f + 0.06f * q), Offset(w * (0.5f + 0.08f * sin(q * 6f)), h * (0.26f - 0.24f * q)))
            }
        }
        Glyph.KIT -> { // toolbox
            drawRoundRect(color, Offset(w * 0.1f, h * 0.36f), Size(w * 0.8f, h * 0.5f), CornerRadius(w * 0.08f))
            drawRoundRect(color, Offset(w * 0.34f, h * 0.2f), Size(w * 0.32f, h * 0.2f), CornerRadius(w * 0.06f), style = Stroke(w * 0.07f))
            drawLine(Color.White.copy(alpha = 0.8f), Offset(w * 0.1f, h * 0.56f), Offset(w * 0.9f, h * 0.56f), w * 0.05f)
            drawRoundRect(Color.White, Offset(w * 0.44f, h * 0.5f), Size(w * 0.12f, h * 0.12f), CornerRadius(w * 0.03f))
        }
        Glyph.SPARK -> { // AI sparkle
            fun star(cx: Float, cy: Float, r: Float, c: Color) {
                val path = Path().apply {
                    moveTo(cx, cy - r); quadraticTo(cx, cy, cx + r, cy); quadraticTo(cx, cy, cx, cy + r)
                    quadraticTo(cx, cy, cx - r, cy); quadraticTo(cx, cy, cx, cy - r); close()
                }
                drawPath(path, c)
            }
            val tw = 0.85f + 0.15f * sin(p * 2 * PI.toFloat())
            star(w * 0.42f, h * 0.55f, w * 0.34f * tw, color)
            star(w * 0.78f, h * 0.24f, w * 0.15f * (1.9f - tw), Color.White)
        }
        Glyph.NAMI -> {
            val drop = Path().apply {
                moveTo(c.x, h * 0.1f)
                cubicTo(w * 0.78f, h * 0.45f, w * 0.72f, h * 0.7f, c.x, h * 0.72f)
                cubicTo(w * 0.28f, h * 0.7f, w * 0.22f, h * 0.45f, c.x, h * 0.1f)
            }
            drawPath(drop, Brush.verticalGradient(listOf(Color(0xFF22D3EE), color)))
            for (k in 0..1) {
                val q = (p + k * 0.5f) % 1f
                drawOval(color.copy(alpha = 1f - q), Offset(c.x - w * 0.45f * q, h * 0.86f - h * 0.08f * q), Size(w * 0.9f * q, h * 0.16f * q), style = Stroke(w * 0.03f))
            }
        }
    }
}

/** Animated "LIVE" dot. */
@Composable
fun LiveDot(color: Color = Palette.accent) {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Canvas(Modifier.size(10.dp)) {
        drawCircle(color.copy(alpha = a * 0.35f), size.minDimension / 2)
        drawCircle(color, size.minDimension / 4)
    }
}

/** Animated radar (spider) chart; values 0..1, null = no data (drawn as a hollow marker). */
@Composable
fun RadarChart(labels: List<String>, values: List<Float?>, modifier: Modifier = Modifier.size(240.dp)) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values) { grow.snapTo(0f); grow.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val n = labels.size
        val r = size.minDimension / 2 * 0.72f
        fun pt(i: Int, f: Float): Offset {
            val a = -PI / 2 + 2 * PI * i / n
            return Offset(center.x + r * f * cos(a).toFloat(), center.y + r * f * sin(a).toFloat())
        }
        for (k in 1..4) {
            val ring = Path().apply { for (i in 0 until n) { val p = pt(i, k / 4f); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
            drawPath(ring, Color.White.copy(alpha = 0.07f), style = Stroke(2f))
        }
        for (i in 0 until n) drawLine(Color.White.copy(alpha = 0.07f), center, pt(i, 1f))
        val poly = Path().apply {
            for (i in 0 until n) { val p = pt(i, (values[i] ?: 0f) * grow.value); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        drawPath(poly, Brush.radialGradient(listOf(Color(0x6634D399), Color(0x3322D3EE)), center, r))
        drawPath(poly, Color(0xFF34D399), style = Stroke(4f))
        val paint = android.graphics.Paint().apply { textSize = 26f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
        for (i in 0 until n) {
            val v = values[i]
            val p = pt(i, (v ?: 0f) * grow.value)
            if (v != null) drawCircle(Color.White, 6f, p) else drawCircle(Color.White.copy(alpha = 0.3f), 5f, pt(i, 0.05f), style = Stroke(2f))
            val lp = pt(i, 1.25f)
            paint.color = if (v == null) 0xFF8C9AB0.toInt() else 0xFFEFF4FA.toInt()
            drawContext.canvas.nativeCanvas.drawText(labels[i], lp.x, lp.y + 8f, paint)
        }
    }
}

/** Stacked daily bars (safe / caution / unsafe) that grow in. */
@Composable
fun ActivityBars(days: List<Triple<Int, Int, Int>>, modifier: Modifier = Modifier.fillMaxWidth().height(120.dp)) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(days) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val maxN = days.maxOfOrNull { it.first + it.second + it.third }?.coerceAtLeast(1) ?: 1
    Canvas(modifier) {
        val bw = size.width / days.size
        days.forEachIndexed { i, (s, c, u) ->
            var top = size.height
            listOf(s to Palette.accent, c to Palette.amber, u to Palette.red).forEach { (n, col) ->
                if (n > 0) {
                    val h = size.height * n / maxN * grow.value
                    drawRoundRect(col, Offset(i * bw + 3f, top - h), Size(bw - 6f, h), CornerRadius(5f))
                    top -= h
                }
            }
            if (s + c + u == 0) drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(i * bw + 3f, size.height - 6f), Size(bw - 6f, 6f), CornerRadius(3f))
        }
    }
}


/** Screen entrance: fade + rise + slight scale, run once per screen. */
fun Modifier.screenIn(): Modifier = composed {
    if (UiPrefs.reduceMotion) return@composed this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 50f; scaleX = 0.985f + 0.015f * a.value; scaleY = scaleX }
}

/**
 * The Shuddh mark: a droplet whose rim is a rotating spectrum, with a prism inside splitting
 * white light into colour — "testing purity with light".
 */
@Composable
fun LogoMark(size: Dp = 56.dp, animated: Boolean = true) {
    val t = rememberInfiniteTransition(label = "logo")
    val shimmer by if (animated) t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "sh")
        else remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val drop = Path().apply {
            moveTo(w * 0.5f, h * 0.04f)
            cubicTo(w * 0.86f, h * 0.42f, w * 0.92f, h * 0.62f, w * 0.88f, h * 0.7f)
            cubicTo(w * 0.80f, h * 0.92f, w * 0.62f, h * 0.98f, w * 0.5f, h * 0.98f)
            cubicTo(w * 0.38f, h * 0.98f, w * 0.20f, h * 0.92f, w * 0.12f, h * 0.7f)
            cubicTo(w * 0.08f, h * 0.62f, w * 0.14f, h * 0.42f, w * 0.5f, h * 0.04f)
            close()
        }
        drawPath(drop, Brush.verticalGradient(listOf(Color(0xFF14233A), Color(0xFF0A1220))))
        // Static spectrum rim (top violet → bottom red); only the prism's rays shimmer.
        drawPath(drop, Brush.linearGradient(SpectrumColors, Offset(w * 0.2f, 0f), Offset(w * 0.8f, h)), style = Stroke(w * 0.07f))
        // prism
        val tri = Path().apply { moveTo(w * 0.36f, h * 0.78f); lineTo(w * 0.5f, h * 0.46f); lineTo(w * 0.64f, h * 0.78f); close() }
        drawPath(tri, Color.White.copy(alpha = 0.9f), style = Stroke(w * 0.035f))
        drawLine(Color.White, Offset(w * 0.2f, h * 0.66f), Offset(w * 0.44f, h * 0.62f), w * 0.03f, StrokeCap.Round)
        SpectrumColors.forEachIndexed { i, c ->
            val dy = (i - 2.5f) * h * 0.022f * (0.8f + 0.4f * shimmer)
            drawLine(c, Offset(w * 0.56f, h * 0.62f), Offset(w * 0.8f, h * 0.64f + dy), w * 0.028f, StrokeCap.Round)
        }
    }
}

/** Logo + wordmark lock-up. */
@Composable
fun Wordmark(size: Int = 34) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        LogoMark((size * 1.6f).dp)
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        Text(
            "shuddh",
            style = androidx.compose.ui.text.TextStyle(
                brush = Brush.linearGradient(listOf(Color(0xFFEFF4FA), Color(0xFF8EF0C8), Color(0xFF7DD3FC))),
                fontSize = size.sp, fontWeight = FontWeight.Black, fontFamily = Display, letterSpacing = (-1.2).sp,
            ),
        )
    }
}

/**
 * Slide-to-confirm control: drag the thumb to the end to trigger [onConfirm]. Releasing early springs
 * it back. Used for actions that should be deliberate, like joining the Bluetooth mesh.
 */
@Composable
fun SlideToConfirm(label: String, modifier: Modifier = Modifier, confirmedLabel: String = "Done ✓", onConfirm: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var trackW by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var done by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val thumb = 56.dp
    val thumbPx = with(density) { thumb.toPx() }
    val maxX = (trackW - thumbPx).coerceAtLeast(1f)
    val t = rememberInfiniteTransition(label = "slide")
    val shimmer by t.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "sh")
    val frac = (offset.value / maxX).coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().height(thumb + 8.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(Color(0x22FFFFFF))
            .onSizeChangedPx { trackW = it }
            .padding(4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Filled trail behind the thumb
        Box(
            Modifier.fillMaxHeight().width(with(density) { (offset.value + thumbPx).toDp() })
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Palette.accent.copy(alpha = 0.35f), Palette.cyan.copy(alpha = 0.55f)))),
        )
        // Shimmering label
        Text(
            if (done) confirmedLabel else label,
            style = androidx.compose.ui.text.TextStyle(
                brush = Brush.linearGradient(
                    0f to Palette.muted, (shimmer - 0.15f).coerceIn(0f, 1f) to Palette.muted, shimmer.coerceIn(0f, 1f) to Color.White,
                    (shimmer + 0.15f).coerceIn(0f, 1f) to Palette.muted, 1f to Palette.muted,
                ),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.align(Alignment.Center).alpha(1f - frac * 0.9f),
        )
        // Thumb
        Box(
            Modifier.graphicsLayer { translationX = offset.value }.size(thumb).clip(androidx.compose.foundation.shape.CircleShape)
                .background(Brush.linearGradient(listOf(Palette.accent, Palette.cyan)))
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    enabled = !done,
                    state = androidx.compose.foundation.gestures.rememberDraggableState { d ->
                        scope.launch { offset.snapTo((offset.value + d).coerceIn(0f, maxX)) }
                    },
                    onDragStopped = {
                        if (offset.value / maxX > 0.85f) {
                            offset.animateTo(maxX, tween(150))
                            done = true
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onConfirm()
                        } else {
                            offset.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (done) "✓" else "›››", color = Color(0xFF032016), fontSize = 18.sp, fontWeight = FontWeight.Black)
        }
    }
}

private fun Modifier.onSizeChangedPx(cb: (Float) -> Unit) = this.onSizeChanged { cb(it.width.toFloat()) }
