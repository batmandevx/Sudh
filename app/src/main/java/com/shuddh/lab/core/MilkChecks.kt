package com.shuddh.lab.core

import kotlin.math.abs

/** Milk types sold in India — fat % per FSSAI standards (typical labelled values). */
enum class MilkType(val label: String, val fat: Double, val key: String) {
    FULL_CREAM("Full cream", 6.0, "fullcream"),
    STANDARDISED("Standardised", 4.5, ""),        // "" = the original calibration key
    TONED("Toned", 3.0, "toned"),
    DOUBLE_TONED("Double toned", 1.5, "dtoned"),
    SKIMMED("Skimmed", 0.5, "skimmed"),
    COW("Cow (loose)", 4.0, "cow"),
    BUFFALO("Buffalo (loose)", 6.5, "buffalo"),
}

/**
 * Spoilage from a thin film of milk on a dark plate: fresh milk is a smooth film; souring milk curdles into
 * flecks. "Speckle" = share of pixels that differ strongly from their neighbourhood. Compared with fresh milk.
 */
object Spoilage {
    /** Speckle fraction of a luma image (w×h) — pixels > 18 % away from their 5×5 neighbourhood mean. */
    fun speckle(l: FloatArray, w: Int, h: Int): Double {
        if (w < 7 || h < 7) return 0.0
        // Integral image for fast box means.
        val ii = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) { var row = 0.0; for (x in 0 until w) { row += l[y * w + x]; ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row } }
        fun box(x0: Int, y0: Int, x1: Int, y1: Int) = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
        var n = 0; var hit = 0
        for (y in 2 until h - 2 step 2) for (x in 2 until w - 2 step 2) {
            val m = box(x - 2, y - 2, x + 3, y + 3) / 25.0
            if (m < 8) continue
            n++; if (abs(l[y * w + x] - m) / m > 0.18) hit++
        }
        return if (n == 0) 0.0 else 100.0 * hit / n
    }

    enum class Call { FRESH, SOURING, SPOILED }

    /** Lines relative to fresh milk's own speckle (with absolute floors for very smooth references). */
    fun lines(fresh: Double?) = (fresh ?: 1.0).let { f -> listOf(maxOf(2.0, f * 2.5), maxOf(5.0, f * 5)) }

    fun call(speckle: Double, fresh: Double?): Call {
        val (a, b) = lines(fresh)
        return when { speckle < a -> Call.FRESH; speckle < b -> Call.SOURING; else -> Call.SPOILED }
    }

    fun confidence(speckle: Double, fresh: Double?) = Dart.confidence(lines(fresh).minOf { abs(speckle - it) }, maxOf(0.6, 0.2 * speckle))
}
