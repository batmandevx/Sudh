package com.shuddh.lab.instruments

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Dart
import com.shuddh.lab.core.MilkType
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.Screen
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.enter
import kotlin.math.PI
import kotlin.math.sin

private enum class Liquid(val label: String, val emoji: String, val color: Long, val sub: String) {
    MILK("Milk", "🥛", 0xFFF8FAFC, "Water, detergent, starch"),
    HONEY("Honey", "🍯", 0xFFF59E0B, "Sugar syrup, dilution"),
    JUICE("Juice", "🧃", 0xFFF97316, "Water, colour"),
    OIL("Oil", "🫗", 0xFFEAB308, "Purity, frying reuse"),
}

private enum class Check(val label: String, val emoji: String, val sub: String, val needs: String) {
    SENSOR("Water & adulteration", "💧", "Colour spectrum + echo + magnetometer → % water and a verdict", "Nothing — a white cap and paper"),
    DETERGENT("Detergent test", "🫧", "Camera times how long the foam stands after shaking", "A clear bottle"),
    STARCH("Starch test", "🌾", "Camera reads the iodine colour change", "2–3 drops iodine tincture (chemist)"),
    OIL_REUSE("Frying-oil reuse", "🍳", "How many times this oil has been heated", "Nothing"),
}

private enum class Step { LIQUID, MILK, CHECK, INTRO, RUN }

/** Purity: choose the liquid → its type → what to test → how it works → the test. */
@Composable
fun PurityScreen(app: AppState) {
    var step by remember { mutableStateOf(Step.LIQUID) }
    // Taps landing during a step transition must not fall through to the next step's cards.
    val changedAt = remember { longArrayOf(0L) }
    androidx.compose.runtime.LaunchedEffect(step) { changedAt[0] = System.currentTimeMillis() }
    fun ready() = System.currentTimeMillis() - changedAt[0] > 350
    var liquid by remember { mutableStateOf(Liquid.MILK) }
    var milk by remember { mutableStateOf(MilkType.TONED) }
    var check by remember { mutableStateOf(Check.SENSOR) }
    fun back() { step = when (step) { Step.LIQUID -> { app.back(); Step.LIQUID }; Step.MILK -> Step.LIQUID; Step.CHECK -> if (liquid == Liquid.MILK) Step.MILK else Step.LIQUID; Step.INTRO -> Step.CHECK; Step.RUN -> Step.INTRO } }
    BackHandler(enabled = step != Step.LIQUID && step != Step.RUN) { back() }

    if (step == Step.RUN) {
        val subject = if (liquid == Liquid.MILK) "${milk.label} milk" else liquid.label
        when (check) {
            Check.SENSOR -> PurityTest(app, subject, if (liquid == Liquid.MILK) milk.fat else 3.0) { step = Step.INTRO }
            Check.DETERGENT -> ScreenFrame("Detergent test", subject, onBack = { step = Step.INTRO }) { DetergentPanel(app, milk) }
            Check.STARCH -> DartRunner(app, Dart.test("starch_milk"), null, onNext = {}) { step = Step.INTRO }
            Check.OIL_REUSE -> {}
        }
        return
    }
    val title = when (step) { Step.LIQUID -> "Purity"; Step.MILK -> "Which milk?"; Step.CHECK -> "What to test?"; else -> check.label }
    ScreenFrame(title, when (step) { Step.LIQUID -> "Step 1 of 4 · choose the liquid"; Step.MILK -> "Step 2 of 4 · milk type"; Step.CHECK -> "Step 3 of 4 · pick a test"; else -> "Step 4 of 4 · how it works" }, onBack = { back() }) {
        StepDots(step.ordinal.coerceAtMost(3))
        AnimatedContent(step, transitionSpec = { (fadeIn(tween(300)) + slideInHorizontally(tween(350)) { it / 4 }).togetherWith(fadeOut(tween(150))) }, label = "wiz") { s ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (s) {
                    Step.LIQUID -> {
                        Text("What are you testing?", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                        Liquid.entries.chunked(2).forEachIndexed { r, row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEachIndexed { i, l -> LiquidCard(l, Modifier.weight(1f).enter(r * 2 + i)) { if (!ready()) return@LiquidCard; liquid = l; check = Check.SENSOR; step = if (l == Liquid.MILK) Step.MILK else Step.CHECK } }
                            }
                        }
                    }
                    Step.MILK -> {
                        Text("Which milk is it?", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                        Text("Fat sets how white and thick milk looks — the test compares it with the right kind.", color = Palette.muted, fontSize = 12.sp)
                        MilkType.entries.forEachIndexed { i, m -> MilkTypeRow(m, Modifier.enter(i.coerceAtMost(6))) { if (ready()) { milk = m; step = Step.CHECK } } }
                    }
                    Step.CHECK -> {
                        Text("What do you want to check?", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                        val options = when (liquid) { Liquid.MILK -> listOf(Check.SENSOR, Check.DETERGENT, Check.STARCH); Liquid.OIL -> listOf(Check.SENSOR, Check.OIL_REUSE); else -> listOf(Check.SENSOR) }
                        options.forEachIndexed { i, c -> CheckCard(c, liquid, Modifier.enter(i)) { if (!ready()) return@CheckCard; if (c == Check.OIL_REUSE) app.go(Screen.OIL) else { check = c; step = Step.INTRO } } }
                    }
                    Step.INTRO -> Intro(check, liquid, milk) { step = Step.RUN }
                    Step.RUN -> {}
                }
            }
        }
    }
}

@Composable
private fun StepDots(i: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { k ->
            val w by animateFloatAsState(if (k == i) 28f else 10f, spring(dampingRatio = 0.6f), label = "d$k")
            Box(Modifier.size(w.dp, 10.dp).clip(RoundedCornerShape(5.dp)).background(if (k <= i) Palette.accent else Palette.veil(0x20)))
        }
    }
}

