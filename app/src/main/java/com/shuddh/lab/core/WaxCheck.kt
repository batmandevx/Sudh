package com.shuddh.lab.core

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Fruit polish / wax coating check from the camera alone.
 *
 * A wax or shellac film is optically smooth, so under the phone's flash it throws a large, crisp,
 * colourless (white) specular highlight; natural peel is micro-rough and scatters the flash. We
 * measure the share of the fruit that turns mirror-like when the flash fires, on three sides, and
 * optionally after a hot-water dip (wax clouds over and goes matte — the FSSAI home test, read by
 * the camera instead of the eye). It is an educated estimate, never a lab result.
 */
object WaxCheck {
    /** One frame's surface statistics inside the fruit box. */
    data class Shine(val specular: Double, val peak: Double, val sat: Double, val luma: Double)

    /** [px] are ARGB pixels from the fruit box. */
    fun shine(px: IntArray, step: Int = 2): Shine {
        if (px.isEmpty()) return Shine(0.0, 0.0, 0.0, 0.0)
        val hist = IntArray(256)
        var spec = 0; var n = 0; var satSum = 0.0; var satN = 0; var lSum = 0.0
        var i = 0
        while (i < px.size) {
            val c = px[i]
            val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
            val mx = max(r, max(g, b)); val mn = minOf(r, g, b)
            val l = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            val s = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            hist[l]++; lSum += l; n++
            if (l >= 235 && s < 0.18) spec++ else { satSum += s; satN++ }
            i += step
        }
        fun pct(q: Double): Int { var acc = 0; val t = (n * q).toInt(); for (k in 0..255) { acc += hist[k]; if (acc >= t) return k }; return 255 }
        return Shine(spec.toDouble() / n, (pct(0.99) - pct(0.5)) / 255.0, if (satN == 0) 0.0 else satSum / satN, lSum / n / 255.0)
    }

    /** Median of a list of frames, per field — robust to a stray hand or blink of the flash. */
    fun median(xs: List<Shine>): Shine {
        if (xs.isEmpty()) return Shine(0.0, 0.0, 0.0, 0.0)
        fun m(f: (Shine) -> Double) = xs.map(f).sorted()[xs.size / 2]
        return Shine(m { it.specular }, m { it.peak }, m { it.sat }, m { it.luma })
    }

    /**
     * Gloss index for one side: % of the fruit area that becomes mirror-like under flash, plus a
     * small bonus for a crisp (sharp-edged) highlight.
     */
    fun gloss(ambient: Shine, flash: Shine): Double =
        (100 * (flash.specular - ambient.specular)).coerceAtLeast(0.0) + 4 * (flash.peak - ambient.peak).coerceAtLeast(0.0)

    /** Hot-water haze: relative loss of colour saturation after a dip (wax turns cloudy-white). */
    fun haze(before: Shine, after: Shine): Double = if (before.sat <= 0.01) 0.0 else (before.sat - after.sat) / before.sat

    data class Result(
        val probability: Double,
        val gloss: Double,
        val uniformity: Double,
        val haze: Double?,
        val reference: Double?,
        val confidence: Int,
    ) {
        val waxed get() = probability >= 0.5
        val words get() = when {
            probability >= 0.75 -> "Heavy artificial shine — likely wax-polished"
            probability >= 0.5 -> "Unusually shiny — possibly coated"
            probability >= 0.3 -> "Some shine — could be natural wax"
            else -> "Natural, matte peel — no coating seen"
        }
    }

    private fun sigmoid(z: Double) = 1 / (1 + exp(-z))

    /**
     * Combines the per-side gloss values with the optional hot-water haze and an optional
     * reference gloss from a fruit the user knows is unwaxed (home-grown, farm-fresh).
     */
    fun assess(sides: List<Double>, haze: Double? = null, reference: Double? = null): Result {
        val g = if (sides.isEmpty()) 0.0 else sides.sorted()[sides.size / 2]
        val hi = sides.maxOrNull() ?: 0.0
        val uniformity = if (hi <= 0.05) 1.0 else ((sides.minOrNull() ?: 0.0) / hi).coerceIn(0.0, 1.0)
        // Gloss evidence: absolute thresholds, or the ratio to the user's own unwaxed reference.
        var z = if (reference != null && reference > 0.05) (ln(max(g, 0.05) / reference) - ln(2.2)) / 0.35 else (g - 1.8) / 0.6
        // A coating is uniform; a single glossy side is more likely a wet spot or a lucky angle.
        if (sides.size >= 2) z += (uniformity - 0.5) * 1.2
        haze?.let { z += (it - 0.10) / 0.05 }
        val p = sigmoid(z)
        // Without a hot-water dip the camera only sees shine, so it is kept an honest estimate.
        val cap = if (haze != null) 0.92 else 0.80
        val pc = p.coerceIn(1 - cap, cap)
        val conf = ((max(pc, 1 - pc) * 100).toInt() - (if (sides.size < 3) 8 else 0) + (if (reference != null) 6 else 0)).coerceIn(50, if (haze != null) 90 else 78)
        return Result(pc, g, uniformity, haze, reference, conf)
    }

    /** Why it matters — kept short and factual. */
    val health = listOf(
        "Food-grade waxes (carnauba, shellac, beeswax) are permitted in India only if labelled — most loose fruit isn't.",
        "Petroleum / mineral-oil wax or coal-tar based polish is not food-grade: linked to indigestion and gut irritation, and the film can seal pesticide residue onto the peel.",
        "Wax lets old, stored fruit look freshly picked — shine is not freshness.",
    )
    val clean = listOf(
        "Rinse under warm (not boiling) water for 30 s and rub with a clean cloth.",
        "Soak 10 min in water with 1 tsp baking soda or 1 tbsp vinegar per litre, then rinse.",
        "For heavy shine, peel before giving to children.",
    )
}
