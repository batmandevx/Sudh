package com.shuddh.lab.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Milk Watch — learns one vendor's milk from daily phone-sensor scans and flags days that differ.
 *
 * Each scan is a vector of optical features (reflectance in R, G, B under room light, torch lock-in and,
 * where available, the iQOO ring light). The baseline is robust (median, MAD-σ) over the vendor's normal
 * days. A new scan gets a z-score per feature; their RMS says how unusual the day is, and the mean signed z
 * says in which direction (watered milk scatters less → lower reflectance → negative). A CUSUM on that
 * signed mean catches slow drift — a little more water each week — that single-day checks miss.
 */
object MilkWatch {
    data class Scan(val at: Long, val f: DoubleArray)

    data class Baseline(val median: DoubleArray, val sigma: DoubleArray, val n: Int)

    /** Need this many scans before judging. */
    const val MIN_BASELINE = 3

    /** Per-feature σ floor (reflectance units): repositioning noise that no amount of averaging removes. */
    const val SIGMA_FLOOR = 0.008

    fun baseline(scans: List<Scan>): Baseline? {
        if (scans.size < MIN_BASELINE) return null
        val k = scans.minOf { it.f.size }
        val med = DoubleArray(k) { i -> median(scans.map { it.f[i] }) }
        val sig = DoubleArray(k) { i -> maxOf(1.4826 * median(scans.map { abs(it.f[i] - med[i]) }), SIGMA_FLOOR) }
        return Baseline(med, sig, scans.size)
    }

    data class Score(val rms: Double, val direction: Double, val z: DoubleArray)

    fun score(s: Scan, b: Baseline): Score {
        val k = minOf(s.f.size, b.median.size)
        val z = DoubleArray(k) { (s.f[it] - b.median[it]) / b.sigma[it] }
        return Score(sqrt(z.sumOf { it * it } / k), z.average(), z)
    }

    enum class Call { NORMAL, UNUSUAL, CHANGED }

    fun call(sc: Score): Call = when { sc.rms <= 2.0 -> Call.NORMAL; sc.rms <= 3.5 -> Call.UNUSUAL; else -> Call.CHANGED }

    /** What the change most likely is, from its direction (lower reflectance = thinner, i.e. watered or skimmed). */
    fun meaning(sc: Score): String = when {
        sc.direction < -1.5 -> "thinner than usual — typical of added water or skimming"
        sc.direction > 1.5 -> "whiter than usual — different source, or something added"
        else -> "different colour balance — a different source or mixing"
    }

    /** One-sided lower CUSUM on the daily signed mean z (k = 0.5, alarm at h = 4): slow watering. */
    fun drift(scores: List<Score>, k: Double = 0.5, h: Double = 4.0): Boolean {
        var c = 0.0
        for (s in scores) { c = maxOf(0.0, c - s.direction - k); if (c > h) return true }
        return false
    }

    /** % of judged days that were normal — the vendor's trust score. */
    fun trust(calls: List<Call>): Int? = if (calls.isEmpty()) null else 100 * calls.count { it == Call.NORMAL } / calls.size

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); val n = s.size; return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2 }
}
