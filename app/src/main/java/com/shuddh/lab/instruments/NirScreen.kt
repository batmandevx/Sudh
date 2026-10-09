package com.shuddh.lab.instruments

import android.content.Context
import android.graphics.RectF
import android.hardware.ConsumerIrManager
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.Frames
import com.shuddh.lab.camera.roi
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
import com.shuddh.lab.ui.Chips
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.log10

/**
 * Lock-in detector: frames are tagged by whether the IR blaster is currently firing.
 * Signal = mean(on) − mean(off), which cancels ambient light and camera offset.
 */
private class LockIn {
    @Volatile var irOn = false
    @Volatile var changedAt = 0L
    private var onSum = 0.0; private var onN = 0
    private var offSum = 0.0; private var offN = 0

    @Synchronized fun reset() { onSum = 0.0; onN = 0; offSum = 0.0; offN = 0 }

    @Synchronized fun add(luma: Double) {
        if (System.nanoTime() - changedAt < 90_000_000L) return // skip frames straddling a transition
        if (irOn) { onSum += luma; onN++ } else { offSum += luma; offN++ }
    }

    @Synchronized fun read(): Triple<Double, Double, Int>? =
        if (onN < 3 || offN < 3) null else Triple(onSum / onN, offSum / offN, minOf(onN, offN))
}

private data class NirRead(val on: Double, val off: Double, val n: Int) {
    val signal get() = on - off
}

private val nirName = Txt("Water content (NIR)", "पानी की मात्रा (इन्फ्रारेड)", "ನೀರಿನ ಅಂಶ (ಇನ್ಫ್ರಾರೆಡ್)")

