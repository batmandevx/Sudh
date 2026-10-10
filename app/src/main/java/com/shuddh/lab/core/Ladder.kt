package com.shuddh.lab.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Multi-point calibration ("ladder"): known mixes at many water levels (0, 10, … 100 %) and a sensor reading
 * for each. The response is fitted with isotonic regression (pool-adjacent-violators) — free-form shape, but
 * forced to be monotonic, because more water can only move the reading one way. Inversion is piecewise linear.
 * Accuracy is reported honestly by leave-one-level-out: each known level is predicted from a curve built
 * without it.
 */
object Ladder {
    data class Model(
        /** Knots (water %, fitted reading), sorted by water %. */
        val knots: List<Pair<Double, Double>>,
        /** Scatter of the recorded readings around the curve (reading units). */
        val residualSd: Double,
        /** Leave-one-level-out mean absolute error, % water (NaN if too few levels). */
        val looMae: Double,
        val levels: Int,
    ) {
        private val increasing get() = knots.last().second > knots.first().second

        /** Water % for a reading, clamped to the calibrated range. */
        fun waterPct(r: Double): Double {
            val ks = knots
            val lo = ks.minOf { it.second }; val hi = ks.maxOf { it.second }
            val rr = r.coerceIn(lo, hi)
            for (i in 0 until ks.size - 1) {
                val (w0, r0) = ks[i]; val (w1, r1) = ks[i + 1]
                val inside = if (increasing) rr in r0..r1 else rr in r1..r0
                if (inside) return if (abs(r1 - r0) < 1e-12) (w0 + w1) / 2 else w0 + (rr - r0) / (r1 - r0) * (w1 - w0)
            }
            return if ((rr - ks.first().second).let { abs(it) } < (rr - ks.last().second).let { abs(it) }) ks.first().first else ks.last().first
        }

        /** |d(water %)/d(reading)| near r — converts reading noise into % water. */
        fun pctPerUnit(r: Double): Double { val h = 0.002; return abs(waterPct(r + h) - waterPct(r - h)) / (2 * h) }

        /** Total 1-σ in % water for a reading with its own noise sd. */
        fun sigmaPct(r: Double, sd: Double): Double {
            val slope = pctPerUnit(r).coerceAtMost(2000.0)
            val fromNoise = slope * sqrt(sd * sd + residualSd * residualSd)
            return sqrt(fromNoise * fromNoise + (if (looMae.isNaN()) 0.0 else 1.25 * looMae).let { it * it } * 0.5).coerceAtLeast(1.0)
        }
    }

    /** Pool-adjacent-violators: monotone (non-decreasing if [up]) fit to y in x order, weighted. */
    fun isotonic(y: List<Double>, w: List<Double>, up: Boolean): List<Double> {
        val ys = if (up) y else y.map { -it }
        val blocks = mutableListOf<Triple<Double, Double, Int>>() // (mean, weight, count)
        for (i in ys.indices) {
            blocks += Triple(ys[i], w[i], 1)
            while (blocks.size > 1 && blocks[blocks.size - 2].first > blocks.last().first) {
                val b = blocks.removeAt(blocks.size - 1); val a = blocks.removeAt(blocks.size - 1)
                val ww = a.second + b.second
                blocks += Triple((a.first * a.second + b.first * b.second) / ww, ww, a.third + b.third)
            }
        }
        val out = blocks.flatMap { b -> List(b.third) { b.first } }
        return if (up) out else out.map { -it }
    }

    /** Fit from (water %, reading) points; several readings per level are fine. Null if it can't separate levels. */
    fun fit(points: List<Pair<Double, Double>>, withLoo: Boolean = true): Model? {
        val byLevel = points.groupBy { it.first }.toSortedMap()
        if (byLevel.size < 3) return null
        val xs = byLevel.keys.toList()
        val ys = byLevel.values.map { v -> v.map { it.second }.average() }
        val ws = byLevel.values.map { it.size.toDouble() }
        if (ys.max() - ys.min() < 0.01) return null
        // Direction from the correlation of reading with water %.
        val mx = xs.average(); val my = ys.average()
        val up = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } > 0
        val fy = isotonic(ys, ws, up)
        val knots = xs.indices.map { xs[it] to fy[it] }
        val res = points.map { (x, y) -> y - fy[xs.indexOf(x)] }
        val residualSd = sqrt(res.sumOf { it * it } / (res.size - 1).coerceAtLeast(1)).coerceAtLeast(0.002)
        val loo = if (!withLoo || byLevel.size < 4) Double.NaN else xs.mapNotNull { lvl ->
            fit(points.filter { it.first != lvl }, withLoo = false)?.let { m -> abs(m.waterPct(byLevel[lvl]!!.map { it.second }.average()) - lvl) }
        }.takeIf { it.isNotEmpty() }?.average() ?: Double.NaN
        return Model(knots, residualSd, loo, byLevel.size)
    }

    /** Pick the best of several channels by leave-one-out error (or by span if LOO isn't available yet). */
    fun best(channels: List<List<Pair<Double, Double>>>): Pair<Int, Model>? =
        channels.mapIndexedNotNull { i, pts -> fit(pts)?.let { i to it } }
            .minByOrNull { (_, m) -> if (m.looMae.isNaN()) 1000 - (m.knots.maxOf { it.second } - m.knots.minOf { it.second }) * 100 else m.looMae }

    /** How to mix a level by spoons (10 spoons total). */
    fun recipe(waterPct: Int): String = when (waterPct) {
        0 -> "pure — no water"
        100 -> "plain water only"
        else -> "${10 - waterPct / 10} spoons + ${waterPct / 10} spoon${if (waterPct / 10 == 1) "" else "s"} water"
    }
}
