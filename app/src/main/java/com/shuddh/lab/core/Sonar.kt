package com.shuddh.lab.core

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Active acoustic sensing with the phone's own speaker and microphone.
 *
 * The speaker plays a train of linear chirps (2→18 kHz). The mic records what comes back —
 * the direct path plus the reflection from the surface right under the phone. A matched filter
 * finds every chirp; dividing each received spectrum by the emitted chirp's spectrum gives the
 * acoustic transfer function H(f) in 16 bands. Porous materials (soil, plaster, grain, cloth)
 * absorb high frequencies when dry and reflect them when their pores fill with water, so H(f)
 * moves predictably between a dry and a wet reference of the same material.
 */
object Sonar {
    const val SR = 48000
    const val F0 = 2000.0
    const val F1 = 18000.0
    private const val CHIRP_S = 0.04
    private const val GAP_S = 0.12
    const val CHIRPS = 12
    const val BANDS = 16

    data class Reading(
        /** Transfer function per 1 kHz band from 2 to 18 kHz, dB. */
        val bands: FloatArray,
        val chirpsFound: Int,
        /** Matched-filter peak over median — how cleanly the chirps were heard. */
        val snr: Double,
        /** Mean per-band standard deviation across chirps, dB — lower is steadier. */
        val spreadDb: Double,
        /** Every accepted chirp's transfer function — kept for per-chirp confidence intervals. */
        val perChirp: List<FloatArray> = emptyList(),
        val rejected: Int = 0,
        /** Per-band SNR (dB) of the received chirp over the room's own noise in the pre-roll. */
        val bandSnr: FloatArray = FloatArray(BANDS) { 30f },
        /** Room noise level before the chirps, dBFS. */
        val noiseDb: Double = -90.0,
        /** True if the mic saturated — readings are distorted; lower the volume. */
        val clipped: Boolean = false,
        /** Down-sampled |signal| envelope of the recording, 0..1, for display. */
        val envelope: FloatArray = FloatArray(0),
    )

    /**
     * Per-band weights for the moisture projection: inverse chirp-to-chirp variance (steady bands
     * count more) × a soft SNR gate (bands drowned by room noise count ~nothing).
     */
    fun weights(r: Reading): DoubleArray = DoubleArray(BANDS) { b ->
        val m = r.bands[b]
        val v = if (r.perChirp.size >= 2) r.perChirp.map { (it[b] - m) * (it[b] - m) }.average() else 1.0
        val snrGate = 1 / (1 + kotlin.math.exp(-(r.bandSnr[b] - 8.0) / 2.0)) // ~0 below 4 dB, ~1 above 12 dB
        snrGate / (v + 0.25)
    }

    /**
     * Pools several pings into one robust reading: per-band median across all chirps, then
     * drops chirps whose RMS distance from that median exceeds 3 × the median distance (MAD rule).
     */
    fun pool(readings: List<Reading>): Reading? {
        val all = readings.flatMap { it.perChirp }
        if (all.size < 3) return readings.firstOrNull()
        fun median(xs: List<Float>) = xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
        val med = FloatArray(BANDS) { b -> median(all.map { it[b] }) }
        val dist = all.map { c -> sqrt((0 until BANDS).sumOf { ((c[it] - med[it]) * (c[it] - med[it])).toDouble() } / BANDS) }
        val mad = dist.sorted()[dist.size / 2].coerceAtLeast(0.15)
        val kept = all.filterIndexed { i, _ -> dist[i] <= 3 * mad }
        val mean = FloatArray(BANDS) { b -> kept.map { it[b] }.average().toFloat() }
        val spread = (0 until BANDS).map { b -> val m = mean[b]; sqrt(kept.map { (it[b] - m) * (it[b] - m) }.average()) }.average()
        val snrB = FloatArray(BANDS) { b -> readings.map { it.bandSnr[b] }.average().toFloat() }
        return Reading(mean, kept.size, readings.map { it.snr }.average(), spread, kept, all.size - kept.size,
            snrB, readings.maxOf { it.noiseDb }, readings.any { it.clipped }, readings.last().envelope)
    }

