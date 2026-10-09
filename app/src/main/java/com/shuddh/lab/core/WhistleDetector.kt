package com.shuddh.lab.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Pressure-cooker whistle detector, frame by frame (pure DSP, unit-tested).
 *
 * Each frame's spectrum (dB) is compared with a running per-bin noise floor, so a steady exhaust
 * fan, mixer hum or TV can't masquerade as a whistle. A frame "hits" when:
 *  - its strongest 0.9–6.5 kHz bin stands ≥ [snrDb] above that bin's own noise floor,
 *  - the energy is concentrated around that peak (tonality, with hysteresis), and
 *  - the band is not flat/noisy (spectral flatness low — rejects clatter, splashes, pressure-hiss).
 * A whistle needs [minFrames] hits whose pitch is stable (speech and music glide), ends after
 * [endFrames] misses, and is followed by a refractory gap so one sputtering whistle counts once.
 */
class WhistleDetector(
    private val sampleRate: Int,
    private val n: Int,
    private val snrDb: Double = 14.0,
    private val minFrames: Int = 12,   // ≈0.55 s at 22.05 kHz / 1024
    private val endFrames: Int = 9,
    private val refractoryFrames: Int = 30, // ≈1.4 s
) {
    data class Frame(val hit: Boolean, val peakHz: Double, val tonality: Double, val flatness: Double, val snr: Double, val inWhistle: Boolean, val progress: Float)

    var learnedHz: Double? = null
    private val hzPerBin = sampleRate.toDouble() / n
    private val lo = (900 / hzPerBin).toInt()
    private val hi = (6500 / hzPerBin).toInt()
    private var noise: DoubleArray? = null
    private var hits = 0
    private var misses = 0
    private var inWhistle = false
    private var refractory = 0
    private val pitches = ArrayList<Double>()

    /** Feeds one frame's magnitude spectrum (dB, length ≥ n/2). Returns frame info; [onWhistle] fires once per whistle. */
    fun push(specDb: FloatArray, onWhistle: (Double) -> Unit): Frame {
        val nz = noise ?: DoubleArray(specDb.size) { specDb[it].toDouble() }.also { noise = it }
        var best = lo
        for (i in lo..hi) if (specDb[i] > specDb[best]) best = i
        val peakHz = Dsp.parabolic(specDb, best) * hzPerBin
        val snr = specDb[best] - nz[best]

        // Tonality: power within ±3 bins of the peak vs. the 200 Hz–8 kHz band.
        var e = 0.0; var ep = 0.0; var logSum = 0.0; var cnt = 0
        val bandHi = minOf(specDb.size - 1, (8000 / hzPerBin).toInt())
        for (i in (200 / hzPerBin).toInt()..bandHi) {
            val p = 10.0.pow(specDb[i] / 10.0) + 1e-15
            e += p; if (abs(i - best) <= 3) ep += p
            logSum += ln(p); cnt++
        }
        val tonality = ep / e.coerceAtLeast(1e-15)
        val flatness = exp(logSum / cnt) / (e / cnt) // 1 = white noise, →0 = pure tone

        val inBand = learnedHz?.let { peakHz in it * 0.8..it * 1.2 } ?: true
        val tonalNeed = if (hits > 0 || inWhistle) 0.22 else 0.33 // hysteresis
        val hit = snr >= snrDb && tonality >= tonalNeed && flatness < 0.25 && inBand

        // Update the noise floor only from non-whistle frames (slow attack, faster decay toward quiet).
        if (!hit && !inWhistle) for (i in nz.indices) {
            val v = specDb[i].toDouble()
            nz[i] = if (v > nz[i]) 0.97 * nz[i] + 0.03 * v else 0.85 * nz[i] + 0.15 * v
        }

        if (refractory > 0) refractory--
        if (hit) { hits++; misses = 0; pitches += peakHz } else { misses++; if (!inWhistle && misses > 2) { hits = 0; pitches.clear() } }

        if (!inWhistle && hits >= minFrames && refractory == 0 && stablePitch()) {
            inWhistle = true
            onWhistle(Dsp.median(pitches))
        }
        if (inWhistle && misses >= endFrames) { inWhistle = false; hits = 0; pitches.clear(); refractory = refractoryFrames }
        val progress = if (inWhistle) 1f else (hits.toFloat() / minFrames).coerceIn(0f, 1f)
        return Frame(hit, peakHz, tonality, flatness, snr, inWhistle, progress)
    }

    /**
     * Whistle pitch is steady: the 10th–90th percentile pitch range over the last half-second is
     * under 5 % of the median. Voices, sirens and music glide much further than that.
     */
    private fun stablePitch(): Boolean {
        val recent = pitches.takeLast(minFrames).sorted()
        val m = Dsp.median(recent)
        val p10 = recent[(recent.size * 0.1).toInt()]
        val p90 = recent[((recent.size - 1) * 0.9).toInt()]
        return (p90 - p10) / m < 0.05
    }

    fun reset() { hits = 0; misses = 0; inWhistle = false; refractory = 0; pitches.clear() }
}

/** Whistle timing: average gap between whistles and when the next one is due. */
object WhistleTiming {
    /** Returns (mean interval ms, predicted next time ms) once two or more whistles are logged. */
    fun predict(times: List<Long>): Pair<Long, Long>? {
        if (times.size < 2) return null
        val gaps = times.zipWithNext { a, b -> b - a }
        // Weight recent gaps more — the cooker settles into a rhythm.
        var wSum = 0.0; var s = 0.0
        gaps.forEachIndexed { i, g -> val w = (i + 1).toDouble(); s += w * g; wSum += w }
        val mean = (s / wSum).toLong()
        return mean to times.last() + mean
    }
}
