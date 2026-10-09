package com.shuddh.lab.instruments

import android.content.Context
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.Frames
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.Dsp
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.Words
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.StepTracker
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.LineChart
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import com.shuddh.lab.ui.Series
import kotlin.math.abs

/** Integrates the gyroscope about the camera axis — the phone itself is the rotating analyser. */
private class Gyro : SensorEventListener {
    @Volatile var angleDeg = 0.0
    private var lastNs = 0L

    fun reset() { angleDeg = 0.0 }

    override fun onSensorChanged(e: SensorEvent) {
        if (lastNs != 0L) angleDeg += Math.toDegrees(e.values[2].toDouble()) * (e.timestamp - lastNs) / 1e9
        lastNs = e.timestamp
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

private class Sweep {
    @Volatile var on = false
    private val pts = mutableListOf<Pair<Double, Double>>()
    @Synchronized fun clear() = pts.clear()
    @Synchronized fun add(a: Double, i: Double) { if (on) pts.add(a to i) }
    @Synchronized fun snapshot() = pts.toList()
}

private data class SweepResult(val pts: List<Pair<Double, Double>>, val fit: Dsp.MalusFit?, val span: Double)

val honeyName = Txt("Honey purity", "शहद की शुद्धता", "ಜೇನುತುಪ್ಪದ ಶುದ್ಧತೆ")

@Composable
fun PolarScreen(app: AppState) {
    val ctx = app.ctx
    val cam = remember { CameraHandle() }
    val gyro = remember { Gyro() }
    val sweep = remember { Sweep() }
    var liveLuma by remember { mutableFloatStateOf(0f) }
    var liveAngle by remember { mutableFloatStateOf(0f) }
    var mode by remember { mutableStateOf<String?>(null) }
    var blank by remember { mutableStateOf<SweepResult?>(null) }
    var sample by remember { mutableStateOf<SweepResult?>(null) }
    var pure by remember { mutableStateOf(app.prefs.double("polar_pure")) }
    var syrup by remember { mutableStateOf(app.prefs.double("polar_syrup")) }
    var status by remember { mutableStateOf("Tape polariser 2 over the lens. Start each sweep where the spot is brightest.") }
    val box = RectF(0.42f, 0.45f, 0.58f, 0.57f)

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (g != null) sm.registerListener(gyro, g, SensorManager.SENSOR_DELAY_GAME)
        else status = "This phone has no gyroscope — polarimetry needs one."
        onDispose { sm.unregisterListener(gyro) }
    }

    fun start(which: String) {
        cam.lock(true)
        gyro.reset(); sweep.clear(); sweep.on = true; mode = which
        status = "Sweeping $which: rotate the phone slowly about the lens axis through at least 180°, then Stop."
    }

    fun stop() {
        sweep.on = false
        val pts = sweep.snapshot()
        val span = if (pts.isEmpty()) 0.0 else pts.maxOf { it.first } - pts.minOf { it.first }
        val r = SweepResult(pts, Dsp.malusFit(pts.map { it.first }, pts.map { it.second }), span)
        if (mode == "blank") blank = r else sample = r
        status = r.fit?.let { "Fit: max transmission at ${fmt(it.maxAngleDeg)}°, contrast ${fmt(it.contrast * 100)}%, span ${fmt(span)}°" }
            ?: "Not enough points — sweep again"
        mode = null
    }

    fun rotation(): Double? {
        val b = blank?.fit ?: return null
        val s = sample?.fit ?: return null
        return Dsp.wrap90(s.maxAngleDeg - b.maxAngleDeg)
    }

    fun verdict(): Outcome {
        val b = blank!!; val s = sample!!
        val alpha = rotation()!!
        val ev = mutableListOf(
            Evidence("OBSERVATION", "Optical rotation α = ${fmt(alpha)}° (sample vs blank sweep)"),
            Evidence("QUALITY", "Sweep spans ${fmt(b.span)}° / ${fmt(s.span)}°; Malus contrast ${fmt(b.fit!!.contrast * 100)}% / ${fmt(s.fit!!.contrast * 100)}%", b.span >= 150 && s.span >= 150 && s.fit.contrast > 0.15),
        )
        val p = pure; val sy = syrup
        if (b.span < 150 || s.span < 150 || s.fit.contrast < 0.15 || b.fit.contrast < 0.15) {
            return Outcome("Shuddh Polar", "honey_polar", honeyName, alpha, "°", Level.INCONCLUSIVE,
                "Sweep too short or polarisers not effective — sweep ≥180° with a bright spot", listOf(Words.retest), ev)
        }
        if (p == null || sy == null || abs(sy - p) < 1.0) {
            ev += Evidence("HYPOTHESIS", "Need pure-honey and syrup reference rotations to convert α into adulteration %", false)
            return Outcome("Shuddh Polar", "honey_polar", honeyName, alpha, "°", Level.INCONCLUSIVE,
                "Rotation measured; save pure-honey and syrup references to grade it", listOf(Words.calibrate), ev)
        }
        val frac = ((alpha - p) / (sy - p) * 100).coerceIn(0.0, 100.0)
        ev += Evidence("PATTERN", "Pure reference ${fmt(p)}°, syrup reference ${fmt(sy)}° → sample sits ${fmt(frac)}% of the way to syrup")
        if ((alpha > 0) != (p > 0) && abs(alpha) > 2 && abs(p) > 2) {
            ev += Evidence("PATTERN", "Rotation has the opposite sign to pure honey — strong syrup signature", false)
        }
        val (lvl, adv) = when {
            frac < 15 -> Level.SAFE to listOf(Words.ok)
            frac < 35 -> Level.CAUTION to listOf(Words.useCare)
            else -> Level.UNSAFE to listOf(Words.adulterated, Words.report)
        }
        ev += Evidence("HYPOTHESIS", "Estimated syrup fraction ≈ ${fmt(frac)}% (linear between references)", lvl == Level.SAFE)
        return Outcome("Shuddh Polar", "honey_polar", honeyName, frac, "%", lvl,
            "≈${fmt(frac)}% of the way from pure honey to sugar syrup (α = ${fmt(alpha)}°)", adv, ev,
            "Polarimetry of sugars is standard lab practice; accuracy depends on your references and a fixed path length.")
    }

    ScreenFrame("Shuddh Polar", "Optical rotation → sugar-syrup in honey", onBack = { app.back() }) {
        StepTracker(listOf("Blank sweep" to (blank != null), "Sample sweep" to (sample != null), "References" to (pure != null && syrup != null), "Verdict" to false))
        CameraView(cam, Modifier.fillMaxWidth(), overlay = { roi(box, Color(0xFFF2B33D)) }) { bmp ->
            val l = Frames.meanRgb(bmp, box).luma.toDouble()
            liveLuma = l.toFloat(); liveAngle = gyro.angleDeg.toFloat()
            sweep.add(gyro.angleDeg, l)
        }
        Note(status, Palette.text)
        Note("Live: brightness ${fmt(liveLuma.toDouble())} · phone angle ${fmt(liveAngle.toDouble())}°", Palette.muted)

        Section("Sweeps") {
            BtnRow {
                Btn(if (cam.torchOn) "Flash lamp: ON" else "Flash lamp: off", primary = cam.torchOn) { cam.torch(!cam.torchOn) }
                if (mode == null) {
                    Btn("Sweep blank (empty vial)") { start("blank") }
                    Btn("Sweep sample", enabled = blank != null) { start("sample") }
                } else {
                    Btn("Stop sweep") { stop() }
                }
                Btn("Unlock exposure", primary = false) { cam.lock(false) }
            }
            listOfNotNull(blank?.let { "Blank" to it }, sample?.let { "Sample" to it }).forEach { (name, r) ->
                if (r.pts.isNotEmpty()) {
                    val xs = r.pts.map { it.first.toFloat() }.toFloatArray()
                    val ys = r.pts.map { it.second.toFloat() }.toFloatArray()
                    val series = mutableListOf(Series(xs, ys, Palette.amber, dots = true))
                    r.fit?.let { f ->
                        val fx = FloatArray(100) { xs.min() + (xs.max() - xs.min()) * it / 99 }
                        series += Series(fx, FloatArray(100) { f.at(fx[it].toDouble()).toFloat() }, Color.White)
                    }
                    Note("$name sweep — brightness vs phone angle (°)")
                    LineChart(series, xLabel = "°")
                }
            }
            rotation()?.let { Note("Optical rotation α = ${fmt(it)}°", Palette.accent) }
        }

        Section("Honey references") {
            Note("Pure: ${pure?.let { "${fmt(it)}°" } ?: "not set"} · Syrup: ${syrup?.let { "${fmt(it)}°" } ?: "not set"}")
            Note("Sweep a honey you trust and a sugar/jaggery syrup of the same depth, then save each.")
            BtnRow {
                Btn("Save as PURE honey", enabled = rotation() != null, primary = false) {
                    pure = rotation(); app.prefs.putDouble("polar_pure", pure)
                }
                Btn("Save as SYRUP", enabled = rotation() != null, primary = false) {
                    syrup = rotation(); app.prefs.putDouble("polar_syrup", syrup)
                }
            }
        }
        HowItWorks(listOf(
            "Polarised light vibrates in one plane. Sugars twist that plane — fructose to the left, sucrose to the right.",
            "Pure honey is fructose-rich (left-turning). Cane-sugar syrup is sucrose (right-turning), so mixing it in shifts the rotation.",
            "The phone is the second polariser: as you turn it, brightness follows Malus’s law, I = A + B·cos²(θ−φ). The fit finds φ to a fraction of a degree.",
            "Rotation = φ(sample) − φ(blank). Comparing with your pure-honey and syrup references gives the syrup fraction.",
        ))
        Btn("Get verdict", Modifier.fillMaxWidth(), enabled = rotation() != null) { app.show(verdict()) }
    }
}
