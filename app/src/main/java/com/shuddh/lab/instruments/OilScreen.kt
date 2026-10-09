package com.shuddh.lab.instruments

import android.graphics.RectF
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.Collector
import com.shuddh.lab.camera.Frames
import com.shuddh.lab.camera.Lab
import com.shuddh.lab.camera.Rgb
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.OilIndex
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.SteadyBar
import com.shuddh.lab.ui.StepTracker
import com.shuddh.lab.ui.rememberMotion
import kotlin.math.sin

private val oilName = Txt("Frying oil quality", "तलने के तेल की गुणवत्ता", "ಕರಿಯುವ ಎಣ್ಣೆಯ ಗುಣಮಟ್ಟ")

/** Oil Check — how many times has this frying oil been reused? Camera colorimetry against a white card. */
@Composable
fun OilScreen(app: AppState) {
    val ctx = app.ctx
    val cam = remember { CameraHandle() }
    val motion = rememberMotion()
    var live by remember { mutableStateOf<Lab?>(null) }
    var fresh by remember { mutableStateOf(app.prefs.json("oil_fresh")?.let { OilIndex.Lab3(it.getDouble("l"), it.getDouble("a"), it.getDouble("b")) }) }
    var sample by remember { mutableStateOf<Pair<OilIndex.Lab3, Rgb>?>(null) }
    var status by remember { mutableStateOf("Pour a little oil into a clear glass or steel spoon on white paper. W on bare paper, O on the oil. Torch on.") }
    val white = RectF(0.16f, 0.44f, 0.36f, 0.58f)
    val oil = RectF(0.6f, 0.44f, 0.84f, 0.58f)
    val collector = remember { Collector<Pair<Rgb, Rgb>> { xs -> Rgb.average(xs.map { it.first }) to Rgb.average(xs.map { it.second }) } }

    fun capture(then: (OilIndex.Lab3, Rgb) -> Unit) {
        if (collector.busy) return
        cam.lock(true)
        collector.start(16) { (w, s) -> val l = Frames.relativeLab(s, w); then(OilIndex.Lab3(l.l, l.a, l.b), w); Haptics.click(ctx) }
    }

    val grade = sample?.let { OilIndex.grade(it.first, fresh) }

    fun verdict(): Outcome {
        val (s, w) = sample!!; val g = grade!!
        val bi = OilIndex.browning(s.l, s.a, s.b)
        val ev = listOf(
            Evidence("OBSERVATION", "Oil colour L* ${fmt(s.l)} a* ${fmt(s.a)} b* ${fmt(s.b)} (browning index ${fmt(bi)}) relative to the white card"),
            Evidence("QUALITY", "White card brightness ${fmt(w.luma.toDouble())}/255 — well lit, not clipped", w.luma in 90f..245f && w.saturated < 0.05f),
            Evidence("CALIBRATION", if (fresh != null) "Compared with your own fresh oil" else "No fresh-oil reference — compared with typical refined oil", fresh != null),
            Evidence("PATTERN", "Darkening ${fmt(g.darkening)} → about ${g.reuses} frying cycle${if (g.reuses == 1) "" else "s"} (≈6.5 units per deep-fry)"),
            Evidence("HYPOTHESIS", g.label, g.level == Level.SAFE),
        )
        return Outcome("Shuddh Oil", "oil_reuse", oilName, g.darkening, "", g.level, "${g.label}: darkening ${fmt(g.darkening)} (~${g.reuses} reuses)",
            listOf(Txt(g.advice)), ev,
            "Colour screening of oil degradation. FSSAI caps total polar compounds at 25 %; colour tracks it but some oils darken faster — a TPC meter is definitive.",
            levelLabel = Txt(g.label.uppercase()))
    }

    ScreenFrame("Oil Check", "Camera colour → how reused is frying oil?", onBack = { app.back() }) {
        StepTracker(listOf("Torch on" to cam.torchOn, "Fresh oil (optional)" to (fresh != null), "Check used oil" to (sample != null), "Verdict" to false))
        Glass(glow = grade?.let { Color(it.level.argb) } ?: Palette.amber) {
            OilPan(sample?.first ?: live?.let { OilIndex.Lab3(it.l, it.a, it.b) }, grade)
            grade?.let { g ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(g.label, color = Color(g.level.argb), fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 26.sp)
                        Text(g.advice, color = Palette.text, fontSize = 13.sp)
                    }
                    ReuseDrops(g.reuses)
                }
            } ?: Note(status, Palette.text)
        }
        CameraView(cam, Modifier.fillMaxWidth(), overlay = { roi(white, Color.White); roi(oil, Color(0xFFFBBF24)) }) { bmp ->
            val w = Frames.meanRgb(bmp, white); val s = Frames.meanRgb(bmp, oil)
            live = Frames.relativeLab(s, w)
            if (motion.steady) collector.offer(w to s)
        }
        SteadyBar(motion, null)
        BtnRow {
            Btn(if (cam.torchOn) "Torch: ON" else "Torch: off", primary = cam.torchOn) { cam.torch(!cam.torchOn) }
            Btn("Save as FRESH oil", primary = false) {
                capture { l, _ -> fresh = l; app.prefs.putJson("oil_fresh", org.json.JSONObject().put("l", l.l).put("a", l.a).put("b", l.b)); status = "Fresh oil saved. Now check the used oil." }
            }
        }
        Btn("Check this oil", Modifier.fillMaxWidth()) { capture { l, w -> sample = l to w; val g = OilIndex.grade(l, fresh); app.voice.speak("${g.label}. ${g.advice}", app.lang); Haptics.rumble(ctx, (g.darkening / 30).toFloat().coerceIn(0f, 1f)) } }
        if (sample != null) Btn("Get verdict", Modifier.fillMaxWidth(), primary = false) { app.show(verdict()) }
        HowItWorks(listOf(
            "Every deep-fry oxidises and polymerises oil; the breakdown products (total polar compounds) darken it and are linked to heart and liver harm.",
            "A white card in the same frame cancels lighting and camera colour drift; colour is measured in CIELAB, and a browning index is computed.",
            "Compared with your own fresh oil, the loss of lightness plus browning gives a darkening score; each deep-fry adds roughly 6–7 units.",
            "Discard heavily reused oil through a RUCO (Repurpose Used Cooking Oil) collector — it becomes biodiesel.",
        ))
    }
}

