package com.shuddh.lab.instruments

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.Words
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.LineChart
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Series
import com.shuddh.lab.ui.StepTracker
import kotlin.math.sqrt

private val magName = Txt("Utensil steel check", "बर्तन स्टील जाँच", "ಪಾತ್ರೆ ಉಕ್ಕು ಪರೀಕ್ಷೆ")
private val foodGrade = Txt(
    "Non-magnetic — consistent with food-grade 304/316 stainless, aluminium, copper or silver.",
    "चुंबकीय नहीं — फूड-ग्रेड 304/316 स्टील, एल्यूमिनियम, तांबा या चाँदी जैसा।",
    "ಕಾಂತೀಯವಲ್ಲ — ಆಹಾರ ದರ್ಜೆಯ 304/316 ಉಕ್ಕು, ಅಲ್ಯೂಮಿನಿಯಂ, ತಾಮ್ರ ಅಥವಾ ಬೆಳ್ಳಿಯಂತೆ.",
)
private val weakMag = Txt(
    "Slightly magnetic — cheaper 200-series steel or a magnetic base. Avoid for acidic food storage.",
    "थोड़ा चुंबकीय — सस्ता 200-सीरीज़ स्टील। खट्टा खाना रखने से बचें।",
    "ಸ್ವಲ್ಪ ಕಾಂತೀಯ — ಅಗ್ಗದ 200-ಸರಣಿ ಉಕ್ಕು. ಹುಳಿ ಆಹಾರ ಇಡಬೇಡಿ.",
)
private val strongMag = Txt(
    "Strongly magnetic — iron or ferritic steel, not 304/316. May rust; don't store acidic food or pickles in it.",
    "बहुत चुंबकीय — लोहा या सस्ता स्टील, 304/316 नहीं। अचार या खट्टा खाना न रखें।",
    "ಬಲವಾಗಿ ಕಾಂತೀಯ — ಕಬ್ಬಿಣ ಅಥವಾ ಅಗ್ಗದ ಉಕ್ಕು. ಉಪ್ಪಿನಕಾಯಿ ಇಡಬೇಡಿ.",
)

/**
 * Shuddh Magneto — the phone's 3-axis magnetometer (compass) as a metal inspector.
 * Austenitic food-grade stainless (304/316) barely disturbs a magnetic field; iron and
 * ferritic/plated steels distort it strongly when brought close to the sensor.
 */
