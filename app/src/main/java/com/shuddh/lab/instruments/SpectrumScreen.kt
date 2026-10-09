package com.shuddh.lab.instruments

import android.graphics.RectF
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.Collector
import com.shuddh.lab.camera.Frames
import com.shuddh.lab.camera.Profile
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.Analyte
import com.shuddh.lab.core.Analytes
import com.shuddh.lab.core.Dsp
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Kind
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Prefs
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
import com.shuddh.lab.ui.LabeledSlider
import com.shuddh.lab.ui.LineChart
import com.shuddh.lab.ui.Mono
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import com.shuddh.lab.ui.Series
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** One averaged spectrometer reading: the sample band plus the self-reference band's total light. */
class SpecFrame(val p: Profile, val refLight: Float)

/** pixel position u (0..1 across the ROI) → wavelength nm. */
data class WaveCal(val a: Double, val b: Double, val calibrated: Boolean, val note: String) {
    fun nm(i: Int, n: Int) = a + b * i / (n - 1).coerceAtLeast(1)

    companion object {
        val default = WaveCal(400.0, 300.0, false, "Uncalibrated: assuming 400–700 nm across the band")

        fun load(p: Prefs) = p.json("wavecal")?.let {
            WaveCal(it.getDouble("a"), it.getDouble("b"), true, it.optString("note"))
        } ?: default

        fun save(p: Prefs, c: WaveCal?) = p.putJson(
            "wavecal", c?.let { JSONObject().put("a", it.a).put("b", it.b).put("note", it.note) },
        )
    }
}

/** Beer–Lambert calibration for one reagent test: (concentration, absorbance) standards. */
data class AnalyteCal(val points: List<Pair<Double, Double>>, val lot: String, val created: Long) {
    val fit get() = Dsp.linearFit(points.map { it.first }, points.map { it.second })

    companion object {
        fun load(p: Prefs, id: String): AnalyteCal? = p.json("acal_$id")?.let { o ->
            val arr = o.getJSONArray("pts")
            AnalyteCal(
                List(arr.length()) { arr.getJSONArray(it).let { a -> a.getDouble(0) to a.getDouble(1) } },
                o.optString("lot"), o.optLong("created"),
            )
        }

        fun save(p: Prefs, id: String, c: AnalyteCal?) = p.putJson(
            "acal_$id",
            c?.let {
                JSONObject().put("pts", JSONArray(it.points.map { (x, y) -> JSONArray(listOf(x, y)) }))
                    .put("lot", it.lot).put("created", it.created)
            },
        )
    }
}

/** Finds CFL mercury lines by colour: the strongest blue-dominant peak is 435.8 nm, green-dominant 546.1 nm, red Eu line 611.6 nm. */
fun autoCalibrate(p: Profile): Result<WaveCal> {
    val t = Dsp.smooth(p.total, 3)
    val peaks = Dsp.peaks(t, max(12f, (t.maxOrNull() ?: 0f) * 0.08f))
    if (peaks.isEmpty()) return Result.failure(IllegalStateException("No sharp lines found. Point the slit at a CFL tube and align the band."))
    fun best(pred: (Int) -> Boolean) = peaks.filter(pred).maxByOrNull { t[it] }
    val blue = best { p.b[it] > p.g[it] && p.b[it] > p.r[it] }
    val green = best { p.g[it] > p.r[it] && p.g[it] > p.b[it] }
    val red = best { p.r[it] > p.g[it] * 1.3f && p.r[it] > p.b[it] }
    val n = p.size - 1.0
    val pts = listOfNotNull(
        blue?.let { Dsp.parabolic(t, it) / n to 435.8 },
        green?.let { Dsp.parabolic(t, it) / n to 546.1 },
        red?.let { Dsp.parabolic(t, it) / n to 611.6 },
    )
    if (pts.size < 2) return Result.failure(IllegalStateException("Found ${pts.size} coloured line(s); need the blue 436 nm and green 546 nm lines."))
    val fit = Dsp.linearFit(pts.map { it.first }, pts.map { it.second })!!
    if (abs(fit.slope) < 80 || abs(fit.slope) > 3000) {
        return Result.failure(IllegalStateException("Lines found but spacing is implausible (${fmt(fit.slope)} nm per band width)."))
    }
    val lines = pts.joinToString { "${fmt(it.second)} nm @ ${fmt(it.first * 100)}%" }
    return Result.success(WaveCal(fit.intercept, fit.slope, true, "CFL lines: $lines · R²=${fmt(fit.r2)}"))
}

