package com.shuddh.lab.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Consistent, hand-drawn line icons for Discover — same stroke, same grid, each with a small loop of motion. */
enum class FIcon { MILK, HEART, DROP, MOSQUITO, SHIELD, POT, PAN, GRAIN, JAR, BOOK, NETWORK, SIREN, FAMILY, CHAT, EYE, MEDAL }

@Composable
fun FeatureIcon(icon: FIcon, color: Color, size: Dp = 30.dp, animated: Boolean = !UiPrefs.reduceMotion) {
    val inf = rememberInfiniteTransition(label = "fi")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "t")
    val b by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "b")
    val p = if (animated) t else 0.3f
    val q = if (animated) b else 0.5f
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val s = w / 24f // 24-unit design grid
        val stroke = Stroke(2.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        fun path(block: Path.() -> Unit) = Path().apply(block)
        fun DrawScope.line(a: Offset, b: Offset, c: Color = color) = drawLine(c, a, b, 2.2f * s, StrokeCap.Round)
        when (icon) {
            FIcon.MILK -> {
                val bottle = path { moveTo(9f * s, 2.5f * s); lineTo(15f * s, 2.5f * s); lineTo(15f * s, 6f * s); lineTo(18f * s, 9.5f * s); lineTo(18f * s, 20f * s)
                    quadraticTo(18f * s, 21.5f * s, 16.5f * s, 21.5f * s); lineTo(7.5f * s, 21.5f * s); quadraticTo(6f * s, 21.5f * s, 6f * s, 20f * s); lineTo(6f * s, 9.5f * s); lineTo(9f * s, 6f * s); close() }
                drawContext.canvas.save(); drawContext.canvas.clipPath(bottle)
                val lvl = (21.5f - 11f * (0.75f + 0.08f * sin(p * 2 * PI.toFloat()))) * s
                drawRect(Color.White.copy(alpha = 0.85f), Offset(0f, lvl), Size(w, w))
                drawRect(color.copy(alpha = 0.6f), Offset(0f, lvl - 1.2f * s), Size(w, 1.2f * s))
                drawContext.canvas.restore()
                drawPath(bottle, color, style = stroke); line(o(8.5f, 6f), o(15.5f, 6f))
            }
            FIcon.HEART -> {
                val k = 1f + 0.08f * q
                val h = path {
                    moveTo(12f * s, 20f * s)
                    cubicTo(2f * s, 13f * s, 3f * s, 4f * s, 8.5f * s, 4.5f * s); cubicTo(10.5f * s, 4.7f * s, 12f * s, 6.5f * s, 12f * s, 7.5f * s)
                    cubicTo(12f * s, 6.5f * s, 13.5f * s, 4.7f * s, 15.5f * s, 4.5f * s); cubicTo(21f * s, 4f * s, 22f * s, 13f * s, 12f * s, 20f * s); close()
                }
                drawContext.transform.scale(k, k, o(12f, 12f)); drawPath(h, color.copy(alpha = 0.25f)); drawPath(h, color, style = stroke); drawContext.transform.scale(1 / k, 1 / k, o(12f, 12f))
                val ecg = path { moveTo(5f * s, 12f * s); lineTo(9f * s, 12f * s); lineTo(10.5f * s, 9f * s); lineTo(12.5f * s, 15f * s); lineTo(14f * s, 12f * s); lineTo(19f * s, 12f * s) }
                drawPath(ecg, Color.White.copy(alpha = 0.9f), style = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            FIcon.DROP -> {
                val d = path { moveTo(12f * s, 3f * s); cubicTo(17f * s, 9f * s, 19f * s, 12f * s, 19f * s, 15f * s); cubicTo(19f * s, 19f * s, 16f * s, 21.5f * s, 12f * s, 21.5f * s)
                    cubicTo(8f * s, 21.5f * s, 5f * s, 19f * s, 5f * s, 15f * s); cubicTo(5f * s, 12f * s, 7f * s, 9f * s, 12f * s, 3f * s); close() }
                drawContext.canvas.save(); drawContext.canvas.clipPath(d)
                val lvl = (21.5f - 10f * (0.55f + 0.15f * sin(p * 2 * PI.toFloat()))) * s
                drawRect(color.copy(alpha = 0.55f), Offset(0f, lvl), Size(w, w))
                drawContext.canvas.restore()
                drawPath(d, color, style = stroke)
            }
            FIcon.MOSQUITO -> {
                // Diagonal body, head with proboscis, six legs, two translucent flapping wings.
                rotate(-25f, o(12f, 12f)) {
                    val f = 2.5f * (q - 0.5f)
                    drawOval(color.copy(alpha = 0.28f), Offset(6.5f * s, (4f + f) * s), Size(5f * s, 8f * s))
                    drawOval(color.copy(alpha = 0.28f), Offset(12.5f * s, (4f - f) * s), Size(5f * s, 8f * s))
                    drawOval(color, Offset(6.5f * s, (4f + f) * s), Size(5f * s, 8f * s), style = Stroke(1.4f * s))
                    drawOval(color, Offset(12.5f * s, (4f - f) * s), Size(5f * s, 8f * s), style = Stroke(1.4f * s))
                    drawOval(color, Offset(4f * s, 11f * s), Size(13f * s, 3.2f * s))
                    drawCircle(color, 2f * s, o(18.5f, 12.6f))
                    drawLine(color, o(20.3f, 12.6f), o(23f, 12.6f), 1.2f * s, StrokeCap.Round)
                    for (x in listOf(8f, 11f, 14f)) {
                        drawLine(color, o(x, 14f), o(x - 2.5f, 19.5f), 1.2f * s, StrokeCap.Round)
                        drawLine(color, o(x, 14f), o(x + 1.5f, 19.5f), 1.2f * s, StrokeCap.Round)
                    }
                }
            }
            FIcon.SHIELD -> {
                val sh = path { moveTo(12f * s, 2.5f * s); lineTo(20f * s, 5.5f * s); cubicTo(20f * s, 13f * s, 17f * s, 18.5f * s, 12f * s, 21.5f * s); cubicTo(7f * s, 18.5f * s, 4f * s, 13f * s, 4f * s, 5.5f * s); close() }
                drawPath(sh, color.copy(alpha = 0.2f)); drawPath(sh, color, style = stroke)
                drawContext.canvas.save(); drawContext.canvas.clipPath(sh)
                val y = (3f + 18f * p) * s; drawRect(Color.White.copy(alpha = 0.35f), Offset(0f, y - 1.5f * s), Size(w, 3f * s))
                drawContext.canvas.restore()
                drawPath(path { moveTo(8.5f * s, 12f * s); lineTo(11f * s, 14.5f * s); lineTo(15.5f * s, 9.5f * s) }, Color.White, style = stroke)
            }
            FIcon.POT -> {
                drawPath(path { moveTo(4f * s, 11f * s); lineTo(20f * s, 11f * s); lineTo(18.5f * s, 20f * s); lineTo(5.5f * s, 20f * s); close() }, color.copy(alpha = 0.25f))
                drawPath(path { moveTo(4f * s, 11f * s); lineTo(20f * s, 11f * s); lineTo(18.5f * s, 20f * s); lineTo(5.5f * s, 20f * s); close() }, color, style = stroke)
                line(o(2f, 11f), o(22f, 11f))
                for (k in 0 until 3) {
                    val ph = (p + k / 3f) % 1f; val x = (8f + k * 4f) * s
                    drawPath(path { moveTo(x, 9f * s - ph * 2f * s); quadraticTo(x + 1.5f * s, (6.5f - ph * 2f) * s, x, (4f - ph * 2f) * s) }, color.copy(alpha = 1 - ph), style = Stroke(1.6f * s, cap = StrokeCap.Round))
                }
            }
            FIcon.PAN -> {
                drawOval(color.copy(alpha = 0.25f), o(3f, 10f), Size(14f * s, 7f * s)); drawOval(color, o(3f, 10f), Size(14f * s, 7f * s), style = stroke)
                line(o(16.5f, 13.5f), o(22f, 11f))
                for (k in 0 until 3) { val ph = (p + k * 0.33f) % 1f; drawCircle(Color.White.copy(alpha = 1 - ph), (0.6f + 1.2f * ph) * s, o(7f + k * 3f, 13.5f - ph * 2f)) }
            }
            FIcon.GRAIN -> {
                rotate(6f * sin(p * 2 * PI.toFloat()), o(12f, 21f)) {
                    line(o(12f, 21f), o(12f, 5f))
                    for (k in 0 until 4) {
                        val y = (7f + k * 3.3f) * s
                        drawOval(color, Offset(12.4f * s, y - 1.2f * s), Size(4f * s, 2.4f * s)); drawOval(color, Offset(7.6f * s, y - 1.2f * s), Size(4f * s, 2.4f * s))
                    }
                    drawOval(color, Offset(10.8f * s, 2f * s), Size(2.4f * s, 4f * s))
                }
            }
            FIcon.JAR -> {
                drawRoundRect(color.copy(alpha = 0.18f), o(5f, 6f), Size(14f * s, 15f * s), androidx.compose.ui.geometry.CornerRadius(3f * s))
                val lvl = (21f - 12f * (0.5f + 0.2f * sin(p * 2 * PI.toFloat()))) * s
                drawRect(color.copy(alpha = 0.5f), Offset(5f * s, lvl), Size(14f * s, 21f * s - lvl))
                drawRoundRect(color, o(5f, 6f), Size(14f * s, 15f * s), androidx.compose.ui.geometry.CornerRadius(3f * s), style = stroke)
                drawRoundRect(color, o(6.5f, 3f), Size(11f * s, 3f * s), androidx.compose.ui.geometry.CornerRadius(1f * s), style = stroke)
            }
            FIcon.BOOK -> {
                drawPath(path { moveTo(12f * s, 6f * s); quadraticTo(7f * s, 3.5f * s, 3f * s, 5f * s); lineTo(3f * s, 19f * s); quadraticTo(7f * s, 17.5f * s, 12f * s, 20f * s) }, color, style = stroke)
                drawPath(path { moveTo(12f * s, 6f * s); quadraticTo(17f * s, 3.5f * s, 21f * s, 5f * s); lineTo(21f * s, 19f * s); quadraticTo(17f * s, 17.5f * s, 12f * s, 20f * s) }, color, style = stroke)
                line(o(12f, 6f), o(12f, 20f))
                val fx = 12f + 8f * cos(p * PI.toFloat())
                drawPath(path { moveTo(12f * s, 6.5f * s); quadraticTo((12f + fx) / 2 * s, 4.5f * s, fx * s, 5.5f * s); lineTo(fx * s, 18.5f * s); quadraticTo((12f + fx) / 2 * s, 17.5f * s, 12f * s, 19.5f * s); close() }, color.copy(alpha = 0.35f))
            }
            FIcon.NETWORK -> {
                val pts = listOf(o(12f, 12f), o(5f, 6f), o(19f, 6f), o(5f, 18f), o(19f, 18f))
                for (i in 1 until pts.size) line(pts[0], pts[i], color.copy(alpha = 0.6f))
                pts.drop(1).forEachIndexed { i, pt ->
                    val ph = (p + i * 0.25f) % 1f
                    drawCircle(color.copy(alpha = (1 - ph) * 0.4f), (2f + 3f * ph) * s, pt)
                    drawCircle(color, 2f * s, pt)
                    drawCircle(Color.White, 1.1f * s, Offset(pts[0].x + (pt.x - pts[0].x) * ph, pts[0].y + (pt.y - pts[0].y) * ph))
                }
                drawCircle(color, 3f * s, pts[0]); drawCircle(Color.White, 1.4f * s, pts[0])
            }
            FIcon.SIREN -> {
                rotate(360f * p, o(12f, 12f)) {
                    drawArc(color.copy(alpha = 0.3f), -30f, 60f, true, o(0f, 0f), Size(w, w)); drawArc(color.copy(alpha = 0.3f), 150f, 60f, true, o(0f, 0f), Size(w, w))
                }
                drawPath(path { moveTo(7f * s, 18f * s); lineTo(7f * s, 12f * s); cubicTo(7f * s, 8f * s, 17f * s, 8f * s, 17f * s, 12f * s); lineTo(17f * s, 18f * s); close() }, color)
                drawRoundRect(color, o(4.5f, 18f), Size(15f * s, 3f * s), androidx.compose.ui.geometry.CornerRadius(1f * s))
                drawCircle(Color.White.copy(alpha = 0.5f + 0.5f * q), 1.6f * s, o(12f, 13f))
            }
            FIcon.FAMILY -> {
                drawCircle(color, 2.6f * s, o(7f, 7f), style = stroke); drawCircle(color, 2.6f * s, o(17f, 7f), style = stroke)
                drawCircle(color, 2f * s, o(12f, 12f + -1.2f * q), style = stroke)
                drawArc(color, 180f, 180f, false, o(2.5f, 11f), Size(9f * s, 9f * s), style = stroke)
                drawArc(color, 180f, 180f, false, o(12.5f, 11f), Size(9f * s, 9f * s), style = stroke)
                drawArc(color, 180f, 180f, false, o(8.5f, 15f - 1.2f * q), Size(7f * s, 7f * s), style = stroke)
            }
            FIcon.CHAT -> {
                val bub = path { moveTo(4f * s, 5f * s); lineTo(20f * s, 5f * s); lineTo(20f * s, 16f * s); lineTo(10f * s, 16f * s); lineTo(6f * s, 20f * s); lineTo(6f * s, 16f * s); lineTo(4f * s, 16f * s); close() }
                drawPath(bub, color.copy(alpha = 0.22f)); drawPath(bub, color, style = stroke)
                for (k in 0 until 3) { val ph = ((p * 3) - k).coerceIn(0f, 1f); drawCircle(Color.White.copy(alpha = 0.4f + 0.6f * sin(ph * PI.toFloat())), 1.3f * s, o(8f + k * 4f, 10.5f)) }
            }
            FIcon.EYE -> {
                val open = if (p in 0.85f..0.95f) 0.15f else 1f
                drawPath(path { moveTo(2f * s, 12f * s); quadraticTo(12f * s, (12f - 9f * open) * s, 22f * s, 12f * s); quadraticTo(12f * s, (12f + 9f * open) * s, 2f * s, 12f * s); close() }, color, style = stroke)
                if (open > 0.5f) { drawCircle(color, 3.6f * s, o(12f + 2f * sin(p * 2 * PI.toFloat()), 12f)); drawCircle(Color.White, 1.3f * s, o(11f + 2f * sin(p * 2 * PI.toFloat()), 11f)) }
            }
            FIcon.MEDAL -> {
                line(o(8f, 2f), o(11f, 10f)); line(o(16f, 2f), o(13f, 10f))
                drawCircle(color.copy(alpha = 0.25f), 6f * s, o(12f, 15f)); drawCircle(color, 6f * s, o(12f, 15f), style = stroke)
                val sx = (6f + 12f * p) * s
                drawLine(Color.White.copy(alpha = 0.7f), Offset(sx - 2f * s, 12f * s), Offset(sx + 2f * s, 18f * s), 1.4f * s, StrokeCap.Round)
                drawPath(path { moveTo(12f * s, 11.5f * s); lineTo(13.1f * s, 14f * s); lineTo(15.6f * s, 14.2f * s); lineTo(13.7f * s, 15.8f * s); lineTo(14.3f * s, 18.3f * s); lineTo(12f * s, 17f * s)
                    lineTo(9.7f * s, 18.3f * s); lineTo(10.3f * s, 15.8f * s); lineTo(8.4f * s, 14.2f * s); lineTo(10.9f * s, 14f * s); close() }, color)
            }
        }
    }
}
