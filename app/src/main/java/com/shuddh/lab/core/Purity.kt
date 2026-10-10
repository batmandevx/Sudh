package com.shuddh.lab.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Purity Check — added water in milk or honey from a fixed-depth layer in a bottle cap, measured
 * against a white card in the same photo (lighting and exposure cancel).
 *
 * MILK scatters light (fat globules, casein micelles). Over a dark cap, the reflectance of the layer
 * follows Kubelka–Munk: R(SX, K/S, Rg). Watering scales S and K together (water itself neither
 * scatters nor absorbs), so the milk fraction m scales SX. Three references from the user's own milk
 * — plain water (m = 0, gives Rg), a 50/50 mix and pure milk — fix SX and K/S; any sample is then
 * inverted for m. This stays accurate where a straight-line calibration bends (deep layers, slight absorption).
 *
 * HONEY absorbs (amber). Over a white cap, Beer–Lambert gives A = −ln(R / R_water) ∝ honey fraction.
 * The 50 % mix checks linearity and joins a least-squares fit through the origin.
 *
 * Each reading has three colour channels; calibration picks the most sensitive usable channel.
 */
object Purity {
    /** How a liquid's light response changes with dilution. */
    enum class Physics { SCATTER, ABSORB, GENERIC }

    /** Liquids the user can test. [adulterant] is what the low reference is made of; [darkCap] = scattering liquids need a dark cap. */
    enum class Kind(val label: String, val emoji: String, val physics: Physics, val adulterant: String, val darkCap: Boolean) {
        MILK("Milk", "🥛", Physics.SCATTER, "water", true),
        HONEY("Honey", "🍯", Physics.ABSORB, "water", false),
        OIL("Oil", "🫒", Physics.GENERIC, "cheaper oil", false),
        JUICE("Juice", "🧃", Physics.GENERIC, "water", false),
        OTHER("Other", "🧪", Physics.GENERIC, "water", false),
    }

    /**
     * Any scalar sensor reading calibrated by three references (adulterant, 50/50, pure): piecewise-linear
     * inversion. Usable only if the 50/50 lies between the other two (a real, monotonic trend).
     */
    data class Scalar3(val w: Double, val h: Double, val p: Double) {
        val monotonic get() = (h - w) * (p - h) > 0
        fun fraction(v: Double): Double {
            val (x0, x1, f0) = if ((v - h) * (w - h) >= 0) Triple(w, h, 0.0) else Triple(h, p, 0.5)
            return (f0 + 0.5 * (v - x0) / (x1 - x0)).coerceIn(0.0, 1.0)
        }
        fun adulterantPct(v: Double) = 100 * (1 - fraction(v))
        /** |d(adulterant %)/d(value)| near [v] — converts sensor noise into % uncertainty. */
        fun pctPerUnit(v: Double): Double = 50.0 / maxOf(abs(if ((v - h) * (w - h) >= 0) h - w else p - h), 1e-9)
    }

    /** Kubelka–Munk reflectance of a layer of scattering power [sx] and absorption ratio [ks] over background [rg]. */
    fun km(sx: Double, ks: Double, rg: Double): Double {
        if (sx <= 1e-9) return rg
        val a = 1 + ks; val b = sqrt(a * a - 1).coerceAtLeast(1e-6)
        val c = 1 / tanh(b * sx)
        return (1 - rg * (a - b * c)) / (a - rg + b * c)
    }

    private fun bisect(lo0: Double, hi0: Double, f: (Double) -> Double): Double {
        var lo = lo0; var hi = hi0; val flo = f(lo)
        repeat(80) { val mid = (lo + hi) / 2; if ((f(mid) > 0) == (flo > 0)) lo = mid else hi = mid }
        return (lo + hi) / 2
    }

    /** Calibration in one colour channel. For MILK: sx0 = scattering power of pure milk, ks, rg. For HONEY: a0 = absorbance of pure honey, rw = water blank. */
    data class Model(val kind: Kind, val channel: Int, val sx0: Double = 0.0, val ks: Double = 0.0, val rg: Double = 0.0, val a0: Double = 0.0, val rw: Double = 1.0, val rh: Double = 0.0, val rp: Double = 0.0, val generic: Boolean = false) {
        /** Product fraction m (1 = pure) for reflectance [r] in this channel. */
        fun fraction(r: Double): Double = when (if (generic) Physics.GENERIC else kind.physics) {
            Physics.GENERIC -> Scalar3(rw, rh, rp).fraction(r)
            Physics.SCATTER -> {
                val rInf = (1 + ks) - sqrt((1 + ks) * (1 + ks) - 1)
                val rr = r.coerceIn(rg + 1e-4, if (ks > 1e-6) rInf - 1e-4 else 0.9999)
                bisect(0.0, sx0 * 4) { km(it, ks, rg) - rr } / sx0
            }
            Physics.ABSORB -> -ln(r.coerceIn(1e-4, rw) / rw) / a0
        }.coerceIn(0.0, 1.0)

        /** d(water %)/dR — converts reflectance noise to water-% uncertainty. */
        fun waterPerR(r: Double): Double { val h = 0.002; return 100 * (fraction(r - h) - fraction(r + h)) / (2 * h) }

        fun waterPct(r: Double) = 100 * (1 - fraction(r))
    }

    /** Reflectances (sample ÷ white card) per channel R,G,B. */
    data class Refl(val rgb: DoubleArray) { operator fun get(i: Int) = rgb[i] }

