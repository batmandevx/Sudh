package com.shuddh.lab.core

import kotlin.math.pow

/**
 * Watermelon checks with phone sensors only.
 *
 * Ripeness: acoustic firmness. A knock excites the fruit's main vibration mode; for a sphere-like fruit the
 * stiffness index S = f² · m^(2/3) (f = resonant frequency in Hz, m = mass in kg) falls as the flesh softens
 * with ripening. The phone gives f (microphone) and the user gives m.
 *
 * Colour: FSSAI's tip for dye-injected watermelon — rub white tissue on the cut flesh. Natural juice leaves a
 * faint pink; erythrosine-type dye leaves a strong red. The camera measures the tissue's redness (CIELAB a*)
 * relative to a clean part of the same tissue.
 */
object Melon {
    fun stiffness(fHz: Double, kg: Double) = fHz.pow(2) * kg.coerceIn(0.5, 20.0).pow(2.0 / 3)

    enum class Dye { NATURAL, BORDERLINE, DYE }

    /** Redness lines (a* units vs clean tissue): below 18 natural pink, above 35 strongly red. */
    val dyeLines = listOf(18.0, 35.0)

    fun dyeCall(aStar: Double): Dye = when {
        aStar < dyeLines[0] -> Dye.NATURAL
        aStar > dyeLines[1] -> Dye.DYE
        else -> Dye.BORDERLINE
    }

    /** Confidence from distance to the nearest line vs ~5 a* units of capture-to-capture noise. */
    fun dyeConfidence(aStar: Double) = Dart.confidence(dyeLines.minOf { kotlin.math.abs(aStar - it) }, 5.0)

    // ── Field spot (ground spot): where the melon rested on the soil ──────────────
    // Creamy-yellow / buttery spot = ripened on the vine; white or pale-green spot = picked early.
    // Measured as yellowness (CIELAB b*) of the spot relative to white paper in the same photo.

    /** Probability the spot says "ripe": logistic in b* centred at 18, ~4 b* units wide. */
    fun spotRipe(bStar: Double) = 1 / (1 + kotlin.math.exp(-(bStar - 18) / 4))

    fun spotWords(bStar: Double, aStar: Double): String = when {
        aStar < -12 -> "this looks like green rind — aim at the pale patch where it lay on the ground"
        bStar >= 24 -> "creamy-yellow field spot — ripened on the vine"
        bStar >= 14 -> "pale-yellow field spot — probably ripe"
        else -> "white field spot — likely picked early"
    }

    /** Combined ripeness from the available signals (each 0..1), with how many signals were used. */
    fun ripeness(spot: Double?, knock: Double?): Pair<Double, Int>? {
        val xs = listOfNotNull(spot, knock)
        return if (xs.isEmpty()) null else xs.average() to xs.size
    }

    // ── Organic-like signs (educated guess, not a certification) ─────────────────
    // Heavy fertiliser/hybrid growing tends to give larger, smoother, more uniform, glossier fruit; natural
    // growing tends to give smaller fruit with more surface blemishes. Each sign is weak, so the estimate is
    // capped at 60 % confidence.

    private fun logistic(x: Double) = 1 / (1 + kotlin.math.exp(-x))

    /** 0..1 "organic-like" score from weight (kg), rind texture (luma CV) and glare fraction (0..1). */
    fun organicScore(kg: Double, tex: Double?, glare: Double?): Double {
        val size = logistic((5.5 - kg) / 1.2)
        val blemish = tex?.let { logistic((it - 0.12) / 0.04) }
        val shine = glare?.let { 1 - logistic((it - 0.03) / 0.01) }
        val parts = listOfNotNull(size to 0.45, blemish?.let { it to 0.35 }, shine?.let { it to 0.2 })
        return parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
    }

    fun organicCall(score: Double): String = when { score >= 0.55 -> "Organic-like signs"; score <= 0.45 -> "Conventional-like signs"; else -> "Can't tell" }

    /** Never above 60 %: these are weak signs. */
    fun organicConfidence(score: Double) = (50 + 20 * kotlin.math.abs(score - 0.5)).coerceAtMost(60.0)
}
