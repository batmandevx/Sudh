package com.shuddh.lab.core

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Lock-in nephelometry for Shuddh Hawa.
 *
 * The flash is switched on and off in cycles while the camera watches the beam from the side.
 * For every ON segment the mean of the neighbouring OFF segments is subtracted, so ambient light,
 * stray light and slow drift cancel — only light scattered *from the flash* by particles remains.
 * Frames just after each switch are dropped while the LED and sensor settle.
 */
object Nephelo {
    data class Frame(val tMs: Long, val on: Boolean, val r: Double, val g: Double, val b: Double, val sparkle: Double) {
        val luma get() = 0.299 * r + 0.587 * g + 0.114 * b
    }

    data class Reading(
        /** Flash-attributable scatter (ON − OFF), luma. */
        val signal: Double,
        /** 95 % CI half-width over cycles. */
        val ci: Double,
        val r: Double, val g: Double, val b: Double,
        /** Mean sparkle (fraction of bright specks) in ON frames minus OFF — coarse particles. */
        val sparkle: Double,
        val cycles: Int,
        val perCycle: List<Double>,
    )

    private class Seg(val on: Boolean, val frames: List<Frame>)

    /** Splits frames into ON/OFF segments, dropping [settleMs] after each switch. */
    private fun segments(frames: List<Frame>, settleMs: Long): List<Seg> {
        val out = mutableListOf<Seg>()
        var cur = mutableListOf<Frame>(); var start = 0L
        for (f in frames) {
            if (cur.isEmpty() || cur.last().on != f.on) {
                if (cur.isNotEmpty()) out += Seg(cur.last().on, cur.filter { it.tMs - start >= settleMs })
                cur = mutableListOf(); start = f.tMs
            }
            cur += f
        }
        if (cur.isNotEmpty()) out += Seg(cur.last().on, cur.filter { it.tMs - start >= settleMs })
        return out.filter { it.frames.isNotEmpty() }
    }

    fun lockIn(frames: List<Frame>, settleMs: Long = 250): Reading? {
        val segs = segments(frames, settleMs)
        data class D(val l: Double, val r: Double, val g: Double, val b: Double, val s: Double)
        fun mean(fs: List<Frame>) = D(fs.map { it.luma }.average(), fs.map { it.r }.average(), fs.map { it.g }.average(), fs.map { it.b }.average(), fs.map { it.sparkle }.average())
        val diffs = mutableListOf<D>()
        segs.forEachIndexed { i, s ->
            if (!s.on) return@forEachIndexed
            val offs = listOfNotNull(segs.getOrNull(i - 1)?.takeIf { !it.on }, segs.getOrNull(i + 1)?.takeIf { !it.on })
            if (offs.isEmpty()) return@forEachIndexed
            val on = mean(s.frames); val off = offs.map { mean(it.frames) }
            fun avg(f: (D) -> Double) = off.map(f).average()
            diffs += D(on.l - avg { it.l }, on.r - avg { it.r }, on.g - avg { it.g }, on.b - avg { it.b }, on.s - avg { it.s })
        }
        if (diffs.size < 2) return null
        // Robust centre: median luma difference; reject cycles > 3 MAD away (a bump, a passing shadow).
        val ls = diffs.map { it.l }
        val med = Dsp.median(ls)
        val mad = Dsp.median(ls.map { kotlin.math.abs(it - med) }).coerceAtLeast(0.05)
        val kept = diffs.filter { kotlin.math.abs(it.l - med) <= 3 * 1.4826 * mad }.ifEmpty { diffs }
        val m = kept.map { it.l }.average()
        val sd = if (kept.size > 1) sqrt(kept.sumOf { (it.l - m) * (it.l - m) } / (kept.size - 1)) else 0.0
        return Reading(
            m, 1.96 * sd / sqrt(kept.size.toDouble()),
            kept.map { it.r }.average(), kept.map { it.g }.average(), kept.map { it.b }.average(),
            kept.map { it.s }.average(), kept.size, kept.map { it.l },
        )
    }

    /** Sample scatter relative to the clean baseline (≥ 0); 1 = as clean as the baseline. */
    fun ratio(sample: Reading, clean: Reading): Double = (sample.signal / clean.signal.coerceAtLeast(0.3)).coerceAtLeast(0.0)

    /** Uncertainty of the ratio by error propagation. */
    fun ratioCi(sample: Reading, clean: Reading): Double {
        val r = ratio(sample, clean)
        val a = sample.ci / sample.signal.coerceAtLeast(0.3); val b = clean.ci / clean.signal.coerceAtLeast(0.3)
        return r * sqrt(a * a + b * b)
    }

    /**
     * Ångström-style exponent of the *excess* scatter (sample − clean) between blue (~465 nm) and
     * red (~610 nm). Fine particles (smoke, < 1 µm) scatter blue much more (α ≳ 1.3); coarse dust
     * and mist scatter all colours alike (α ≲ 0.5). Null when the excess is too small to tell.
     */
    fun angstrom(sample: Reading, clean: Reading): Double? {
        val er = sample.r - clean.r; val eb = sample.b - clean.b
        if (er < 0.8 || eb < 0.8) return null
        // Normalise by the clean channel response to cancel the LED's and sensor's own colour.
        val nr = er / clean.r.coerceAtLeast(0.5); val nb = eb / clean.b.coerceAtLeast(0.5)
        return -ln(nb / nr) / ln(465.0 / 610.0)
    }

    fun particleType(alpha: Double?, sparkleExcess: Double): String = when {
        sparkleExcess > 0.004 -> "Coarse dust / pollen (visible specks)"
        alpha == null -> "Too little haze to classify"
        alpha >= 1.3 -> "Fine smoke (combustion, incense, mosquito coil)"
        alpha >= 0.6 -> "Mixed fine + coarse particles"
        else -> "Coarse dust, flour or mist"
    }

    data class Band(val name: String, val min: Double, val advice: String)

    val airBands = listOf(
        Band("Clean", 0.0, "Air is as clear as your baseline."),
        Band("Light haze", 1.3, "Some particles — open a window."),
        Band("Moderate", 1.8, "Ventilate; avoid incense or frying smoke."),
        Band("Heavy", 2.6, "Smoky — run the exhaust fan, step out if it persists."),
        Band("Severe", 4.0, "Very smoky — leave the room, check for fire or burning food."),
    )
    val waterBands = listOf(
        Band("Clear", 0.0, "As clear as your reference water."),
        Band("Slightly cloudy", 1.3, "Let it settle, then filter."),
        Band("Cloudy", 2.0, "Filter and boil before drinking."),
        Band("Turbid", 3.5, "Don't drink — use another source or treat thoroughly."),
    )

    fun band(r: Double, bands: List<Band>): Band = bands.last { r >= it.min }
}
