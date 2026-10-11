package com.shuddh.lab.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Live sensor values as Compose state; unregisters automatically. Returns null if the sensor is absent. */
@Composable
fun rememberSensor(type: Int, rate: Int = SensorManager.SENSOR_DELAY_GAME): FloatArray? {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sm.getDefaultSensor(type) } ?: return null
    var v by remember { mutableStateOf(FloatArray(sensor.let { 5 })) }
    DisposableEffect(type) {
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { v = e.values.copyOf() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, rate)
        onDispose { sm.unregisterListener(l) }
    }
    return v
}

/** What the 3D farm shows. */
data class FarmScene(
    val progress: Float, val wet: Float, val rainy: Boolean, val hot: Boolean, val flooded: Boolean,
    val leaf: Color = Color(0xFF16A34A), val ripe: Color = Color(0xFFEAB308), val soil: Color = Color(0xFF8B5A2B),
    val fishPond: Boolean = false,
    /** Live wind (km/h) and cloud cover (0–1) from the weather service. */
    val wind: Float = 6f, val cloud: Float = 0.2f,
    /** grain | bush | tall | tree — how plants are drawn. */
    val shape: String = "grain",
)

fun plantShape(crop: String?) = when (crop) {
    "maize", "sugarcane", "cotton" -> "tall"; "tomato", "potato", "chilli", "onion", "soybean", "groundnut", "tur", "turmeric", "strawberry" -> "bush"
    "banana", "avocado", "dragonfruit", "blueberry" -> "tree"; else -> "grain"
}

/**
 * Perspective 3D field: a tilted grid of soil tiles with rows of plants, a pond, sky, sun and rain.
 * Drag to orbit; the gyroscope adds parallax so tilting the phone looks around the farm.
 */