@Composable
fun MagnetoScreen(app: AppState) {
    val ctx = app.ctx
    var field by remember { mutableFloatStateOf(0f) }
    var baseline by remember { mutableStateOf<Float?>(null) }
    var peak by remember { mutableFloatStateOf(0f) }
    var heading by remember { mutableFloatStateOf(0f) }
    // Vector baseline: steel bends the field's *direction* as well as its strength, so the
    // disturbance is |B − B₀| over all three axes, not the change in magnitude alone.
    val vec = remember { FloatArray(3) }
    val baseVec = remember { FloatArray(3) }
    var vecDelta by remember { mutableFloatStateOf(0f) }
    val trace = remember { mutableStateListOf<Float>() }
    val recent = remember { mutableListOf<Float>() }
    val has = remember { (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null }

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                field = sqrt(x * x + y * y + z * z)
                vec[0] = x; vec[1] = y; vec[2] = z
                val dx = x - baseVec[0]; val dy = y - baseVec[1]; val dz = z - baseVec[2]
                vecDelta = sqrt(dx * dx + dy * dy + dz * dz)
                heading = Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble())).toFloat()
                // Peak of a 5-sample moving median: one noisy sample can't trigger a verdict.
                baseline?.let { b ->
                    recent.add(vecDelta); if (recent.size > 5) recent.removeAt(0)
                    if (recent.size == 5) peak = maxOf(peak, recent.sorted()[2])
                }
                trace.add(field); if (trace.size > 200) trace.removeAt(0)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(l) }
    }

    val delta = if (baseline != null) vecDelta else 0f
    // Haptic "geiger counter": clicks speed up and strengthen as metal disturbs the field.
    val deltaNow by androidx.compose.runtime.rememberUpdatedState(delta)
    androidx.compose.runtime.LaunchedEffect(baseline) {
        if (baseline == null) return@LaunchedEffect
        while (true) {
            val d = deltaNow
            if (d > 3f) com.shuddh.lab.core.Haptics.tick(ctx, (0.25f + d / 60f).coerceAtMost(1f))
            kotlinx.coroutines.delay((700 / (1 + d / 6f)).toLong().coerceIn(45L, 700L))
        }
    }

    fun verdict(): Outcome {
        val ev = mutableListOf(
            Evidence("OBSERVATION", "Peak field disturbance ${fmt(peak.toDouble())} µT over a baseline of ${fmt(baseline!!.toDouble())} µT"),
            Evidence("QUALITY", "Sweep signal ${fmt(peak.toDouble())} µT vs sensor noise ≈ 1.5 µT (5-sample median)", peak < 3f || peak > 6f),
            Evidence("QUALITY", "Earth's field ≈ 25–65 µT; baseline ${if (baseline!! in 20f..80f) "normal" else "unusual — move away from metal and re-zero"}", baseline!! in 20f..80f),
        )
        val (lvl, label, adv) = when {
            peak < 8 -> Triple(Level.SAFE, Txt("NON-MAGNETIC", "गैर-चुंबकीय", "ಕಾಂತೀಯವಲ್ಲ"), listOf(foodGrade))
            peak < 40 -> Triple(Level.CAUTION, Txt("WEAKLY MAGNETIC", "थोड़ा चुंबकीय", "ಸ್ವಲ್ಪ ಕಾಂತೀಯ"), listOf(weakMag))
            else -> Triple(Level.UNSAFE, Txt("MAGNETIC", "चुंबकीय", "ಕಾಂತೀಯ"), listOf(strongMag))
        }
        ev += Evidence("PATTERN", "Thresholds: <8 µT non-magnetic · 8–40 weak · >40 strong (utensil touching the phone's top edge)")
        ev += Evidence("HYPOTHESIS", label.en.lowercase().replaceFirstChar { it.uppercase() }, lvl == Level.SAFE)
        return Outcome("Shuddh Magneto", "magneto", magName, peak.toDouble(), "µT", lvl, "Peak disturbance ${fmt(peak.toDouble())} µT", adv, ev,
            "A grade hint, not a safety verdict: cold-worked 304 can be slightly magnetic and ferritic 430 (magnetic) is common in cutlery. Magnetism screens steel grade; it can't detect coatings or lead. Thick-base pans may have a magnetic plate for induction.", levelLabel = label)
    }

    ScreenFrame("Shuddh Magneto", "Compass sensor → is this steel food-grade?", onBack = { app.back() }) {
        StepTracker(listOf("Zero away from metal" to (baseline != null), "Sweep utensil" to (peak > 0f), "Verdict" to false))
        if (!has) Note("This phone has no magnetometer.", Palette.red)
        Glass(glow = Palette.violet) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FieldDial(delta, heading) }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${fmt(field.toDouble())} µT", color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Note(baseline?.let { "Δ ${fmt(delta.toDouble())} µT now · peak Δ ${fmt(peak.toDouble())} µT" } ?: "Hold the phone away from metal, then tap Zero.")
                if (baseline != null) Note("📳 Feel it: the phone clicks faster as you approach magnetic steel — like a Geiger counter.", Palette.cyan)
            }
            BtnRow {
                Btn(if (baseline == null) "Zero here" else "Re-zero") { baseline = field; vec.copyInto(baseVec); peak = 0f; recent.clear() }
                Btn("Get verdict", enabled = baseline != null && peak > 0f) { app.show(verdict()) }
            }
        }
        if (trace.size > 3) {
            LineChart(
                listOf(Series(FloatArray(trace.size) { it.toFloat() }, trace.toFloatArray(), Palette.violet, fill = true)),
                Modifier.fillMaxWidth().height(150.dp), xLabel = "samples",
            )
        }
        Note("Slide the utensil slowly along the TOP edge of the phone (where the compass sits). Watch the dial jump for magnetic steel.")
        HowItWorks(listOf(
            "Your phone has a 3-axis magnetometer for its compass; it measures the magnetic field in micro-tesla (µT).",
            "Food-grade 304/316 stainless steel is austenitic — its crystal structure is non-magnetic, so the field barely changes.",
            "Iron and cheap ferritic or plated steels are ferromagnetic: they bend the field, and the reading jumps by tens of µT.",
            "Shuddh zeroes on the room's field, tracks the peak disturbance as you sweep, and grades it.",
        ))
    }
}

/** Animated dial: arc fills with the field disturbance, needle shows compass heading. */
@Composable
private fun FieldDial(delta: Float, heading: Float) {
    val d by animateFloatAsState((delta / 80f).coerceIn(0f, 1f), tween(250), label = "d")
    val h by animateFloatAsState(heading, tween(250), label = "h")
    Canvas(Modifier.size(200.dp)) {
        val stroke = 22f
        val tl = Offset(stroke, stroke); val sz = Size(size.width - 2 * stroke, size.height - 2 * stroke)
        drawArc(Color.White.copy(alpha = 0.07f), 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(
            Brush.sweepGradient(listOf(Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFF43F5E), Color(0xFF34D399))),
            135f, 270f * d, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round),
        )
        rotate(h) {
            drawLine(Color(0xFFF43F5E), center, Offset(center.x, center.y - size.height * 0.28f), 8f, StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.6f), center, Offset(center.x, center.y + size.height * 0.2f), 8f, StrokeCap.Round)
        }
        drawCircle(Color.White, 10f, center)
    }
}
