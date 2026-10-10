package com.shuddh.lab.instruments

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
    var kind by remember { mutableStateOf(Kind.MILK) }
    // Milk type: each type gets its own calibration (toned milk is legitimately "thinner" than full cream).
    var milkType by remember { mutableStateOf(MilkType.entries.firstOrNull { it.name == app.prefs.json("milk_type")?.optString("t") } ?: MilkType.STANDARDISED) }
    var milkTest by remember { mutableStateOf("water") }
    val key = "purity_${kind.name.lowercase()}" + if (kind == Kind.MILK && milkType.key.isNotEmpty()) "_${milkType.key}" else ""
    var refs by remember(kind, milkType) { mutableStateOf(app.prefs.json(key) ?: JSONObject()) }
    var busy by remember { mutableStateOf<String?>(null) }
    /** Estimates for the sample currently under test — accumulates across both poses until "New sample". */
    val sample = remember(kind) { mutableStateMapOf<String, Fusion.Estimate>() }
    var ringLive by remember { mutableStateOf<Color?>(null) }
    /** Which sensor the running capture is on — drives the live progress panel. */
    var phase by remember { mutableStateOf<TestStep?>(null) }
    var stage by remember { mutableStateOf(StageInfo()) }
    /** Latest cap ÷ paper ratio per channel from the camera thread — drives the live spectrum bars. */
    val liveRgb = remember { DoubleArray(3) }
    val qc = remember(kind) { mutableStateMapOf<String, Boolean>() }
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

    val points = remember(refs) { calPoints(refs) }
    /** Per light source: the best colour channel and its ladder curve (leave-one-level-out picks the channel). */
    val ladders = remember(points) {
        Mode.entries.associateWith { m ->
            Ladder.best((0..2).map { ch -> points.mapNotNull { p -> p.reads[m.id]?.let { p.lvl to it[ch] } } })?.takeIf { it.second.looMae.isNaN() || it.second.looMae < 20 }
        }
    }
    val echoLadder = remember(points) {
        Ladder.best((0..1).map { k -> points.mapNotNull { p -> p.echo?.let { p.lvl to it[k] } } })?.takeIf { it.second.looMae.isNaN() || it.second.looMae < 30 }
    }
    val magLadder = remember(points) { Ladder.fit(points.mapNotNull { p -> p.mag?.let { p.lvl to it } })?.takeIf { it.looMae.isNaN() || it.looMae < 30 } }
    val levelCount = points.groupingBy { it.lvl.toInt() }.eachCount()
    val calibrated = Mode.entries.filter { ladders[it] != null }

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


    fun record(level: Int) {
        if (busy != null) return
        busy = "Recording ${level}% ${kind.adulterant}…"
        scope.launch {
            val s = shootUp()
            busy = null
            if (s == null || (s.reads.isEmpty() && s.mag == null)) { status = "Couldn't read — keep steady with card and cap in the boxes. ${s?.notes?.filter { !it.second }?.joinToString { it.first } ?: ""}"; return@launch }
            val o = JSONObject(refs.toString())
            val pt = JSONObject().put("lvl", level)
            s.reads.forEach { (m, r) -> pt.put(m.id, arr(r.first.rgb)) }
            s.mag?.let { pt.put("mag", it.first) }
            s.echo?.let { pt.put("echo", JSONArray(listOf(it.first, it.second))) }
            o.put("pts", (o.optJSONArray("pts") ?: JSONArray()).put(pt))
            refs = o; app.prefs.putJson(key, o); sample.clear()
            Haptics.click(ctx)
            status = "${level}% recorded (${s.reads.size + (if (s.mag != null) 1 else 0) + (if (s.echo != null) 1 else 0)} readings)."
        }
    }

    fun test() {
        if (busy != null) return
        busy = "Measuring…"
        scope.launch {
            val s = shootUp()
            busy = null
            if (s == null) { status = "Couldn't read — try again."; return@launch }
            s.notes.forEach { (t, ok) -> qc[t] = ok }
            for ((m, rd) in s.reads) {
                val (ch, md) = ladders[m] ?: continue
                val x = rd.first[ch]
                // σ from this reading's noise through the curve's local slope, plus the curve's own leave-one-out error.
                sample[m.id] = Fusion.Estimate(m.sensor, md.waterPct(x), md.sigmaPct(x, rd.second[ch]))
            }
            var magEst: Fusion.Estimate? = null
            // Magnetometer: |B| calibrated by the three references. Its noise floor (1 µT) is what re-placing a
            // phone typically changes the reading by, so its weight reflects how repeatable it really is.
            s.mag?.let { (b, sd) ->
                val ml = magLadder
                // Milk and water are almost identical magnetically: never tighter than ±15 % water.
                if (ml != null) magEst = Fusion.Estimate("Magnetometer", ml.waterPct(b), ml.sigmaPct(b, sqrt(sd * sd / 50 + 1.0)).coerceAtLeast(15.0))
                else if (points.count { it.mag != null } >= 3) qc["Magnetometer: no consistent trend across your mixes — excluded"] = false
            }
            magEst?.let { sample["mag"] = it }
            s.echo?.let { e ->
                val el = echoLadder
                if (el != null) { val x = if (el.first == 0) e.first else e.second; sample["echo"] = Fusion.Estimate("Nami echo", el.second.waterPct(x), el.second.sigmaPct(x, 0.5).coerceAtLeast(15.0)) }
                else if (points.count { it.echo != null } >= 3) qc["Nami echo: no consistent trend across your mixes — excluded"] = false
            }
            if (sample.isEmpty()) { status = "Record at least 3 mixes (e.g. 0 %, 50 %, 100 %) first."; return@launch }
            // The camera stages carry the information; echo and magnetometer may only refine them.
            if (sample.keys.none { it in Mode.entries.map { m -> m.id } }) {
                sample.clear()
                status = "The camera couldn't read the sample (too dark, or the paper and cap aren't in their boxes), so there's no verdict. Echo and magnetometer alone aren't reliable enough."
                return@launch
            }
            status = null
            android.util.Log.w("Purity", "${kind.name} estimates: " + sample.values.joinToString { "${it.sensor}=${fmt(it.value)}±${fmt(it.sigma)}" })
            val f = fuse(sample.values.toList()) ?: return@launch
            android.util.Log.w("Purity", "fused ${fmt(f.value)}±${fmt(f.sigma)} using ${f.parts.joinToString { it.sensor }}")
            val v = Purity.verdict(kind, f.value)
            // Feel the verdict: a light ping when fine, a thud when borderline, a long strong rumble when adulterated.
            when (v.level) { Level.SAFE -> Haptics.ping(ctx); Level.CAUTION -> Haptics.thud(ctx); else -> Haptics.rumble(ctx, 1f, 900) }
            app.voice.say("${v.label}. About ${f.value.toInt()} percent ${kind.adulterant}." + if (v.level != Level.SAFE) " " + Purity.healthEffects(kind).first() else "", app.lang)
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
            data class Try(val type: Int, val pv: Boolean, val enc: Int, val score: Double, val red: DoubleArray, val blue: DoubleArray)
            val tries = mutableListOf<Try>()
            val combos = BackLight.candidates.flatMap { (t, pv) -> (0..2).map { e -> Triple(t, pv, e) } }
            for ((i, c) in combos.withIndex()) {
                val (t, pv, e) = c
                busy = "Ring set-up ${i + 1}/${combos.size}: type $t${if (pv) " (preview)" else ""}, format ${e + 1}"
                if (!BackLight.setRaw(ctx, 0xFFFF0000.toInt(), type = t, preview = pv, enc = e)) continue
                ringLive = Color.Red; delay(450); val r = grab(5, 3000); BackLight.off(ctx)
                if (!BackLight.setRaw(ctx, 0xFF0000FF.toInt(), type = t, preview = pv, enc = e)) continue
                ringLive = Color.Blue; delay(450); val bl = grab(5, 3000); BackLight.off(ctx); ringLive = null
                if (r == null || bl == null) continue
                val sr = shares(r); val sb = shares(bl)
                // Good = red request lights mostly red AND blue request lights mostly blue.
                tries += Try(t, pv, e, sr[0] + sb[2] - sr[2] - sb[0], sr, sb)
                delay(150)
            }
            BackLight.off(ctx); cam.lock(false); ringLive = null
            android.util.Log.w("BackLight", "ring search: " + tries.joinToString { "${it.type}/${it.pv}/${it.enc}=${fmt(it.score)} r=${it.red.joinToString("/") { v -> fmt(v) }} b=${it.blue.joinToString("/") { v -> fmt(v) }}" })
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
                    BackLight.type = best.type; BackLight.preview = best.pv; BackLight.encoding = best.enc; BackLight.multicolour = false
                    BackLight.saveMap(app.prefs); busy = null
                    status = "Ring set-up: on this phone the ring shows one colour whatever we ask (camera saw ${listOf("red", "green", "blue")[best.red.indices.maxByOrNull { best.red[it] }!!]} for every colour). It's used as one extra light."
                    return@launch
                }
            }
            BackLight.type = best!!.type; BackLight.preview = best.pv; BackLight.encoding = best.enc
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

    val fused = fuse(sample.values.toList())

    fun outcome(f: Fusion.Fused): Outcome {
        val v = Purity.verdict(kind, f.value)
        val product = kind.label
        val ev = mutableListOf<Evidence>()
        f.parts.forEachIndexed { i, p -> ev += Evidence("OBSERVATION", "${p.sensor}: ${fmt(p.value)}% ± ${fmt(p.sigma)}% (weight ${(f.weights[i] * 100).toInt()}%)") }
        ev += Evidence("QUALITY", "${f.parts.size} sensor channels agree ${(f.agreement * 100).toInt()}% (χ² test)" + (f.outlier?.let { " — $it disagrees" } ?: ""), f.agreement >= 0.6)
        qc.forEach { (t, ok) -> ev += Evidence("QUALITY", t, ok) }
        ev += Evidence("CALIBRATION", "Your own references: ${kind.adulterant}, 50/50 mix, pure ${product.lowercase()} — same cap, same paper", true)
        ev += Evidence("PATTERN", when (kind.physics) { Purity.Physics.SCATTER -> "Kubelka–Munk scattering model fitted per light channel"; Purity.Physics.ABSORB -> "Beer–Lambert absorbance fitted per light channel"; else -> "Three-point calibration curve per sensor" })
        ev += Evidence("HYPOTHESIS", "${v.label}: ${fmt(f.value)}% ± ${fmt(1.96 * f.sigma)}% ${kind.adulterant}", v.level == Level.SAFE)
        val level = if (f.parts.size > 1 && f.agreement < 0.35) Level.INCONCLUSIVE else v.level
        return Outcome("Shuddh Purity", "${kind.name.lowercase()}_${if (kind.adulterant == "water") "water" else "adulterant"}", Txt("$product: added ${kind.adulterant}", "मिलावट: ${kind.label}"),
            f.value, "%", level, "$product: ${f.value.toInt()}% ± ${(1.96 * f.sigma).toInt()}% ${kind.adulterant} (${f.parts.size} sensors)", (listOf(v.advice) + if (v.level != Level.SAFE) Purity.healthEffects(kind) else emptyList()).map { Txt(it) }, ev,
            "Relative to your own pure sample and your own ${kind.adulterant} reference. Detects dilution/mixing; does not identify unknown chemical adulterants.",
            levelLabel = Txt(v.label.uppercase()))
    }

    val ready = calibrated.map { it.sensor } + listOfNotNull(if (magLadder != null) "Magnetometer" else null, if (echoLadder != null) "Nami echo" else null)
    val bestLoo = (calibrated.mapNotNull { ladders[it]?.second?.looMae } + listOfNotNull(magLadder?.looMae)).filter { !it.isNaN() }.minOrNull()
    val canTest = ready.isNotEmpty()
    val nextLevel = listOf(0, 100, 50, 20, 80, 10, 30, 40, 60, 70, 90).firstOrNull { it !in levelCount }
    val recordingLevel = busy?.takeIf { it.startsWith("Recording ") }?.removePrefix("Recording ")?.substringBefore("%")?.toIntOrNull()
    val steps = TestStep.entries.toList()

    ScreenFrame("Purity", "How much of your ${kind.label.lowercase()} is real?", onBack = { BackLight.off(ctx); app.back() }) {
        LiquidPicker(kind) { if (busy == null) kind = it }
        if (kind == Kind.MILK) {
            MilkTypeChips(milkType, enabled = busy == null) { milkType = it; sample.clear(); qc.clear(); app.prefs.putJson("milk_type", JSONObject().put("t", it.name)) }
            TestSwitch(milkTest, enabled = busy == null) { milkTest = it }
            if (milkTest == "spoiled") { SpoilagePanel(app, milkType); return@ScreenFrame }
            if (milkTest == "detergent") { DetergentPanel(app, milkType); return@ScreenFrame }
        }

        // ── Hero: what to do now, live progress, or the result ──
        val f = fused
        val glow = when { busy != null -> Palette.cyan; f != null -> Color(Purity.verdict(kind, f.value).level.argb); else -> Palette.accent }
        Glass(glow = glow, padding = 18) {
            when {
                busy != null -> FiveStagePanel(phase, busy!!, ringLive, stage, liveRgb, sensors)
                f != null -> ResultHero(kind, f)
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PurityCup(kind, null, false, Modifier.size(width = 84.dp, height = 108.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (canTest) "Ready to test" else "Calibrate once", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                        Text(
                            if (canTest) "Fill the cap with the sample, hold the phone over it, tap Run test."
                            else "Record known mixes on the ladder below — at least 0 %, 50 % and 100 % ${kind.adulterant}; more mixes = more precise.",
                            color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                    }
                }
            }
            if (busy == null) {
                Btn(if (f == null) "▶  Run test" else "↻  Test again", Modifier.fillMaxWidth(), enabled = canTest) { test() }
                if (f != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("New sample", Modifier.weight(1f), primary = false) { sample.clear(); qc.clear() }
                    Btn("Full report", Modifier.weight(1f), primary = false) { app.show(outcome(f)) }
                }
            }
        }
        if (f != null && busy == null) {
            val ringCal = listOf(Mode.RING_R, Mode.RING_G, Mode.RING_B, Mode.RING_W).any { ladders[it] != null }
            val why = mapOf(
                TestStep.SPECTRUM to when {
                    stage.specAmb == null && stage.specFlash == null -> "camera couldn't see the sample — too dark, or paper/cap outside the boxes"
                    ladders[Mode.AMB] == null && ladders[Mode.FLASH] == null -> "not calibrated — record ladder mixes"
                    else -> "reading didn't fit the calibration"
                },
                TestStep.RING to when {
                    stage.ring.isEmpty() -> "ring didn't light — open Setup → Set up iQOO ring light"
                    stage.ring.none { it.third } -> "colours didn't change — run Setup → Set up iQOO ring light"
                    !ringCal -> "not calibrated yet — re-record your ladder mixes once"
                    else -> "reading didn't fit the calibration"
                },
                TestStep.ECHO to when {
                    stage.echo == null -> "didn't run — microphone busy or no permission"
                    echoLadder == null -> "not calibrated yet — re-record your ladder mixes once (echo is stored per mix)"
                    else -> "reading didn't fit the calibration"
                },
                TestStep.MAGNET to if (magLadder == null) "no consistent trend across your mixes" else "reading didn't fit the calibration",
            )
            StageSummary(stage, sample.toMap(), why)
        }
        if (f != null) Fold("Sensor breakdown · ${f.parts.size} sensors, agree ${(f.agreement * 100).toInt()}%", Palette.cyan) {
            FusionBars(f, 100.0) { v, sg -> "${v.toInt()}±${sg.toInt()}%" }
            qc.forEach { (t, ok) -> Text((if (ok) "✓  " else "⚠  ") + t, color = if (ok) Palette.muted else Palette.amber, fontSize = 12.sp, lineHeight = 16.sp) }
        }

        // ── Milk Watch: learns each vendor's milk from daily scans ──
        MilkWatchSection(app, busy == null, onScan = { label ->
            busy = "Daily scan: $label…"
            val shot = shootUp()
            busy = null
            shot
        })

        // ── Camera: compact, with a two-item legend ──
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.6f, overlay = {
                roi(white, Color.White); roi(cap, capColor(kind))
            }) { bmp ->
                val w = Frames.meanRgb(bmp, white); val sp = Frames.meanRgb(bmp, cap)
                for (i in 0..2) liveRgb[i] = ch(sp, i) / ch(w, i).coerceAtLeast(1.0)
                collector.offer(listOf(w to sp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                LegendDot(Color.White, "white paper"); LegendDot(capColor(kind), if (kind.darkCap) "dark cap, brim-full" else "white cap, brim-full")
            }
        }

        // ── Calibration ladder: known mixes from pure (0 %) to all water (100 %) ──
        Row(verticalAlignment = Alignment.Bottom) {
            Text("CALIBRATION LADDER", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
            Text("${levelCount.size} of 11 mixes", color = Palette.muted, fontSize = 11.sp)
        }
        Text("Mix 10 spoons total. More mixes = a more precise curve; tap a mix again to add a repeat.", color = Palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
        (0..100 step 10).toList().chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { lvl ->
                    LadderChip(kind, lvl, levelCount[lvl] ?: 0, next = lvl == nextLevel, recording = recordingLevel == lvl,
                        progress = if (recordingLevel == lvl) (steps.indexOf(phase).coerceAtLeast(0) + 0.5f) / steps.size else 0f,
                        enabled = busy == null, modifier = Modifier.weight(1f)) { record(lvl) }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (busy != null && recordingLevel != null) Note("Recording the ${recordingLevel}% mix (${Ladder.recipe(recordingLevel)}) — hold still over the cap…", Palette.cyan)
        status?.takeIf { f == null && busy == null }?.let { Note(it, if (it.contains("recorded")) Palette.accent else Palette.amber) }
        when {
            canTest -> Note("Calibrated from ${levelCount.size} mixes" + (bestLoo?.let { " · accuracy ±${fmt(it)}% ${kind.adulterant} (each mix predicted from the others)" } ?: " · add a 4th mix to measure accuracy"), Palette.accent)
            levelCount.size >= 3 -> Note("These mixes look too alike — use a ${if (kind.darkCap) "darker" else "white"} cap, fill to the brim, and re-record.", Palette.amber)
        }
        val bestMode = calibrated.minByOrNull { ladders[it]!!.second.looMae.takeIf { l -> !l.isNaN() } ?: 99.0 }
        if (bestMode != null) {
            val (ch, md) = ladders[bestMode]!!
            CalCurve(points.mapNotNull { p -> p.reads[bestMode.id]?.let { p.lvl to it[ch] } }, md, capColor(kind), "${bestMode.sensor} · ${listOf("red", "green", "blue")[ch]} channel")
        }

        Fold("Setup & ring light", Palette.violet) {
            Note("1. ${if (kind.darkCap) "Dark" else "White"} bottle cap, filled to the brim, on white paper. Same cap every time; rinse between samples.")
            Note("2. Hold the phone flat ~15 cm above — paper in the white box, cap in the coloured box.")
            if (ready.isNotEmpty()) Note("Calibrated sensors: ${ready.joinToString()}", Palette.accent)
            Btn(if (BackLight.multicolour == null) "💡 Set up iQOO ring light" else "💡 Re-check ring light", Modifier.fillMaxWidth(), primary = false, enabled = busy == null) { checkRing() }
            if (points.isNotEmpty()) Btn("Reset calibration", Modifier.fillMaxWidth(), primary = false, enabled = busy == null) { refs = JSONObject(); app.prefs.putJson(key, null); sample.clear(); qc.clear(); status = null }
        }
        HowItWorks(listOf(
            when (kind.physics) {
                Purity.Physics.SCATTER -> "Milk is white because fat and protein particles scatter light; water scatters nothing — so watered milk reflects less."
                Purity.Physics.ABSORB -> "Honey's amber colour absorbs blue light; water and syrup are colourless — so diluted honey looks lighter."
                else -> "Mixing in ${kind.adulterant} changes how the liquid reflects and absorbs light — each sensor learns that change from your 3 samples."
            },
            "Every reading divides the cap by the white paper in the same frame, so lighting and exposure cancel.",
            "Lights: room light, the torch, and (once set up) the iQOO ring light — each measured ON minus OFF (lock-in).",
            "Each sensor gives its own estimate and error; they're combined by inverse-variance weighting, and sensors that disagree are dropped.",
            "Relative to your own pure sample — it measures how much was mixed in. For chemical adulterants use the DART tests.",
        ))
    }
}

/** Inverse-variance fusion plus a 1.5 % floor shared by all sensors (same cap fill, same mixing — correlated, so it doesn't average away). */
private fun fuse(parts0: List<Fusion.Estimate>): Fusion.Fused? {
    // Reject any sensor more than 3σ from the others (robust: compared to the fit without it), then combine.
    var parts = parts0
    while (parts.size > 2) {
        val worst = parts.maxByOrNull { p -> Fusion.combine(parts - p)?.let { o -> abs(p.value - o.value) / sqrt(p.sigma * p.sigma + o.sigma * o.sigma) } ?: 0.0 } ?: break
        val o = Fusion.combine(parts - worst) ?: break
        if (abs(worst.value - o.value) / sqrt(worst.sigma * worst.sigma + o.sigma * o.sigma) <= 3) break
        parts = parts - worst
    }
    return Fusion.combine(parts)?.let { it.copy(value = it.value.coerceIn(0.0, 100.0), sigma = sqrt(it.sigma * it.sigma + 1.5 * 1.5)) }
}

private fun label(kind: Kind, n: String) = when (n) { "water" -> if (kind.adulterant == "water") "Plain water" else kind.adulterant.replaceFirstChar { it.uppercase() }; "half" -> "50/50 mix"; else -> "Pure ${kind.label.lowercase()}" }


/** Steps of the full test, in order — shown live while it runs. */
private enum class TestStep(val label: String, val icon: String, val sensor: String) {
    SPECTRUM("Spectrum", "🌈", "Camera + torch"), RING("Ring light", "💡", "iQOO RGB ring"), ECHO("Nami echo", "🔊", "Speaker + mic"), MAGNET("Magnet", "🧲", "Magnetometer"), VERDICT("Verdict", "⚖️", "Fusion")
}

private fun capColor(kind: Kind) = when (kind) {
    Kind.MILK -> Color(0xFF7DD3FC); Kind.HONEY -> Color(0xFFFBBF24); Kind.OIL -> Color(0xFFFDE047); Kind.JUICE -> Color(0xFFF87171); Kind.OTHER -> Color(0xFF5EEAD4)
}

/** Liquid picker: a row of rounded pills; the selected one fills with the liquid's colour. */
@Composable
private fun LiquidPicker(kind: Kind, onPick: (Kind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Kind.entries.forEach { k ->
            val sel = k == kind
            val bg by animateColorAsState(if (sel) capColor(k).copy(alpha = 0.22f) else Color(0x10FFFFFF), tween(300), label = "bg")
            val border by animateColorAsState(if (sel) capColor(k) else Color(0x22FFFFFF), tween(300), label = "bd")
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(bg)
                    .border(1.5.dp, border, RoundedCornerShape(16.dp)).clickable { onPick(k) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(k.emoji, fontSize = if (sel) 24.sp else 20.sp)
                Text(k.label, color = if (sel) Palette.text else Palette.muted, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
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
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Palette.accent, Palette.cyan, Color.White.copy(alpha = 0.6f + 0.4f * wave), Palette.cyan))))
        }
        TestStep.entries.forEach { st ->
            val done = idx > st.ordinal; val now = idx == st.ordinal
            val pop by animateFloatAsState(if (now) 1.12f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 300f), label = "pop${st.ordinal}")
            val alpha by animateFloatAsState(if (now || done) 1f else 0.45f, tween(400), label = "a${st.ordinal}")
            val tint = when { now && st == TestStep.RING && ring != null -> ring; now -> Palette.cyan; done -> Palette.accent; else -> Color(0x33FFFFFF) }
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
                                    drawCircle(Color.White.copy(alpha = 0.25f), size.minDimension / 2, style = Stroke(2f))
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
        listOf(Color(0xFFF87171), Color(0xFF4ADE80), Color(0xFF60A5FA)).forEachIndexed { i, c ->
            val h by animateFloatAsState(rgb.getOrElse(i) { 0.0 }.toFloat().coerceIn(0.03f, 1.1f) / 1.1f, tween(200), label = "b$i")
            Box(Modifier.size(width = 14.dp, height = (32 * h).dp).clip(RoundedCornerShape(3.dp)).background(c))
        }
    }
}

/** After a test: what each of the five stages measured and the water % it estimated on its own. */
@Composable
private fun StageSummary(info: StageInfo, est: Map<String, Fusion.Estimate>, why: Map<TestStep, String>) {
    fun e(vararg ids: String) = ids.mapNotNull { est[it] }
    Glass(padding = 14) {
        Text("FIVE STAGES", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        @Composable fun row(st: TestStep, reading: String, ests: List<Fusion.Estimate>, extra: (@Composable () -> Unit)? = null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(st.icon, fontSize = 18.sp)
                Column(Modifier.weight(1f)) {
                    Text("${st.ordinal + 1} · ${st.label}", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(reading, color = Palette.muted, fontSize = 11.sp, lineHeight = 14.sp)
                    if (ests.isEmpty()) why[st]?.let { Text("Excluded: $it", color = Palette.amber, fontSize = 11.sp, lineHeight = 14.sp) }
                }
                extra?.invoke()
                Text(if (ests.isEmpty()) "excluded" else ests.joinToString(" / ") { "${it.value.toInt()}%" }, color = if (ests.isEmpty()) Palette.muted else Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        row(TestStep.SPECTRUM, info.specFlash?.let { "torch R ${fmt(it[0])} G ${fmt(it[1])} B ${fmt(it[2])}" } ?: "room light only", e("amb", "flash")) { SpectrumBars(info.specFlash ?: info.specAmb ?: DoubleArray(3)) }
        row(TestStep.RING, info.ringNote ?: "${info.ring.count { it.third }} of ${info.ring.size} colours verified by the camera", e("ringR", "ringG", "ringB", "ringW")) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { info.ring.forEach { (_, seen, ok) -> Box(Modifier.size(14.dp).clip(CircleShape).background(seen).border(1.5.dp, if (ok) Palette.accent else Palette.red, CircleShape)) } }
        }
        row(TestStep.ECHO, info.echo?.let { "echo ${fmt(it.first)} dB · tilt ${fmt(it.second)} dB · ${it.third} chirps" } ?: "not run", e("echo"))
        row(TestStep.MAGNET, info.mag?.let { "|B| ${fmt(it)} µT" } ?: "not available", e("mag"))
        row(TestStep.VERDICT, "inverse-variance fusion · outliers dropped", est.values.toList().let { if (it.isEmpty()) it else listOfNotNull(fuse(it)?.let { f -> Fusion.Estimate("fused", f.value, f.sigma) }) })
    }
}

/** Result: animated ring gauge with the % in the middle, verdict pill and one line of advice. */
@Composable
private fun ResultHero(kind: Kind, f: Fusion.Fused) {
    ResultHeroMain(kind, f)
    val v = Purity.verdict(kind, f.value)
    androidx.compose.animation.AnimatedVisibility(v.level != Level.SAFE, enter = androidx.compose.animation.fadeIn(tween(600, delayMillis = 900)) + androidx.compose.animation.expandVertically(tween(600, delayMillis = 900))) {
        HealthRisks(kind, Color(v.level.argb))
    }
}

/** Health effects of consuming this adulterated product. */
@Composable
private fun HealthRisks(kind: Kind, col: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = 0.10f)).border(1.dp, col.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("⚠  HEALTH RISKS IF CONSUMED", color = col, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
        Purity.healthEffects(kind).forEach { Text("•  $it", color = Palette.text, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable
private fun ResultHeroMain(kind: Kind, f: Fusion.Fused) {
    val v = Purity.verdict(kind, f.value)
    val col = Color(v.level.argb)
    val sweep by animateFloatAsState((f.value / 100).toFloat().coerceIn(0f, 1f), tween(1200), label = "sweep")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val st = 14.dp.toPx(); val inset = st / 2
                val sz = Size(size.width - st, size.height - st)
                drawArc(Color(0x1FFFFFFF), 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(st, cap = StrokeCap.Round))
                drawArc(capColor(kind), -90f + 360f * sweep, 360f * (1 - sweep), false, Offset(inset, inset), sz, style = Stroke(st, cap = StrokeCap.Round))
                if (sweep > 0.005f) drawArc(col, -90f, 360f * sweep, false, Offset(inset, inset), sz, style = Stroke(st, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val shown by animateFloatAsState(f.value.toFloat(), tween(1200), label = "count")
                Text("${shown.toInt()}%", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 32.sp)
                Text(kind.adulterant, color = Palette.muted, fontSize = 11.sp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(v.label.uppercase(), color = col, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp))
            Text("${(100 - f.value).toInt()}% real ${kind.label.lowercase()}", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("± ${fmt(1.96 * f.sigma)}% · ${f.parts.size} sensors", color = Palette.muted, fontSize = 12.sp)
            // Educated estimate: distance from the nearest verdict line (8 % / 18 % added) vs the fused σ.
            val conf = com.shuddh.lab.core.Dart.confidence(listOf(8.0, 18.0).minOf { kotlin.math.abs(f.value - it) }, f.sigma)
            Text(com.shuddh.lab.core.Dart.estimate(v.level, conf), color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(v.advice, color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun LegendDot(c: Color, t: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(10.dp).border(2.dp, c, RoundedCornerShape(2.dp)))
    Text(t, color = Palette.muted, fontSize = 11.sp)
}

/** A glass split into product (bottom) and water (top), with a gentle wave. */
@Composable
private fun PurityCup(kind: Kind, water: Double?, busy: Boolean, modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "cup")
    val t by inf.animateFloat(0f, 6.283f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t")
    val w by animateFloatAsState(((water ?: 0.0) / 100).toFloat().coerceIn(0f, 1f), tween(1100), label = "w")
    val product = capColor(kind).let { if (kind == Kind.MILK) Color(0xFFF8FAFC) else it }
    Canvas(modifier) {
        val W = size.width; val H = size.height
        val left = W * 0.12f; val right = W * 0.88f; val top = H * 0.08f; val bottom = H * 0.95f
        val fillTop = top + (bottom - top) * 0.12f
        val split = fillTop + (bottom - fillTop) * w
        fun wave(y: Float, amp: Float) = Path().apply {
            moveTo(left, y)
            var x = left
            while (x <= right) { lineTo(x, y + amp * sin((x / W) * 9f + t)); x += 4f }
            lineTo(right, bottom); lineTo(left, bottom); close()
        }
        drawPath(wave(fillTop, if (busy) 5f else 2.5f), Color(0xFF38BDF8).copy(alpha = 0.55f))
        drawPath(wave(split, if (busy) 4f else 2f), product)
        drawRoundRect(Color.White.copy(alpha = 0.7f), Offset(left, top), Size(right - left, bottom - top), CornerRadius(10f), style = Stroke(4f))
    }
}


// ── Milk Watch UI ────────────────────────────────────────────────────────────

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
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) accent.copy(alpha = 0.25f) else Color(0x10FFFFFF)).border(1.dp, if (sel) accent else Color(0x22FFFFFF), RoundedCornerShape(50))
                        .clickable { vendor = v; note = null }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
            Text("+ vendor", color = accent, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { adding = !adding }.padding(horizontal = 10.dp, vertical = 7.dp))
        }
        if (adding) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedTextField(newName, { newName = it.take(24) }, label = { Text("Vendor / brand") }, singleLine = true, modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text, focusedBorderColor = accent, unfocusedBorderColor = Color(0x33FFFFFF), focusedLabelColor = accent, unfocusedLabelColor = Palette.muted))
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
    Canvas(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33000000))) {
        val lim = 5.0
        fun y(v: Double) = (size.height / 2 - (v.coerceIn(-lim, lim) / lim) * (size.height / 2 - 8)).toFloat()
        val band = (size.height / 2 - 8) * (2.0 / lim).toFloat()
        drawRect(Color(0xFF34D399).copy(alpha = 0.12f), Offset(0f, size.height / 2 - band), Size(size.width, band * 2))
        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f)
        val dx = size.width / (pts.size + 1)
        pts.forEachIndexed { i, v ->
            val c = when (cs.getOrNull(i)) { "CHANGED" -> Color(0xFFF43F5E); "UNUSUAL" -> Color(0xFFFBBF24); "LEARN" -> Color(0xFFA78BFA); else -> Color(0xFF34D399) }
            if (i > 0) drawLine(Color.White.copy(alpha = 0.3f), Offset(dx * i, y(pts[i - 1])), Offset(dx * (i + 1), y(v)), 2f)
            drawCircle(c, 7f, Offset(dx * (i + 1), y(v)))
        }
    }
    Text("Each dot = one day · green band = this vendor's normal · below = thinner", color = Palette.muted, fontSize = 10.sp)
}


/** One calibration capture: known water level + every sensor reading taken at it. */
private class CalPoint(val lvl: Double, val reads: Map<String, DoubleArray>, val mag: Double?, val echo: DoubleArray? = null)

/** Calibration points from storage; older 3-reference calibrations (water / half / pure) are converted. */
private fun calPoints(o: JSONObject): List<CalPoint> {
    val out = mutableListOf<CalPoint>()
    o.optJSONArray("pts")?.let { a ->
        for (i in 0 until a.length()) {
            val j = a.getJSONObject(i)
            out += CalPoint(j.getDouble("lvl"), Mode.entries.mapNotNull { m -> darr(j.optJSONArray(m.id))?.let { m.id to it } }.toMap(), j.optDouble("mag").takeIf { !it.isNaN() }, darr(j.optJSONArray("echo")))
        }
    }
    listOf("water" to 100.0, "half" to 50.0, "pure" to 0.0).forEach { (n, lvl) ->
        val reads = Mode.entries.mapNotNull { m -> darr(o.optJSONArray("${m.id}_$n"))?.let { m.id to it } }.toMap()
        val mag = o.optDouble("mag_$n").takeIf { !it.isNaN() }
        if (reads.isNotEmpty() || mag != null) out += CalPoint(lvl, reads, mag)
    }
    return out
}

@Composable
private fun LadderChip(kind: Kind, lvl: Int, count: Int, next: Boolean, recording: Boolean, progress: Float, enabled: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val done = count > 0
    val inf = rememberInfiniteTransition(label = "chip")
    val glow by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "g")
    val p by animateFloatAsState(progress, tween(400), label = "cp")
    val border = when { recording -> Palette.cyan; done -> Palette.accent; next -> Palette.cyan; else -> Color(0x22FFFFFF) }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (recording) Palette.cyan.copy(alpha = 0.12f) else if (done) Palette.accent.copy(alpha = 0.08f) else Color(0x0CFFFFFF))
            .border(1.5.dp, if (recording || (next && !done)) border.copy(alpha = glow) else border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onTap() }.padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Tiny glass: product (bottom) vs water (top) for this mix.
        Canvas(Modifier.size(width = 18.dp, height = 22.dp)) {
            val w = size.width; val h = size.height; val m = 1f - lvl / 100f
            drawRect(Color(0xFF38BDF8).copy(alpha = 0.5f), Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.85f))
            if (m > 0f) drawRect(capColor(kind), Offset(w * 0.1f, h * 0.1f + h * 0.85f * (1 - m)), Size(w * 0.8f, h * 0.85f * m))
            drawRoundRect(Color.White.copy(alpha = 0.7f), Offset(w * 0.05f, h * 0.05f), Size(w * 0.9f, h * 0.92f), CornerRadius(4f), style = Stroke(2f))
        }
        Text("$lvl%", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (recording) Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x22FFFFFF))) { Box(Modifier.fillMaxWidth(p.coerceAtLeast(0.1f)).fillMaxHeight().background(Palette.cyan)) }
        else Text(if (done) "✓${if (count > 1) " ×$count" else ""}" else when (lvl) { 0 -> "pure"; 100 -> "water"; else -> "${10 - lvl / 10}+${lvl / 10}" },
            color = if (done) Palette.accent else if (next) Palette.cyan else Palette.muted, fontSize = 10.sp, maxLines = 1)
    }
}

