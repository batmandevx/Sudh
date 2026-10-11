package com.shuddh.lab.instruments

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Spacer
import com.shuddh.lab.core.PurityTrain
import com.shuddh.lab.core.PurityAi
import android.content.Context
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.camera.Collector
import com.shuddh.lab.camera.Frames
import com.shuddh.lab.camera.Rgb
import com.shuddh.lab.camera.roi
import com.shuddh.lab.core.BackLight
import com.shuddh.lab.core.Dart
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Fusion
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.MilkType
import com.shuddh.lab.core.Ladder
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Purity
import com.shuddh.lab.core.Purity.Kind
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Chips
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Fold
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.FusionBars
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import com.shuddh.lab.ui.SteadyBar
import com.shuddh.lab.ui.StepTracker
import com.shuddh.lab.ui.rememberMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt


/** Every optical channel Purity fuses — all through the rear camera. */
private enum class Mode(val id: String, val sensor: String) {
    AMB("amb", "Rear cam · room light"),
    FLASH("flash", "Rear cam · torch lock-in"),
    RING_R("ringR", "iQOO ring · red"),
    RING_G("ringG", "iQOO ring · green"),
    RING_B("ringB", "iQOO ring · blue"),
    RING_W("ringW", "iQOO ring · white"),
}

/** One capture: reflectance per camera channel (and 1-σ) for each mode that produced a usable reading, plus quality notes. */
private data class Shot(
    val reads: Map<Mode, Pair<Purity.Refl, DoubleArray>>,
    val notes: List<Pair<String, Boolean>>,
    /** Magnetometer |B| mean and SD over the capture, µT. */
    val mag: Pair<Double, Double>? = null,
    /** Nami echo features: mean echo level (dB) and spectral tilt (dB), with chirps heard. */
    val echo: Triple<Double, Double, Int>? = null,
)

/** What each stage measured, for the live panel and the per-stage summary. */
private class StageInfo {
    var specAmb: DoubleArray? = null
    var specFlash: DoubleArray? = null
    /** (requested colour, colour the camera actually saw, matched?) per ring colour. */
    var ring: List<Triple<Color, Color, Boolean>> = emptyList()
    var ringNote: String? = null
    var echo: Triple<Double, Double, Int>? = null
    var mag: Double? = null
    var tex: Double? = null
}

private fun arr(d: DoubleArray) = JSONArray().apply { d.forEach { put(it) } }
private fun darr(a: JSONArray?) = a?.let { DoubleArray(it.length()) { i -> it.getDouble(i) } }
private fun ch(c: Rgb, i: Int) = when (i) { 0 -> c.r; 1 -> c.g; else -> c.b }.toDouble()
private fun meanSd(xs: List<Double>): Pair<Double, Double> {
    val m = xs.average(); return m to sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1).coerceAtLeast(1))
}

/**
 * Phone sensors read during the full test: magnetometer field magnitude (a fused sensor), plus
 * accelerometer / gyroscope / light kept for motion and lighting checks.
 */
private class PhoneSensors : SensorEventListener {
    @Volatile var gz = 9.8f
    @Volatile var gyro = 0f
    @Volatile var lux = -1f
    val mag = FloatArray(3)
    private var magMin = FloatArray(3) { Float.MAX_VALUE }; private var magMax = FloatArray(3) { -Float.MAX_VALUE }
    private var gyroMax = 0f


    private val magN = mutableListOf<Double>()
    @Volatile var magCollect = false
    @Synchronized fun startMag() { magN.clear(); magCollect = true }
    /** Max rotation rate since the last reset — was the phone held still during a capture? */
    @Synchronized fun resetGyro() { gyroMax = 0f }
    @Synchronized fun gyroPeak() = gyroMax
    /** Mean and SD of the field magnitude since [startMag] (orientation-independent). */
    @Synchronized fun stopMag(): Pair<Double, Double>? { magCollect = false; return if (magN.size < 10) null else meanSd(magN.toList()) }


    @Synchronized override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> gz = 0.8f * gz + 0.2f * e.values[2]
            Sensor.TYPE_GYROSCOPE -> { gyro = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]); gyroMax = maxOf(gyroMax, gyro) }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                for (i in 0..2) { mag[i] = e.values[i]; magMin[i] = minOf(magMin[i], e.values[i]); magMax[i] = maxOf(magMax[i], e.values[i]) }
                if (magCollect) magN += sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
            }
            Sensor.TYPE_LIGHT -> lux = e.values[0]
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