/** 400–700 nm in 5 nm steps: the grid every fingerprint is stored on. */
private val fpGrid = (0..60).map { 400.0 + it * 5 }

/** Resamples an absorbance spectrum onto [fpGrid]; NaN where the calibrated band doesn't reach. */
fun fingerprintOf(a: FloatArray, wave: WaveCal): FloatArray {
    val n = a.size
    val nms = DoubleArray(n) { wave.nm(it, n) }
    return FloatArray(fpGrid.size) { k ->
        val target = fpGrid[k]
        val i = nms.indices.minByOrNull { abs(nms[it] - target) } ?: return@FloatArray Float.NaN
        if (abs(nms[i] - target) > 6) Float.NaN else a[i]
    }
}

/** Pearson correlation and RMS difference over points both spectra cover. */
fun compareFingerprints(x: FloatArray, y: FloatArray): Pair<Double, Double>? {
    val idx = x.indices.filter { !x[it].isNaN() && !y[it].isNaN() }
    if (idx.size < 10) return null
    val mx = idx.map { x[it].toDouble() }.average(); val my = idx.map { y[it].toDouble() }.average()
    var sxy = 0.0; var sxx = 0.0; var syy = 0.0; var se = 0.0
    for (i in idx) {
        val dx = x[i] - mx; val dy = y[i] - my
        sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        se += (x[i] - y[i]).toDouble().let { it * it }
    }
    val r = if (sxx == 0.0 || syy == 0.0) 0.0 else sxy / kotlin.math.sqrt(sxx * syy)
    return r to kotlin.math.sqrt(se / idx.size)
}

private fun loadFingerprints(p: Prefs): Map<String, FloatArray> = p.json("fingerprints")?.let { o ->
    o.keys().asSequence().associateWith { k ->
        val a = o.getJSONArray(k); FloatArray(a.length()) { a.optDouble(it, Double.NaN).toFloat() }
    }
} ?: emptyMap()

private fun saveFingerprints(p: Prefs, m: Map<String, FloatArray>) = p.putJson(
    "fingerprints",
    JSONObject().apply { m.forEach { (k, v) -> put(k, JSONArray(v.map { if (it.isNaN()) JSONObject.NULL else it.toDouble() })) } },
)

private val fpName = com.shuddh.lab.core.Txt("Product fingerprint", "उत्पाद की पहचान", "ಉತ್ಪನ್ನ ಗುರುತು")

fun fingerprintVerdict(name: String?, score: Pair<Double, Double>?, library: Int): Outcome {
    val ev = mutableListOf(Evidence("OBSERVATION", "Absorbance spectrum 400–700 nm compared with $library saved fingerprint${if (library == 1) "" else "s"}"))
    if (name == null || score == null) {
        return Outcome("Shuddh Spectrum", "fingerprint", fpName, null, "", Level.INCONCLUSIVE,
            "No overlapping fingerprint to compare", listOf(Words.calibrate), ev + Evidence("HYPOTHESIS", "Save a trusted product first", false))
    }
    val (r, rms) = score
    ev += Evidence("PATTERN", "Best match \"$name\": shape correlation ${fmt(r * 100)}%, RMS difference ${fmt(rms)} AU", r > 0.95)
    val (lvl, label, adv) = when {
        r >= 0.95 && rms < 0.06 -> Triple(Level.SAFE, com.shuddh.lab.core.Txt("MATCH", "मेल खाता है", "ಹೊಂದುತ್ತದೆ"), listOf(Words.ok))
        r >= 0.85 -> Triple(Level.CAUTION, com.shuddh.lab.core.Txt("PARTIAL", "आंशिक", "ಭಾಗಶಃ"), listOf(Words.retest))
        else -> Triple(Level.UNSAFE, com.shuddh.lab.core.Txt("MISMATCH", "मेल नहीं", "ಹೊಂದುವುದಿಲ್ಲ"), listOf(Words.adulterated, Words.report))
    }
    ev += Evidence("HYPOTHESIS", when (lvl) {
        Level.SAFE -> "Same product as \"$name\""
        Level.CAUTION -> "Similar to \"$name\" but not identical — different batch, or partly refilled"
        else -> "Does not match \"$name\" — possible refill or counterfeit"
    }, lvl == Level.SAFE)
    return Outcome("Shuddh Spectrum", "fingerprint", fpName, r * 100, "%", lvl,
        "${fmt(r * 100)}% match to \"$name\"", adv, ev,
        "Fingerprints compare spectral shape under the same lamp, vial and blank. Not a chemical identification.", levelLabel = label)
}

