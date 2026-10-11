package com.shuddh.lab.core

import kotlin.math.exp
import kotlin.math.ln

/**
 * Educated guess for milk without training, from light-scattering physics calibrated on this phone's
 * own reference samples (water, 50/50, pure toned milk, spoiled milk, honey, juice):
 *
 *  - Milk's fat and protein globules scatter the torch light. Reflectance vs milk fraction f follows
 *    R(f) = A·(1 − e^(−k·f)); the reference points water → 0, 50/50 → 0.556, pure → 0.79 give
 *    A = 0.959, k = 1.735. Inverting gives % water.
 *  - Spoiling milk turns yellow (red ≫ blue) and curdles (higher surface texture).
 *  - Honey and juice are strongly coloured; water is clear (reflects almost nothing).
 * Used when fewer than two kinds are trained, and as a cross-check otherwise.
 */
object PurityGuess {
    const val A = 0.959
    const val K = 1.735

    enum class Kind { MILK, WATERED_MILK, SPOILED, WATER, NOT_MILK, UNCLEAR }

    data class Guess(val kind: Kind, val waterPct: Double?, val spread: Double, val confidence: Double, val why: String) {
        val safe get() = kind == Kind.MILK
        val title get() = when (kind) {
            Kind.MILK -> "Looks like pure milk"
            Kind.WATERED_MILK -> "Milk with added water"
            Kind.SPOILED -> "Looks spoiled / curdling"
            Kind.WATER -> "Water — no milk detected"
            Kind.NOT_MILK -> "Not milk (coloured liquid)"
            Kind.UNCLEAR -> "Unclear reading"
        }
    }

    /** Brightness scale for the milk type: fattier milk scatters more (toned 3% = reference). */
    fun fatScale(fatPct: Double) = (1 + 0.035 * (fatPct - 3.0)).coerceIn(0.85, 1.15)

    /** % water from reflectance via the inverted scattering curve. */
    fun waterFrom(refl: Double, fatPct: Double = 3.0): Double {
        val a = A * fatScale(fatPct)
        val r = refl.coerceIn(0.0, a * 0.985)
        val f = (-ln(1 - r / a) / K).coerceIn(0.0, 1.0)
        return (100 * (1 - f)).coerceIn(0.0, 100.0)
    }

    fun reflFrom(waterPct: Double, fatPct: Double = 3.0) = A * fatScale(fatPct) * (1 - exp(-K * (1 - waterPct / 100)))

    fun guess(f: Map<String, Double>, fatPct: Double = 3.0, aiWater: Double? = null): Guess {
        val src = if (f.containsKey("flash_r")) "flash" else "amb"
        val r = f["${src}_r"]; val g = f["${src}_g"]; val b = f["${src}_b"]
        if (r == null || g == null || b == null) return Guess(Kind.UNCLEAR, null, 0.0, 30.0, "The camera got no colour reading.")
        val mean = (r + g + b) / 3
        val yellow = (r - b) / mean.coerceAtLeast(0.05)
        val tex = f["tex"] ?: 0.0
        return when {
            mean < 0.12 -> Guess(Kind.WATER, 100.0, 5.0, 70.0, "Almost no light scattered back — a clear liquid like water, not milk.")
            yellow > 0.75 -> Guess(Kind.NOT_MILK, null, 0.0, 65.0, "Strongly coloured (red ≫ blue) — honey, juice or oil, not milk.")
            yellow > 0.25 && tex > 0.05 -> Guess(Kind.SPOILED, null, 0.0, 62.0, "Yellowish and uneven surface — the pattern of souring, curdling milk.")
            else -> {
                var w = waterFrom(mean, fatPct)
                // The photo-based AI guess is a light second opinion when it roughly agrees.
                if (aiWater != null && kotlin.math.abs(aiWater - w) <= 30) w = 0.75 * w + 0.25 * aiWater
                // Spread: ±0.03 reflectance (positioning, light) mapped through the curve.
                val lo = waterFrom(mean + 0.03, fatPct); val hi = waterFrom(mean - 0.03, fatPct)
                val spread = ((hi - lo) / 2).coerceIn(4.0, 25.0)
                val kind = if (w >= 10) Kind.WATERED_MILK else Kind.MILK
                val conf = (75 - spread).coerceIn(40.0, 70.0) - (if (src == "amb") 10 else 0)
                Guess(kind, w, spread, conf, "Reflectance ${"%.2f".format(mean)} vs ${"%.2f".format(reflFrom(0.0, fatPct))} for pure ${if (fatPct >= 5) "full-cream" else if (fatPct <= 2) "low-fat" else "toned"} milk — fewer fat/protein globules scatter less light." +
                    if (tex > 0.05) " Surface is a little uneven — check freshness." else "")
            }
        }
    }
}
