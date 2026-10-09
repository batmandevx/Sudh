package com.shuddh.lab.instruments

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.Collector
import com.shuddh.lab.camera.Frames
import com.shuddh.lab.camera.Lab
import com.shuddh.lab.camera.Rgb
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Prefs
import com.shuddh.lab.core.StripTest
import com.shuddh.lab.core.StripTests
import com.shuddh.lab.core.Words
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.OnWave
import com.shuddh.lab.ui.SteadyBar
import com.shuddh.lab.ui.rememberMotion
import com.shuddh.lab.ui.StepTracker
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Chips
import com.shuddh.lab.ui.Mono
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import org.json.JSONArray
import org.json.JSONObject

private data class Swatch(val value: Double, val lab: Lab)

private fun loadChart(p: Prefs, id: String): List<Swatch> = p.json("strip_$id")?.getJSONArray("pts")?.let { a ->
    List(a.length()) { i -> a.getJSONArray(i).let { Swatch(it.getDouble(0), Lab(it.getDouble(1), it.getDouble(2), it.getDouble(3))) } }
} ?: emptyList()

private fun saveChart(p: Prefs, id: String, s: List<Swatch>) = p.putJson(
    "strip_$id",
    if (s.isEmpty()) null else JSONObject().put("pts", JSONArray(s.map { JSONArray(listOf(it.value, it.lab.l, it.lab.a, it.lab.b)) })),
)

/** Projects a colour onto the chart's polyline (ordered by value) and interpolates the value. */
private fun readChart(chart: List<Swatch>, c: Lab): Pair<Double, Double>? {
    val s = chart.sortedBy { it.value }
    if (s.isEmpty()) return null
    if (s.size == 1) return s[0].value to s[0].lab.dist(c)
    var best: Pair<Double, Double>? = null
    for (i in 0 until s.size - 1) {
        val a = s[i].lab; val b = s[i + 1].lab
        val ab = doubleArrayOf(b.l - a.l, b.a - a.a, b.b - a.b)
        val ac = doubleArrayOf(c.l - a.l, c.a - a.a, c.b - a.b)
        val len2 = ab.sumOf { it * it }
        val t = if (len2 == 0.0) 0.0 else ((ab[0] * ac[0] + ab[1] * ac[1] + ab[2] * ac[2]) / len2).coerceIn(0.0, 1.0)
        val p = Lab(a.l + ab[0] * t, a.a + ab[1] * t, a.b + ab[2] * t)
        val d = p.dist(c)
        if (best == null || d < best.second) best = (s[i].value + (s[i + 1].value - s[i].value) * t) to d
    }
    return best
}

internal fun labToColor(l: Lab): Color {
    val fy = (l.l + 16) / 116; val fx = fy + l.a / 500; val fz = fy - l.b / 200
    fun inv(t: Double) = if (t * t * t > 0.008856) t * t * t else (t - 16.0 / 116) / 7.787
    val x = inv(fx) * 0.95047; val y = inv(fy); val z = inv(fz) * 1.08883
    fun g(v: Double) = (if (v <= 0.0031308) 12.92 * v else 1.055 * Math.pow(v, 1 / 2.4) - 0.055).coerceIn(0.0, 1.0).toFloat()
    return Color(g(3.2406 * x - 1.5372 * y - 0.4986 * z), g(-0.9689 * x + 1.8758 * y + 0.0415 * z), g(0.0557 * x - 0.2040 * y + 1.0570 * z))
}

