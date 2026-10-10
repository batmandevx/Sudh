package com.shuddh.lab.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Shuddh DART — FSSAI's "Detect Adulteration with Rapid Test" home methods, read objectively by the phone.
 * The chemistry/physics is FSSAI's; the phone turns a by-eye judgement into a number, a verdict and a record.
 */
object Dart {
    enum class Kind { COLOUR, LACTO, FOAM, SPOTS }

    data class Test(
        val id: String,
        val title: String,
        val emoji: String,
        val food: String,
        val adulterant: String,
        val kind: Kind,
        val needs: List<String>,
        val steps: List<String>,
        /** Seconds to wait for the reaction / settling before measuring (0 = none). */
        val waitS: Int,
        val positive: String,
        val negative: String,
        /** Calibration unit for lab mode (what the user spikes in). */
        val unit: String = "%",
        val science: List<String>,
        val fssai: String,
    )

    val tests = listOf(
        Test(
            "starch_milk", "Starch in milk", "🥛", "Milk", "starch", Kind.COLOUR,
            needs = listOf("Milk", "Iodine / Betadine", "White cap", "White paper"),
            steps = listOf(
                "Pour 1 spoon of milk into a white cap or spoon.",
                "Add 2 drops of iodine and swirl gently.",
                "Wait while the colour develops.",
                "Put it on white paper — cap in the coloured box, paper in the white box.",
            ),
            waitS = 30,
            positive = "Starch detected", negative = "No starch",
            unit = "% rice water",
            science = listOf(
                "Starch (flour, rice water, arrowroot) is added to milk to hide added water and fake thickness.",
                "Iodine slips into the coil of starch molecules and forms a blue–black complex; milk alone stays yellow-brown.",
                "The camera measures the sample's colour against white paper in CIELAB and reports how far it moved toward blue versus your control.",
                "Lab mode fits that blue shift against known spiked samples, so the reading becomes a concentration with a measured error.",
            ),
            fssai = "FSSAI DART: Detection of starch in milk — iodine test.",
        ),
        Test(
            "iodised_salt", "Iodised salt", "🧂", "Salt", "iodine", Kind.COLOUR,
            needs = listOf("Salt", "Potato", "Lemon"),
            steps = listOf(
                "Cut a potato in half.",
                "Rub a pinch of the salt on the cut face, then add 2 drops of lemon juice.",
                "Wait while the colour develops.",
                "Put the potato on white paper — spot in the coloured box, paper in the white box.",
            ),
            waitS = 60,
            positive = "Iodised ✓", negative = "Not iodised",
            unit = "ppm iodine",
            science = listOf(
                "India mandates iodised salt to prevent goitre and iodine deficiency in children.",
                "Lemon acid releases iodine from the potassium iodate in salt; it reacts with the potato's own starch to give a blue–violet colour.",
                "The camera measures the blue shift against a control spot (potato + lemon, no salt).",
            ),
            fssai = "FSSAI DART: Common salt — test for iodised salt (potato method).",
        ),
        Test(
            "urea_milk", "Urea in milk", "🧪", "Milk", "urea", Kind.COLOUR,
            needs = listOf("Milk", "Soybean / arhar dal powder", "Red litmus paper", "White paper"),
            steps = listOf(
                "Mix 1 spoon of milk with half a spoon of soybean (or arhar dal) powder in a cup.",
                "Shake well and dip a strip of RED litmus paper in it.",
                "Wait while the colour develops.",
                "Lay the strip on white paper — strip in the coloured box, paper in the white box.",
            ),
            waitS = 30,
            positive = "Urea detected", negative = "No urea",
            unit = "% urea",
            science = listOf(
                "Urea is added to fake protein (it raises the SNF reading) — it harms kidneys.",
                "Soybean contains urease, an enzyme that splits urea into ammonia; ammonia is alkaline and turns red litmus blue.",
                "The camera measures the strip's shift from red toward blue against white paper, compared with a control strip dipped in pure milk + soybean.",
            ),
            fssai = "FSSAI DART: Detection of urea in milk — soybean / red litmus test.",
        ),
        Test(
            "water_milk", "Water in milk", "💧", "Milk", "water", Kind.LACTO,
            needs = listOf("Milk", "Lactometer (₹150)", "Tall glass"),
            steps = listOf(
                "Pour milk into a tall glass, nearly full.",
                "Gently lower the lactometer in and let it float freely, not touching the sides.",
                "Read the number on the stem at the milk surface (e.g. 28).",
                "Enter that reading, the milk's temperature and the milk type below.",
            ),
            waitS = 0,
            positive = "Water added", negative = "No added water",
            science = listOf(
                "Milk is denser than water because of its solids-not-fat (SNF: protein, lactose, minerals). Adding water lowers the density.",
                "A lactometer measures that density as a reading (CLR). It's the method dairies and food inspectors use.",
                "Shuddh corrects the reading for temperature (+0.2 per °C above the lactometer's 27 °C), then computes SNF = CLR/4 + 0.21×fat + 0.36.",
                "Added water = how far SNF falls below the FSSAI minimum for that milk (8.5 % cow, 9.0 % buffalo).",
            ),
            fssai = "FSSAI: Lactometer reading → SNF (Richmond / ISI formula); minimum SNF per FSS Regulations.",
        ),
        Test(
            "detergent_milk", "Detergent in milk", "🫧", "Milk", "detergent", Kind.FOAM,
            needs = listOf("Milk", "Water", "Clear bottle"),
            steps = listOf(
                "Put equal parts milk and water in the bottle (a few spoons each).",
                "Close it and shake hard for 10 seconds.",
                "Stand the bottle up and point the box at the foam layer.",
                "Tap Start — keep the phone still for 60 seconds.",
            ),
            waitS = 0,
            positive = "Detergent detected", negative = "No detergent",
            science = listOf(
                "Detergent is added to emulsify cheap oil into 'synthetic milk'. It's harmful to the gut and liver.",
                "Shaken milk makes a little foam that collapses within seconds; detergent stabilises bubbles, so the foam survives.",
                "The camera measures bubble texture in the foam every half-second and fits how fast it disappears — the foam's half-life.",
            ),
            fssai = "FSSAI DART: Detection of detergent in milk — lather test.",
        ),
        Test(
            "tea_colour", "Colour in tea", "🍵", "Tea leaves", "artificial colour", Kind.SPOTS,
            needs = listOf("Tea leaves", "Tissue / filter paper", "Water"),
            steps = listOf(
                "Lay a white tissue or filter paper flat and wet it evenly with water.",
                "Sprinkle a pinch of the tea leaves over it.",
                "Wait while any dye bleeds out.",
                "Brush the leaves off. Hold the phone over the paper — the wet patch fills the box.",
            ),
            waitS = 60,
            positive = "Artificial colour", negative = "No added colour",
            unit = "% coloured",
            science = listOf(
                "Exhausted or low-grade tea is dyed (tartrazine, sunset yellow, carmoisine) to look rich; the dyes are water-soluble.",
                "On wet white paper real tea leaves stay put; added dye bleeds out immediately as bright yellow, orange or red spots.",
                "The camera counts pixels much brighter and more saturated than the paper — dark leaf fragments are ignored — and compares the coloured area with a control from a trusted brand.",
            ),
            fssai = "FSSAI DART: Detection of colour in tea leaves — wet filter paper test.",
        ),
    )

