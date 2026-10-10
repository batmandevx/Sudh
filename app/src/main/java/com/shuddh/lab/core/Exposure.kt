package com.shuddh.lab.core

/**
 * True Price — turns a dilution result into money: if milk is 30 % water, every "litre" you pay
 * for holds only 0.7 L of milk, so the real price per litre of milk is paid ÷ 0.7.
 */
object TruePrice {
    private val dilutionIds = setOf("milk_water", "nir_water", "honey_polar", "honey_water")

    fun applies(analyteId: String, unit: String) = unit == "%" && analyteId in dilutionIds

    /** Price per litre (or kg) of the genuine product. */
    fun real(paid: Double, adulterantPct: Double): Double = paid / (1 - adulterantPct.coerceIn(0.0, 95.0) / 100)

    /** Money paid for the adulterant per month, at [qtyPerDay] litres/kg a day. */
    fun lostPerMonth(paid: Double, adulterantPct: Double, qtyPerDay: Double): Double = paid * qtyPerDay * 30 * adulterantPct.coerceIn(0.0, 95.0) / 100

    fun product(analyteId: String) = if (analyteId.startsWith("honey")) "honey" to "kg" else "milk" to "L"
}

/**
 * Family Exposure Ledger — cumulative intake, not single verdicts. For each contaminant, the
 * concentration in the family's water is taken from the most recent test in effect on each day
 * (tests stay valid until the next one), times how much that person drinks, as a share of a
 * health-based daily limit for their body weight.
 */
object Exposure {
    data class Member(val name: String, val emoji: String, val weightKg: Double, val waterL: Double)

    data class Contaminant(val key: String, val name: String, val ids: Set<String>, val toMg: Double, val limitMgPerKgDay: Double, val basis: String)

    val contaminants = listOf(
        Contaminant("no3", "Nitrate", setOf("no3"), 1.0, 3.7, "WHO/JECFA acceptable daily intake 3.7 mg/kg/day"),
        Contaminant("f", "Fluoride", setOf("f", "strip_f"), 1.0, 0.1, "EFSA tolerable upper intake 0.1 mg/kg/day"),
        Contaminant("as", "Arsenic", setOf("strip_as"), 0.001, 0.0003, "Benchmark equal to the WHO 10 µg/L guideline (0.3 µg/kg/day)"),
    )

    val defaultFamily = listOf(Member("Child", "🧒", 18.0, 1.0), Member("Adult", "🧑", 60.0, 2.5), Member("Elder", "👵", 55.0, 2.0))

    data class Sample(val time: Long, val analyteId: String, val value: Double)

    data class Result(
        val contaminant: Contaminant,
        /** Average % of the daily limit over the window (days with a known concentration). */
        val avgPct: Double,
        val peakPct: Double,
        /** Daily % of limit for each day of the window (null = no test yet). */
        val daily: List<Double?>,
        val knownDays: Int,
    )

    fun ledger(samples: List<Sample>, m: Member, now: Long, days: Int = 30): List<Result> {
        val dayMs = 86_400_000L
        return contaminants.mapNotNull { c ->
            val s = samples.filter { it.analyteId in c.ids }.sortedBy { it.time }
            if (s.isEmpty()) return@mapNotNull null
            val daily = (days - 1 downTo 0).map { back ->
                val dayEnd = now - back * dayMs
                s.lastOrNull { it.time <= dayEnd }?.let { 100 * it.value * c.toMg * m.waterL / (c.limitMgPerKgDay * m.weightKg) }
            }
            val known = daily.filterNotNull()
            if (known.isEmpty()) return@mapNotNull null
            Result(c, known.average(), known.max(), daily, known.size)
        }
    }

    fun headline(m: Member, results: List<Result>): String {
        val worst = results.maxByOrNull { it.avgPct } ?: return "No water tests yet — test nitrate, fluoride or arsenic to start ${m.name.lowercase()}'s ledger."
        return "This month ${m.name} took in about ${worst.avgPct.toInt()}% of the safe ${worst.contaminant.name.lowercase()} limit, from drinking water."
    }
}