    /** Moisture index per chirp → mean and 95 % confidence half-width. */
    fun moistureStats(r: Reading, dry: FloatArray, wet: FloatArray): Triple<Double, Double, List<Double>> {
        val w = weights(r)
        val ts = r.perChirp.map { moisture(it, dry, wet, w).first }
        if (ts.isEmpty()) return Triple(moisture(r.bands, dry, wet, w).first, 0.0, emptyList())
        val m = ts.average()
        val sd = sqrt(ts.sumOf { (it - m) * (it - m) } / (ts.size - 1).coerceAtLeast(1))
        return Triple(m, 1.96 * sd / sqrt(ts.size.toDouble()), ts)
    }

    fun chirp(): FloatArray {
        val n = (SR * CHIRP_S).toInt()
        val t = CHIRP_S
        return FloatArray(n) { i ->
            val s = i.toDouble() / SR
            val phase = 2 * PI * (F0 * s + (F1 - F0) * s * s / (2 * t))
            val win = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            (sin(phase) * win).toFloat()
        }
    }

    fun mediaVolumeFraction(ctx: Context): Float {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    /** Plays the chirp train while recording; blocking (~1.8 s). Call off the main thread. */
    @SuppressLint("MissingPermission")
    fun capture(ctx: Context): FloatArray {
        val c = chirp()
        val gap = (SR * GAP_S).toInt()
        val period = c.size + gap
        val out = FloatArray(period * CHIRPS)
        for (k in 0 until CHIRPS) for (i in c.indices) out[k * period + i] = c[i] * 0.9f

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val total = out.size + SR / 2
        val rec = AudioRecord(
            source, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), total * 2),
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(out.size * 4)
            .build()
        try {
            track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
            val buf = ShortArray(total)
            rec.startRecording()
            var got = rec.read(buf, 0, SR / 10)
            track.play()
            while (got < total) {
                val n = rec.read(buf, got, minOf(4096, total - got))
                if (n <= 0) break
                got += n
            }
            return FloatArray(got) { buf[it] / 32768f }
        } finally {
            runCatching { track.stop() }; track.release()
            runCatching { rec.stop() }; rec.release()
        }
    }

