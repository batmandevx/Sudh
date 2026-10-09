package com.shuddh.lab

import com.shuddh.lab.core.Ppg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class PpgTest {
    /** Synthetic fingertip signal: slow drift + pulse dips + camera noise, jittery ~29 fps timing. */
    private fun synth(bpm: Double, seconds: Double, noise: Double = 0.4): Pair<List<Double>, List<Double>> {
        val rnd = Random(5)
        val t = mutableListOf<Double>(); val v = mutableListOf<Double>()
        var tt = 0.0
        while (tt < seconds) {
            val phase = (tt * bpm / 60) % 1.0
            val pulse = -3.0 * exp(-((phase - 0.2) * (phase - 0.2)) / 0.01) // brightness dips at systole
            t += tt; v += 180 + 6 * sin(2 * PI * 0.1 * tt) + pulse + rnd.nextDouble(-noise, noise)
            tt += 1 / 29.0 + rnd.nextDouble(-0.004, 0.004)
        }
        return t to v
    }

    @Test fun recoversHeartRate() {
        for (bpm in listOf(55.0, 72.0, 96.0, 130.0)) {
            val (t, v) = synth(bpm, 20.0)
            val r = Ppg.analyse(t, v)
            assertNotNull(r)
            assertEquals("bpm $bpm", bpm, r!!.bpm, 3.0)
            assertTrue(r.quality > 0.3)
        }
    }

    @Test fun beatsAndHrv() {
        val (t, v) = synth(75.0, 20.0)
        val r = Ppg.analyse(t, v)!!
        assertEquals(25.0, r.beats.size.toDouble(), 3.0) // ~75 bpm × 20 s
        assertNotNull(r.rmssd)
    }

    @Test fun tooShortGivesNoResult() {
        val (t, v) = synth(70.0, 3.0)
        assertNull(Ppg.analyse(t, v))
    }

    @Test fun fingerDetection() {
        assertTrue(Ppg.fingerOn(200f, 40f, 30f))
        assertTrue(!Ppg.fingerOn(120f, 110f, 100f)) // room scene
        assertTrue(!Ppg.fingerOn(20f, 5f, 5f)) // too dark: flash off
        assertTrue(!Ppg.fingerOn(230f, 160f, 120f)) // flash glare on a warm surface
        val red = floatArrayOf(200f, 40f, 30f)
        assertTrue(Ppg.fingerCovers(red, List(4) { floatArrayOf(150f, 30f, 20f) }))
        assertTrue(!Ppg.fingerCovers(red, List(4) { floatArrayOf(30f, 28f, 25f) })) // only a hot spot in the middle
    }
}
