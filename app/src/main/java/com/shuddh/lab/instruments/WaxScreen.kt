package com.shuddh.lab.instruments

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.WaxCheck
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Fold
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.enter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SIDES = 3

/** Fruit Shine Check: is this apple (or any fruit) coated with an artificial polish? */
@Composable
fun WaxScreen(app: AppState) {
    val prefs = remember { app.ctx.getSharedPreferences("shuddh_wax", Context.MODE_PRIVATE) }
    val cam = remember { CameraHandle() }
    val scope = rememberCoroutineScope()
    val box = remember { android.graphics.RectF(0.30f, 0.22f, 0.70f, 0.78f) }
    val frames = remember { mutableListOf<WaxCheck.Shine>() }
    val sides = remember { mutableStateListOf<Double>() }
    var lastFlash by remember { mutableStateOf<WaxCheck.Shine?>(null) }
    var haze by remember { mutableStateOf<Double?>(null) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var hotWater by remember { mutableStateOf(false) }
    var reference by remember { mutableStateOf(prefs.getFloat("ref", -1f).takeIf { it > 0 }?.toDouble()) }
    val result = if (sides.size >= SIDES) WaxCheck.assess(sides.toList(), haze, reference) else null

    suspend fun grab(ms: Long): WaxCheck.Shine {
        synchronized(frames) { frames.clear() }
        delay(ms)
        return WaxCheck.median(synchronized(frames) { frames.toList() })
    }

    fun scanSide() = scope.launch {
        busy = true
        stage = "Room light…"; cam.torch(false); delay(700)
        val amb = grab(900)
        stage = "Flash on — reading the shine…"; cam.torch(true); delay(900)
        val fl = grab(1100)
        cam.torch(false)
        lastFlash = fl
        sides += WaxCheck.gloss(amb, fl)
        Haptics.click(app.ctx)
        stage = ""
        busy = false
        if (sides.size < SIDES) app.voice.say("Side ${sides.size} done. Turn the fruit.", app.lang)
        else WaxCheck.assess(sides.toList(), haze, reference).let { app.voice.say(it.words, app.lang) }
    }

    fun scanAfterDip() = scope.launch {
        busy = true
        stage = "Flash on — looking for a cloudy film…"; cam.torch(true); delay(900)
        val after = grab(1200)
        cam.torch(false)
        lastFlash?.let { haze = WaxCheck.haze(it, after) }
        hotWater = false; stage = ""; busy = false
        Haptics.click(app.ctx)
        WaxCheck.assess(sides.toList(), haze, reference).let { app.voice.say(it.words, app.lang) }
    }

    ScreenFrame("Fruit Shine Check", "Camera + flash → artificial wax / polish", onBack = { cam.torch(false); app.back() }) {
        ShineHero(Modifier.enter(0))

        Glass(glow = Color(0xFFE11D48), padding = 16, modifier = Modifier.enter(1)) {
            Text(if (result == null) "SCAN ${minOf(sides.size + 1, SIDES)} OF $SIDES" else if (hotWater) "HOT-WATER CONFIRM" else "SCAN DONE",
                color = Color(0xFFE11D48), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text(
                when {
                    hotWater -> "Dip the fruit in hot tap water (not boiling) for 30–60 s. Take it out — don't wipe — and scan it in the box."
                    result == null -> "Hold the fruit 15–20 cm away, filling the red box. Dim room light is best. We read it with and without flash."
                    else -> "Turn the fruit between scans so we see three different sides."
                },
                color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp,
            )
            SideDots(sides.size)
            if (result == null || hotWater) {
                CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.62f, overlay = { roi(box, Color(0xFFE11D48)) }) { bmp ->
                    val x0 = (box.left * bmp.width).toInt(); val y0 = (box.top * bmp.height).toInt()
                    val w = ((box.right - box.left) * bmp.width).toInt(); val h = ((box.bottom - box.top) * bmp.height).toInt()
                    val px = IntArray(w * h); bmp.getPixels(px, 0, w, x0, y0, w, h)
                    val s = WaxCheck.shine(px)
                    synchronized(frames) { frames += s; while (frames.size > 40) frames.removeAt(0) }
                }
                if (stage.isNotEmpty()) Text(stage, color = Palette.cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (hotWater) Btn("📷  Scan after the dip", Modifier.fillMaxWidth(), enabled = !busy) { scanAfterDip() }
                else Btn(if (sides.isEmpty()) "📷  Scan side 1" else "📷  Scan side ${sides.size + 1}", Modifier.fillMaxWidth(), enabled = !busy) { scanSide() }
            }
        }

        AnimatedVisibility(result != null && !hotWater, enter = fadeIn(tween(400)) + scaleIn(spring(dampingRatio = 0.6f), initialScale = 0.9f)) {
            result?.let { r ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShineResult(r)
                    BtnRow {
                        if (r.haze == null) Btn("♨️ Hot-water confirm", primary = false) { hotWater = true }
                        Btn("Scan another", primary = false) { sides.clear(); haze = null; lastFlash = null }
                    }
                    Btn(if (reference == null) "Save as my unwaxed reference" else "Replace my unwaxed reference", Modifier.fillMaxWidth(), primary = false) {
                        reference = r.gloss.coerceAtLeast(0.1)
                        prefs.edit().putFloat("ref", r.gloss.coerceAtLeast(0.1).toFloat()).apply()
                    }
                    Note("Use the reference button only on fruit you know is unwaxed (home-grown, straight from a farm). Later scans are then compared with it — this makes the check much more accurate on your phone.")
                }
            }
        }

        Fold("Why polished fruit is a problem", Color(0xFFE11D48)) { WaxCheck.health.forEach { Note("• $it", Palette.text) } }
        Fold("How to remove wax", Palette.accent) { WaxCheck.clean.forEach { Note("• $it", Palette.text) } }
        Note("This is an educated estimate from shine — some apple varieties have natural wax. The hot-water step makes it far more reliable.")
    }
}

@Composable
private fun SideDots(done: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(SIDES) { i ->
            val on = i < done
            val s by animateFloatAsState(if (on) 1f else 0.6f, spring(dampingRatio = 0.4f), label = "d")
            Box(Modifier.size(22.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape)
                .background(if (on) Color(0xFFE11D48) else Palette.veil(0x18)), contentAlignment = Alignment.Center) {
                Text(if (on) "✓" else "${i + 1}", color = if (on) Color.White else Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("sides", color = Palette.muted, fontSize = 12.sp)
    }
}

/** Animated apple with a glint sweeping across it. */
@Composable
private fun ShineHero(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "shine")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t")
    val bob by inf.animateFloat(-4f, 4f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "b")
    Glass(modifier, glow = Color(0xFFE11D48), padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(92.dp).graphicsLayer { translationY = bob }) {
                val w = size.width; val h = size.height
                val apple = Path().apply {
                    moveTo(w * 0.5f, h * 0.28f)
                    cubicTo(w * 0.15f, h * 0.05f, -w * 0.05f, h * 0.6f, w * 0.3f, h * 0.92f)
                    cubicTo(w * 0.4f, h * 1.0f, w * 0.6f, h * 1.0f, w * 0.7f, h * 0.92f)
                    cubicTo(w * 1.05f, h * 0.6f, w * 0.85f, h * 0.05f, w * 0.5f, h * 0.28f); close()
                }
                drawPath(apple, Brush.radialGradient(listOf(Color(0xFFFB7185), Color(0xFFBE123C)), Offset(w * 0.4f, h * 0.45f), w * 0.6f))
                drawLine(Color(0xFF78350F), Offset(w * 0.5f, h * 0.28f), Offset(w * 0.56f, h * 0.08f), w * 0.04f, StrokeCap.Round)
                drawOval(Color(0xFF16A34A), Offset(w * 0.56f, h * 0.06f), Size(w * 0.22f, h * 0.1f))
                // The glint: a white band that sweeps over the peel.
                val x = -w * 0.3f + t * w * 1.6f
                drawLine(Color.White.copy(alpha = 0.55f * (1 - kotlin.math.abs(t - 0.5f) * 2).coerceAtLeast(0f)), Offset(x, h * 0.3f), Offset(x + w * 0.2f, h * 0.8f), w * 0.07f, StrokeCap.Round)
                drawCircle(Color.White.copy(alpha = 0.7f), w * 0.05f, Offset(w * 0.35f, h * 0.45f))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Is the shine real?", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 19.sp)
                Text("Wax and polish make a mirror-smooth film. Your flash sees it; your eyes can't measure it.", color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
                Text("🍎 apple · 🍐 pear · 🍋 citrus · 🥒 cucumber", color = Palette.muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ShineResult(r: WaxCheck.Result) {
    val c = when { r.probability >= 0.5 -> Palette.red; r.probability >= 0.3 -> Palette.amber; else -> Palette.accent }
    val p by animateFloatAsState(r.probability.toFloat(), tween(1100), label = "p")
    Glass(glow = c, padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(96.dp)) {
                    val st = 10.dp.toPx()
                    drawArc(Palette.veil(0x18), 135f, 270f, false, Offset(st / 2, st / 2), Size(size.width - st, size.height - st), style = Stroke(st, cap = StrokeCap.Round))
                    drawArc(c, 135f, 270f * p, false, Offset(st / 2, st / 2), Size(size.width - st, size.height - st), style = Stroke(st, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(p * 100).toInt()}%", color = c, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text("coated", color = Palette.muted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(r.words, color = c, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp)
                Text("Confidence ${r.confidence}%" + if (r.haze == null) " · add hot-water step for more" else "", color = Palette.muted, fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Gloss", fmt(r.gloss), Modifier.weight(1f))
            Metric("Even on all sides", "${(r.uniformity * 100).toInt()}%", Modifier.weight(1f))
            Metric("Hot-water haze", r.haze?.let { "${(it * 100).toInt()}%" } ?: "—", Modifier.weight(1f))
        }
        r.reference?.let { Text("Compared with your unwaxed reference (gloss ${fmt(it)}).", color = Palette.muted, fontSize = 11.sp) }
        if (r.waxed) Text("Wash in warm water and rub, or peel before eating.", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Metric(label: String, v: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Palette.veil(0x0C)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = Palette.muted, fontSize = 10.sp, maxLines = 1)
    }
}