@Composable
private fun pressScale(src: MutableInteractionSource): Float {
    val p by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (p) 0.94f else 1f, spring(dampingRatio = 0.45f), label = "ps")
    return s
}

/** A glass that fills with the liquid, with a moving surface and rising bubbles. */
@Composable
private fun LiquidGlass(c: Color, modifier: Modifier, level: Float = 0.7f) {
    val inf = rememberInfiniteTransition(label = "glass")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t")
    val fill by animateFloatAsState(level, tween(1200, easing = FastOutSlowInEasing), label = "f")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val glass = Path().apply { moveTo(w * 0.18f, h * 0.08f); lineTo(w * 0.82f, h * 0.08f); lineTo(w * 0.72f, h * 0.95f); lineTo(w * 0.28f, h * 0.95f); close() }
        val top = h * 0.95f - (h * 0.87f) * fill
        val liquid = Path().apply {
            moveTo(w * 0.18f, top)
            for (x in 0..20) { val xx = w * (0.18f + 0.64f * x / 20); lineTo(xx, top + 4f * sin(2 * PI * (x / 20.0 * 2 + t)).toFloat()) }
            lineTo(w * 0.72f, h * 0.95f); lineTo(w * 0.28f, h * 0.95f); close()
        }
        clipPath(glass) {
            drawPath(liquid, Brush.verticalGradient(listOf(c, lerp(c, Color.Black, 0.12f)), top, h))
            repeat(5) { k -> val p = (t + k / 5f) % 1f; drawCircle(Color.White.copy(alpha = 0.6f * (1 - p)), 3f + k % 2 * 2f, Offset(w * (0.35f + 0.07f * k), h * 0.93f - (h * 0.93f - top) * p)) }
        }
        drawPath(glass, Color(0xFF94A3B8), style = Stroke(3f))
        drawLine(Color.White.copy(alpha = 0.6f), Offset(w * 0.27f, h * 0.15f), Offset(w * 0.33f, h * 0.85f), 4f, StrokeCap.Round)
    }
}