    fun test(id: String) = tests.first { it.id == id }

    /** Which phone sensors make the measurement in each kind of test. */
    fun sensors(t: Test): List<String> = when (t.kind) {
        Kind.COLOUR -> listOf("📷 Camera colour", "📄 White-paper reference", "🔒 Locked exposure")
        Kind.FOAM -> listOf("📷 Camera texture", "⏱ 60 s time series")
        Kind.SPOTS -> listOf("📷 Camera colour segmentation")
        Kind.LACTO -> listOf("🧪 Lactometer", "📱 SNF model")
    }

    /**
     * Confidence that the call is on the right side of its decision line: the normal CDF of
     * (distance from the line ÷ the measurement's own σ). 50 % = coin toss, capped at 99 %.
     */
    fun confidence(distance: Double, sigma: Double): Double = (100 * phi(abs(distance) / sigma.coerceAtLeast(1e-6))).coerceIn(50.0, 99.0)

    /** Standard normal CDF (Abramowitz–Stegun 7.1.26 erf approximation, |error| < 1.5e-7). */
    fun phi(z: Double): Double {
        val x = abs(z) / sqrt(2.0); val t = 1 / (1 + 0.3275911 * x)
        val erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return if (z >= 0) 0.5 * (1 + erf) else 0.5 * (1 - erf)
    }