@Composable
fun StripScreen(app: AppState) {
    val cam = remember { CameraHandle() }
    val motion = rememberMotion()
    var test by remember { mutableStateOf(StripTests.all.first()) }
    var chart by remember(test) { mutableStateOf(loadChart(app.prefs, test.id)) }
    var live by remember { mutableStateOf<Lab?>(null) }
    var swatchValue by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Plain white paper under W, strip pad under S. Torch on, then lock exposure.") }
    val white = RectF(0.18f, 0.44f, 0.38f, 0.56f)
    val pad = RectF(0.62f, 0.44f, 0.82f, 0.56f)
    val collector = remember { Collector<Pair<Rgb, Rgb>> { xs -> Rgb.average(xs.map { it.first }) to Rgb.average(xs.map { it.second }) } }

    fun capture(then: (Lab, Rgb) -> Unit) {
        if (collector.busy) return
        collector.start(16) { (w, s) -> then(Frames.relativeLab(s, w), w) }
    }

    fun verdict(t: StripTest, c: Lab, w: Rgb): Outcome {
        val r = readChart(chart, c)
        val ev = mutableListOf(
            Evidence("OBSERVATION", "Pad colour L*${fmt(c.l)} a*${fmt(c.a)} b*${fmt(c.b)} relative to the in-frame white patch"),
            Evidence("QUALITY", "White patch brightness ${fmt(w.luma.toDouble())}/255", w.luma in 80f..245f),
        )
        if (r == null || chart.size < 2) {
            ev += Evidence("HYPOTHESIS", "Capture at least 2 chart swatches for ${t.name.en}", false)
            return Outcome("Shuddh Strips", t.id, t.name, null, t.unit, Level.INCONCLUSIVE, "Chart not captured", listOf(Words.calibrate), ev, t.limitNote)
        }
        val (v, dE) = r
        ev += Evidence("PATTERN", "Nearest point on the ${chart.size}-swatch chart: ΔE ${fmt(dE)}", dE < 12)
        if (dE > 25) {
            ev += Evidence("HYPOTHESIS", "Colour does not lie on this chart — wrong test, stale strip, or bad light", false)
            return Outcome("Shuddh Strips", t.id, t.name, v, t.unit, Level.INCONCLUSIVE, "Colour off-chart (ΔE ${fmt(dE)})", listOf(Words.retest), ev, t.limitNote)
        }
        val (lvl, adv) = t.judge(v)
        ev += Evidence("HYPOTHESIS", "${t.name.en} ≈ ${fmt(v)} ${t.unit}", lvl == Level.SAFE)
        return Outcome("Shuddh Strips", t.id, t.name, v, t.unit, lvl, "${t.name.en}: ${fmt(v)} ${t.unit} (colour-matched, ΔE ${fmt(dE)})", adv, ev, t.limitNote)
    }

    ScreenFrame("Shuddh Strips", "Universal reader for colour test strips", onBack = { app.back() }) {
        StepTracker(listOf("Torch + lock" to (cam.torchOn && cam.locked), "Chart (2+)" to (chart.size >= 2), "Read strip" to false))
        CameraView(cam, Modifier.fillMaxWidth(), overlay = { roi(white, Color.White); roi(pad, Color(0xFF3DDC97)) }) { bmp ->
            val w = Frames.meanRgb(bmp, white); val s = Frames.meanRgb(bmp, pad)
            live = Frames.relativeLab(s, w)
            if (motion.steady) collector.offer(w to s)
        }
        SteadyBar(motion, "read the strip")
        OnWave(motion) { if (chart.size >= 2) { val t = test; capture { lab, w -> app.show(verdict(t, lab, w)) } } }
        Note("W = white reference (left) · S = strip pad (right). $status", Palette.text)
        BtnRow {
            Btn(if (cam.torchOn) "Torch: ON" else "Torch: off", primary = cam.torchOn) { cam.torch(!cam.torchOn) }
            Btn(if (cam.locked) "Exposure: LOCKED" else "Exposure: auto", primary = cam.locked) { cam.lock(!cam.locked) }
        }
        live?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(labToColor(c), RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(10.dp))
                Mono("L* ${fmt(c.l)}  a* ${fmt(c.a)}  b* ${fmt(c.b)}")
            }
        }
        Section("Test") {
            Chips(StripTests.all, test, { it.name.en }) { test = it }
            Note(test.limitNote)
        }
        Section("Shade chart (${chart.size} swatches)") {
            Note("Place S over each printed chart block (W on the chart's white margin), enter its value, capture.")
            chart.sortedBy { it.value }.forEach { sw ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).background(labToColor(sw.lab), RoundedCornerShape(4.dp)))
                    Spacer(Modifier.width(8.dp))
                    Mono("${fmt(sw.value)} ${test.unit}")
                }
            }
            OutlinedTextField(
                swatchValue, { swatchValue = it }, label = { Text("Swatch value ${test.unit}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            BtnRow {
                Btn("Capture swatch", enabled = swatchValue.toDoubleOrNull() != null, primary = false) {
                    val v = swatchValue.toDouble()
                    capture { lab, _ ->
                        chart = chart.filter { it.value != v } + Swatch(v, lab)
                        saveChart(app.prefs, test.id, chart); swatchValue = ""
                        status = "Swatch ${fmt(v)} saved."
                    }
                }
                Btn("Clear chart", primary = false) { chart = emptyList(); saveChart(app.prefs, test.id, chart) }
            }
        }
        HowItWorks(listOf(
            "Your eyes and camera auto-white-balance shift colours with the light. A white patch in the same photo cancels that.",
            "Colours are converted to CIELAB, a space where distances match how different colours look (ΔE).",
            "The pad colour is placed on the path through your kit’s own chart swatches, and the value is interpolated between neighbours.",
        ))
        Btn("Read strip", Modifier.fillMaxWidth(), enabled = chart.size >= 2) {
            val t = test
            capture { lab, w -> app.show(verdict(t, lab, w)) }
        }
    }
}