@Composable
fun NirScreen(app: AppState) {
    val ctx = app.ctx
    val ir = remember { ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager }
    val hasIr = remember { ir?.hasIrEmitter() == true }
    val cam = remember { CameraHandle() }
    val lock = remember { LockIn() }
    var lens by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var pulsing by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<NirRead?>(null) }
    var ref by remember { mutableStateOf<NirRead?>(null) }
    var sample by remember { mutableStateOf<NirRead?>(null) }
    var measuring by remember { mutableStateOf<String?>(null) }
    var pureA by remember { mutableStateOf(app.prefs.double("nir_pure")) }
    var dilA by remember { mutableStateOf(app.prefs.double("nir_dil")) }
    var dilPct by remember { mutableStateOf(app.prefs.double("nir_dil_pct")?.let { fmt(it) } ?: "20") }
    var status by remember { mutableStateOf(if (hasIr) "IR blaster found. Point it through the vial at the camera inside a dark box." else "No IR blaster on this phone — use the remote-control test below, or a phone with an IR blaster.") }
    val box = RectF(0.35f, 0.38f, 0.65f, 0.62f)

    // Drive the blaster: ~300 ms bursts of 38 kHz carrier, then ~300 ms off.
    LaunchedEffect(pulsing) {
        if (!pulsing || !hasIr) return@LaunchedEffect
        val burst = IntArray(60) { if (it % 2 == 0) 9000 else 1000 } // 30 × (9 ms on, 1 ms gap)
        withContext(Dispatchers.IO) {
            while (isActive && pulsing) {
                lock.irOn = true; lock.changedAt = System.nanoTime()
                runCatching { ir!!.transmit(38000, burst) }.onFailure { status = "IR transmit failed: ${it.message}" }
                lock.irOn = false; lock.changedAt = System.nanoTime()
                delay(300)
            }
        }
    }
    LaunchedEffect(pulsing) {
        while (pulsing) {
            delay(1500)
            if (measuring != null) continue
            lock.read()?.let { live = NirRead(it.first, it.second, it.third) }
            lock.reset()
        }
    }
    DisposableEffect(Unit) { onDispose { pulsing = false } }

    fun measure(which: String) {
        cam.lock(true); pulsing = true; measuring = which
        lock.reset()
        status = "Measuring $which (4 s lock-in)…"
    }
    LaunchedEffect(measuring) {
        val which = measuring ?: return@LaunchedEffect
        delay(600); lock.reset(); delay(4000)
        val r = lock.read()
        measuring = null
        if (r == null) { status = "Too few frames — try again"; return@LaunchedEffect }
        val read = NirRead(r.first, r.second, r.third)
        if (which == "reference") ref = read else sample = read
        status = "$which: IR signal ${fmt(read.signal)} (on ${fmt(read.on)} − off ${fmt(read.off)}, ${read.n} frame pairs)"
    }

    fun absorbance(): Double? {
        val r = ref ?: return null; val s = sample ?: return null
        if (r.signal <= 0.5 || s.signal <= 0.05) return null
        return log10(r.signal / s.signal)
    }

    fun verdict(): Outcome {
        val r = ref!!; val s = sample!!
        val a = absorbance()
        val ev = mutableListOf(
            Evidence("OBSERVATION", "IR lock-in signal: reference ${fmt(r.signal)}, sample ${fmt(s.signal)}"),
            Evidence("QUALITY", "Ambient rejected by on/off subtraction (${r.n}+${s.n} frame pairs)", r.signal > 2),
        )
        if (a == null || r.signal < 2) {
            ev += Evidence("HYPOTHESIS", "IR not reaching the sensor clearly (rear cameras often have IR-cut filters)", false)
            return Outcome("Shuddh NIR", "nir_water", nirName, null, "", Level.INCONCLUSIVE,
                "IR signal too weak — try the other camera or a darker box", listOf(Words.retest), ev)
        }
        ev += Evidence("PATTERN", "940 nm absorbance A = ${fmt(a)} (water absorbs at this band)")
        val p = pureA; val d = dilA; val pct = dilPct.toDoubleOrNull()
        if (p == null || d == null || pct == null || d - p < 0.005) {
            return Outcome("Shuddh NIR", "nir_water", nirName, a, "AU", Level.INCONCLUSIVE,
                "Absorbance measured; save pure and diluted references to grade it", listOf(Words.calibrate), ev)
        }
        ev += Evidence("CALIBRATION", "Pure vs ${fmt(pct)}% references separated by ΔA = ${fmt(d - p)} (≥ 0.02 needed for a reliable scale)", d - p >= 0.02)
        ev += Evidence("QUALITY", "Sample signal ${fmt(s.signal)} (≥ 5 = clean IR path)", s.signal >= 5)
        ev += Evidence("QUALITY", "Sample inside the calibrated range (A between pure − 0.01 and diluted + 50 %)", a in (p - 0.01)..(d + (d - p) * 0.5))
        val added = ((a - p) / (d - p) * pct).coerceAtLeast(0.0)
        val (lvl, adv) = when {
            added < pct * 0.25 -> Level.SAFE to listOf(Words.ok)
            added < pct * 0.6 -> Level.CAUTION to listOf(Words.useCare)
            else -> Level.UNSAFE to listOf(Words.adulterated, Words.report)
        }
        ev += Evidence("HYPOTHESIS", "≈${fmt(added)}% added water (linear between 0% and ${fmt(pct)}% references)", lvl == Level.SAFE)
        return Outcome("Shuddh NIR", "nir_water", nirName, added, "%", lvl,
            "≈${fmt(added)}% added water by 940 nm absorption", adv, ev,
            "Screening-grade: water's 940 nm band is weak; keep vial and path identical between references and sample.")
    }

    ScreenFrame("Shuddh NIR", "IR blaster → 940 nm water probe", onBack = { app.back() }) {
        StepTracker(listOf("Camera sees IR" to ((live?.signal ?: 0.0) > 2 || (ref?.signal ?: 0.0) > 2), "Reference" to (ref != null), "Sample" to (sample != null), "Verdict" to false))
        CameraView(cam, Modifier.fillMaxWidth(), lensFacing = lens, overlay = { roi(box, Color(0xFFE5484D)) }) { bmp ->
            lock.add(Frames.meanRgb(bmp, box).luma.toDouble())
        }
        Note(status, Palette.text)
        Chips(listOf(CameraSelector.LENS_FACING_BACK, CameraSelector.LENS_FACING_FRONT), lens, { if (it == CameraSelector.LENS_FACING_BACK) "Rear camera" else "Front camera" }) { lens = it }

        Section("Can your camera see infrared?") {
            Note("Point any TV remote at the camera and press a button: a purple flicker means this camera sees IR. If not, switch camera.")
            if (hasIr) {
                BtnRow {
                    Btn(if (pulsing && measuring == null) "Stop IR pulses" else "Pulse IR blaster", enabled = measuring == null) { pulsing = !pulsing }
                }
                live?.let {
                    Note("Live lock-in: on ${fmt(it.on)} · off ${fmt(it.off)} · signal ${fmt(it.signal)}", if (it.signal > 2) Palette.accent else Palette.amber)
                }
            }
        }

        if (hasIr) Section("Measure") {
            Note("Reference = empty vial (or water-free reference). Sample = the vial under test. Same position each time.")
            BtnRow {
                Btn("Measure reference", enabled = measuring == null) { measure("reference") }
                Btn("Measure sample", enabled = measuring == null && ref != null) { measure("sample") }
            }
            absorbance()?.let { Note("940 nm absorbance A = ${fmt(it)}", Palette.accent) }
        }

        if (hasIr) Section("Dilution references") {
            Note("Pure: ${pureA?.let { fmt(it) } ?: "not set"} · Diluted: ${dilA?.let { fmt(it) } ?: "not set"}")
            OutlinedTextField(
                dilPct, { dilPct = it }, label = { Text("Water % in the diluted reference") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            BtnRow {
                Btn("Save as PURE", enabled = absorbance() != null, primary = false) { pureA = absorbance(); app.prefs.putDouble("nir_pure", pureA) }
                Btn("Save as DILUTED", enabled = absorbance() != null && dilPct.toDoubleOrNull() != null, primary = false) {
                    dilA = absorbance(); app.prefs.putDouble("nir_dil", dilA); app.prefs.putDouble("nir_dil_pct", dilPct.toDouble())
                }
            }
        }
        HowItWorks(listOf(
            "TV-remote IR blasters emit near-infrared light at about 940 nm — invisible to us, but many camera sensors can see it.",
            "Water absorbs near-infrared light around 940–970 nm, so more water in honey or oil means less IR gets through.",
            "Lock-in detection: the blaster flashes on and off; subtracting off-frames from on-frames removes room light completely.",
        ))
        if (hasIr) Btn("Get verdict", Modifier.fillMaxWidth(), enabled = ref != null && sample != null) {
            pulsing = false; app.show(verdict())
        }
    }
}