/** Purity — how much of a liquid is adulterant, from camera, torch, iQOO ring light and magnetometer, fused into one verdict. */
@Composable
fun PurityScreen(app: AppState) {
    val ctx = app.ctx
    val scope = rememberCoroutineScope()
    val cam = remember { CameraHandle() }
    val motion = rememberMotion()
    val sensors = remember { PhoneSensors() }
    // Training data: labelled samples captured with the same five stages, plus a photo each.
    var store by remember { mutableStateOf(app.prefs.json("purity_train") ?: JSONObject()) }
    val samples = remember(store) { PurityTrain.load(store) }
    val custom = remember(store) { PurityTrain.customLabels(store) }
    val labels = PurityTrain.presets + custom
    var training by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PurityOutcome?>(null) }
    /** Recent texture (luma CV) of the cap from the camera thread — curdled milk is lumpier. */
    val texBuf = remember { ArrayDeque<Double>() }
    /** Photo request/holder for the latest frame. */
    val snap = remember { arrayOfNulls<Bitmap>(1) }
    val wantSnap = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var ringLive by remember { mutableStateOf<Color?>(null) }
    /** Which sensor the running capture is on — drives the live progress panel. */
    var phase by remember { mutableStateOf<TestStep?>(null) }
    var stage by remember { mutableStateOf(StageInfo()) }
    /** Latest cap ÷ paper ratio per channel from the camera thread — drives the live spectrum bars. */
    val liveRgb = remember { DoubleArray(3) }
    val qc = remember { mutableStateMapOf<String, Boolean>() }
    var status by remember { mutableStateOf<String?>(null) }
    val white = RectF(0.10f, 0.40f, 0.34f, 0.60f)
    val cap = RectF(0.60f, 0.43f, 0.80f, 0.57f)
    val collector = remember { Collector<List<Pair<Rgb, Rgb>>> { xs -> xs.flatten() } }

    DisposableEffect(Unit) {
        BackLight.loadMap(app.prefs)
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_LIGHT)
            .forEach { t -> sm.getDefaultSensor(t)?.let { sm.registerListener(sensors, it, SensorManager.SENSOR_DELAY_GAME) } }
        onDispose { sm.unregisterListener(sensors); cam.torch(false); BackLight.off(ctx) }
    }

    suspend fun grab(n: Int, timeout: Long = 8000): List<Pair<Rgb, Rgb>>? = withTimeoutOrNull(timeout) {
        suspendCancellableCoroutine { c -> collector.start(n) { if (c.isActive) c.resume(it) } }
    }

    /** Lock-in reflectance per channel: (sample ON − OFF) ÷ (white ON − OFF), with 1-σ from frame scatter + a positioning floor. */
    fun lockIn(on: List<Pair<Rgb, Rgb>>, off: List<Pair<Rgb, Rgb>>, minSignal: Double, floor: Double): Pair<Purity.Refl, DoubleArray>? {
        if (on.size < 3 || off.size < 3) return null
        val wOn = Rgb.average(on.map { it.first }); val wOff = Rgb.average(off.map { it.first })
        val sOn = Rgb.average(on.map { it.second }); val sOff = Rgb.average(off.map { it.second })
        if (sOn.saturated > 0.02f || wOn.saturated > 0.05f) return null
        val dW = DoubleArray(3) { ch(wOn, it) - ch(wOff, it) }
        if (dW.max() < minSignal) return null
        val r = DoubleArray(3) { if (dW[it] < minSignal / 3) Double.NaN else (ch(sOn, it) - ch(sOff, it)) / dW[it] }
        val sd = DoubleArray(3) { i ->
            val a = meanSd(on.map { ch(it.second, i) }).second; val b = meanSd(off.map { ch(it.second, i) }).second
            sqrt((a * a + b * b) / minOf(on.size, off.size)) / dW[i].coerceAtLeast(1.0) + floor
        }
        // Unusable channels get a reflectance no model accepts, so calibration picks another channel.
        return Purity.Refl(DoubleArray(3) { if (r[it].isNaN()) 0.0 else r[it] }) to sd
    }

    /** Face-up: torch ON (exposure locked there) then OFF — room-light ratio and torch lock-in from one pose. */
    /** Nami sonar in place (phone held over the cap): 2 pings at a pinned volume; returns (level dB, tilt dB, chirps). */
    suspend fun echoShot(): Triple<Double, Double, Int>? {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (am.mode == android.media.AudioManager.MODE_IN_CALL || am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) return null
        val stream = android.media.AudioManager.STREAM_MUSIC
        val before = am.getStreamVolume(stream)
        return try {
            runCatching { am.setStreamVolume(stream, (am.getStreamMaxVolume(stream) * 0.85f).toInt().coerceAtLeast(1), 0) }
            val reads = (0 until 2).mapNotNull { kotlinx.coroutines.withContext(Dispatchers.Default) { runCatching { com.shuddh.lab.core.Sonar.analyse(com.shuddh.lab.core.Sonar.capture(ctx)) }.getOrNull() } }
            com.shuddh.lab.core.Sonar.pool(reads)?.let { r -> Triple(r.bands.average(), com.shuddh.lab.core.Sonar.tilt(r.bands), r.chirpsFound) }
        } finally { runCatching { am.setStreamVolume(stream, before, 0) } }
    }

    /**
     * Five stages: 1 spectrum (torch ON/OFF + room light, R G B vs white paper) → 2 iQOO ring light in
     * red, green, blue, white — each colour verified by the camera → 3 Nami echo → 4 magnetometer → 5 verdict.
     */
    suspend fun shootUp(): Shot? {
        val info = StageInfo(); stage = info
        sensors.startMag()
        // Expose ~1 EV darker than auto so white paper and pure milk don't clip under the torch.
        val ev = cam.camera?.cameraInfo?.exposureState?.let { st ->
            if (!st.isExposureCompensationSupported) 0 else (-1.0 / st.exposureCompensationStep.toDouble()).roundToInt().coerceIn(st.exposureCompensationRange.lower, st.exposureCompensationRange.upper)
        } ?: 0
        cam.exposure(ev)
        fun narrate(t: String) = app.voice.say(t, app.lang)
        fun stageStart(st: TestStep) { if (phase != null && phase != st) Haptics.click(ctx); phase = st; Haptics.tick(ctx, 0.8f) }
        // ── 1 · Spectrum ──
        stageStart(TestStep.SPECTRUM)
        app.voice.stop(); narrate("Performing test. Measuring spectrum.")
        // Steadiness-gated capture: if the gyroscope saw the phone rotate, retake once.
        suspend fun steadyGrab(n: Int): List<Pair<Rgb, Rgb>>? {
            for (attempt in 0..1) {
                sensors.resetGyro(); val fr = grab(n)
                if (fr == null || sensors.gyroPeak() < 0.35f) return fr
                Haptics.tick(ctx, 1f); narrate("Hold still.")
            }
            return grab(n)
        }
        cam.lock(false); cam.torch(true); delay(1000); cam.lock(true); delay(300)
        val on = steadyGrab(18)
        cam.torch(false); delay(600)
        val off1 = steadyGrab(18)
        delay(250)
        val off2 = steadyGrab(18)
        // Two separate room-light reads must agree; their union is used.
        val off = if (off1 != null && off2 != null) {
            val a1 = Rgb.average(off1.map { it.second }).luma / Rgb.average(off1.map { it.first }).luma.coerceAtLeast(1f)
            val a2 = Rgb.average(off2.map { it.second }).luma / Rgb.average(off2.map { it.first }).luma.coerceAtLeast(1f)
            if (kotlin.math.abs(a1 - a2) > 0.03f) status = "The two room-light reads disagreed (${fmt((a1 * 100).toDouble())}% vs ${fmt((a2 * 100).toDouble())}%) — keep the phone still; the average was used."
            off1 + off2
        } else off1 ?: off2
        off?.let { fr ->
            val w = Rgb.average(fr.map { it.first }); val sp = Rgb.average(fr.map { it.second })
            val pc = (0..2).map { (100 * ch(sp, it) / ch(w, it).coerceAtLeast(1.0)).roundToInt() }
            narrate("Spectrum: red ${pc[0]}, green ${pc[1]}, blue ${pc[2]} percent of white.")
        }
        info.tex = synchronized(texBuf) { texBuf.toList() }.takeIf { it.size >= 5 }?.sorted()?.let { it[it.size / 2] }
        // ── 2 · iQOO ring light, every colour checked by the camera ──
        stageStart(TestStep.RING)
        narrate("Ring light.")
        val ring = mutableMapOf<Mode, List<Pair<Rgb, Rgb>>>()
        val ringColours = listOf(Mode.RING_R to 0xFFFF0000.toInt(), Mode.RING_G to 0xFF00FF00.toInt(), Mode.RING_B to 0xFF0000FF.toInt(), Mode.RING_W to 0xFFFFFFFF.toInt())
        var ringOk = false
        for ((mode, argb) in if (BackLight.multicolour == false) ringColours.takeLast(1) else ringColours) {
            if (!BackLight.set(ctx, argb)) break
            ringOk = true; ringLive = Color(argb)
            narrate(when (mode) { Mode.RING_R -> "Red"; Mode.RING_G -> "Green"; Mode.RING_B -> "Blue"; else -> "White" })
            delay(450)
            grab(8, 4000)?.let { ring[mode] = it }
        }
        BackLight.off(ctx); ringLive = null
        cam.lock(false); cam.exposure(0)
        // ── 3 · Nami echo (in place) ──
        stageStart(TestStep.ECHO)
        narrate("Pinging echo.")
        // The microphone must not hear the voice: wait for it to finish (max 5 s).
        run { var waited = 0; delay(300); while (app.voice.speaking && waited < 5000) { delay(100); waited += 100 } }
        val echo = echoShot()
        info.echo = echo
        narrate(echo?.let { "Echo: ${it.third} chirps, level ${it.first.roundToInt()} decibels." } ?: "Echo skipped.")
        // ── 4 · Magnetometer ──
        stageStart(TestStep.MAGNET)
        narrate("Measuring magnetic field.")
        delay(400)
        val mag = sensors.stopMag()
        info.mag = mag?.first
        mag?.let { narrate("${it.first.roundToInt()} microtesla. Computing verdict.") }
        stageStart(TestStep.VERDICT)
        if (on == null || off == null) { phase = null; return null }
        val reads = mutableMapOf<Mode, Pair<Purity.Refl, DoubleArray>>(); val notes = mutableListOf<Pair<String, Boolean>>()
        val wOff = Rgb.average(off.map { it.first }); val sOff = Rgb.average(off.map { it.second })
        if (wOff.luma in 18f..245f && sOff.saturated < 0.02f) {
            val per = (0..2).map { i -> meanSd(off.map { (w, s) -> ch(s, i) / ch(w, i).coerceAtLeast(1.0) }) }
            reads[Mode.AMB] = Purity.Refl(DoubleArray(3) { per[it].first }) to DoubleArray(3) { sqrt(per[it].second * per[it].second / off.size + 0.004 * 0.004) }
        } else notes += (if (wOff.luma < 30f) "Room too dark for the room-light reading" else "White card glaring in room light") to false
        lockIn(on, off, 15.0, 0.006)?.let { reads[Mode.FLASH] = it } ?: run { notes += "Torch reading unusable (glare on liquid or bright room)" to false }
        info.specAmb = reads[Mode.AMB]?.first?.rgb; info.specFlash = reads[Mode.FLASH]?.first?.rgb
        // Ring: what colour did the camera actually see on the white paper for each requested colour?
        val w0 = Rgb.average(off.map { it.first })
        val resp = ringColours.mapNotNull { (m, argb) -> ring[m]?.let { fr -> val w1 = Rgb.average(fr.map { it.first }); Triple(m, argb, DoubleArray(3) { ch(w1, it) - ch(w0, it) }) } }
        info.ring = resp.map { (m, argb, d) ->
            val mx = d.max().coerceAtLeast(1e-6)
            val seen = Color((d[0] / mx).toFloat().coerceIn(0f, 1f), (d[1] / mx).toFloat().coerceIn(0f, 1f), (d[2] / mx).toFloat().coerceIn(0f, 1f))
            val dom = d.indices.maxByOrNull { d[it] }!!
            val ok = mx > 3 && when {
                BackLight.multicolour == false -> true // known single-colour ring: used as one plain light
                m == Mode.RING_R -> dom == 0; m == Mode.RING_G -> dom == 1; m == Mode.RING_B -> dom == 2
                else -> d.min() > 0.4 * mx
            }
            Triple(Color(argb), seen, ok)
        }
        // Self-correct the colour order from these measurements (if R, G, B each lit a different channel).
        val rgbResp = resp.filter { it.first != Mode.RING_W }
        if (rgbResp.size == 3 && rgbResp.all { it.third.max() > 3 }) {
            val before = BackLight.map.toList()
            // Responses were taken with the current mapping; convert back to raw slots before learning.
            val raw = Array(3) { slot -> rgbResp[BackLight.map.indexOf(slot).coerceAtLeast(0)].third }
            BackLight.learn(raw); BackLight.saveMap(app.prefs)
            if (BackLight.map.toList() != before) info.ringNote = "Ring colours were in the wrong order — corrected for next time."
        }
        ring.forEach { (m, fr) ->
            val okColour = info.ring.getOrNull(ringColours.indexOfFirst { it.first == m })?.third == true
            if (okColour) lockIn(fr, off, 4.0, 0.006)?.let { reads[m] = it }
        }
        val bad = info.ring.count { !it.third }
        notes += when {
            !ringOk -> "Ring light didn't respond" to false
            info.ring.isEmpty() -> "Ring light not seen by the camera" to false
            bad > 0 -> "Ring light: $bad colour${if (bad > 1) "s" else ""} didn't light as asked — excluded" to false
            else -> "Ring light: all ${info.ring.size} colours verified by the camera" to true
        }
        notes += (if (echo != null) "Nami echo: ${echo.third} chirps heard" else "Nami echo skipped (mic busy or no permission)") to (echo != null)
        stage = info
        return Shot(reads, notes, mag, echo)
    }


    /** Sensor features of one capture (keys like amb_r, flash_g, ringR_b, tex, echo_l, mag). */
    fun features(sh: Shot, info: StageInfo): Map<String, Double> = buildMap {
        sh.reads.forEach { (m, r) -> for (i in 0..2) put("${m.id}_${"rgb"[i]}", r.first[i]) }
        info.tex?.let { put("tex", it) }
        sh.mag?.let { put("mag", it.first) }
        sh.echo?.let { put("echo_l", it.first); put("echo_t", it.second) }
    }

    /** Takes a photo of the current frame (cap + paper region) and saves it as a small JPEG. */
    suspend fun photo(name: String): String? {
        snap[0] = null; wantSnap.set(true)
        repeat(20) { if (snap[0] != null) return@repeat; delay(50) }
        val b = snap[0] ?: return null
        val dir = java.io.File(ctx.filesDir, "purity_train").apply { mkdirs() }
        val f = java.io.File(dir, "$name.jpg")
        return runCatching { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) }; f.absolutePath }.getOrNull()
    }

    /** Training: capture one labelled sample with all five stages + a photo. */
    fun trainCapture(lb: PurityTrain.Label) {
        if (busy != null) return
        busy = "Recording ${lb.title}…"
        scope.launch {
            val sh = shootUp()
            val f = sh?.let { features(it, stage) }.orEmpty()
            if (sh == null || !PurityTrain.usable(f)) { busy = null; status = "Not saved: the camera got no reading — hold the phone steady ~15 cm over the cap."; app.voice.say("Not saved.", app.lang); Haptics.thud(ctx); return@launch }
            val warn = PurityTrain.captureProblem(f)
            val id = "${lb.id}_${System.currentTimeMillis()}"
            val img = photo(id)
            busy = null
            val list = samples + PurityTrain.Sample(id, lb.id, System.currentTimeMillis(), f, img)
            store = JSONObject(store.toString()).put("s", PurityTrain.save(list).getJSONArray("s")); app.prefs.putJson("purity_train", store)
            status = (warn?.let { "Saved, but check: $it  " } ?: "") + "${lb.emoji} ${lb.title} saved (${list.count { it.label == lb.id }} sample${if (list.count { it.label == lb.id } > 1) "s" else ""})."
            Haptics.click(ctx); app.voice.say("${lb.title} saved.", app.lang)
        }
    }

    /** Test: five stages → classifier vs training → % water → on-device AI second opinion → final decision. */
    fun runTest() {
        if (busy != null) return
        busy = "Measuring…"; result = null
        scope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            busy = null; phase = null; BackLight.off(ctx); cam.torch(false)
            status = "Test failed (${e.javaClass.simpleName}) — try again."
            android.util.Log.w("Purity", "test crashed", e)
        }) {
            val sh = shootUp()
            val f = sh?.let { features(it, stage) }.orEmpty()
            val img = photo("last_test")
            android.util.Log.w("PurityDump", "CAPTURE ok=${sh != null} " + f.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${String.format(java.util.Locale.US, "%.3f", it.value)}" })
            // Developer test fixture comes first — it must work whatever the camera saw.
            val fxLeft = app.prefs.json("purity_fixture")?.optInt("left", 0) ?: 0
            if (fxLeft > 0) {
                app.prefs.putJson("purity_fixture", JSONObject().put("left", fxLeft - 1))
                busy = null
                result = fixture(6 - fxLeft, labels, PurityTrain.Result(emptyList(), null, emptyList()), img, f)
                Haptics.click(ctx); return@launch
            }
            if (sh == null || !PurityTrain.usable(f)) {
                busy = null; status = "The camera got no reading — ${sh?.notes?.filter { !it.second }?.joinToString { it.first } ?: "no frames"}. Hold the phone steady over the cap and try again."
                android.util.Log.w("PurityDump", "NO READING: $status"); app.voice.say("No reading. Try again.", app.lang); Haptics.thud(ctx); return@launch
            }
            sh.notes.forEach { (t, ok) -> qc[t] = ok }
            val warning = PurityTrain.captureProblem(f)
            val usable = samples.filter { PurityTrain.usable(it.f) }.map { it.label }.distinct()
            val r = PurityTrain.classify(f, samples)
            if (r == null || usable.size < 2) { busy = null; status = "Train at least 2 kinds first (🎓 Training) — you have ${usable.size}."; android.util.Log.w("PurityDump", "NOT ENOUGH TRAINING ${usable}"); return@launch }
            android.util.Log.w("PurityDump", "TEST " + f.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${String.format(java.util.Locale.US, "%.3f", it.value)}" } +
                " | keys=" + r.features.joinToString(",") + " | " + r.ranked.joinToString { "${it.label}=${(it.p * 100).toInt()}%/d${String.format(java.util.Locale.US, "%.2f", it.dist)}" })
            val top = labels.firstOrNull { it.id == r.ranked.first().label }
            val curve = PurityTrain.waterCurve(samples, labels)
            val fit = PurityTrain.dilutionFit(f, samples, labels)
            val mix = PurityTrain.onDilutionLine(fit, r)
            val unk = PurityTrain.unknown(r, fit)
            val waterAny = curve?.let { (k, m) -> f[k]?.let { m.waterPct(it) to m.sigmaPct(it, 0.006) } }
            val water = if (mix || (top?.family == "milk" && top.id != "spoiled_milk" && !unk)) waterAny else null
            // Second opinion from the on-device AI (text-only: it gets the training and test numbers).
            var op: PurityAi.Opinion? = null
            // The second opinion may never hold up the verdict: skipped if the model is busy, capped at 20 s.
            if (app.llm.hasChat() && !app.llm.busy) {
                busy = "Comparing with your training…"
                val prompt = PurityAi.prompt(samples, labels, f, r, water?.first)
                val job = scope.async(Dispatchers.Default) { runCatching { app.llm.generate(app.llm.chatRole(longForm = false), prompt) }.getOrNull() }
                val text = withTimeoutOrNull(20_000) { job.await() }
                op = text?.let { PurityAi.parse(it, samples.map { s -> s.label }.distinct()) }
            }
            val d0 = PurityTrain.decide(r, op?.label, op?.confidence)
            // Milk–water mixes get a % range; far-from-everything is "unknown"; weak matches say "not sure".
            val mode = when {
                mix && water != null -> "mix"
                // Far from everything trained: unknown, whatever the AI says.
                unk -> "unknown"
                // Borderline: the AI may only confirm when the match is reasonably close.
                r.unsure && !(d0.aiAgrees == true && r.ranked.first().dist <= 3) -> "unsure"
                else -> "class"
            }
            val d = when (mode) {
                "mix" -> d0.copy(label = "mix", confidence = Dart.confidence(minOf(fit!!.second.let { 3.0 - it }, 3.0), 1.0))
                "unknown", "unsure" -> d0.copy(confidence = d0.confidence.coerceAtMost(50.0))
                else -> d0
            }
            val lb = when (mode) {
                "mix" -> water!!.first.let { w -> when {
                    w >= 95 -> labels.first { it.id == "water" }
                    w <= 5 -> labels.first { it.id == "pure_milk" }
                    else -> PurityTrain.Label("mix", "Milk with ${PurityTrain.range(w, water.second).let { (a, b) -> "$a–$b" }}% water", "🥛", w, w < 8, "milk")
                } }
                "class" -> labels.firstOrNull { it.id == d.label }
                else -> null
            }
            val unsafeWater = (water?.first ?: 0.0) >= 8 && lb?.family == "milk" && lb.id != "water"
            val safe = mode != "unknown" && (lb?.safe ?: false) && !unsafeWater
            val risks = when {
                mode == "unknown" -> listOf(
                    "This doesn't match milk, water or anything you trained — it may contain other substances.",
                    "Phone sensors can't identify dissolved chemicals (urea, detergent, formalin, bleach). Don't consume it; get it tested at an FSSAI-approved lab.",
                )
                lb?.family == "adulterant" -> listOf("Matches your trained '${lb.title}' sample.") + Purity.healthEffects(Purity.Kind.MILK)
                lb?.id == "spoiled_milk" -> listOf("Spoiled milk can carry Salmonella, E. coli and Listeria — vomiting, diarrhoea and fever, worst for children, pregnant women and the elderly.", "Boiling doesn't undo souring — throw it away.")
                lb?.family == "milk" && !safe -> Purity.healthEffects(Purity.Kind.MILK)
                lb?.family == "honey" && !safe -> Purity.healthEffects(Purity.Kind.HONEY)
                lb?.family == "juice" && !safe -> Purity.healthEffects(Purity.Kind.JUICE)
                !safe -> Purity.healthEffects(Purity.Kind.OTHER)
                else -> emptyList()
            }
            busy = null
            val fx = app.prefs.json("purity_fixture")?.optInt("left", 0) ?: 0
            result = if (fx > 0) {
                app.prefs.putJson("purity_fixture", JSONObject().put("left", fx - 1))
                fixture(6 - fx, labels, r, img, f)
            } else PurityOutcome(lb, if (warning != null) d.copy(confidence = d.confidence * 0.7) else d, r, water ?: waterAny?.takeIf { mode != "class" }, op, img, safe, risks, f, mode, warning)
            android.util.Log.w("PurityDump", "RESULT mode=$mode label=${lb?.id} water=${water?.first} safe=$safe warning=$warning")
            if (safe) Haptics.ping(ctx) else Haptics.rumble(ctx, 1f, 900)
            app.voice.say(if (mode == "unknown") "Unknown sample. Don't consume it." else if (lb == null) "Not sure. Add more training samples, or check the setup." else "${lb.title}. ${if (safe) "Looks safe." else "Not safe."}" + (water?.let { " About ${it.first.roundToInt()} percent water." } ?: "") +
                (risks.firstOrNull()?.let { " $it" } ?: ""), app.lang)
        }
    }

    /**
     * Ring light set-up, judged by the camera. Searches every light type × colour byte format: lights red, then
     * blue, and keeps the combination where the paper really turns red and then blue. Then learns the R/G/B order.
     */
    fun checkRing() {
        if (busy != null) return
        busy = "Setting up the ring light — hold the phone 10 cm over white paper…"
        scope.launch {
            cam.lock(false); delay(700); cam.lock(true); delay(250)
            val off = grab(6, 4000)
            if (off == null) { busy = null; cam.lock(false); status = "Ring set-up: the camera didn't deliver frames."; return@launch }
            val w0 = Rgb.average(off.map { it.first })
            fun shares(fr: List<Pair<Rgb, Rgb>>): DoubleArray { val w1 = Rgb.average(fr.map { it.first }); val d = DoubleArray(3) { (ch(w1, it) - ch(w0, it)).coerceAtLeast(0.0) }; val t = d.sum(); return if (t < 3) DoubleArray(3) else DoubleArray(3) { d[it] / t } }
            data class Try(val type: Int, val pv: Boolean, val enc: Int, val score: Double, val red: DoubleArray, val blue: DoubleArray, val fx: Int = -1)
            val tries = mutableListOf<Try>()
            data class Combo(val t: Int, val pv: Boolean, val e: Int, val fx: Int)
            val combos = BackLight.candidates.flatMap { (t, pv) -> listOf(-1, 0, 1, 2, 3).flatMap { fx -> listOf(0, 2).map { e -> Combo(t, pv, e, fx) } } }
            for ((i, c) in combos.withIndex()) {
                val (t, pv, e, fx) = c
                busy = "Ring set-up ${i + 1}/${combos.size}: type $t${if (pv) " (preview)" else ""}, ${if (fx < 0) "steady" else "effect $fx"}, format ${e + 1}"
                if (!BackLight.setRaw(ctx, 0xFFFF0000.toInt(), type = t, preview = pv, enc = e, effect = fx)) continue
                ringLive = Color.Red; delay(450); val r = grab(5, 3000); BackLight.off(ctx)
                if (!BackLight.setRaw(ctx, 0xFF0000FF.toInt(), type = t, preview = pv, enc = e, effect = fx)) continue
                ringLive = Color.Blue; delay(450); val bl = grab(5, 3000); BackLight.off(ctx); ringLive = null
                if (r == null || bl == null) continue
                val sr = shares(r); val sb = shares(bl)
                // Good = red request lights mostly red AND blue request lights mostly blue.
                tries += Try(t, pv, e, sr[0] + sb[2] - sr[2] - sb[0], sr, sb, fx)
                delay(150)
            }
            BackLight.off(ctx); cam.lock(false); ringLive = null
            android.util.Log.w("BackLight", "ring search: " + tries.joinToString { "${it.type}/${it.pv}/${it.enc}/fx${it.fx}=${fmt(it.score)} r=${it.red.joinToString("/") { v -> fmt(v) }} b=${it.blue.joinToString("/") { v -> fmt(v) }}" })
            val seen = tries.filter { it.red.sum() > 0 || it.blue.sum() > 0 }
            if (seen.isEmpty()) {
                busy = null
                BackLight.multicolour = null; BackLight.saveMap(app.prefs)
                status = "Ring set-up: the camera saw no light from the ring at all. Hold the phone 8–10 cm above white paper (camera facing the paper), in a dim spot, and try again."
                return@launch
            }
            val best = seen.maxByOrNull { it.score }
            when {
                best == null -> { busy = null; status = "Ring set-up: the ring didn't light for any light type. Unplug the USB cable (charging light has priority) and check the ring light is on in Settings."; return@launch }
                best.score < 0.4 -> {
                    BackLight.type = best.type; BackLight.preview = best.pv; BackLight.encoding = best.enc; BackLight.effect = best.fx; BackLight.multicolour = false
                    BackLight.saveMap(app.prefs); busy = null
                    status = "Ring set-up: on this phone the ring shows one colour whatever we ask (camera saw ${listOf("red", "green", "blue")[best.red.indices.maxByOrNull { best.red[it] }!!]} for every colour). It's used as one extra light."
                    return@launch
                }
            }
            BackLight.type = best!!.type; BackLight.preview = best.pv; BackLight.encoding = best.enc; BackLight.effect = best.fx
            // Now the colour order on the winning combination.
            cam.lock(false); delay(500); cam.lock(true); delay(200)
            val resp = Array(3) { DoubleArray(3) }
            for (slot in 0..2) {
                if (!BackLight.setRaw(ctx, 0xFF000000.toInt() or (0xFF shl (16 - 8 * slot)))) break
                delay(450)
                val fr = grab(6, 4000) ?: break
                val w1 = Rgb.average(fr.map { it.first })
                for (c in 0..2) resp[slot][c] = ch(w1, c) - ch(w0, c)
                BackLight.off(ctx)
            }
            BackLight.off(ctx); cam.lock(false)
            status = "Ring set-up: colours change ✓ (type ${best.type}, format ${best.enc + 1}). " + BackLight.learn(resp).also { BackLight.saveMap(app.prefs) }
            busy = null
            for (c in listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())) { BackLight.set(ctx, c); ringLive = Color(c); delay(600) }
            BackLight.off(ctx); ringLive = null
        }
    }

    fun outcome(o: PurityOutcome): Outcome {
        val title = o.label?.title ?: o.decision.label
        val ev = mutableListOf<Evidence>()
        ev += Evidence("OBSERVATION", "Five-stage reading: " + o.features.filterKeys { !it.startsWith("ring") }.entries.joinToString(", ") { "${it.key} ${fmt(it.value)}" })
        o.ranked.ranked.take(3).forEach { ev += Evidence("PATTERN", "Matches ${labels.firstOrNull { l -> l.id == it.label }?.title ?: it.label}: ${(it.p * 100).toInt()}% (distance ${fmt(it.dist)}σ)", it.label == o.decision.label) }
        o.water?.let { ev += Evidence("PATTERN", "Estimated added water ${fmt(it.first)}% ± ${fmt(1.96 * it.second)}% (from your water / 50-50 / pure training samples)", it.first < 8) }
        qc.forEach { (t, ok) -> ev += Evidence("QUALITY", t, ok) }
        ev += Evidence("CALIBRATION", "Training: ${samples.size} samples across ${samples.map { it.label }.distinct().size} classes", samples.size >= 6)
        val lv = if (o.safe) Level.SAFE else Level.UNSAFE
        return Outcome("Shuddh Purity", "purity_${o.decision.label}", Txt(title), o.water?.first ?: o.decision.confidence, if (o.water != null) "% water" else "% sure", lv,
            "$title · ${o.decision.confidence.toInt()}% sure" + (o.water?.let { " · ≈${it.first.roundToInt()}% water" } ?: ""), (listOf(if (o.safe) "Matches a safe sample you trained." else "Matches an unsafe sample, or has added water.") + o.risks).map { Txt(it) }, ev,
            "Phone-sensor classification against your own training samples. Not a lab test.", levelLabel = Txt(if (o.safe) "SAFE" else "NOT SAFE"))
    }

    val classesTrained = samples.map { it.label }.distinct().size

    ScreenFrame("Purity", if (training) "🎓 Training — teach Shuddh your samples" else "What is it — and is it safe?", onBack = { if (training) training = false else { BackLight.off(ctx); app.back() } }) {
        if (training) {
            TrainingPage(app, labels, samples, busy, phase, ringLive, stage, liveRgb, sensors, status,
                onCapture = { trainCapture(it) },
                onDelete = { sm -> val list = samples.filter { it.id != sm.id }; sm.image?.let { runCatching { java.io.File(it).delete() } }
                    store = JSONObject(store.toString()).put("s", PurityTrain.save(list).getJSONArray("s")); app.prefs.putJson("purity_train", store) },
                onAddLabel = { title, safe ->
                    val arr = store.optJSONArray("custom") ?: JSONArray()
                    arr.put(JSONObject().put("id", "c_" + title.lowercase().replace(Regex("[^a-z0-9]+"), "_")).put("title", title).put("safe", safe))
                    store = JSONObject(store.toString()).put("custom", arr); app.prefs.putJson("purity_train", store)
                },
                onRing = { checkRing() },
                onDone = { training = false },
            )
        } else {
            val r = result
            Glass(glow = when { busy != null -> Palette.cyan; r != null -> if (r.safe) Palette.accent else Palette.red; else -> Palette.accent }, padding = 18) {
                when {
                    busy != null -> FiveStagePanel(phase, busy!!, ringLive, stage, liveRgb, sensors)
                    r != null -> ResultCard(r, labels)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (classesTrained >= 2) "Ready to test" else "Train once, then test anything", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                        Text(if (classesTrained >= 2) "$classesTrained kinds trained · ${samples.size} samples. Fill the cap, hold the phone 15 cm above, tap Run test."
                            else "Open Training and record water, pure milk, 50/50, fresh and spoiled milk, honey, juice — the phone learns what each looks like to its sensors.",
                            color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
                status?.takeIf { busy == null && r == null }?.let { Text(it, color = Palette.amber, fontSize = 13.sp, lineHeight = 18.sp) }
                if (busy == null) {
                    Btn(if (r == null) "▶  Run test" else "↻  Test again", Modifier.fillMaxWidth(), enabled = classesTrained >= 2) { runTest() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("🎓 Training (${samples.size})", Modifier.weight(1f), primary = classesTrained < 2) { training = true; status = null }
                        if (r != null) Btn("Full report", Modifier.weight(1f), primary = false) { app.show(outcome(r)) }
                    }
                }
            }
            result?.let { r -> if (busy == null) MatchCard(r, labels) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.6f, overlay = { roi(white, Color.White); roi(cap, Palette.tint(Color(0xFF7DD3FC))) }) { bmp ->
                val w = Frames.meanRgb(bmp, white); val sp = Frames.meanRgb(bmp, cap)
                for (i in 0..2) liveRgb[i] = ch(sp, i) / ch(w, i).coerceAtLeast(1.0)
                val t = capTexture(bmp, cap); synchronized(texBuf) { texBuf.addLast(t); while (texBuf.size > 24) texBuf.removeFirst() }
                if (wantSnap.getAndSet(false)) snap[0] = cropScaled(bmp, RectF(0.05f, 0.30f, 0.95f, 0.70f), 320)
                collector.offer(listOf(w to sp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                LegendDot(Color.White, "white paper"); LegendDot(Palette.tint(Color(0xFF7DD3FC)), "cap, filled to the brim")
            }
            AimHint(liveRgb)
        }
        if (!training) {
            Fold("🔁 Milk Watch — learns your milkman", Color(0xFFA78BFA)) {
                MilkWatchSection(app, busy == null, onScan = { label -> busy = "Daily scan: $label…"; val sh = shootUp(); busy = null; sh })
            }
            HowItWorks(listOf(
                "Five stages measure the sample: camera spectrum (room light + torch), the iQOO ring light in colours, a speaker→mic echo, and the magnetometer.",
                "Training stores those readings — and a photo — for samples you label: water, pure milk, 50/50, fresh and spoiled milk, honey, juice, or your own.",
                "A test is matched to the nearest trained kind (in units of each feature's own repeatability), and % water comes from your water / 50-50 / pure samples.",
            ))
        }
    }
}

/** Steps of the full test, in order — shown live while it runs. */
private enum class TestStep(val label: String, val icon: String, val sensor: String) {
    SPECTRUM("Spectrum", "🌈", "Camera + torch"), RING("Ring light", "💡", "iQOO RGB ring"), ECHO("Nami echo", "🔊", "Speaker + mic"), MAGNET("Magnet", "🧲", "Magnetometer"), VERDICT("Verdict", "⚖️", "Fusion")
}

/**
 * Five-stage live panel: each stage lights up in turn with its own live visual — spectrum bars from the
 * camera, the ring's colour (and what the camera saw), sonar waves, the magnetometer needle — then the verdict.
 */
@Composable
private fun FiveStagePanel(phase: TestStep?, hint: String, ring: Color?, info: StageInfo, liveRgb: DoubleArray, sensors: PhoneSensors) {
    val inf = rememberInfiniteTransition(label = "five")
    val pulse by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "p")
    val wave by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "w")
    // Tick to pull live values from the camera / sensor threads.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(120); tick++ } }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val idx = phase?.ordinal ?: -1
    val progress by animateFloatAsState(((idx + 0.5f) / TestStep.entries.size).coerceIn(0f, 1f), tween(700), label = "prog")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (hint.startsWith("Recording")) hint.removeSuffix("…") else "Measuring…", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Text("${(progress * 100).toInt()}%", color = Palette.cyan, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        // Overall progress bar with a moving shimmer.
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.veil(0x22))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Palette.accent, Palette.cyan, Color.White.copy(alpha = 0.6f + 0.4f * wave), Palette.cyan))))
        }
        TestStep.entries.forEach { st ->
            val done = idx > st.ordinal; val now = idx == st.ordinal
            val pop by animateFloatAsState(if (now) 1.12f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 300f), label = "pop${st.ordinal}")
            val alpha by animateFloatAsState(if (now || done) 1f else 0.45f, tween(400), label = "a${st.ordinal}")
            val tint = when { now && st == TestStep.RING && ring != null -> ring; now -> Palette.cyan; done -> Palette.accent; else -> Palette.veil(0x33) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(14.dp)).background(if (now) tint.copy(alpha = 0.10f) else Color.Transparent).padding(6.dp)) {
                Box(Modifier.size(38.dp).graphicsLayer { scaleX = pop; scaleY = pop }.clip(CircleShape).background(tint.copy(alpha = if (now) 0.18f + 0.2f * pulse else if (done) 0.18f else 0.05f))
                    .border(2.dp, tint.copy(alpha = if (now) pulse else 1f), CircleShape), contentAlignment = Alignment.Center) {
                    Text(if (done) "✓" else st.icon, fontSize = 16.sp, color = Palette.accent)
                }
                Column(Modifier.weight(1f)) {
                    Text("${st.ordinal + 1} · ${st.label}", color = if (now || done) Palette.text else Palette.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(st.sensor, color = Palette.muted, fontSize = 11.sp)
                }
                Box(Modifier.size(width = 112.dp, height = 34.dp), contentAlignment = Alignment.CenterEnd) {
                    when {
                        st == TestStep.SPECTRUM && (now || done) -> SpectrumBars(if (done) info.specFlash ?: info.specAmb ?: liveRgb.copyOf() else liveRgb.copyOf())
                        st == TestStep.RING && now -> Box(Modifier.size(30.dp).clip(CircleShape).background((ring ?: Color.Gray).copy(alpha = 0.4f + 0.6f * pulse)))
                        st == TestStep.RING && done -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            info.ring.forEach { (_, seen, ok) -> Box(Modifier.size(18.dp).clip(CircleShape).background(seen).border(2.dp, if (ok) Palette.accent else Palette.red, CircleShape)) }
                        }
                        st == TestStep.ECHO && now -> Canvas(Modifier.fillMaxSize()) {
                            for (k in 0..2) { val ph = (wave + k / 3f) % 1f; drawArc(Palette.cyan.copy(alpha = 1 - ph), -40f, 80f, false, Offset(size.width - size.height * (0.4f + ph), size.height * (0.5f - 0.5f * (0.4f + ph))), Size(size.height * (0.8f + 2 * ph), size.height * (0.4f + ph)), style = Stroke(3f)) }
                        }
                        st == TestStep.ECHO && done -> Text(info.echo?.let { "${it.third} chirps" } ?: "skipped", color = Palette.text, fontSize = 12.sp)
                        st == TestStep.MAGNET && (now || done) -> {
                            val b = sqrt(sensors.mag.sumOf { (it * it).toDouble() })
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Canvas(Modifier.size(28.dp)) {
                                    val ang = Math.toRadians((b % 60) * 6.0 - 90).toFloat()
                                    drawCircle(Palette.ink.copy(alpha = 0.25f), size.minDimension / 2, style = Stroke(2f))
                                    drawLine(Palette.red, center, Offset(center.x + kotlin.math.cos(ang) * size.width * 0.42f, center.y + kotlin.math.sin(ang) * size.width * 0.42f), 3f, cap = StrokeCap.Round)
                                }
                                Text("${fmt(info.mag ?: b)} µT", color = Palette.text, fontSize = 12.sp)
                            }
                        }
                        st == TestStep.VERDICT && now -> Text("combining…", color = Palette.cyan, fontSize = 12.sp)
                    }
                }
            }
        }
        if (phase == TestStep.RING) Note("Watch the back of the phone — the ring should glow red, green, blue, then white.", Palette.muted)
    }
}

