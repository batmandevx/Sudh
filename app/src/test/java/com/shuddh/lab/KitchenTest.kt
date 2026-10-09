package com.shuddh.lab

import com.shuddh.lab.core.BoilDetector
import com.shuddh.lab.core.BoilDetector.Stage
import com.shuddh.lab.core.BoilPhysics
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.OilIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class KitchenTest {
    private val fps = 21.5
    private val rnd = Random(4)
    private fun feed(d: BoilDetector, seconds: Double, db: (Double) -> Double, flux: Double = 1.0): Stage {
        var s = d.stage
        val n = (seconds * fps).toInt()
        for (i in 0 until n) s = d.push(db(i / fps) + rnd.nextDouble(-0.8, 0.8), flux * (1 + rnd.nextDouble(-0.2, 0.2)))
        return s
    }

    @Test fun singingThenDropMeansBoiling() {
        val d = BoilDetector(fps)
        assertEquals(Stage.COLD, feed(d, 3.5, { 40.0 }))
        assertEquals(Stage.SINGING, feed(d, 30.0, { t -> 40 + 15 * (t / 30) }))
        assertNotEquals(Stage.BOILING, feed(d, 3.0, { 55.0 }))
        assertEquals(Stage.BOILING, feed(d, 7.0, { 51.0 }))
        // Heat off: back to room level → stopped.
        assertEquals(Stage.STOPPED, feed(d, 8.0, { 41.0 }))
    }

    @Test fun irregularBubblingWithoutSinging() {
        val d = BoilDetector(fps)
        feed(d, 3.5, { 40.0 })
        feed(d, 10.0, { t -> 40 + 9 * (t / 10) })
        assertEquals(Stage.BOILING, feed(d, 8.0, { 49.0 }, flux = 3.0))
    }

    @Test fun talkingNearbyIsNotBoiling() {
        val d = BoilDetector(fps)
        feed(d, 3.5, { 40.0 })
        // Short bursts of speech: loud for 1 s, quiet for 1 s.
        val s = feed(d, 20.0, { t -> if ((t.toInt() % 2) == 0) 52.0 else 40.0 })
        assertNotEquals(Stage.BOILING, s)
    }

    @Test fun boilingPointWithAltitude() {
        assertEquals(100.0, BoilPhysics.boilingPointC(1013.25), 0.05)
        val high = BoilPhysics.boilingPointC(795.0) // ≈ 2,000 m
        assertTrue(high in 92.5..94.5)
        assertEquals(60, BoilPhysics.safeBoilSeconds(900.0))
        assertEquals(180, BoilPhysics.safeBoilSeconds(2500.0))
    }

    @Test fun oilGrades() {
        val fresh = OilIndex.Lab3(86.0, -2.0, 30.0)
        assertEquals(Level.SAFE, OilIndex.grade(OilIndex.Lab3(83.0, -1.0, 32.0), fresh).level)
        assertEquals(Level.CAUTION, OilIndex.grade(OilIndex.Lab3(74.0, 2.0, 42.0), fresh).level)
        val bad = OilIndex.grade(OilIndex.Lab3(55.0, 10.0, 50.0), fresh)
        assertEquals(Level.UNSAFE, bad.level)
        assertTrue(bad.reuses >= 4)
    }
}
