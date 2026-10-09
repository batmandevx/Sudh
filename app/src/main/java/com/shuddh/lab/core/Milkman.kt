package com.shuddh.lab.core

import java.util.Calendar

/**
 * Milkman Ledger — daily litres + the purity tests you already run. Any day with a water-in-milk
 * result counts as watered by that %; month-end shows what the milk was really worth.
 */
object Milkman {
    data class Day(val dayKey: Int, val litres: Double)
    data class Test(val time: Long, val waterPct: Double)

    data class DayView(val dayKey: Int, val litres: Double, val waterPct: Double?)

    data class Month(val days: List<DayView>, val litres: Double, val billed: Double, val fair: Double, val wateredDays: Int, val waterLitres: Double) {
        val overpaid get() = billed - fair
    }

    /** yyyymmdd for a timestamp. */
    fun key(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 10000 + (it.get(Calendar.MONTH) + 1) * 100 + it.get(Calendar.DAY_OF_MONTH) }

    /** A test applies to its own day and stays in effect for up to 3 following days (same milkman, same habit). */
    fun month(days: List<Day>, tests: List<Test>, pricePerL: Double, monthStartKey: Int): Month {
        val inMonth = days.filter { it.dayKey / 100 == monthStartKey / 100 }.sortedBy { it.dayKey }
        val testByKey = tests.groupBy { key(it.time) }.mapValues { (_, v) -> v.maxOf { it.waterPct } }
        val views = inMonth.map { d ->
            val w = testByKey[d.dayKey] ?: (1..3).firstNotNullOfOrNull { back -> testByKey[shift(d.dayKey, -back)] }
            DayView(d.dayKey, d.litres, w)
        }
        val litres = views.sumOf { it.litres }
        val billed = litres * pricePerL
        val water = views.sumOf { v -> v.litres * ((v.waterPct ?: 0.0).coerceIn(0.0, 90.0) / 100) }
        val watered = views.count { (it.waterPct ?: 0.0) >= 3 }
        return Month(views, litres, billed, billed - water * pricePerL, watered, water)
    }

    private fun shift(k: Int, d: Int): Int {
        val c = Calendar.getInstance().apply { set(k / 10000, (k / 100) % 100 - 1, k % 100); add(Calendar.DAY_OF_YEAR, d) }
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    fun message(m: Month, price: Double, monthName: String): String =
        "Milk bill $monthName: ${fmt(m.litres)} L × ₹${price.toInt()} = ₹${m.billed.toInt()}. " +
            (if (m.wateredDays > 0) "My Shuddh tests found added water on ${m.wateredDays} days (≈${fmt(m.waterLitres)} L of water). Fair amount: ₹${m.fair.toInt()} — please adjust ₹${m.overpaid.toInt()}."
            else "All tested milk was pure — thank you!")
}
