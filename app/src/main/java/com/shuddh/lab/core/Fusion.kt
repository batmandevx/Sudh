package com.shuddh.lab.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Multi-sensor fusion — the Nami idea generalised: every sensor gives an estimate with its own
 * uncertainty; estimates are combined by inverse-variance weighting, and a χ² consistency check
 * says whether the sensors actually agree. A verdict is only "confident" when they do.
 */
object Fusion {
    /** One sensor's estimate of the same quantity, with its 1-σ uncertainty (same units). */
    data class Estimate(val sensor: String, val value: Double, val sigma: Double)

    data class Fused(
        val value: Double,
        val sigma: Double,
        val parts: List<Estimate>,
        /** Each part's share of the final answer (sums to 1). */
        val weights: List<Double>,
        /** 0..1 — how well the sensors agree (1 = perfectly consistent). */
        val agreement: Double,
        /** Sensor that disagrees most with the fused value, when agreement is poor. */
        val outlier: String?,
    ) {
        /** Confidence 0..1: high when sensors agree and the combined uncertainty is small vs [scale]. */
        fun confidence(scale: Double): Double = (agreement * (1 - (sigma / scale).coerceIn(0.0, 1.0))).coerceIn(0.0, 1.0)
    }

    fun combine(parts0: List<Estimate>): Fused? {
        val parts = parts0.filter { it.value.isFinite() && it.sigma.isFinite() && it.sigma > 0 }
        if (parts.isEmpty()) return null
        val w = parts.map { 1 / (it.sigma * it.sigma) }
        val sw = w.sum()
        val v = parts.indices.sumOf { w[it] * parts[it].value } / sw
        val sigma = sqrt(1 / sw)
        // χ² of the parts about the fused value; with n−1 dof. Agreement = P-like score in 0..1.
        val z = parts.map { (it.value - v) / it.sigma }
        val chi2 = z.sumOf { it * it }
        val dof = (parts.size - 1).coerceAtLeast(1)
        val agreement = if (parts.size == 1) 0.7 else (1 / (1 + (chi2 / dof - 1).coerceAtLeast(0.0) / 2)).coerceIn(0.0, 1.0)
        val worst = z.indices.maxByOrNull { abs(z[it]) }
        val outlier = if (parts.size > 1 && agreement < 0.6 && worst != null) parts[worst].sensor else null
        // When sensors disagree, widen σ by the excess scatter (Birge ratio) so we never overclaim.
        val birge = sqrt((chi2 / dof).coerceAtLeast(1.0))
        return Fused(v, sigma * if (parts.size > 1) birge else 1.0, parts, w.map { it / sw }, agreement, outlier)
    }
}
