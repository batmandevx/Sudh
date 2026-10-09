package com.shuddh.lab

import com.shuddh.lab.core.Sonar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SonarTest {
    /** Builds a fake recording: the chirp train delayed by latency, scaled, plus a small echo and noise. */
    private fun record(gain: Float, echo: Float): FloatArray {
        val c = Sonar.chirp()
        val period = (Sonar.SR * 0.16).toInt()
        val x = FloatArray(period * Sonar.CHIRPS + Sonar.SR / 2)
        val rnd = Random(3)
        val latency = 3000
        for (k in 0 until Sonar.CHIRPS) for (i in c.indices) {
            val p = latency + k * period + i
            x[p] += c[i] * gain
            if (p + 40 < x.size) x[p + 40] += c[i] * echo
        }
        for (i in x.indices) x[i] += (rnd.nextFloat() - 0.5f) * 0.002f
        return x
    }

    @Test fun findsEveryChirpAndFlatResponse() {
        val r = Sonar.analyse(record(0.5f, 0f))!!
        assertEquals(Sonar.CHIRPS, r.chirpsFound)
        r.bands.forEach { assertEquals(-6.0, it.toDouble(), 1.0) } // gain 0.5 = -6 dB, flat
        assertTrue(r.spreadDb < 0.5)
    }

    @Test fun moistureProjectsBetweenReferences() {
        val dry = FloatArray(Sonar.BANDS) { -10f - it * 0.5f }
        val wet = FloatArray(Sonar.BANDS) { -6f }
        val mid = FloatArray(Sonar.BANDS) { (dry[it] + wet[it]) / 2 }
        val (t, off) = Sonar.moisture(mid, dry, wet)
        assertEquals(0.5, t, 1e-6)
        assertEquals(0.0, off, 1e-6)
        assertEquals(0.0, Sonar.moisture(dry, dry, wet).first, 1e-6)
        assertEquals(1.0, Sonar.moisture(wet, dry, wet).first, 1e-6)
    }

    @Test fun bandSnrAndNoiseFloor() {
        val r = Sonar.analyse(record(0.5f, 0f))!!
        assertTrue(r.bandSnr.all { it > 20f })
        assertTrue(r.noiseDb < -50)
        assertTrue(!r.clipped)
        assertTrue(r.envelope.isNotEmpty())
    }

    @Test fun shapeChangeIgnoresDistanceShift() {
        // Wet = high bands brighter (shape change). A dry reading 3 dB louder overall (phone closer)
        // must still read ~0, not "wetter".
        val dry = FloatArray(Sonar.BANDS) { -12f }
        val wet = FloatArray(Sonar.BANDS) { -12f + it * 0.6f }
        val closerDry = FloatArray(Sonar.BANDS) { -9f }
        assertEquals(0.0, Sonar.moisture(closerDry, dry, wet).first, 0.05)
        val half = FloatArray(Sonar.BANDS) { (dry[it] + wet[it]) / 2 + 2f }
        assertEquals(0.5, Sonar.moisture(half, dry, wet).first, 0.05)
    }

    @Test fun noisyBandsAreDownWeighted() {
        val dry = FloatArray(Sonar.BANDS) { -12f }
        val wet = FloatArray(Sonar.BANDS) { -12f + it * 0.6f }
        val truth = FloatArray(Sonar.BANDS) { dry[it] + 0.3f * (wet[it] - dry[it]) }
        val corrupt = truth.copyOf().also { it[15] += 15f } // one band drowned by a noise burst
        val snr = FloatArray(Sonar.BANDS) { if (it == 15) 0f else 30f }
        val r = Sonar.Reading(corrupt, 10, 50.0, 0.5, List(10) { corrupt }, bandSnr = snr)
        val (t, _) = Sonar.moisture(r.bands, dry, wet, Sonar.weights(r))
        assertEquals(0.3, t, 0.05)
    }

    @Test fun quickEstimateRisesWithHighBandEcho() {
        fun r(t: Float) = FloatArray(Sonar.BANDS) { if (it >= 8) -10f + t else -10f }.let { Sonar.Reading(it, 10, 50.0, 0.5, List(10) { _ -> it }) }
        val dry = Sonar.quickEstimate(r(-12f)).first
        val wet = Sonar.quickEstimate(r(2f)).first
        assertTrue(dry < 0.2 && wet > 0.8)
    }
}
