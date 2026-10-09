package com.shuddh.lab

import com.shuddh.lab.core.Nephelo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NepheloTest {
    /** Simulates OFF/ON cycles at 30 fps. [scatter] = flash-attributable RGB; ambient drifts and flickers. */
    private fun run(scatter: DoubleArray, cycles: Int = 6, ambientDrift: Double = 20.0, seed: Int = 1): List<Nephelo.Frame> {
        val rnd = Random(seed); val out = mutableListOf<Nephelo.Frame>()
        var t = 0L
        val seq = listOf(false) + List(cycles) { listOf(true, false) }.flatten()
        for (on in seq) {
            val segStart = t
            while (t - segStart < 650) {
                val amb = 30 + ambientDrift * t / 10_000.0 + rnd.nextDouble(-0.5, 0.5)
                // The LED takes ~120 ms to settle: early ON frames are dimmer.
                val rise = if (on) ((t - segStart) / 120.0).coerceAtMost(1.0) else 0.0
                fun ch(k: Int) = amb + (if (on) scatter[k] * rise else 0.0) + rnd.nextDouble(-0.3, 0.3)
                out += Nephelo.Frame(t, on, ch(0), ch(1), ch(2), 0.0)
                t += 33
            }
        }
        return out
    }

    @Test fun lockInCancelsAmbientDrift() {
        val r = Nephelo.lockIn(run(doubleArrayOf(10.0, 10.0, 10.0), ambientDrift = 60.0))!!
        assertEquals(10.0, r.signal, 0.6)
        assertTrue(r.ci < 1.0)
        assertEquals(6, r.cycles)
    }

    @Test fun ratioAndBands() {
        val clean = Nephelo.lockIn(run(doubleArrayOf(5.0, 5.0, 5.0), seed = 2))!!
        val smoky = Nephelo.lockIn(run(doubleArrayOf(14.0, 15.0, 18.0), seed = 3))!!
        val r = Nephelo.ratio(smoky, clean)
        assertTrue(r in 2.6..3.4)
        assertEquals("Heavy", Nephelo.band(r, Nephelo.airBands).name)
        assertEquals("Clean", Nephelo.band(1.05, Nephelo.airBands).name)
    }

    @Test fun angstromSeparatesSmokeFromDust() {
        val clean = Nephelo.lockIn(run(doubleArrayOf(6.0, 6.0, 6.0), seed = 4))!!
        // Smoke: blue excess ≫ red excess.
        val smoke = Nephelo.lockIn(run(doubleArrayOf(8.0, 11.0, 16.0), seed = 5))!!
        // Dust: equal excess in every colour.
        val dust = Nephelo.lockIn(run(doubleArrayOf(12.0, 12.0, 12.0), seed = 6))!!
        val aS = Nephelo.angstrom(smoke, clean); val aD = Nephelo.angstrom(dust, clean)
        assertNotNull(aS); assertNotNull(aD)
        assertTrue("smoke α=$aS", aS!! > 1.3)
        assertTrue("dust α=$aD", kotlin.math.abs(aD!!) < 0.5)
        assertTrue(Nephelo.particleType(aS, 0.0).startsWith("Fine smoke"))
    }

    @Test fun outlierCycleRejected() {
        val f = run(doubleArrayOf(10.0, 10.0, 10.0)).toMutableList()
        // A shadow during one ON period.
        val bad = f.indices.filter { f[it].on && f[it].tMs in 1400..1900 }
        bad.forEach { i -> f[i] = f[i].copy(r = f[i].r - 25, g = f[i].g - 25, b = f[i].b - 25) }
        val r = Nephelo.lockIn(f)!!
        assertEquals(10.0, r.signal, 1.0)
    }
}