/** Your calibration: every recorded mix (dots) and the fitted monotone curve. */
@Composable
private fun CalCurve(pts: List<Pair<Double, Double>>, m: Ladder.Model, c: Color, title: String) {
    if (pts.isEmpty()) return
    val lo = pts.minOf { it.second }; val hi = pts.maxOf { it.second }
    Glass(padding = 14) {
        Text("Your calibration curve", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val pad = 12f
            fun X(w: Double) = (pad + w / 100 * (size.width - 2 * pad)).toFloat()
            fun Y(r: Double) = (size.height - pad - (r - lo) / (hi - lo).coerceAtLeast(1e-6) * (size.height - 2 * pad)).toFloat()
            for (k in 1..3) drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
            m.knots.zipWithNext().forEach { (a, b) -> drawLine(c, Offset(X(a.first), Y(a.second)), Offset(X(b.first), Y(b.second)), 4f, cap = StrokeCap.Round) }
            pts.forEach { (w, r) -> drawCircle(Color.White, 6f, Offset(X(w), Y(r))) }
        }
        Text("x: % ${"water"} in the mix (0 → 100) · y: reading · $title", color = Palette.muted, fontSize = 10.sp)
    }
}


@Composable
private fun MilkTypeChips(t: MilkType, enabled: Boolean, pick: (MilkType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("WHAT MILK IS IT?", color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        MilkType.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { m ->
                    val sel = m == t
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) Color(0xFF7DD3FC).copy(alpha = 0.22f) else Color(0x10FFFFFF))
                        .border(1.dp, if (sel) Color(0xFF7DD3FC) else Color(0x22FFFFFF), RoundedCornerShape(12.dp)).clickable(enabled = enabled) { pick(m) }.padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(m.label.substringBefore(" ("), color = if (sel) Palette.text else Palette.muted, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        Text("${fmt(m.fat)}% fat", color = Palette.muted, fontSize = 9.sp)
                    }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** Water % · Spoiled? · Detergent? */
@Composable
private fun TestSwitch(cur: String, enabled: Boolean, pick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x14FFFFFF)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("water" to "💧 Water %", "spoiled" to "🦠 Spoiled?", "detergent" to "🫧 Detergent?").forEach { (k, t) ->
            val sel = k == cur
            Text(t, color = if (sel) Palette.text else Palette.muted, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (sel) Palette.accent.copy(alpha = 0.22f) else Color.Transparent).clickable(enabled = enabled) { pick(k) }.padding(vertical = 10.dp))
        }
    }
}
