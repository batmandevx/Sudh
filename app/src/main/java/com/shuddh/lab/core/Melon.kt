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
}