@Composable
fun SpectrumScreen(app: AppState) {
    val ctx = app.ctx
    val cam = remember { CameraHandle() }
    val motion = rememberMotion()
    var roiY by remember { mutableFloatStateOf(0.5f) }
    var roiH by remember { mutableFloatStateOf(0.06f) }
    var selfRef by remember { mutableStateOf(false) }
    var refY by remember { mutableFloatStateOf(0.3f) }
    var live by remember { mutableStateOf<Profile?>(null) }
    var wave by remember { mutableStateOf(WaveCal.load(app.prefs)) }
    var analyte by remember { mutableStateOf(Analytes.spectrum.first()) }
    var cal by remember(analyte) { mutableStateOf(AnalyteCal.load(app.prefs, analyte.id)) }
    var blank by remember { mutableStateOf<SpecFrame?>(null) }
    var sample by remember { mutableStateOf<SpecFrame?>(null) }
    var status by remember { mutableStateOf("Align the rainbow band inside the violet box.") }
    var stdConc by remember { mutableStateOf("") }
    var lot by remember { mutableStateOf("") }
    var fps by remember { mutableStateOf(loadFingerprints(app.prefs)) }
    var newFp by remember { mutableStateOf("") }
    val collector = remember {
        Collector<SpecFrame> { xs -> SpecFrame(Profile.average(xs.map { it.p }), xs.map { it.refLight }.average().toFloat()) }
    }

    fun bandRect() = RectF(0.04f, roiY - roiH / 2, 0.96f, roiY + roiH / 2)
    fun refRect() = RectF(0.04f, refY - 0.03f, 0.96f, refY + 0.03f)

    DisposableEffect(Unit) { onDispose { Haptics.cancel(ctx) } }

    fun capture(label: String, then: (SpecFrame) -> Unit) {
        if (collector.busy) return
        cam.lock(true)
        status = "Measuring $label… hold still"
        collector.start(20) { then(it) }
    }

    fun absorbance(b: SpecFrame, s: SpecFrame): FloatArray {
        val k = if (selfRef && s.refLight > 1f) b.refLight / s.refLight else 1f
        val sc = s.p.scaled(k)
        return FloatArray(b.p.size) { log10((b.p.total[it] + 1f) / (sc.total[it] + 1f)) }
    }

    fun bandAbs(a: FloatArray, n: Int, nm: Double): Double? {
        val idx = (0 until n).filter { abs(wave.nm(it, n) - nm) <= 12 }
        return if (idx.isEmpty()) null else idx.map { a[it].toDouble() }.average()
    }

    fun verdict(b: SpecFrame, s: SpecFrame): Outcome {
        val n = b.p.size
        val a = absorbance(b, s)
        val ev = mutableListOf<Evidence>()
        val bandA = bandAbs(a, n, analyte.bandNm)
        val lit = (0 until n).filter { b.p.total[it] > 30f }
        val peakIdx = lit.maxByOrNull { a[it] }
        ev += Evidence("OBSERVATION", bandA?.let { "Absorbance ${fmt(it)} AU at ${analyte.bandNm.toInt()} ± 12 nm (${analyte.method})" } ?: "Band ${analyte.bandNm.toInt()} nm is outside the calibrated range")
        peakIdx?.let { ev += Evidence("PATTERN", "Strongest absorption at ${wave.nm(it, n).toInt()} nm (expected ≈${analyte.bandNm.toInt()} nm)", abs(wave.nm(it, n) - analyte.bandNm) < 40) }
        ev += Evidence("QUALITY", wave.note, wave.calibrated)
        val bandLight = (0 until n).filter { abs(wave.nm(it, n) - analyte.bandNm) <= 12 }.map { b.p.total[it] }.average()
        val dark = bandLight.isNaN() || bandLight < 30
        ev += Evidence("QUALITY", "Reference light in band: ${if (bandLight.isNaN()) "none" else fmt(bandLight)} / 765", !dark)
        val sat = b.p.saturated > 0.02f
        if (sat) ev += Evidence("QUALITY", "Reference frame ${fmt(b.p.saturated * 100.0)}% clipped — lower exposure", false)
        ev += Evidence("QUALITY", "Frames averaged only while the phone was steady (accelerometer gate < 0.12 m/s²)", true)
        if (selfRef) ev += Evidence("QUALITY", "Self-reference band: lamp drift corrected ×${fmt((b.refLight / s.refLight.coerceAtLeast(1f)).toDouble())}", true)

        fun inconclusive(why: String, extra: List<com.shuddh.lab.core.Txt> = listOf(Words.retest)) = Outcome(
            "Shuddh Spectrum", analyte.id, analyte.name, bandA, "AU", Level.INCONCLUSIVE, why, extra,
            ev + Evidence("HYPOTHESIS", "No verdict: $why", false), analyte.limitNote,
        )
        if (bandA == null) return inconclusive("Wavelength band not covered — recalibrate or widen the band")
        if (dark) return inconclusive("Too little light at ${analyte.bandNm.toInt()} nm")
        if (sat) return inconclusive("Reference saturated — lower exposure and re-capture the blank")

        return when (analyte.kind) {
            Kind.PRESENCE -> {
                val (lvl, adv) = analyte.judge(bandA)
                ev += Evidence(
                    "HYPOTHESIS",
                    if (lvl == Level.SAFE) "No colour beyond the pure-milk reference" else "Extra ${analyte.method.substringBefore(' ')} colour beyond reference: ${analyte.name.en.lowercase()} probable",
                    lvl == Level.SAFE,
                )
                Outcome(
                    "Shuddh Spectrum", analyte.id, analyte.name, bandA, "AU", lvl,
                    "ΔA = ${fmt(bandA)} vs pure reference (threshold ${fmt(analyte.presenceThreshold)})", adv, ev, analyte.limitNote,
                )
            }
            Kind.QUANT -> {
                val c = cal
                val fit = c?.fit
                if (c == null || fit == null || fit.slope == 0.0) {
                    return inconclusive("Not calibrated: run at least 2 standards for ${analyte.name.en}", listOf(Words.calibrate))
                }
                val conc = fit.invert(bandA).coerceAtLeast(0.0)
                val ageDays = (System.currentTimeMillis() - c.created) / 86_400_000
                ev += Evidence("CALIBRATION", "Beer–Lambert A = ${fmt(fit.slope)}·c + ${fmt(fit.intercept)}, R² ${fmt(fit.r2)}, ${c.points.size} standards" + (if (c.lot.isNotBlank()) ", lot ${c.lot}" else ""), fit.r2 > 0.95)
                if (ageDays > 90) ev += Evidence("CALIBRATION", "Calibration is $ageDays days old — reagent may have drifted; re-run standards", false)
                val maxStd = c.points.maxOf { it.first }
                val extrapolated = conc > maxStd * 1.25
                if (extrapolated) ev += Evidence("CALIBRATION", "Above highest standard (${fmt(maxStd)}) — value extrapolated", false)
                val (lvl, adv) = analyte.judge(conc)
                ev += Evidence("HYPOTHESIS", "${analyte.name.en} ${fmt(conc)} ${analyte.unit} → ${lvl.name}", lvl == Level.SAFE)
                Outcome(
                    "Shuddh Spectrum", analyte.id, analyte.name, conc, analyte.unit, lvl,
                    "${analyte.name.en}: ${fmt(conc)} ${analyte.unit}" + (if (extrapolated) " (extrapolated)" else ""),
                    adv, ev, analyte.limitNote,
                )
            }
        }
    }

    ScreenFrame("Shuddh Spectrum", "Flash + camera + CD grating → visible spectrometer", onBack = { app.back() }) {
        StepTracker(listOf("λ calibrated" to wave.calibrated, "Blank" to (blank != null), "Sample" to (sample != null), "Verdict" to false))
        CameraView(
            cam, Modifier.fillMaxWidth(),
            overlay = {
                roi(bandRect(), Color(0xFFA78BFA))
                if (selfRef) roi(refRect(), Color(0xFF5AA9FF))
            },
        ) { bmp ->
            val p = Frames.bandProfile(bmp, bandRect())
            val ref = if (selfRef) Frames.meanRgb(bmp, refRect()).let { it.r + it.g + it.b } else 0f
            live = p
            if (motion.steady) collector.offer(SpecFrame(p, ref))
        }

        SteadyBar(motion, "capture the sample")
        OnWave(motion) { if (blank != null) capture("sample") { sample = it; status = "Sample measured (wave-triggered)." } }
        Note(status, Palette.text)
        live?.let { p ->
            val xs = FloatArray(p.size) { wave.nm(it, p.size).toFloat() }
            LineChart(
                listOf(
                    Series(xs, FloatArray(p.size) { p.total[it] / 3 }, Color.White),
                    Series(xs, p.r, Color(0xAAF43F5E)), Series(xs, p.g, Color(0xAA34D399)), Series(xs, p.b, Color(0xAA60A5FA)),
                ),
                xLabel = "nm", yMin = 0f, yMax = 255f, rainbow = true,
                markers = listOf(435.8f to Color(0xFF5AA9FF), 546.1f to Color(0xFF3DDC97), analyte.bandNm.toFloat() to Color(0xFFA78BFA)),
            )
            if (p.saturated > 0.02f) Note("⚠ ${fmt(p.saturated * 100.0)}% of the band is clipped — lower exposure", Palette.amber)
        }

        Section("Light & camera") {
            BtnRow {
                Btn(if (cam.torchOn) "Flash lamp: ON" else "Flash lamp: off", primary = cam.torchOn) { cam.torch(!cam.torchOn) }
                Btn(if (cam.locked) "Exposure: LOCKED" else "Exposure: auto", primary = cam.locked) { cam.lock(!cam.locked) }
                Btn("Stir 10 s (vibrate)", primary = false) { Haptics.stir(ctx); status = "Stirring: rest the vial on the phone for 10 s" }
            }
            val r = cam.evRange
            if (r.last > r.first) LabeledSlider("Exposure compensation: ${cam.evIndex}", cam.evIndex.toFloat(), r.first.toFloat()..r.last.toFloat(), r.last - r.first - 1) { cam.exposure(it.toInt()) }
            LabeledSlider("Band position", roiY, 0.08f..0.92f) { roiY = it }
            LabeledSlider("Band height", roiH, 0.02f..0.2f) { roiH = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(selfRef, { selfRef = it })
                Text("  Self-reference band (lamp light that bypasses the vial)", color = Palette.text)
            }
            if (selfRef) LabeledSlider("Reference band position", refY, 0.05f..0.95f) { refY = it }
        }

        Section("1 · Wavelength calibration") {
            Note(wave.note, if (wave.calibrated) Palette.accent else Palette.amber)
            BtnRow {
                Btn("Auto-calibrate on CFL") {
                    capture("CFL lines") { f ->
                        cam.lock(false)
                        autoCalibrate(f.p).fold(
                            { wave = it; WaveCal.save(app.prefs, it); status = "Calibrated. ${it.note}" },
                            { status = it.message ?: "Calibration failed" },
                        )
                    }
                }
                Btn("Reset", primary = false) { WaveCal.save(app.prefs, null); wave = WaveCal.default }
            }
        }

        Section("2 · Test") {
            Chips(Analytes.spectrum, analyte, { "${it.matrix}: ${it.name.en}" }) { analyte = it; sample = null }
            Note("${analyte.method} · reads at ${analyte.bandNm.toInt()} nm · ${if (analyte.kind == Kind.QUANT) "quantitative (mg/L)" else "presence vs pure reference"}")
            Note(analyte.limitNote)
        }

        Section("3 · Measure") {
            Note(
                if (analyte.kind == Kind.PRESENCE) "Blank = known-pure milk run through the same reagent. Sample = the milk under test."
                else "Blank = clean water + reagent (or the kit's zero standard). Sample = your water + reagent.",
            )
            BtnRow {
                Btn(if (blank == null) "Capture blank" else "Re-capture blank", primary = blank == null) {
                    capture("blank") { blank = it; status = "Blank stored. Now insert the sample." }
                }
                Btn("Capture sample", enabled = blank != null) {
                    capture("sample") { sample = it; status = "Sample measured." }
                }
            }
            val b = blank; val s = sample
            if (b != null && s != null) {
                val a = absorbance(b, s)
                val xs = FloatArray(a.size) { wave.nm(it, a.size).toFloat() }
                Note("Absorbance spectrum (sample vs blank)")
                LineChart(listOf(Series(xs, a, Color(0xFFA78BFA), fill = true)), xLabel = "nm", markers = listOf(analyte.bandNm.toFloat() to Color(0xFFF2B33D)))
                Btn("Get verdict", Modifier.fillMaxWidth()) { app.show(verdict(b, s)) }
            }
        }

        Section("Fingerprint · refill & counterfeit check") {
            Note("Capture blank + sample of a product you trust and save its spectrum. Later, scan another bottle the same way and compare.")
            if (fps.isNotEmpty()) Note("Library: " + fps.keys.joinToString(", "), Palette.text)
            OutlinedTextField(newFp, { newFp = it }, label = { Text("Name (e.g. Brand ghee, Jan batch)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val b = blank; val s2 = sample
            BtnRow {
                Btn("Save fingerprint", enabled = b != null && s2 != null && newFp.isNotBlank(), primary = false) {
                    fps = fps + (newFp.trim() to fingerprintOf(absorbance(b!!, s2!!), wave))
                    saveFingerprints(app.prefs, fps); status = "Fingerprint \"${newFp.trim()}\" saved"; newFp = ""
                }
                Btn("Compare", enabled = b != null && s2 != null && fps.isNotEmpty()) {
                    val fp = fingerprintOf(absorbance(b!!, s2!!), wave)
                    val best = fps.mapNotNull { (k, v) -> compareFingerprints(fp, v)?.let { k to it } }.maxByOrNull { it.second.first }
                    app.show(fingerprintVerdict(best?.first, best?.second, fps.size))
                }
                if (fps.isNotEmpty()) Btn("Clear", primary = false) { fps = emptyMap(); saveFingerprints(app.prefs, fps) }
            }
        }

        HowItWorks(listOf(
            "A CD has ~1,350 grooves per mm; light diffracting off them spreads into a rainbow — each camera column sees one wavelength.",
            "A CFL tube contains mercury, which glows at exactly 435.8 nm (blue) and 546.1 nm (green). Finding those two lines pins the wavelength scale.",
            "Reagents turn a colourless contaminant into a coloured dye. Beer–Lambert law: absorbance A = log10(I₀/I) rises in a straight line with concentration.",
            "Measuring the blank and the sample in the same geometry cancels the lamp, the vial and the camera — only the chemistry remains.",
        ))
        if (analyte.kind == Kind.QUANT) {
            Section("Standards (Beer–Lambert calibration)") {
                val c = cal
                if (c == null || c.points.isEmpty()) {
                    Note("No standards yet. Capture the blank, then each known standard from your reagent kit.")
                } else {
                    c.points.forEach { (x, y) -> Mono("${fmt(x)} ${analyte.unit} → A = ${fmt(y)}") }
                    c.fit?.let { Note("Fit: A = ${fmt(it.slope)}·c + ${fmt(it.intercept)}  (R² ${fmt(it.r2)})", if (it.r2 > 0.95) Palette.accent else Palette.amber) }
                }
                OutlinedTextField(
                    stdConc, { stdConc = it }, label = { Text("Standard concentration (${analyte.unit})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(lot, { lot = it }, label = { Text("Reagent lot (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                BtnRow {
                    Btn("Capture standard", enabled = blank != null && stdConc.toDoubleOrNull() != null) {
                        val conc = stdConc.toDouble()
                        val b = blank!!
                        capture("standard ${fmt(conc)}") { f ->
                            val a = bandAbs(absorbance(b, f), f.p.size, analyte.bandNm)
                            if (a == null) {
                                status = "Band not in calibrated range"
                            } else {
                                val prev = cal
                                val next = AnalyteCal(
                                    (prev?.points ?: emptyList()) + (conc to a),
                                    lot.ifBlank { prev?.lot ?: "" }, prev?.created ?: System.currentTimeMillis(),
                                )
                                cal = next; AnalyteCal.save(app.prefs, analyte.id, next)
                                status = "Standard ${fmt(conc)} → A=${fmt(a)} saved"; stdConc = ""
                            }
                        }
                    }
                    Btn("Clear standards", primary = false) { cal = null; AnalyteCal.save(app.prefs, analyte.id, null) }
                }
            }
        }
    }
}