    fun analyse(x: FloatArray): Reading? {
        val c = chirp()
        // Matched filter by FFT cross-correlation.
        var n = 1
        while (n < x.size + c.size) n = n shl 1
        val xr = DoubleArray(n) { if (it < x.size) x[it].toDouble() else 0.0 }; val xi = DoubleArray(n)
        val cr = DoubleArray(n) { if (it < c.size) c[it].toDouble() else 0.0 }; val ci = DoubleArray(n)
        Dsp.fft(xr, xi); Dsp.fft(cr, ci)
        for (k in 0 until n) { // X · conj(C)
            val re = xr[k] * cr[k] + xi[k] * ci[k]
            val im = xi[k] * cr[k] - xr[k] * ci[k]
            xr[k] = re; xi[k] = -im // conjugate for inverse via forward FFT
        }
        Dsp.fft(xr, xi)
        val corr = DoubleArray(x.size) { kotlin.math.abs(xr[it]) / n }
        val clipped = x.count { kotlin.math.abs(it) > 0.985f } > 20
        val envN = 240
        val env = FloatArray(envN) { k ->
            var m = 0f; val a = k * x.size / envN; val b = ((k + 1) * x.size / envN).coerceAtMost(x.size)
            for (i in a until b) m = max(m, kotlin.math.abs(x[i])); m
        }.let { e -> val top = (e.maxOrNull() ?: 1f).coerceAtLeast(1e-6f); FloatArray(envN) { e[it] / top } }
        val sorted = corr.sorted()
        val median = sorted[sorted.size / 2].coerceAtLeast(1e-12)
        val maxV = sorted.last()
        if (maxV <= 0) return null

        // Peaks: local maxima above 35% of max, at least half a chirp period apart.
        val minDist = ((CHIRP_S + GAP_S) * SR / 2).toInt()
        val cand = corr.indices.filter { corr[it] > 0.35 * maxV }.sortedByDescending { corr[it] }
        val peaks = mutableListOf<Int>()
        for (i in cand) if (peaks.none { kotlin.math.abs(it - i) < minDist }) peaks.add(i)
        val good = peaks.filter { it + c.size < x.size }.sorted()
        if (good.size < 3) return null

        // Per-chirp transfer function H(f) = |R(f)| / |C(f)| in 1 kHz bands.
        val seg = 4096
        val cSpec = magnitude(c, seg)
        val hz = SR.toDouble() / seg
        // Room noise: the 0.1 s recorded before playback starts (and before the first chirp arrives).
        val preEnd = minOf(seg, good.first() - 200).coerceAtLeast(0)
        val nSpec = if (preEnd >= 1024) magnitude(FloatArray(seg) { if (it < preEnd) x[it] else 0f }, seg) else null
        val nScale = if (preEnd > 0) (c.size + 480).toDouble() / preEnd else 1.0 // match window lengths
        val noiseDb = if (preEnd > 0) 10 * log10((0 until preEnd).sumOf { (x[it] * x[it]).toDouble() } / preEnd + 1e-12) else -90.0
        val sigPow = DoubleArray(BANDS)
        val perChirp = good.map { p ->
            val r = FloatArray(seg) { if (p + it < x.size && it < c.size + 480) x[p + it] else 0f }
            val rSpec = magnitude(r, seg)
            FloatArray(BANDS) { b ->
                val lo = ((F0 + b * 1000) / hz).toInt(); val hi = ((F0 + (b + 1) * 1000) / hz).toInt()
                var num = 0.0; var den = 0.0
                for (k in lo until hi) { num += rSpec[k] * rSpec[k]; den += cSpec[k] * cSpec[k] }
                sigPow[b] += num / good.size
                (10 * log10((num + 1e-18) / (den + 1e-18))).toFloat()
            }
        }
        val mean = FloatArray(BANDS) { b -> perChirp.map { it[b] }.average().toFloat() }
        val spread = (0 until BANDS).map { b ->
            val m = mean[b]; sqrt(perChirp.map { (it[b] - m) * (it[b] - m) }.average())
        }.average()
        val bandSnr = FloatArray(BANDS) { b ->
            if (nSpec == null) 30f else {
                val lo = ((F0 + b * 1000) / hz).toInt(); val hi = ((F0 + (b + 1) * 1000) / hz).toInt()
                var np = 0.0; for (k in lo until hi) np += nSpec[k] * nSpec[k]
                (10 * log10((sigPow[b] + 1e-18) / (np * nScale + 1e-18))).toFloat().coerceIn(-10f, 60f)
            }
        }
        return Reading(mean, good.size, maxV / median, spread, perChirp, 0, bandSnr, noiseDb, clipped, env)
    }

    private fun magnitude(x: FloatArray, n: Int): DoubleArray {
        val re = DoubleArray(n) { if (it < x.size) x[it].toDouble() else 0.0 }
        val im = DoubleArray(n)
        Dsp.fft(re, im)
        return DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
    }