    /** Plain-language estimate for a level + confidence. */
    fun estimate(level: Level, conf: Double): String = when (level) {
        Level.SAFE -> if (conf >= 90) "Likely safe" else "Probably safe"
        Level.UNSAFE -> if (conf >= 90) "Likely unsafe" else "Probably unsafe"
        Level.CAUTION -> "Borderline"
        else -> "Can't tell yet"
    } + " · ${conf.toInt()}% sure"

    // ── Colour tests ─────────────────────────────────────────────────────────────

    /** Blue shift of a sample vs its control in CIELAB: positive = bluer (b* fell), plus darkening helps starch–iodine. */
    fun blueShift(controlB: Double, sampleB: Double, controlL: Double, sampleL: Double): Double =
        (controlB - sampleB) + 0.25 * (controlL - sampleL).coerceAtLeast(0.0)

    /** Threshold for a positive colour test, in the same units — about 3× typical repeat-capture noise. */
    const val COLOUR_THRESHOLD = 6.0

    /**
     * Three-way call that never guesses: positive only if the signal is above the threshold by 2σ of its
     * own measured noise, negative only if below by 2σ; anything in between is "retest".
     */
    fun colourCall(signal: Double, sigma: Double): Boolean? = when {
        signal - 2 * sigma > COLOUR_THRESHOLD -> true
        signal + 2 * sigma < COLOUR_THRESHOLD -> false
        else -> null
    }

    /** Lactometers sold in India are calibrated at 27 °C (ISI) or 15.5 °C (60 °F, Quevenne). */
    val lactoCalibrations = listOf(27.0, 15.5)

    /** A corrected reading outside this range isn't milk on a working lactometer (stuck, touching the glass, foam). */
    fun plausibleClr(clr: Double) = clr in 20.0..36.0

    /** Straight-line calibration of signal vs known concentration, with leave-one-out validation. */
    data class Calibration(val slope: Double, val intercept: Double, val r2: Double, val mae: Double, val n: Int, val loo: List<Pair<Double, Double>>) {
        fun predict(signal: Double) = ((signal - intercept) / slope).coerceAtLeast(0.0)
    }

    fun calibrate(points: List<Pair<Double, Double>>): Calibration? {
        if (points.size < 3 || points.map { it.first }.distinct().size < 2) return null
        fun line(ps: List<Pair<Double, Double>>): Pair<Double, Double>? {
            val mx = ps.map { it.first }.average(); val my = ps.map { it.second }.average()
            val sxx = ps.sumOf { (it.first - mx) * (it.first - mx) }
            if (sxx <= 0) return null
            val b = ps.sumOf { (it.first - mx) * (it.second - my) } / sxx
            return b to (my - b * mx)
        }
        val (b, a) = line(points) ?: return null
        if (b <= 0) return null
        val my = points.map { it.second }.average()
        val ssr = points.sumOf { (it.second - (a + b * it.first)).let { r -> r * r } }
        val sst = points.sumOf { (it.second - my) * (it.second - my) }
        // Leave-one-out: predict each known sample from a line fitted to the others — honest accuracy.
        val loo = points.indices.mapNotNull { i ->
            val rest = points.filterIndexed { j, _ -> j != i }
            if (rest.map { it.first }.distinct().size < 2) null else line(rest)?.let { (bb, aa) -> if (bb <= 0) null else points[i].first to ((points[i].second - aa) / bb).coerceAtLeast(0.0) }
        }
        val mae = if (loo.isEmpty()) Double.NaN else loo.map { abs(it.first - it.second) }.average()
        return Calibration(b, a, if (sst <= 0) 0.0 else 1 - ssr / sst, mae, points.size, loo)
    }

    // ── Lactometer ───────────────────────────────────────────────────────────────

    enum class Milk(val label: String, val minSnf: Double, val minFat: Double, val typicalFat: Double) {
        COW("Cow", 8.5, 3.5, 4.0), BUFFALO("Buffalo", 9.0, 6.0, 6.5), MIXED("Mixed / packet", 8.5, 4.5, 4.5), TONED("Toned", 8.5, 3.0, 3.0)
    }

    /** Lactometer reading corrected to its calibration temperature: +0.2 per °C above (−0.2 per °C below). */
    fun correctedClr(reading: Double, tempC: Double, calibC: Double = 27.0) = reading + 0.2 * (tempC - calibC)

    /** SNF % from corrected lactometer reading and fat % (ISI / Richmond formula used by Indian dairies). */
    fun snf(clr: Double, fat: Double) = clr / 4 + 0.21 * fat + 0.36