    /** Fit from water blank, 50/50 mix and pure product. Returns null if the setup cannot distinguish them. */
    fun calibrate(kind: Kind, water: Refl, half: Refl, pure: Refl): Model? {
        // Physics model first; if it can't fit (e.g. pure milk as bright as the paper), fall back to the
        // three-point curve, which only needs the 50/50 to lie between the other two.
        val models = (0..2).mapNotNull { ch ->
            (fitChannel(kind, water[ch], half[ch], pure[ch]) ?: fitGeneric(kind, water[ch], half[ch], pure[ch]))?.copy(channel = ch)
        }.filter { m ->
            // Only channels that really see the difference: ≥3 % reflectance between water and pure,
            // and the 50/50 sitting in the middle of that span (not stuck near one end = saturated / noise).
            val span = pure[m.channel] - water[m.channel]
            val pos = (half[m.channel] - water[m.channel]) / span
            abs(span) > 0.03 && (if (m.generic) pos in 0.15..0.85 else pos in 0.05..0.97)
        }
        // Most sensitive = largest reflectance change per % water around the middle of the range.
        return models.maxByOrNull { m -> abs(pure[m.channel] - half[m.channel]) }
    }

    private fun fitGeneric(kind: Kind, rw: Double, rh: Double, rp: Double): Model? =
        if ((rh - rw) * (rp - rh) > 0 && abs(rh - rw) > 0.005 && abs(rp - rh) > 0.005) Model(kind, 0, rw = rw, rh = rh, rp = rp, generic = true) else null

    fun fitChannel(kind: Kind, rw: Double, rh: Double, rp: Double): Model? = when (kind.physics) {
        Physics.GENERIC -> fitGeneric(kind, rw, rh, rp)
        Physics.SCATTER -> {
            // Pure must scatter more than the mix, and the mix more than water — with real margins.
            if (!(rp - rh > 0.01 && rh - rw > 0.01) || rp >= 0.995) null else {
                val rg = rw
                // Find ks so that halving SX (fixed by the pure reading) reproduces the 50 % reading.
                fun sxFor(r: Double, ks: Double): Double? {
                    val rInf = (1 + ks) - sqrt((1 + ks) * (1 + ks) - 1)
                    if (r >= rInf - 1e-6) return null
                    return bisect(0.0, 200.0) { km(it, ks, rg) - r }
                }
                fun err(ks: Double): Double { val sx = sxFor(rp, ks) ?: return 1.0; return km(sx / 2, ks, rg) - rh }
                val ksMax = generateSequence(1e-6) { it * 1.5 }.takeWhile { it < 5 }.lastOrNull { sxFor(rp, it) != null } ?: 1e-6
                val ks = if (err(1e-6) >= 0) 1e-6 else if (err(ksMax) <= 0) ksMax else bisect(1e-6, ksMax) { err(it) }
                sxFor(rp, ks)?.let { Model(kind, 0, sx0 = it, ks = ks, rg = rg) }
            }
        }
        Physics.ABSORB -> {
            if (!(rw - rh > 0.01 && rh - rp > 0.01) || rp < 0.03) null else {
                val ah = -ln(rh / rw); val ap = -ln(rp / rw)
                // Least squares through origin on (0.5, ah), (1, ap).
                val a0 = (0.5 * ah + ap) / (0.25 + 1.0)
                Model(kind, 0, a0 = a0, rw = rw)
            }
        }
    }

    /** How far the 50 % reference sits from what the fitted model predicts (in % water) — a self-check of the setup. */
    fun linearityError(m: Model, half: Refl): Double = kotlin.math.abs(m.waterPct(half[m.channel]) - 50)

    data class Verdict(val level: Level, val label: String, val advice: String)

    fun verdict(kind: Kind, pct: Double): Verdict {
        val p = kind.label.lowercase(); val ad = kind.adulterant
        return when {
            pct < 8 -> Verdict(Level.SAFE, "Not adulterated", "Matches your pure $p — no meaningful added $ad.")
            pct < 18 -> Verdict(Level.CAUTION, "Possibly adulterated", "About ${pct.toInt()}% $ad — retest with a fresh sample to confirm.")
            else -> Verdict(Level.UNSAFE, "Adulterated", "About ${pct.toInt()}% of what you paid for is $ad, not $p.")
        }
    }

    /** What eating/drinking this adulterated product does — shown with any non-safe verdict. */
    fun healthEffects(kind: Kind): List<String> = when (kind) {
        Kind.MILK -> listOf(
            "Less protein, calcium and vitamins than you paid for — a real gap for children, pregnant women and the elderly who rely on milk.",
            "The added water is often untreated — a route for diarrhoea, typhoid, cholera and hepatitis A.",
            "Watered milk is often 'corrected' with starch, sugar, urea or detergent to hide it — these strain the kidneys and irritate the gut.",
        )
        Kind.HONEY -> listOf(
            "Sugar-syrup honey raises blood sugar quickly — risky for people with diabetes.",
            "Loses the natural enzymes and antioxidants that make honey worth buying.",
            "Watered honey ferments and spoils faster.",
        )
        Kind.OIL -> listOf(
            "Cheaper or reused oils carry oxidation products linked to heart disease and liver strain.",
            "Cheap edible oils have been found mixed with argemone oil, which causes epidemic dropsy (swelling, heart failure).",
        )
        Kind.JUICE -> listOf(
            "Unsafe water in juice is a common cause of diarrhoea, typhoid and hepatitis A — especially from street stalls.",
            "Less vitamin C and fruit nutrients than you paid for; often topped up with sugar and colour.",
        )
        Kind.OTHER -> listOf("Diluted food gives less nutrition than paid for, and untreated water or unknown additives can cause stomach infections.")
    }
}
