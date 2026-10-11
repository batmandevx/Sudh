package com.shuddh.lab.core

import kotlin.math.abs

/**
 * Three independent opinions on a Purity sample — the trained sensor match, the light-scattering physics
 * model and the on-device AI — fused by weighted vote. Two that agree outvote one; three-way (or close
 * two-way) disagreement means "not sure — retake". Water % is a weighted blend of the estimates that agree.
 */
object PurityFusion {
    enum class Fam(val title: String) { WATER("Water"), MILK("Pure milk"), WATERED("Milk with added water"), SPOILED("Spoiled milk"), NOT_MILK("Not milk"), ADULTERANT("Adulterated milk"), UNKNOWN("Unknown") }

    data class Vote(val source: String, val fam: Fam, val weight: Double)

    data class Fused(val fam: Fam?, val confidence: Double, val agree: Int, val votes: List<Vote>, val unsure: Boolean, val water: Double?, val note: String)

    fun famOfLabel(id: String?, family: String? = null, waterPct: Double? = null): Fam? = when {
        id == null -> null
        id == "water" -> Fam.WATER
        id == "pure_milk" || id == "fresh_milk" -> Fam.MILK
        id == "half" || id == "mix" -> when { (waterPct ?: 50.0) >= 95 -> Fam.WATER; (waterPct ?: 50.0) <= 5 -> Fam.MILK; else -> Fam.WATERED }
        id == "spoiled_milk" -> Fam.SPOILED
        id == "honey" || id == "juice" || id == "not_milk" || family == "honey" || family == "juice" || family == "other" -> Fam.NOT_MILK
        family == "adulterant" || id.endsWith("_milk") -> Fam.ADULTERANT
        id == "unknown" -> Fam.UNKNOWN
        family == "milk" -> Fam.MILK
        else -> Fam.UNKNOWN
    }

    fun famOfAi(op: PurityAi.Opinion?): Fam? = op?.label?.let { famOfLabel(it, null, op.waterPct) } ?: when (op?.sample) {
        "water" -> Fam.WATER
        "milk" -> if ((op.waterPct ?: 0.0) >= 10) Fam.WATERED else Fam.MILK
        "diluted milk" -> Fam.WATERED
        "spoiled milk" -> Fam.SPOILED
        "adulterated" -> Fam.ADULTERANT
        "other" -> Fam.NOT_MILK
        else -> null
    }

    fun famOfGuess(k: PurityGuess.Kind) = when (k) {
        PurityGuess.Kind.MILK -> Fam.MILK; PurityGuess.Kind.WATERED_MILK -> Fam.WATERED; PurityGuess.Kind.SPOILED -> Fam.SPOILED
        PurityGuess.Kind.WATER -> Fam.WATER; PurityGuess.Kind.NOT_MILK -> Fam.NOT_MILK; PurityGuess.Kind.UNCLEAR -> Fam.UNKNOWN
    }

    /** Weighted average of water estimates that lie within 25 points of their median (outliers dropped). */
    fun water(estimates: List<Pair<Double, Double>>): Double? {
        val e = estimates.filter { it.second > 0 }
        if (e.isEmpty()) return null
        val med = e.map { it.first }.sorted()[e.size / 2]
        val ok = e.filter { abs(it.first - med) <= 25 }.ifEmpty { e }
        return ok.sumOf { it.first * it.second } / ok.sumOf { it.second }
    }

    fun fuse(votes0: List<Vote?>, waters: List<Pair<Double, Double>> = emptyList()): Fused {
        val votes = votes0.filterNotNull().filter { it.weight > 0.01 }
        if (votes.isEmpty()) return Fused(null, 0.0, 0, votes, true, water(waters), "No opinion available.")
        val byFam = votes.groupBy { it.fam }.mapValues { (_, v) -> v.sumOf { it.weight } }
        val (best, bw) = byFam.maxBy { it.value }
        val total = byFam.values.sum()
        val agree = votes.count { it.fam == best }
        val second = byFam.filterKeys { it != best }.values.maxOrNull() ?: 0.0
        val unsure = when {
            votes.size >= 3 && agree == 1 -> true                 // everyone disagrees
            votes.size == 2 && agree == 1 && bw - second < 0.15 -> true  // two close, opposite opinions
            else -> false
        }
        val conf = (100 * bw / total * (0.62 + 0.14 * agree)).coerceIn(30.0, 95.0)
        val who = votes.groupBy { it.fam }.entries.joinToString(" · ") { (f, v) -> "${v.joinToString("+") { it.source }}: ${f.title}" }
        return Fused(if (unsure) null else best, if (unsure) conf.coerceAtMost(45.0) else conf, agree, votes, unsure, water(waters), who)
    }
}