    /**
     * Added water %: the fraction of the sample that must be water for its SNF to fall that far below the
     * legal minimum. ±1 lactometer division ≈ ±0.25 SNF ≈ ±3 % water.
     */
    fun addedWater(snf: Double, minSnf: Double) = (100 * (minSnf - snf) / minSnf).coerceAtLeast(0.0)

    fun lactoVerdict(water: Double): Pair<Level, String> = when {
        water < 3 -> Level.SAFE to "SNF meets the FSSAI minimum"
        water < 10 -> Level.CAUTION to "About ${water.toInt()}% below standard — some water likely"
        else -> Level.UNSAFE to "About ${water.toInt()}% added water"
    }

    // ── Foam test ────────────────────────────────────────────────────────────────

    /**
     * Foam half-life from (time s, bubble-texture) samples: fit ln(texture − floor) vs t on the decaying part.
     * Returns seconds; a long half-life (or no decay) means stabilised foam.
     */
    fun foamHalfLife(series: List<Pair<Double, Double>>): Double? {
        if (series.size < 10) return null
        val lo = series.minOf { it.second }
        // Fit v = floor + A·e^(−k t): try floors from 0 to just under the minimum, keep the best least-squares fit.
        var best: Triple<Double, Double, Double>? = null // (sse, k, floor)
        for (step in 0..33) {
            val floor = lo * step * 0.03
            val pts = series.filter { it.second - floor > 1e-6 }.map { it.first to ln(it.second - floor) }
            if (pts.size < 8) continue
            val mt = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
            val stt = pts.sumOf { (it.first - mt) * (it.first - mt) }
            if (stt <= 0) continue
            val k = -pts.sumOf { (it.first - mt) * (it.second - my) } / stt
            val a = exp(my + k * mt)
            val sse = series.sumOf { (it.second - (floor + a * exp(-k * it.first))).let { r -> r * r } }
            if (best == null || sse < best.first) best = Triple(sse, k, floor)
        }
        val k = best?.second ?: return null
        return if (k <= 1e-4) Double.POSITIVE_INFINITY else ln(2.0) / k
    }

    /** Fraction of the initial bubble texture still there at the end (robust second metric). */
    fun foamRemaining(series: List<Pair<Double, Double>>): Double? {
        if (series.size < 10) return null
        val start = series.take(4).map { it.second }.average()
        val end = series.takeLast(4).map { it.second }.average()
        return if (start <= 1e-6) null else (end / start).coerceIn(0.0, 2.0)
    }

    fun foamVerdict(remaining: Double, controlRemaining: Double?): Pair<Level, String> {
        val ref = controlRemaining ?: 0.35
        return when {
            remaining < ref + 0.15 -> Level.SAFE to "Foam collapsed like normal milk"
            remaining < ref + 0.35 -> Level.CAUTION to "Foam lasting longer than normal — retest"
            else -> Level.UNSAFE to "Foam is stable — detergent likely"
        }
    }

    // ── Spot / streak tests ──────────────────────────────────────────────────────

    /**
     * A pixel is "dye" if, relative to the paper colour, it is still bright (not a dark leaf) and strongly
     * saturated: max channel ≥ 45 % of paper and (max − min)/max ≥ 0.4.
     */
    fun isDye(r: Double, g: Double, b: Double, pr: Double, pg: Double, pb: Double): Boolean {
        val nr = r / pr.coerceAtLeast(1.0); val ng = g / pg.coerceAtLeast(1.0); val nb = b / pb.coerceAtLeast(1.0)
        val mx = maxOf(nr, ng, nb); val mn = minOf(nr, ng, nb)
        return mx >= 0.45 && (mx - mn) / mx >= 0.4
    }

    /** Positive if the dyed area is clearly above both an absolute floor and the control's own area. */
    fun spotsCall(areaPct: Double, controlPct: Double?): Boolean? {
        val ref = controlPct ?: 0.3
        return when {
            areaPct > maxOf(1.5, ref * 3) -> true
            areaPct < maxOf(0.6, ref * 1.5) -> false
            else -> null
        }
    }

    /** Simulated foam texture for tests: decays with half-life [h] seconds to a floor. */
    fun simulateFoam(t: Double, h: Double, start: Double = 40.0, floor: Double = 4.0) = floor + (start - floor) * exp(-ln(2.0) * t / h)
}
