package com.shuddh.lab

import com.shuddh.lab.core.GrainScan
import com.shuddh.lab.core.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrainScanTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Draws filled ellipses on a background; returns pixels. */
    private fun scene(bg: Int, blobs: List<Triple<Pair<Int, Int>, Pair<Int, Int>, Int>>, w: Int = 320, h: Int = 240): IntArray {
        val px = IntArray(w * h) { bg }
        blobs.forEach { (c, r, col) ->
            for (y in c.second - r.second..c.second + r.second) for (x in c.first - r.first..c.first + r.first) {
                val dx = (x - c.first).toDouble() / r.first; val dy = (y - c.second).toDouble() / r.second
                if (dx * dx + dy * dy <= 1 && x in 0 until w && y in 0 until h) px[y * w + x] = col
            }
        }
        return px
    }

    private fun grid(count: Int, start: Int = 0): List<Pair<Int, Int>> = List(count) { i -> val k = i + start; (20 + (k % 12) * 24) to (20 + (k / 12) * 26) }

    @Test fun countsGrainsAndFindsImpurities() {
        val rice = argb(240, 236, 222); val stone = argb(95, 90, 85); val yellow = argb(225, 190, 40)
        val pos = grid(48)
        val blobs = pos.mapIndexed { i, p ->
            when {
                i < 3 -> Triple(p, 8 to 4, stone)      // 3 stones
                i < 5 -> Triple(p, 8 to 4, yellow)     // 2 discoloured
                i < 9 -> Triple(p, 4 to 2, rice)       // 4 broken
                else -> Triple(p, 8 to 4, rice)        // 39 whole grains
            }
        }
        val r = GrainScan.analyse(scene(argb(25, 25, 30), blobs), 320, 240)
        assertTrue(r.darkBackground)
        assertEquals(3, r.foreign)
        assertEquals(2, r.discoloured)
        assertEquals(4, r.broken)
        assertEquals(39, r.grains)
        assertEquals(Level.UNSAFE, GrainScan.grade(r).first)
    }

    @Test fun cleanDalOnWhitePaper() {
        val dal = argb(230, 170, 50)
        val r = GrainScan.analyse(scene(argb(245, 245, 240), grid(40).map { Triple(it, 6 to 6, dal) }), 320, 240)
        assertTrue(!r.darkBackground)
        assertEquals(40, r.grains)
        assertEquals(Level.SAFE, GrainScan.grade(r).first)
    }
}
