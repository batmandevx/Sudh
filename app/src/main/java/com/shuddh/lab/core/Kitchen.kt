package com.shuddh.lab.core

import kotlin.math.ln

/**
 * Boil Guard: hears a pot of water come to a rolling boil.
 *
 * Heating water gets louder (convection, then "singing" as vapour bubbles collapse in still-cool
 * water). At a true rolling boil, bubbles reach the surface instead of collapsing, so the hiss
 * drops a few dB from its peak while staying well above the room — and the sound becomes
 * irregular (high spectral flux from bursting bubbles). Either pattern, held for a few seconds,
 * means boiling. Pure state machine over (level dB, flux) frames — unit-tested.
 */
class BoilDetector(private val fps: Double = 21.5) {
    enum class Stage(val label: String, val emoji: String) {
        LISTENING("Learning the room", "👂"), COLD("Not heating yet", "💧"), HEATING("Heating up", "🌡"),
        SINGING("Almost there — singing", "🎵"), BOILING("Rolling boil", "♨️"), STOPPED("Boil stopped", "⏸"),
    }

    var stage = Stage.LISTENING; private set
    var baseline = Double.NaN; private set
    var level = Double.NaN; private set
    var peak = Double.NEGATIVE_INFINITY; private set
    /** 0..1 progress toward the boil, for the UI. */
    var progress = 0.0; private set
    private val learn = ArrayList<Double>()
    private val fluxLearn = ArrayList<Double>()
    private var fluxBase = 1.0
    private var fluxS = 0.0
    private var dropFrames = 0
    private var fluxFrames = 0
    private var quietFrames = 0

    /** Feeds one frame: band level in dB and spectral flux. Returns the stage. */
    fun push(levelDb: Double, flux: Double): Stage {
        level = if (level.isNaN()) levelDb else 0.9 * level + 0.1 * levelDb
        fluxS = 0.9 * fluxS + 0.1 * flux
        if (stage == Stage.LISTENING) {
            learn += levelDb; fluxLearn += flux
            if (learn.size >= (fps * 3).toInt()) {
                baseline = learn.sorted()[learn.size / 2]
                fluxBase = fluxLearn.sorted()[fluxLearn.size / 2].coerceAtLeast(1e-6)
                stage = Stage.COLD
            }
            return stage
        }
        val rise = level - baseline
        if (stage != Stage.BOILING && stage != Stage.STOPPED) peak = maxOf(peak, level)
        progress = (rise / 12.0).coerceIn(0.0, 0.95)
        val hold = (fps * 4).toInt()
        when (stage) {
            Stage.COLD -> if (rise >= 4) stage = Stage.HEATING
            Stage.HEATING, Stage.SINGING -> {
                if (rise >= 10) stage = Stage.SINGING
                // Pattern A: singing peak, then a ≥3 dB drop while still loud.
                dropFrames = if (stage == Stage.SINGING && level <= peak - 3 && rise >= 6) dropFrames + 1 else 0
                // Pattern B: loud and bubbling irregularly (pots that never "sing").
                fluxFrames = if (rise >= 8 && fluxS >= 2.0 * fluxBase) fluxFrames + 1 else 0
                if (dropFrames >= hold || fluxFrames >= (fps * 6).toInt()) { stage = Stage.BOILING; progress = 1.0 }
                if (rise < 2) { stage = Stage.COLD; peak = level }
            }
            Stage.BOILING -> {
                progress = 1.0
                quietFrames = if (rise < 4) quietFrames + 1 else 0
                if (quietFrames >= (fps * 5).toInt()) stage = Stage.STOPPED
            }
            Stage.STOPPED -> if (rise >= 8) { stage = Stage.BOILING; quietFrames = 0 }
            Stage.LISTENING -> {}
        }
        return stage
    }

    /** User says "it's boiling" (fallback when the room is too noisy). */
    fun forceBoiling() { stage = Stage.BOILING; progress = 1.0 }
}

object BoilPhysics {
    /** Boiling point of water (°C) at pressure [hPa] by Clausius–Clapeyron (ΔHvap 40.66 kJ/mol). */
    fun boilingPointC(hPa: Double): Double {
        val r = 8.314; val l = 40_660.0
        return 1.0 / (1.0 / 373.15 - r * ln(hPa / 1013.25) / l) - 273.15
    }

    /** WHO: bring water to a rolling boil for 1 minute; 3 minutes above 2,000 m. */
    fun safeBoilSeconds(altitudeM: Double?): Int = if ((altitudeM ?: 0.0) > 2000) 180 else 60
}

/**
 * Oil Check: how degraded is cooking oil? Repeated frying oxidises and polymerises oil, which
 * darkens it (browning) — the colour tracks total polar compounds (FSSAI limit 25 % TPC).
 * Colour is measured relative to a white card in the same frame (CIELAB), compared with fresh
 * oil of the same kind when available.
 */
object OilIndex {
    /** Browning index from CIELAB (Buera et al.). */
    fun browning(l: Double, a: Double, b: Double): Double {
        val x = (a + 1.75 * l) / (5.645 * l + a - 3.012 * b)
        return 100 * (x - 0.31) / 0.17
    }

    data class Grade(val darkening: Double, val reuses: Int, val level: Level, val label: String, val advice: String)

    /** Typical refined-oil lightness through ~1 cm on white, used when no fresh sample was saved. */
    const val TYPICAL_FRESH_L = 86.0

    /**
     * Darkening = lightness lost plus a share of the browning gained versus fresh oil.
     * Each deep-frying cycle darkens oil by roughly 5–8 units on this scale (screening heuristic).
     */
    fun grade(sample: Lab3, fresh: Lab3?): Grade {
        val fl = fresh?.l ?: TYPICAL_FRESH_L
        val fbi = fresh?.let { browning(it.l, it.a, it.b) } ?: browning(TYPICAL_FRESH_L, -2.0, 30.0)
        val bi = browning(sample.l, sample.a, sample.b)
        val d = ((fl - sample.l) + 0.15 * (bi - fbi)).coerceAtLeast(0.0)
        val reuses = (d / 6.5).toInt()
        return when {
            d < 8 -> Grade(d, reuses, Level.SAFE, "Fresh", "Oil looks fresh. Fine to use.")
            d < 20 -> Grade(d, reuses, Level.CAUTION, "Reused", "Already reused ~$reuses× — use once more at most, for shallow cooking.")
            else -> Grade(d, reuses, Level.UNSAFE, "Discard", "Heavily reused (~$reuses×). Don't fry with it — hand it to a used-oil (RUCO) collector, never pour it down the drain.")
        }
    }

    data class Lab3(val l: Double, val a: Double, val b: Double)
}