/** Three bars: how much red, green and blue light the sample sends back relative to white paper. */
@Composable
private fun SpectrumBars(rgb: DoubleArray) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(32.dp)) {
        listOf(Color(0xFFF87171), Palette.tint(Color(0xFF4ADE80)), Color(0xFF60A5FA)).forEachIndexed { i, c ->
            val h by animateFloatAsState(rgb.getOrElse(i) { 0.0 }.toFloat().coerceIn(0.03f, 1.1f) / 1.1f, tween(200), label = "b$i")
            Box(Modifier.size(width = 14.dp, height = (32 * h).dp).clip(RoundedCornerShape(3.dp)).background(c))
        }
    }
}

/** Health effects of consuming this adulterated product. */
@Composable
private fun HealthRisks(items: List<String>, col: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = 0.10f)).border(1.dp, col.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("⚠  HEALTH RISKS IF CONSUMED", color = col, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
        items.forEach { Text("•  $it", color = Palette.text, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable
private fun LegendDot(c: Color, t: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(10.dp).border(2.dp, c, RoundedCornerShape(2.dp)))
    Text(t, color = Palette.muted, fontSize = 11.sp)
}

/** Optical fingerprint of one scan: room-light and torch lock-in reflectance in R, G, B (6 numbers). */
private fun fingerprint(s: Shot): DoubleArray? {
    val a = s.reads[Mode.AMB]?.first ?: return null
    val f = s.reads[Mode.FLASH]?.first ?: return null
    return doubleArrayOf(a[0], a[1], a[2], f[0], f[1], f[2])
}

private fun watchKey(v: String) = "watch_" + v.lowercase().replace(Regex("[^a-z0-9]+"), "_")

private fun loadScans(o: org.json.JSONObject?): List<Pair<com.shuddh.lab.core.MilkWatch.Scan, String>> = o?.optJSONArray("scans")?.let { a ->
    List(a.length()) { i -> a.getJSONObject(i).let { j -> com.shuddh.lab.core.MilkWatch.Scan(j.getLong("at"), j.getJSONArray("f").let { f -> DoubleArray(f.length()) { k -> f.getDouble(k) } }) to j.optString("call", "LEARN") } }
} ?: emptyList()

@Composable
private fun MilkWatchSection(app: AppState, enabled: Boolean, onScan: suspend (String) -> Shot?) {
    val scope = rememberCoroutineScope()
    val vendorsJ = app.prefs.json("watch_vendors")
    var vendors by remember { mutableStateOf(vendorsJ?.optJSONArray("v")?.let { a -> List(a.length()) { a.getString(it) } } ?: listOf("My milkman")) }
    var vendor by remember { mutableStateOf(vendors.first()) }
    var scans by remember(vendor) { mutableStateOf(loadScans(app.prefs.json(watchKey(vendor)))) }
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val accent = Color(0xFFA78BFA)

    // Baseline = scans judged normal (or still learning); today's verdict = the last scan vs the ones before it.
    val normalOnes = scans.filter { it.second == "NORMAL" || it.second == "LEARN" }.map { it.first }
    val last = scans.lastOrNull()
    val prior = if (last != null && (last.second == "NORMAL" || last.second == "LEARN")) normalOnes.dropLast(1) else normalOnes
    val base = com.shuddh.lab.core.MilkWatch.baseline(prior.takeLast(14))
    val today = if (last != null && base != null) com.shuddh.lab.core.MilkWatch.score(last.first, base) else null
    val judged = scans.filter { it.second != "LEARN" }.map { com.shuddh.lab.core.MilkWatch.Call.valueOf(it.second) }
    val trust = com.shuddh.lab.core.MilkWatch.trust(judged)
    val history = base?.let { b -> scans.map { com.shuddh.lab.core.MilkWatch.score(it.first, b) } } ?: emptyList()
    val drifting = com.shuddh.lab.core.MilkWatch.drift(history.takeLast(14))

    Glass(glow = accent, padding = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MILK WATCH", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Text("Learns your milkman's milk", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            trust?.let { Text("$it%", color = if (it >= 80) Palette.accent else if (it >= 50) Palette.amber else Palette.red, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 24.sp) }
        }
        Text("Scan the same cap of milk each day. After 3 days Shuddh knows this vendor's normal and flags any day that's different — and slow watering over weeks.", color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
        // Vendor chips
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            vendors.take(3).forEach { v ->
                val sel = v == vendor
                Text(v, color = if (sel) Palette.text else Palette.muted, fontSize = 12.sp, maxLines = 1, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) accent.copy(alpha = 0.25f) else Palette.veil(0x10)).border(1.dp, if (sel) accent else Palette.veil(0x22), RoundedCornerShape(50))
                        .clickable { vendor = v; note = null }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
            Text("+ vendor", color = accent, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { adding = !adding }.padding(horizontal = 10.dp, vertical = 7.dp))
        }
        if (adding) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedTextField(newName, { newName = it.take(24) }, label = { Text("Vendor / brand") }, singleLine = true, modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text, focusedBorderColor = accent, unfocusedBorderColor = Palette.veil(0x33), focusedLabelColor = accent, unfocusedLabelColor = Palette.muted))
            Btn("Add", enabled = newName.isNotBlank()) {
                vendors = (listOf(newName.trim()) + vendors.filter { it != newName.trim() }).take(6); vendor = newName.trim(); newName = ""; adding = false
                app.prefs.putJson("watch_vendors", org.json.JSONObject().put("v", org.json.JSONArray(vendors)))
            }
        }
        // Today's verdict
        when {
            scans.isEmpty() -> Note("No scans yet for $vendor.", Palette.muted)
            base == null -> Note("Learning $vendor's milk: ${normalOnes.size} of ${com.shuddh.lab.core.MilkWatch.MIN_BASELINE} days scanned.", Palette.cyan)
            today != null -> {
                val c = com.shuddh.lab.core.MilkWatch.call(today)
                val lv = when (c) { com.shuddh.lab.core.MilkWatch.Call.NORMAL -> Level.SAFE; com.shuddh.lab.core.MilkWatch.Call.UNUSUAL -> Level.CAUTION; else -> Level.UNSAFE }
                val conf = com.shuddh.lab.core.Dart.confidence(listOf(2.0, 3.5).minOf { abs(today.rms - it) }, 0.5)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(when (c) { com.shuddh.lab.core.MilkWatch.Call.NORMAL -> "NORMAL"; com.shuddh.lab.core.MilkWatch.Call.UNUSUAL -> "UNUSUAL"; else -> "CHANGED" }, color = Color(lv.argb), fontSize = 12.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(lv.argb).copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp))
                    Text(com.shuddh.lab.core.Dart.estimate(lv, conf), color = Color(lv.argb), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(if (c == com.shuddh.lab.core.MilkWatch.Call.NORMAL) "Today's milk matches $vendor's usual (${fmt(today.rms)}σ)." else "Today's milk is ${com.shuddh.lab.core.MilkWatch.meaning(today)} (${fmt(today.rms)}σ from $vendor's usual).", color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        if (drifting) Note("📉 Slow drift: $vendor's milk has been getting thinner over the last days — the kind of gradual watering a single test misses.", Palette.red)
        if (history.size >= 2) WatchChart(history.map { it.direction }, scans.map { it.second })
        note?.let { Text(it, color = Palette.cyan, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("📷  Scan today's milk", Modifier.weight(1f), enabled = enabled) {
                scope.launch {
                    val shot = onScan(vendor)
                    val f = shot?.let { fingerprint(it) }
                    if (f == null) { note = "Couldn't read both room-light and torch — keep the paper and cap in their boxes, in normal room light."; return@launch }
                    val sc = com.shuddh.lab.core.MilkWatch.Scan(System.currentTimeMillis(), f)
                    val b = com.shuddh.lab.core.MilkWatch.baseline(normalOnes.takeLast(14))
                    val call = b?.let { com.shuddh.lab.core.MilkWatch.call(com.shuddh.lab.core.MilkWatch.score(sc, it)).name } ?: "LEARN"
                    val o = app.prefs.json(watchKey(vendor)) ?: org.json.JSONObject()
                    val arr = o.optJSONArray("scans") ?: org.json.JSONArray()
                    arr.put(org.json.JSONObject().put("at", sc.at).put("f", org.json.JSONArray(f.toList())).put("call", call))
                    while (arr.length() > 60) arr.remove(0)
                    app.prefs.putJson(watchKey(vendor), o.put("scans", arr))
                    scans = loadScans(app.prefs.json(watchKey(vendor)))
                    Haptics.click(app.ctx)
                    note = if (call == "LEARN") "Saved. ${(com.shuddh.lab.core.MilkWatch.MIN_BASELINE - normalOnes.size - 1).coerceAtLeast(0)} more day(s) to learn $vendor." else null
                    app.voice.speak(when (call) { "NORMAL" -> "Normal milk for $vendor."; "UNUSUAL" -> "Unusual today."; "CHANGED" -> "Changed. This milk is different from usual."; else -> "Saved." }, app.lang)
                }
            }
            if (today != null && com.shuddh.lab.core.MilkWatch.call(today) != com.shuddh.lab.core.MilkWatch.Call.NORMAL)
                Btn("📣 Warn", Modifier.weight(0.6f), primary = false) { app.go(com.shuddh.lab.ui.Screen.COMMUNITY) }
        }
    }
}

/** Daily signed deviation with the vendor's normal band (±2σ) — a lab-style quality-control chart. */
@Composable
private fun WatchChart(dev: List<Double>, calls: List<String>) {
    val pts = dev.takeLast(21); val cs = calls.takeLast(21)
    Canvas(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(Palette.well(0x33))) {
        val lim = 5.0
        fun y(v: Double) = (size.height / 2 - (v.coerceIn(-lim, lim) / lim) * (size.height / 2 - 8)).toFloat()
        val band = (size.height / 2 - 8) * (2.0 / lim).toFloat()
        drawRect(Color(0xFF34D399).copy(alpha = 0.12f), Offset(0f, size.height / 2 - band), Size(size.width, band * 2))
        drawLine(Palette.ink.copy(alpha = 0.25f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f)
        val dx = size.width / (pts.size + 1)
        pts.forEachIndexed { i, v ->
            val c = when (cs.getOrNull(i)) { "CHANGED" -> Color(0xFFF43F5E); "UNUSUAL" -> Palette.tint(Color(0xFFFBBF24)); "LEARN" -> Color(0xFFA78BFA); else -> Color(0xFF34D399) }
            if (i > 0) drawLine(Color.White.copy(alpha = 0.3f), Offset(dx * i, y(pts[i - 1])), Offset(dx * (i + 1), y(v)), 2f)
            drawCircle(c, 7f, Offset(dx * (i + 1), y(v)))
        }
    }
    Text("Each dot = one day · green band = this vendor's normal · below = thinner", color = Palette.muted, fontSize = 10.sp)
}



// ── Training-based Purity: result, closest match, training page ──

private class PurityOutcome(
    val label: PurityTrain.Label?,
    val decision: PurityTrain.Decision,
    val ranked: PurityTrain.Result,
    /** % water and its 1-σ, for milk. */
    val water: Pair<Double, Double>?,
    val ai: PurityAi.Opinion?,
    val image: String?,
    val safe: Boolean,
    val risks: List<String>,
    val features: Map<String, Double>,
    /** "class" · "mix" (milk–water mix with a % range) · "unknown" (matches nothing) · "unsure" · "fixture" (scripted test data). */
    val mode: String = "class",
    /** Setup warning shown above the result (paper in the box, etc.) — never blocks it. */
    val warning: String? = null,
)

/** Developer test fixture: scripted results for runs 1–5, always shown with a TEST DATA badge. */
private fun fixture(n: Int, labels: List<PurityTrain.Label>, r: PurityTrain.Result, img: String?, f: Map<String, Double>): PurityOutcome {
    fun lb(id: String) = labels.first { it.id == id }
    val dec = { id: String -> PurityTrain.Decision(id, 90.0, null, false) }
    val milkRisks = Purity.healthEffects(Purity.Kind.MILK)
    return when (n) {
        1 -> PurityOutcome(lb("water"), dec("water"), r, 100.0 to 2.0, null, img, true, emptyList(), f, "fixture")
        2 -> PurityOutcome(lb("pure_milk"), dec("pure_milk"), r, 0.0 to 2.0, null, img, true, emptyList(), f, "fixture")
        3 -> PurityOutcome(PurityTrain.Label("mix", "Milk with 35–45% water", "🥛", 40.0, false, "milk"), dec("mix"), r, 40.0 to 2.5, null, img, false, milkRisks, f, "fixture")
        4 -> PurityOutcome(lb("spoiled_milk"), dec("spoiled_milk"), r, null, null, img, false,
            listOf("Spoiled milk can carry Salmonella, E. coli and Listeria — vomiting, diarrhoea and fever.", "Possible detergent: foam and texture look unusual — detergent irritates the gut and strains the liver and kidneys."), f, "fixture")
        else -> PurityOutcome(PurityTrain.Label("mix", "Milk with 65–75% water", "🥛", 70.0, false, "milk"), dec("mix"), r, 70.0 to 2.5, null, img, false, milkRisks, f, "fixture")
    }
}

/** Texture of the cap: luma coefficient of variation (curdled milk is lumpy). */
private fun capTexture(bmp: Bitmap, r: RectF): Double {
    val x0 = (r.left * bmp.width).toInt(); val y0 = (r.top * bmp.height).toInt()
    val w = ((r.right - r.left) * bmp.width).toInt().coerceAtLeast(2); val h = ((r.bottom - r.top) * bmp.height).toInt().coerceAtLeast(2)
    val px = IntArray(w * h); bmp.getPixels(px, 0, w, x0, y0, w, h)
    var s = 0.0; var s2 = 0.0; var n = 0
    for (i in px.indices step 2) { val c = px[i]; val l = 0.299 * ((c shr 16) and 0xff) + 0.587 * ((c shr 8) and 0xff) + 0.114 * (c and 0xff); s += l; s2 += l * l; n++ }
    val m = s / n; return if (m < 1) 0.0 else sqrt((s2 / n - m * m).coerceAtLeast(0.0)) / m
}

private fun cropScaled(bmp: Bitmap, r: RectF, maxW: Int): Bitmap? = runCatching {
    val x0 = (r.left * bmp.width).toInt(); val y0 = (r.top * bmp.height).toInt()
    val w = ((r.right - r.left) * bmp.width).toInt(); val h = ((r.bottom - r.top) * bmp.height).toInt()
    val c = Bitmap.createBitmap(bmp, x0, y0, w, h)
    Bitmap.createScaledBitmap(c, maxW, (h * maxW / w.toFloat()).toInt().coerceAtLeast(1), true)
}.getOrNull()

@Composable
private fun Thumb(path: String?, modifier: Modifier) {
    val img = remember(path) { path?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Palette.veil(0x22)), contentAlignment = Alignment.Center) {
        if (img != null) androidx.compose.foundation.Image(img, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Text("📷", fontSize = 16.sp)
    }
}

@Composable
private fun ResultCard(r: PurityOutcome, labels: List<PurityTrain.Label>) {
    r.warning?.let { Text("⚠ $it", color = Palette.amber, fontSize = 12.sp, lineHeight = 16.sp) }
    if (r.mode == "fixture") Text("🧪 TEST DATA — not a measurement", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFBBF24)).padding(horizontal = 10.dp, vertical = 6.dp))
    val col = if (r.safe) Palette.accent else Palette.red
    val conf by animateFloatAsState(r.decision.confidence.toFloat(), tween(1200), label = "conf")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val st = 12.dp.toPx(); val o = Offset(st / 2, st / 2); val sz = Size(size.width - st, size.height - st)
                drawArc(Palette.veil(0x22), 0f, 360f, false, o, sz, style = Stroke(st))
                drawArc(col, -90f, 360f * conf / 100f, false, o, sz, style = Stroke(st, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(r.label?.emoji ?: "❔", fontSize = 30.sp)
                Text("${conf.toInt()}%", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when { r.mode == "unknown" -> "UNKNOWN — DON'T CONSUME"; r.label == null -> "NOT SURE"; r.safe -> "SAFE"; else -> "NOT SAFE" }, color = if (r.mode == "unknown") Palette.red else if (r.label == null) Palette.amber else col, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp))
            Text(r.label?.title ?: if (r.mode == "unknown") "Doesn't match milk, water or anything trained" else "Doesn't match your training well", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp, lineHeight = 24.sp)
            if (r.label == null && r.mode != "unknown") Text("Closest: ${labels.firstOrNull { it.id == r.decision.label }?.title ?: r.decision.label}. Add 3 samples of each kind, and check the dark cap + white paper setup.", color = Palette.amber, fontSize = 12.sp, lineHeight = 16.sp)
            r.water?.let { (w, sd) ->
                val shown by animateFloatAsState(w.toFloat(), tween(1200), label = "w")
                val (lo, hi) = PurityTrain.range(w, sd)
                Text("$lo–$hi% water  (≈${shown.roundToInt()}%)", color = if (w >= 8) Palette.red else Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text("${r.decision.confidence.toInt()}% sure · ${r.ranked.features.size} sensor features", color = Palette.muted, fontSize = 12.sp)
        }
    }
    if (r.risks.isNotEmpty()) androidx.compose.animation.AnimatedVisibility(true, enter = androidx.compose.animation.fadeIn(tween(600, delayMillis = 700)) + androidx.compose.animation.expandVertically(tween(600, delayMillis = 700))) {
        HealthRisks(r.risks, col)
    }
}

/** Your sample next to the closest training sample, plus the top matches. */
@Composable
private fun MatchCard(r: PurityOutcome, labels: List<PurityTrain.Label>) {
    Glass(padding = 14) {
        Text("CLOSEST MATCH", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Thumb(r.image, Modifier.fillMaxWidth().height(90.dp)); Text("your sample", color = Palette.muted, fontSize = 11.sp)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Thumb(r.ranked.nearest?.image, Modifier.fillMaxWidth().height(90.dp))
                Text("trained: ${labels.firstOrNull { it.id == r.ranked.nearest?.label }?.title ?: "—"}", color = Palette.muted, fontSize = 11.sp, maxLines = 1)
            }
        }
        r.ranked.ranked.take(3).forEach { rk ->
            val lb = labels.firstOrNull { it.id == rk.label }
            val p by animateFloatAsState(rk.p.toFloat(), tween(900), label = "rk${rk.label}")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${lb?.emoji ?: "🏷️"} ${lb?.title ?: rk.label}", color = Palette.text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1.3f))
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.veil(0x22))) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).fillMaxHeight().background(if (lb?.safe == false) Palette.red else Palette.cyan))
                }
                Text("${(rk.p * 100).toInt()}%", color = Palette.text, fontSize = 12.sp, modifier = Modifier.width(36.dp))
            }
        }
    }
}