    /**
     * Position of [r] between [dry] (0) and [wet] (1) by projection onto the dry→wet direction.
     * Also returns how far the reading sits off that line (dB RMS) — large = different material.
     */
    fun moisture(r: FloatArray, dry: FloatArray, wet: FloatArray, w: DoubleArray? = null): Pair<Double, Double> {
        val wt = w ?: DoubleArray(BANDS) { 1.0 }
        var d = DoubleArray(BANDS) { (wet[it] - dry[it]).toDouble() }
        var v = DoubleArray(BANDS) { (r[it] - dry[it]).toDouble() }
        // Level-invariant mode: a broadband shift (phone a little closer, volume nudged) moves every
        // band equally, while moisture changes the spectral *shape*. Remove the weighted mean when
        // the dry→wet difference is mostly shape, so distance jitter can't masquerade as water.
        val ws = wt.sum().coerceAtLeast(1e-9)
        val dm = d.indices.sumOf { wt[it] * d[it] } / ws
        val dShape = DoubleArray(BANDS) { d[it] - dm }
        val e = d.indices.sumOf { wt[it] * d[it] * d[it] }
        if (e > 1e-9 && d.indices.sumOf { wt[it] * dShape[it] * dShape[it] } / e > 0.15) {
            val vm = v.indices.sumOf { wt[it] * v[it] } / ws
            d = dShape; v = DoubleArray(BANDS) { v[it] - vm }
        }
        val dd = d.indices.sumOf { wt[it] * d[it] * d[it] }
        if (dd < 1e-9) return 0.0 to 0.0
        val t = d.indices.sumOf { wt[it] * d[it] * v[it] } / dd
        val off = sqrt(d.indices.sumOf { wt[it] * (v[it] - t * d[it]).let { e2 -> e2 * e2 } } / ws)
        return t to off
    }

    /**
     * Surface echo only: the mic hears the direct speaker→mic sound plus the surface reflection.
     * Subtracting the open-air capture *in power* removes the direct path and the phone's own
     * speaker/mic colouring, leaving the reflection — far more sensitive to the surface's state.
     * Bands where the reflection is below 5 % of the direct power are floored (not trusted).
     */
    fun echo(bands: FloatArray, air: FloatArray): FloatArray = FloatArray(BANDS) { b ->
        val pr = 10.0.pow(bands[b] / 10.0); val pa = 10.0.pow(air[b] / 10.0)
        (10 * log10(maxOf(pr - pa, 0.05 * pa))).toFloat()
    }

    fun echo(r: Reading, air: FloatArray): Reading = r.copy(bands = echo(r.bands, air), perChirp = r.perChirp.map { echo(it, air) })

    /** High-band (10–18 kHz) minus low-band (2–8 kHz) echo level, dB. Water in pores lifts the high bands. */
    fun tilt(bands: FloatArray, w: DoubleArray? = null): Double {
        fun wm(r: IntRange): Double {
            val ws = r.sumOf { w?.get(it) ?: 1.0 }.coerceAtLeast(1e-9)
            return r.sumOf { (w?.get(it) ?: 1.0) * bands[it] } / ws
        }
        return wm(8 until BANDS) - wm(0 until 6)
    }

    /** Typical dry-surface tilt on phone speakers; adjustable per device via [quickEstimate]'s centre. */
    const val QUICK_CENTRE = -6.0
    const val QUICK_SCALE = 3.0

    /**
     * Reference-free moisture estimate 0..1 with a 95% CI, from the spectral tilt of every chirp.
     * Less accurate than the dry/wet calibration — shown as a "quick estimate" until references exist.
     */
    fun quickEstimate(r: Reading, air: FloatArray? = null): Triple<Double, Double, List<Double>> {
        // With an open-air baseline, the phone's own speaker/mic colouring cancels out and what is
        // left is the surface's contribution — a sharper, device-independent scale.
        val centre = if (air != null) 0.0 else QUICK_CENTRE
        val scale = if (air != null) 2.5 else QUICK_SCALE
        val w = weights(r)
        fun idx(b: FloatArray): Double {
            val x = if (air != null) FloatArray(BANDS) { b[it] - air[it] } else b
            return 1 / (1 + kotlin.math.exp(-(tilt(x, w) - centre) / scale))
        }
        val ts = r.perChirp.map { idx(it) }
        if (ts.size < 2) return Triple(idx(r.bands), 0.2, ts)
        val m = ts.average()
        val sd = sqrt(ts.sumOf { (it - m) * (it - m) } / (ts.size - 1))
        // Floor on the CI: the absolute scale itself is uncertain without references.
        return Triple(m, maxOf(1.96 * sd / sqrt(ts.size.toDouble()), 0.1), ts)
    }
}
