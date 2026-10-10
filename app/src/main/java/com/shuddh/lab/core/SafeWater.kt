package com.shuddh.lab.core

import kotlin.math.ceil

/**
 * Safe water at home: bleach disinfection dose and an H₂S faecal-contamination test reading.
 *
 * Dose follows the US EPA "Emergency Disinfection of Drinking Water" table: for 6 % sodium hypochlorite,
 * 2 drops per litre of clear water (8 drops per gallon), doubled for cloudy water, 30 minutes contact,
 * then a slight chlorine smell should remain. Other strengths scale inversely with concentration.
 */
object SafeWater {
    /** Volume of one household drop (a dropper or a spoon tip), mL. */
    const val DROP_ML = 0.05

    data class Dose(val drops: Int, val ml: Double, val contactMin: Int)

    fun bleachDose(litres: Double, bleachPct: Double, cloudy: Boolean): Dose {
        val perLitre = 2.0 * (6.0 / bleachPct.coerceIn(1.0, 15.0)) * (if (cloudy) 2 else 1)
        val drops = ceil(perLitre * litres.coerceAtLeast(0.1) - 1e-9).toInt().coerceAtLeast(1)
        return Dose(drops, drops * DROP_ML, 30)
    }

    /** Clarity from text contrast through the water vs the same text seen directly (1 = perfectly clear). */
    fun clarity(throughWater: Double, direct: Double) = if (direct <= 0.01) 0.0 else (throughWater / direct).coerceIn(0.0, 1.2)

    /** Below this the water counts as cloudy: filter through cloth, let it settle, and use the double dose. */
    const val CLOUDY_BELOW = 0.6

    /**
     * H₂S vial reading: positive when the contents have darkened strongly vs the start (ΔL*) and are now dark
     * relative to the white paper — the black iron-sulphide precipitate that faecal bacteria produce.
     */
    fun h2sPositive(startL: Double, nowL: Double): Boolean? = when {
        startL - nowL > 25 && nowL < 45 -> true
        startL - nowL < 10 -> false
        else -> null // partly darkened: wait until 48 h and read again
    }

    // ── Oral rehydration (WHO) ──────────────────────────────────────────────────

    /** WHO home ORS: per litre of safe water, 6 level teaspoons sugar + ½ level teaspoon salt. */
    data class Ors(val litres: Double, val sugarTsp: Double, val saltTsp: Double)

    fun ors(litres: Double) = Ors(litres, 6.0 * litres, 0.5 * litres)

    /** WHO guide for ORS after each loose stool, mL (range). */
    fun orsPerStool(ageYears: Double): IntRange = when {
        ageYears < 2 -> 50..100
        ageYears < 10 -> 100..200
        else -> 200..400
    }

    /** WHO/IMCI danger signs — any one means go to a health facility now. */
    val dangerSigns = listOf(
        "Can't drink or breastfeed, or vomits everything",
        "Very sleepy, hard to wake, or unconscious",
        "Fits (convulsions)",
        "Blood in the stool",
        "Sunken eyes, and a skin pinch goes back very slowly",
        "No urine for 6 hours or more (adults) — dry nappies for 6+ hours (babies)",
        "High fever, or diarrhoea lasting more than 14 days",
    )
}