@Composable
private fun LiquidCard(l: Liquid, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val sc = pressScale(src)
    val c = Color(l.color)
    Column(
        modifier.graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(lerp(c, Palette.surface, 0.75f), Palette.surface))).border(1.dp, lerp(c, Color.Gray, 0.4f).copy(alpha = 0.5f), RoundedCornerShape(22.dp))
            .clickable(src, null, onClick = onClick).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LiquidGlass(if (l == Liquid.MILK) Color(0xFFF1F5F9) else c, Modifier.size(70.dp, 84.dp))
        Text("${l.emoji} ${l.label}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 17.sp)
        Text(l.sub, color = Palette.muted, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MilkTypeRow(m: MilkType, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val sc = pressScale(src)
    val fat by animateFloatAsState((m.fat / 7.0).toFloat(), tween(900, easing = FastOutSlowInEasing), label = "fat")
    Row(
        modifier.fillMaxWidth().graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(18.dp)).background(Palette.surface)
            .border(1.dp, Palette.line, RoundedCornerShape(18.dp)).clickable(src, null, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidGlass(lerp(Color(0xFFE0F2FE), Color(0xFFFFFBEB), fat), Modifier.size(36.dp, 44.dp), level = 0.5f + 0.4f * fat)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.label, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.veil(0x12))) {
                Box(Modifier.fillMaxWidth(fat).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF93C5FD), Color(0xFFFDE68A)))))
            }
        }
        Text("${"%.1f".format(m.fat)}% fat", color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun CheckCard(c: Check, l: Liquid, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val sc = pressScale(src)
    val tint = when (c) { Check.SENSOR -> Palette.cyan; Check.DETERGENT -> Color(0xFF14B8A6); Check.STARCH -> Color(0xFF6366F1); Check.OIL_REUSE -> Palette.amber }
    Row(
        modifier.fillMaxWidth().graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.14f), Palette.surface))).border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .clickable(src, null, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Text(c.emoji, fontSize = 26.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (c == Check.SENSOR && l != Liquid.MILK) "Purity & dilution" else c.label, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(c.sub, color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
            Text("Needs: ${c.needs}", color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("›", color = tint, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Step 4: how to use + the science, animated ─────────────────────────────────────────────────

@Composable
private fun Intro(c: Check, l: Liquid, m: MilkType, onStart: () -> Unit) {
    val subject = if (l == Liquid.MILK) "${m.label.lowercase()} milk" else l.label.lowercase()
    Glass(glow = Palette.cyan, padding = 14) {
        Text("How to do it", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0B1220))) {
            when (c) {
                Check.SENSOR, Check.OIL_REUSE -> SetupScene(Modifier.fillMaxSize())
                Check.DETERGENT -> ShakeScene(Modifier.fillMaxSize())
                Check.STARCH -> IodineScene(Modifier.fillMaxSize())
            }
        }
        steps(c, subject).forEachIndexed { i, s -> StepRow(i + 1, s, Modifier.enter(i)) }
    }
    Glass(glow = Palette.violet, padding = 14) {
        Text("The science", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
        when (c) {
            Check.SENSOR, Check.OIL_REUSE -> {
                Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0B1220))) { ScatterScene(Modifier.fillMaxSize()) }
                Fact("🌈", "Light", "Fat and protein globules scatter light. Pure milk is packed with them → bright white. Every bit of added water means fewer globules → darker and slightly bluish. The camera measures this under several colours of light.")
                Fact("🔊", "Echo", "The speaker sweeps 2–18 kHz tones at the sample. Thicker, creamier liquid reflects them differently from thin, watered liquid — the microphone hears the difference.")
                Fact("🧲", "Magnetometer", "Iron or steel particles bend the phone's magnetic field reading — a quick check for metal contamination.")
                Fact("🧠", "Verdict", "All readings are matched against the samples you trained (pure, water, 50/50…) to give a kind, a % water range and how sure it is.")
            }
            Check.DETERGENT -> {
                Fact("🫧", "Why foam stands", "Detergent molecules have a water-loving head and an oil-loving tail. They coat every bubble wall so it can't drain and pop. Real milk's foam collapses in seconds; detergent foam stands for a minute or more.")
                Fact("📷", "What the camera measures", "It tracks the foam's height and bubble texture every second and compares the collapse curve with pure milk.")
                Fact("⚠️", "Why it matters", "Detergent is used to make 'synthetic milk' from oil and water. It irritates the gut and harms the liver and kidneys over time.")
            }
            Check.STARCH -> {
                Fact("🌾", "Why starch is added", "Flour, rice water or arrowroot thickens watered milk so it doesn't look thin.")
                Fact("🧪", "Iodine + starch = blue", "Starch's amylose chains coil like a spring; iodine slips inside and the complex turns deep blue–black. Pure milk has no starch, so it stays yellow-brown.")
                Fact("📷", "What the camera measures", "It compares the sample's colour with a pure-milk control in CIELAB colour space — a drop in b* (towards blue) means starch.")
            }
        }
    }
    Btn("▶  Start ${c.label.lowercase()}", Modifier.fillMaxWidth(), onClick = onStart)
}

private fun steps(c: Check, subject: String) = when (c) {
    Check.SENSOR, Check.OIL_REUSE -> listOf(
        "Fill a white bottle cap to the brim with the $subject.",
        "Put it on a sheet of white paper — paper in the white box, cap in the blue box on screen.",
        "Hold the phone flat about 15 cm above, in steady room light. Wait for the aim ring to show ✓.",
        "Tap Run test and keep still for ~25 s: spectrum → echo → metal check → verdict.",
    )
    Check.DETERGENT -> listOf(
        "Pour 10 ml of the $subject and 10 ml of water into a clear bottle.",
        "Close it and shake hard 10 times.",
        "Stand it in front of the camera with the foam inside the box and tap Start.",
        "Keep it still for 60 s while the camera times the foam.",
    )
    Check.STARCH -> listOf(
        "Pour 1 spoon of the $subject into a white cap or spoon.",
        "Add 2 drops of iodine tincture (from any chemist) and swirl gently.",
        "Wait while the colour develops (about 30 s).",
        "Put it on white paper — cap in the coloured box, paper in the white box. The camera reads the colour.",
    )
}

@Composable
private fun StepRow(n: Int, text: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Palette.accent), contentAlignment = Alignment.Center) { Text("$n", color = Palette.onAccent, fontWeight = FontWeight.Black, fontSize = 12.sp) }
        Spacer(Modifier.width(10.dp))
        Text(text, color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun Fact(emoji: String, title: String, text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.veil(0x08)).padding(10.dp)) {
        Text(emoji, fontSize = 20.sp); Spacer(Modifier.width(10.dp))
        Column { Text(title, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(text, color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp) }
    }
}

