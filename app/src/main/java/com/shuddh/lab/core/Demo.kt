package com.shuddh.lab.core

import kotlin.random.Random

/**
 * In-memory sample history used ONLY by the Insights "Preview with sample data" toggle so the
 * charts can be shown before real scans exist. It is never saved, hashed or exported.
 */
object Demo {
    private data class T(val instrument: String, val id: String, val name: String, val unit: String, val good: Double, val bad: Double)

    private val tests = listOf(
        T("Shuddh Spectrum", "milk_detergent", "Detergent in milk", "AU", 0.03, 0.22),
        T("Shuddh Spectrum", "cl2", "Free chlorine", "mg/L", 0.6, 2.4),
        T("Shuddh Spectrum", "no3", "Nitrate", "mg/L", 18.0, 58.0),
        T("Shuddh Float", "milk_water", "Water in milk", "%", 1.0, 14.0),
        T("Shuddh Polar", "honey_polar", "Honey purity", "%", 6.0, 48.0),
        T("Shuddh Strips", "strip_ph", "pH", "", 7.2, 9.1),
        T("Shuddh Nami", "nami_grain", "Surface moisture", "%", 18.0, 66.0),
        T("Shuddh Magneto", "magneto", "Utensil steel check", "µT", 3.0, 120.0),
        T("Shuddh Hawa", "air_scatter", "Air particulates", "×", 1.1, 4.2),
        T("Shuddh Echo", "echo_coconut", "Coconut fill", "%", 86.0, 22.0),
    )
    private val vendors = listOf("Ramesh Dairy", "Gupta Kirana", "Fresh Mart", "Sai Honey", "")

    fun records(days: Int = 150, now: Long = System.currentTimeMillis()): List<ScanRecord> {
        val rnd = Random(42)
        val out = mutableListOf<ScanRecord>()
        var id = 1L
        for (d in days downTo 0) {
            val dayStart = Insights.startOfDay(now) - d * 86_400_000L
            // Busier on weekends, quieter mid-week, gaps like a real family.
            val n = when { rnd.nextFloat() < 0.32f -> 0; rnd.nextFloat() < 0.6f -> 1; rnd.nextFloat() < 0.8f -> 2; else -> 3 + rnd.nextInt(3) }
            repeat(n) {
                val t = tests[rnd.nextInt(tests.size)]
                val vendor = if (t.id.startsWith("milk")) vendors[rnd.nextInt(2)] else vendors[2 + rnd.nextInt(3)]
                val monsoon = ((d / 30) % 12) in 3..5
                val pBad = (if (vendor == "Ramesh Dairy") 0.35 else 0.1) + if (monsoon) 0.1 else 0.0
                val r = rnd.nextDouble()
                val level = when { r < pBad -> Level.UNSAFE; r < pBad + 0.12 -> Level.CAUTION; else -> Level.SAFE }
                val v = when (level) { Level.UNSAFE -> t.bad; Level.CAUTION -> (t.good + t.bad) / 2; else -> t.good } * (0.85 + rnd.nextDouble() * 0.3)
                val hour = listOf(7, 8, 8, 9, 13, 18, 19, 19, 20, 21)[rnd.nextInt(10)]
                out += ScanRecord(
                    id++, dayStart + hour * 3_600_000L + rnd.nextInt(3_000_000), t.instrument, t.id, t.name, v, t.unit, level,
                    "Sample", emptyList(), "", vendor, rnd.nextFloat() < 0.4f, "", "", "JP Nagar",
                )
            }
        }
        return out
    }
}