@Composable
private fun TrainingPage(
    app: AppState, labels: List<PurityTrain.Label>, samples: List<PurityTrain.Sample>, busy: String?, phase: TestStep?, ring: Color?,
    stage: StageInfo, liveRgb: DoubleArray, sensors: PhoneSensors, status: String?,
    onCapture: (PurityTrain.Label) -> Unit, onDelete: (PurityTrain.Sample) -> Unit, onAddLabel: (String, Boolean) -> Unit, onRing: () -> Unit, onDone: () -> Unit,
) {
    Glass(glow = if (busy != null) Palette.cyan else Color(0xFFA78BFA), padding = 18) {
        if (busy != null) FiveStagePanel(phase, busy, ring, stage, liveRgb, sensors)
        else {
            Text("Teach Shuddh your samples", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text("Use a DARK cap (black, blue or red) filled to the brim, on WHITE paper in the white box. Hold the phone 15 cm above, tap a kind. Record 3 of each kind, re-pouring each time.", color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
            status?.let { Text(it, color = if (it.startsWith("Not saved") || it.startsWith("Saved, but")) Palette.amber else Palette.accent, fontSize = 13.sp, lineHeight = 18.sp) }
            PurityTrain.separation(samples, "water", "pure_milk")?.let { sep ->
                if (sep < 3) Text("⚠ Water and pure milk look almost the same to the camera (${fmt(sep)}σ apart). Use a DARK cap (black/blue/red) on WHITE paper — water must look dark, milk white. Then re-record both.", color = Palette.amber, fontSize = 12.sp, lineHeight = 16.sp)
                else Text("✓ Water and milk are clearly different to the camera (${fmt(sep)}σ apart).", color = Palette.accent, fontSize = 12.sp)
            }
            val few = labels.filter { lb -> samples.count { it.label == lb.id } in 1..2 }
            if (few.isNotEmpty()) Text("Add more samples for: ${few.joinToString { it.title }} (3 each recommended).", color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
            Btn("✓ Done — back to test", Modifier.fillMaxWidth(), primary = samples.map { it.label }.distinct().size >= 2) { onDone() }
        }
    }
    labels.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { lb ->
                val mine = samples.filter { it.label == lb.id }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Palette.veil(0x0F))
                    .border(1.5.dp, if (mine.isNotEmpty()) Palette.accent.copy(alpha = 0.7f) else Palette.veil(0x22), RoundedCornerShape(18.dp))
                    .clickable(enabled = busy == null) { onCapture(lb) }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(lb.emoji, fontSize = 22.sp); Spacer(Modifier.width(8.dp))
                        Text(lb.title, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                    }
                    Text(if (mine.isEmpty()) "Tap to record" else "${mine.size} sample${if (mine.size > 1) "s" else ""} · tap to add", color = if (mine.isEmpty()) Palette.cyan else Palette.accent, fontSize = 11.sp)
                    if (mine.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        mine.takeLast(3).forEach { sm ->
                            Box(Modifier.weight(1f)) {
                                Thumb(sm.image, Modifier.fillMaxWidth().height(40.dp))
                                Text("✕", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Color(0xAA000000)).clickable(enabled = busy == null) { onDelete(sm) }.padding(horizontal = 5.dp))
                            }
                        }
                    }
                    val bad = mine.count { PurityTrain.captureProblem(it.f) != null }
                    if (bad > 0) Text("⚠ $bad capture${if (bad > 1) "s" else ""} may have a setup issue", color = Palette.amber, fontSize = 10.sp, lineHeight = 13.sp)
                    Text(if (lb.safe) "safe" else "not safe", color = if (lb.safe) Palette.muted else Palette.red, fontSize = 10.sp)
                }
            }
            if (row.size == 1) Box(Modifier.weight(1f))
        }
    }
    // Custom kind
    var title by remember { mutableStateOf("") }
    var safe by remember { mutableStateOf(true) }
    Glass(padding = 14) {
        Text("ADD YOUR OWN KIND", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        androidx.compose.material3.OutlinedTextField(title, { title = it.take(28) }, label = { Text("e.g. Honey + sugar syrup") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text, focusedBorderColor = Color(0xFFA78BFA), unfocusedBorderColor = Palette.veil(0x33), focusedLabelColor = Color(0xFFA78BFA), unfocusedLabelColor = Palette.muted))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (safe) "✓ Safe to consume" else "✕ Not safe", Modifier.weight(1f), primary = false) { safe = !safe }
            Btn("Add kind", Modifier.weight(1f), enabled = title.isNotBlank()) { onAddLabel(title.trim(), safe); title = "" }
        }
    }
    Btn("💡 Set up iQOO ring light (hold over white paper)", Modifier.fillMaxWidth(), primary = false, enabled = busy == null) { onRing() }
}


/** Live aiming check: is the blue box on the cap, or on the paper? */
@Composable
private fun AimHint(liveRgb: DoubleArray) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(300); tick++ } }
    @Suppress("UNUSED_VARIABLE") val t = tick
    val v = liveRgb.copyOf()
    val onPaper = v.all { it in 0.90..1.10 } && (v.max() - v.min()) < 0.12
    val brighter = v.average() > 1.08
    val (msg, col) = when {
        onPaper -> "✗ The blue box sees the paper — move so the cap fills it" to Palette.red
        brighter -> "✗ Cap looks brighter than the paper — white paper in the white box, dark cap" to Palette.amber
        v.average() < 0.02 -> "… aim at the cap on white paper" to Palette.muted
        else -> "✓ Blue box is on the cap" to Palette.accent
    }
    Text(msg, color = col, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}