/** Phone above a cap on paper; light and sound go down, the sample answers back. */
@Composable
private fun SetupScene(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "setup")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "t")
    val hover by inf.animateFloat(-4f, 4f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        // Paper and cap
        drawRoundRect(Color.White, Offset(w * 0.18f, h * 0.74f), Size(w * 0.64f, h * 0.16f), CornerRadius(6f))
        drawOval(Color(0xFFE2E8F0), Offset(w * 0.55f, h * 0.72f), Size(w * 0.16f, h * 0.09f))
        drawOval(Color(0xFFF8FAFC), Offset(w * 0.565f, h * 0.73f), Size(w * 0.13f, h * 0.06f))
        // Phone hovering
        val py = h * 0.12f + hover
        drawRoundRect(Color(0xFF1E293B), Offset(w * 0.3f, py), Size(w * 0.4f, h * 0.1f), CornerRadius(10f))
        drawCircle(Color(0xFF38BDF8), 5f, Offset(w * 0.62f, py + h * 0.05f))
        // Light cone in cycling colours
        val cols = listOf(Color(0xFFEF4444), Color(0xFF22C55E), Color(0xFF3B82F6), Color.White)
        val c = cols[(t * 4).toInt() % 4]
        val cone = Path().apply { moveTo(w * 0.6f, py + h * 0.1f); lineTo(w * 0.53f, h * 0.73f); lineTo(w * 0.73f, h * 0.73f); lineTo(w * 0.64f, py + h * 0.1f); close() }
        drawPath(cone, c.copy(alpha = 0.22f))
        // Sound arcs going down
        for (k in 0..2) { val p = (t + k / 3f) % 1f; val y = py + h * 0.12f + (h * 0.55f) * p; drawArc(Color(0xFF22D3EE).copy(alpha = 1 - p), 20f, 140f, false, Offset(w * 0.42f - 30f * p, y), Size(w * 0.16f + 60f * p, 16f), style = Stroke(3f)) }
        // 15 cm marker
        drawLine(Color.White.copy(alpha = 0.5f), Offset(w * 0.8f, py + h * 0.1f), Offset(w * 0.8f, h * 0.73f), 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    }
    Box(modifier.padding(10.dp)) {
        Text("≈15 cm", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterEnd))
        Text("white paper", color = Color(0xFF94A3B8), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart))
        Text("cap, full", color = Color(0xFF94A3B8), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 40.dp))
    }
}

