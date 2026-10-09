package com.shuddh.lab

import com.shuddh.lab.core.Dsp
import com.shuddh.lab.core.MosquitoRadar
import com.shuddh.lab.core.MosquitoRadar.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class MosquitoTest {
    private val sr = 22050; private val n = 4096
    private val rnd = Random(2)

    /** Runs [frames] frames of a harmonic buzz at [f0] (with natural ±[jitter] Hz wobble); returns first detection. */
    private fun run(det: MosquitoRadar.Detector, frames: Int, f0: Double?, jitter: Double = 4.0, amp: Double = 0.05): Double? {
        var hit: Double? = null
        var phase = 0.0
        for (k in 0 until frames) {
            val f = f0?.let { it + jitter * sin(k * 0.9) }
            val x = FloatArray(n) { i ->
                var v = rnd.nextDouble(-0.004, 0.004)
                if (f != null) { phase += 2 * PI * f / sr; v += amp * (sin(phase) + 0.6 * sin(2 * phase) + 0.35 * sin(3 * phase)) }
                v.toFloat()
            }
            det.push(Dsp.spectrumDb(x))?.let { if (hit == null) hit = it }
        }
        return hit
    }

    @Test fun detectsAedesBuzz() {
        val d = MosquitoRadar.Detector(sr, n)
        assertNull(run(d, 8, null))
        val hz = run(d, 10, 462.0)!!
        assertEquals(462.0, hz, 8.0)
        assertEquals(Kind.AEDES, MosquitoRadar.classify(hz, hour = 11).top)
    }

    @Test fun rejectsMainsHumAndSilence() {
        val d = MosquitoRadar.Detector(sr, n)
        run(d, 8, null)
        assertNull(run(d, 12, 400.0, jitter = 0.0)) // perfectly steady 400 Hz = 8 × 50 Hz mains hum
        assertNull(run(d, 12, null))
    }

    @Test fun classificationUsesPitchSexAndTimeOfDay() {
        assertEquals(Kind.MALE, MosquitoRadar.classify(700.0, 12).top)
        assertEquals(Kind.CULEX, MosquitoRadar.classify(370.0, 23).top)
        assertEquals(Kind.AEDES, MosquitoRadar.classify(450.0, 10).top)
        // Same pitch at night shifts weight away from day-biting Aedes.
        assertTrue(MosquitoRadar.classify(450.0, 23).probs[Kind.AEDES]!! < MosquitoRadar.classify(450.0, 10).probs[Kind.AEDES]!!)
    }
}