/** Pan of oil shimmering in its measured colour, with frying bubbles that grow with reuse. */
@Composable
private fun OilPan(lab: OilIndex.Lab3?, g: OilIndex.Grade?) {
    val target = lab?.let { labToColor(Lab(it.l, it.a, it.b)) } ?: Color(0xFFE9C46A)
    val col by animateColorAsState(target, tween(900), label = "oil")
    val inf = rememberInfiniteTransition(label = "pan")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "t")
    val foam by animateFloatAsState(((g?.darkening ?: 0.0) / 30).toFloat().coerceIn(0f, 1f), tween(900), label = "foam")
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val w = size.width; val h = size.height
        val cx = w / 2; val cy = h * 0.55f; val rx = w * 0.36f; val ry = h * 0.3f
        drawLine(Color(0xFF1F2937), Offset(cx + rx - 10f, cy), Offset(w - 8f, cy - 24f), 18f)
        drawOval(Color(0xFF334155), Offset(cx - rx - 10f, cy - ry - 8f), Size(2 * rx + 20f, 2 * ry + 18f))
        drawOval(Brush.radialGradient(listOf(col.copy(alpha = 0.95f), col), Offset(cx - rx * 0.3f, cy - ry * 0.3f), rx * 1.2f), Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry))
        // Shimmer
        drawOval(Color.White.copy(alpha = 0.12f + 0.08f * sin((t * 6.28f).toDouble()).toFloat()), Offset(cx - rx * 0.6f, cy - ry * 0.7f), Size(rx * 0.7f, ry * 0.35f))
        // Bubbles — more foam on degraded oil
        val n = (6 + foam * 30).toInt()
        for (k in 0 until n) {
            val a = (k * 2.4f + t * 6.28f * (0.3f + (k % 3) * 0.1f))
            val r = (0.2f + (k % 5) * 0.15f)
            val x = cx + kotlin.math.cos(a) * rx * r; val y = cy + sin(a) * ry * r
            drawCircle(Color.White.copy(alpha = 0.45f), 3f + (k % 4) * 1.5f * (0.5f + foam), Offset(x, y), style = Stroke(2f))
        }
    }
}

/** Drop icons: one per estimated reuse (up to 8). */
@Composable
private fun ReuseDrops(n: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row { repeat(8) { i -> Text(if (i < n) "💧" else "·", fontSize = if (i < n) 14.sp else 16.sp, color = Palette.muted) } }
        Text("~$n reuses", color = Palette.muted, fontSize = 11.sp)
    }
}