/** Pure vs watered milk side by side: light rays scatter off globules — fewer globules, less light back. */
@Composable
private fun ScatterScene(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "scatter")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        fun panel(x0: Float, n: Int, label: Color) {
            drawRoundRect(label.copy(alpha = 0.9f), Offset(x0, h * 0.45f), Size(w * 0.4f, h * 0.45f), CornerRadius(12f))
            val rnd = java.util.Random(n.toLong())
            repeat(n) { val x = x0 + rnd.nextFloat() * w * 0.4f; val y = h * 0.47f + rnd.nextFloat() * h * 0.41f; drawCircle(Color(0xFFFDE68A), 3f, Offset(x + 2f * sin((t * 6.28f + it).toDouble()).toFloat(), y)) }
            // incoming ray and reflected rays (more for more globules)
            val p = t
            drawLine(Color.White, Offset(x0 + w * 0.2f, h * 0.05f), Offset(x0 + w * 0.2f, h * 0.05f + (h * 0.4f) * p.coerceAtMost(1f)), 3f)
            val back = (n / 40f).coerceIn(0.1f, 1f)
            for (k in -2..2) drawLine(Color.White.copy(alpha = back * (1 - p)), Offset(x0 + w * 0.2f, h * 0.45f), Offset(x0 + w * 0.2f + k * 22f * p, h * 0.45f - h * 0.3f * p), 2f)
        }
        panel(w * 0.05f, 60, Color(0xFFF1F5F9))
        panel(w * 0.55f, 18, Color(0xFFCBD5E1))
    }
    Box(modifier.padding(8.dp)) {
        Text("pure milk — bright", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart))
        Text("watered — dimmer, bluish", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** Bottle shakes, then the foam collapses for pure milk but stands with detergent. */
@Composable
private fun ShakeScene(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "shake")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val shaking = t < 0.3f
        val ang = if (shaking) 18f * sin(t * 60f) else 0f
        val foamT = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
        fun bottle(cx: Float, detergent: Boolean) {
            rotate(ang, Offset(cx, h * 0.6f)) {
                drawRoundRect(Color(0xFF94A3B8), Offset(cx - w * 0.1f, h * 0.18f), Size(w * 0.2f, h * 0.72f), CornerRadius(14f), style = Stroke(3f))
                drawRect(Color(0xFFF1F5F9), Offset(cx - w * 0.095f, h * 0.55f), Size(w * 0.19f, h * 0.34f))
                val foam = if (shaking) 0.22f else if (detergent) 0.22f * (1 - 0.1f * foamT) else 0.22f * (1 - foamT).coerceAtLeast(0.02f)
                val top = h * 0.55f - h * foam
                drawRect(Color.White.copy(alpha = 0.9f), Offset(cx - w * 0.095f, top), Size(w * 0.19f, h * foam))
                val rnd = java.util.Random(if (detergent) 5 else 9)
                repeat((40 * foam / 0.22f).toInt()) { drawCircle(Color(0xFFCBD5E1), 3f + rnd.nextFloat() * 3f, Offset(cx - w * 0.09f + rnd.nextFloat() * w * 0.18f, top + rnd.nextFloat() * h * foam), style = Stroke(1.5f)) }
            }
        }
        bottle(w * 0.28f, false); bottle(w * 0.72f, true)
    }
    Box(modifier.padding(8.dp)) {
        Text("pure milk: foam falls", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart))
        Text("detergent: foam stands", color = Color(0xFFFBBF24), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

/** An iodine drop falls into two cups: the starch one turns blue–black, pure milk stays yellow-brown. */
@Composable
private fun IodineScene(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "iodine")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val drop = (t / 0.35f).coerceIn(0f, 1f); val mix = ((t - 0.35f) / 0.45f).coerceIn(0f, 1f)
        fun cup(cx: Float, starch: Boolean) {
            val target = if (starch) Color(0xFF1E3A8A) else Color(0xFFD6B26B)
            drawRoundRect(lerp(Color(0xFFF8FAFC), target, mix), Offset(cx - w * 0.13f, h * 0.55f), Size(w * 0.26f, h * 0.35f), CornerRadius(10f))
            drawRoundRect(Color(0xFF94A3B8), Offset(cx - w * 0.13f, h * 0.45f), Size(w * 0.26f, h * 0.45f), CornerRadius(10f), style = Stroke(3f))
            if (drop < 1f) drawCircle(Color(0xFF92400E), 7f, Offset(cx, h * 0.08f + (h * 0.47f) * drop))
            else if (mix < 1f) drawCircle(target.copy(alpha = 1 - mix), 30f * mix, Offset(cx, h * 0.6f), style = Stroke(3f))
        }
        cup(w * 0.28f, false); cup(w * 0.72f, true)
    }
    Box(modifier.padding(8.dp)) {
        Text("pure milk → yellow-brown", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart))
        Text("starch → blue-black", color = Color(0xFF93C5FD), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd))
    }
}