@Composable
fun Farm3D(scene: FarmScene, modifier: Modifier, autoSpin: Boolean = true) {
    val inf = rememberInfiniteTransition(label = "f3d")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(60000, easing = LinearEasing)), label = "spin")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "t")
    var dragYaw by remember { mutableFloatStateOf(35f) }
    var dragPitch by remember { mutableFloatStateOf(28f) }
    val gyro = rememberSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    // Parallax from device tilt (small, smoothed).
    val tiltX by animateFloatAsState(((gyro?.getOrNull(0) ?: 0f) * 40f).coerceIn(-12f, 12f), spring(stiffness = 60f), label = "tx")
    val tiltY by animateFloatAsState(((gyro?.getOrNull(1) ?: 0f) * 40f).coerceIn(-12f, 12f), spring(stiffness = 60f), label = "ty")
    val prog by animateFloatAsState(scene.progress.coerceIn(0f, 1f), spring(stiffness = 120f), label = "pg")
    val wet by animateFloatAsState(scene.wet.coerceIn(0f, 1.5f), tween(400), label = "wt")

    Canvas(
        modifier.clip(RoundedCornerShape(22.dp)).pointerInput(Unit) {
            detectDragGestures { ch, d -> ch.consume(); dragYaw += d.x * 0.35f; dragPitch = (dragPitch - d.y * 0.2f).coerceIn(12f, 70f) }
        },
    ) {
        val w = size.width; val h = size.height
        // Sky
        val sky = when { scene.rainy -> listOf(Color(0xFF475569), Color(0xFF94A3B8)); scene.hot -> listOf(Color(0xFFEA580C), Color(0xFFFDE68A)); else -> listOf(Color(0xFF0EA5E9), Color(0xFFE0F2FE)) }
        drawRect(Brush.verticalGradient(sky))
        val yaw = Math.toRadians((dragYaw + (if (autoSpin) spin * 0.25f else 0f) + tiltY).toDouble())
        val pitch = Math.toRadians((dragPitch + tiltX).toDouble())
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val f = w * 0.95f; val dist = 3.2
        fun proj(x: Double, y: Double, z: Double): Offset? {
            // Rotate about Y (yaw), then X (pitch), then push away from camera.
            val x1 = x * cy - z * sy; val z1 = x * sy + z * cy
            val y2 = y * cp - z1 * sp; val z2 = y * sp + z1 * cp + dist
            if (z2 < 0.2) return null
            return Offset((w / 2 + f * x1 / z2).toFloat(), (h * 0.58f - f * y2 / z2).toFloat())
        }
        // Clouds drifting with the wind.
        val nClouds = (1 + scene.cloud * 5).toInt()
        val drift = (t + spin / 360f * (1 + scene.wind / 10f)) % 1f
        repeat(nClouds) { k ->
            val cx = ((k * 0.37f + drift * (0.6f + k * 0.15f)) % 1.2f - 0.1f) * w; val cyc = h * (0.08f + 0.06f * (k % 3))
            val a = 0.55f + 0.35f * scene.cloud
            drawCircle(Color.White.copy(alpha = a), h * 0.05f, Offset(cx, cyc)); drawCircle(Color.White.copy(alpha = a), h * 0.065f, Offset(cx + h * 0.06f, cyc - h * 0.015f)); drawCircle(Color.White.copy(alpha = a), h * 0.045f, Offset(cx + h * 0.12f, cyc))
        }
        // Sun
        if (!scene.rainy && scene.cloud < 0.85f) {
            val sc = Offset(w * 0.84f, h * 0.15f)
            drawCircle(Color(0xFFFDE047).copy(alpha = 0.3f), h * (0.11f + 0.015f * sin(t * 6.28f)), sc); drawCircle(Color(0xFFFACC15), h * 0.065f, sc)
        }
        // Ground tiles back-to-front.
        val n = 8
        data class Tile(val i: Int, val j: Int, val depth: Double)
        val tiles = (0 until n).flatMap { i -> (0 until n).map { j ->
            val x = -1 + (i + 0.5) * 2 / n; val z = -1 + (j + 0.5) * 2 / n
            Tile(i, j, x * sy + z * cy)
        } }.sortedByDescending { it.depth }
        val soilWet = lerp(scene.soil, Color(0xFF3F2A1E), (wet / 1.5f).coerceIn(0f, 1f) * 0.7f)
        val pondI = if (scene.fishPond) setOf(0, 1) else emptySet()
        for (tl in tiles) {
            val x0 = -1 + tl.i * 2.0 / n; val z0 = -1 + tl.j * 2.0 / n; val s = 2.0 / n
            val pts = listOf(proj(x0, 0.0, z0), proj(x0 + s, 0.0, z0), proj(x0 + s, 0.0, z0 + s), proj(x0, 0.0, z0 + s))
            if (pts.any { it == null }) continue
            val path = Path().apply { moveTo(pts[0]!!.x, pts[0]!!.y); pts.drop(1).forEach { lineTo(it!!.x, it.y) }; close() }
            val pond = tl.i in pondI && tl.j in pondI
            val shade = if ((tl.i + tl.j) % 2 == 0) 1f else 0.92f
            val c = if (pond || scene.flooded) Color(0xFF38BDF8).copy(alpha = if (pond) 0.95f else 0.75f) else Color(soilWet.red * shade, soilWet.green * shade, soilWet.blue * shade)
            drawPath(path, c)
            drawPath(path, Color.Black.copy(alpha = 0.08f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            if (pond) {
                // Fish: little arcs that swim around the pond.
                val cx = (pts[0]!!.x + pts[2]!!.x) / 2; val cyy = (pts[0]!!.y + pts[2]!!.y) / 2
                val a = (t * 6.28f + tl.i + tl.j * 2)
                drawCircle(Color(0xFFF97316), 4f, Offset(cx + 8f * cos(a), cyy + 4f * sin(a)))
            } else {
                // Two plants per tile.
                for (k in 0..1) {
                    val px = x0 + s * (0.3 + 0.4 * k); val pz = z0 + s * 0.5
                    val tall = when (scene.shape) { "tall" -> 1.6; "tree" -> 1.9; "bush" -> 0.7; else -> 1.0 }
                    val hgt = (0.05 + 0.45 * prog) * tall
                    val base = proj(px, 0.0, pz) ?: continue
                    val sway = (0.01 + scene.wind / 500.0) * sin((t * 6.28 * (1 + scene.wind / 20) + tl.i * 0.7 + k).toDouble())
                    val top = proj(px + sway, hgt, pz) ?: continue
                    val col = lerp(scene.leaf, scene.ripe, ((prog - 0.7f) / 0.3f).coerceIn(0f, 1f))
                    val thick = ((top.y - base.y) * -0.08f).coerceIn(1.5f, 5f)
                    drawLine(col, base, top, thick, StrokeCap.Round)
                    if (scene.shape == "bush" || scene.shape == "tree") {
                        val r0 = thick * (if (scene.shape == "tree") 3.2f else 2.4f) * (0.4f + prog)
                        drawCircle(col, r0, top); drawCircle(Color.White.copy(alpha = 0.15f), r0 * 0.45f, Offset(top.x - r0 * 0.3f, top.y - r0 * 0.3f))
                        if (prog > 0.6f) repeat(3) { q -> drawCircle(scene.ripe, thick * 0.7f, Offset(top.x + (q - 1) * r0 * 0.5f, top.y + r0 * 0.3f)) }
                    } else if (prog > 0.15f) {
                        val mid = Offset((base.x + top.x) / 2, (base.y + top.y) / 2)
                        drawLine(col, mid, Offset(mid.x + thick * 3, mid.y - thick * 2), thick * 0.8f, StrokeCap.Round)
                        drawLine(col, mid, Offset(mid.x - thick * 3, mid.y - thick * 1.5f), thick * 0.8f, StrokeCap.Round)
                    }
                    if (prog > 0.55f) drawCircle(lerp(scene.leaf, scene.ripe, ((prog - 0.55f) / 0.45f).coerceIn(0f, 1f)), thick * 1.1f, top)
                }
            }
        }
        // Border trees and a farmhouse (drawn on top so they read as standing up).
        for ((tx, tz) in listOf(-1.1 to -1.1, 1.1 to -1.1, -1.1 to 1.1, 0.0 to -1.12)) {
            val b0 = proj(tx, 0.0, tz) ?: continue; val tp = proj(tx + 0.015 * sin(t * 6.28) * (scene.wind / 20.0), 0.32, tz) ?: continue
            drawLine(Color(0xFF7C4A1E), b0, tp, 5f, StrokeCap.Round)
            val rr = ((b0.y - tp.y) * 0.32f).coerceIn(6f, 26f)
            drawCircle(Color(0xFF15803D), rr, tp); drawCircle(Color(0xFF22C55E).copy(alpha = 0.6f), rr * 0.6f, Offset(tp.x - rr * 0.25f, tp.y - rr * 0.25f))
        }
        run {
            val hx = 1.25; val hz = 0.2; val s0 = 0.18
            val c = listOf(proj(hx - s0, 0.0, hz - s0), proj(hx + s0, 0.0, hz - s0), proj(hx + s0, 0.0, hz + s0), proj(hx - s0, 0.0, hz + s0))
            val up = listOf(proj(hx - s0, 0.25, hz - s0), proj(hx + s0, 0.25, hz - s0), proj(hx + s0, 0.25, hz + s0), proj(hx - s0, 0.25, hz + s0))
            val roof = proj(hx, 0.42, hz)
            if ((c + up).all { it != null } && roof != null) {
                fun quad(a: Offset, b: Offset, cc: Offset, d: Offset, col: Color) = drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(cc.x, cc.y); lineTo(d.x, d.y); close() }, col)
                for (k in 0..3) quad(c[k]!!, c[(k + 1) % 4]!!, up[(k + 1) % 4]!!, up[k]!!, if (k % 2 == 0) Color(0xFFF5E6C8) else Color(0xFFE7D3AE))
                for (k in 0..3) drawPath(Path().apply { moveTo(up[k]!!.x, up[k]!!.y); lineTo(up[(k + 1) % 4]!!.x, up[(k + 1) % 4]!!.y); lineTo(roof.x, roof.y); close() }, if (k % 2 == 0) Color(0xFFB91C1C) else Color(0xFF991B1B))
            }
        }
        // Rain in screen space.
        if (scene.rainy) {
            val rnd = java.util.Random(3)
            repeat(60) {
                val x = rnd.nextFloat() * w; val y = ((rnd.nextFloat() + t * 1.5f) % 1f) * h
                drawLine(Color.White.copy(alpha = 0.55f), Offset(x, y), Offset(x - 4f, y + 14f), 2f)
            }
        }
        if (scene.hot) drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x33F97316))))
    }
}
