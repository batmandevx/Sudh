package com.shuddh.lab.core

import kotlin.math.sqrt

/**
 * Camera photoplethysmography: a fingertip lit by the flash brightens and darkens slightly with
 * each heartbeat as blood volume changes. This turns a stream of (time, brightness) samples
 * into BPM, beat times and HRV — deterministic DSP, no ML.
 */
object Ppg {
    const val FS = 30.0 // resample rate, Hz

    data class Result(
        val bpm: Double,
        /** Autocorrelation peak height 0..1 — how periodic (trustworthy) the signal is. */
        val quality: Double,
        /** Beat times in seconds (relative to the window start). */
        val beats: List<Double>,
        /** RMSSD of beat-to-beat intervals, ms (null if too few beats). */
        val rmssd: Double?,
        /** The cleaned, zero-mean signal used for display. */
        val clean: DoubleArray,
    )

    /** Does this frame look like a fingertip pressed over a lit lens? */
    fun fingerOn(r: Float, g: Float, b: Float): Boolean = r > 120f && g < r * 0.5f && b < r * 0.45f

    /** A fingertip fills the whole lens: every corner must be deep red too, not just a flash reflection. */
    fun fingerCovers(centre: FloatArray, corners: List<FloatArray>): Boolean =
        fingerOn(centre[0], centre[1], centre[2]) && corners.all { (r, g, b) -> r > 70f && g < r * 0.6f && b < r * 0.55f }

    /** Linear-interpolates irregular samples onto a uniform [FS] grid. */
    fun resample(t: List<Double>, v: List<Double>): DoubleArray {
        if (t.size < 2) return DoubleArray(0)
        val n = ((t.last() - t.first()) * FS).toInt()
        val out = DoubleArray(n.coerceAtLeast(0))
        var j = 0
        for (i in out.indices) {
            val tt = t.first() + i / FS
            while (j < t.size - 2 && t[j + 1] < tt) j++
            val f = ((tt - t[j]) / (t[j + 1] - t[j]).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
            out[i] = v[j] + (v[j + 1] - v[j]) * f
        }
        return out
    }

    /** Removes slow drift (1 s moving average) then lightly smooths (5-point). Inverted so beats are peaks. */
    fun clean(x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return x
        val w = FS.toInt()
        val detr = DoubleArray(x.size) { i ->
            val a = (i - w / 2).coerceAtLeast(0); val b = (i + w / 2).coerceAtMost(x.size - 1)
            var s = 0.0; for (k in a..b) s += x[k]
            -(x[i] - s / (b - a + 1))
        }
        return DoubleArray(detr.size) { i ->
            var s = 0.0; var c = 0
            for (k in (i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(detr.size - 1)) { s += detr[k]; c++ }
            s / c
        }
    }

    /** BPM from the strongest autocorrelation peak between 40 and 180 BPM. */
    fun analyse(t: List<Double>, v: List<Double>): Result? {
        val x = clean(resample(t, v))
        if (x.size < FS * 5) return null
        val mean = x.average()
        val c = DoubleArray(x.size) { x[it] - mean }
        val e = c.sumOf { it * it }.coerceAtLeast(1e-12)
        val minLag = (FS * 60 / 180).toInt(); val maxLag = (FS * 60 / 40).toInt().coerceAtMost(c.size / 2)
        var bestLag = -1; var best = 0.0
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            var s = 0.0
            for (i in 0 until c.size - lag) s += c[i] * c[i + lag]
            ac[lag] = s / e
        }
        for (lag in minLag + 1..maxLag) {
            if (ac[lag] > ac[lag - 1] && ac[lag] >= ac[lag + 1] && ac[lag] > best) { best = ac[lag]; bestLag = lag }
        }
        if (bestLag < 0) return null
        // Parabolic refinement of the lag.
        val y0 = ac[bestLag - 1]; val y1 = ac[bestLag]; val y2 = ac[bestLag + 1]
        val d = y0 - 2 * y1 + y2
        val lag = bestLag + if (d != 0.0) 0.5 * (y0 - y2) / d else 0.0
        val bpm = 60 * FS / lag

        // Beats: local maxima at least 60% of a period apart and above 0.3 σ.
        val sd = sqrt(e / c.size)
        val minGap = (lag * 0.6).toInt()
        val beats = mutableListOf<Int>()
        for (i in 1 until c.size - 1) {
            if (c[i] > c[i - 1] && c[i] >= c[i + 1] && c[i] > 0.3 * sd) {
                if (beats.isEmpty() || i - beats.last() >= minGap) beats += i
                else if (c[i] > c[beats.last()]) beats[beats.lastIndex] = i
            }
        }
        val ibis = beats.zipWithNext { a, b -> (b - a) / FS * 1000 }.filter { it in 330.0..1500.0 }
        val rmssd = if (ibis.size >= 3) sqrt(ibis.zipWithNext { a, b -> (b - a) * (b - a) }.average()) else null
        return Result(bpm, best.coerceIn(0.0, 1.0), beats.map { it / FS }, rmssd, c)
    }

    fun zone(bpm: Double) = when {
        bpm < 60 -> "Resting / athletic"
        bpm <= 100 -> "Normal resting range"
        else -> "Elevated"
    }
}
