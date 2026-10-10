package com.shuddh.lab

import com.shuddh.lab.core.MilkWatch
import com.shuddh.lab.core.MilkWatch.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MilkWatchTest {
    private val rnd = Random(11)
    // Six reflectance features of one vendor's milk; day-to-day noise ±0.006.
    private val normal = doubleArrayOf(0.82, 0.80, 0.74, 0.78, 0.77, 0.70)
    private fun day(i: Int, watered: Double = 0.0) = MilkWatch.Scan(i.toLong(), DoubleArray(6) { normal[it] * (1 - 0.25 * watered) + rnd.nextDouble(-0.006, 0.006) })

    @Test fun normalDaysStayNormal() {
        val b = MilkWatch.baseline((0 until 5).map { day(it) })!!
        repeat(20) { assertEquals(Call.NORMAL, MilkWatch.call(MilkWatch.score(day(10 + it), b))) }
    }

    @Test fun wateredDayIsCaughtAndExplained() {
        val b = MilkWatch.baseline((0 until 5).map { day(it) })!!
        val sc = MilkWatch.score(day(9, watered = 0.25), b) // 25 % water → ~6 % lower reflectance
        assertEquals(Call.CHANGED, MilkWatch.call(sc))
        assertTrue(MilkWatch.meaning(sc).startsWith("thinner"))
    }

    @Test fun slowDriftCaughtByCusum() {
        val b = MilkWatch.baseline((0 until 5).map { day(it) })!!
        val creeping = (1..14).map { MilkWatch.score(day(10 + it, watered = 0.008 * it), b) } // +0.8 % water a day
        assertTrue(MilkWatch.drift(creeping))
        assertFalse(MilkWatch.drift((1..14).map { MilkWatch.score(day(30 + it), b) }))
        assertEquals(50, MilkWatch.trust(listOf(Call.NORMAL, Call.CHANGED)))
    }
}
