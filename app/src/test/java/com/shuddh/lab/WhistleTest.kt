package com.shuddh.lab

import com.shuddh.lab.core.Dsp
import com.shuddh.lab.core.WhistleDetector
import com.shuddh.lab.core.WhistleTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class WhistleTest {
    private val sr = 22050; private val n = 1024
    private val rnd = Random(9)

    /** Runs [frames] frames; [gen] gives the sample at absolute index i. Returns whistle count. */
    private fun run(det: WhistleDetector, frames: Int, gen: (Int) -> Double): Int {
        var count = 0
        for (f in 0 until frames) {
            val x = FloatArray(n) { (gen(f * n + it) + rnd.nextDouble(-0.003, 0.003)).toFloat() }
            det.push(Dsp.spectrumDb(x)) { count++ }
        }
        return count
    }

    private fun tone(i: Int, hz: Double, a: Double = 0.3) = a * sin(2 * PI * hz * i / sr)

    @Test fun countsSeparateWhistlesOnce() {
        val det = WhistleDetector(sr, n)
        // 2 s quiet, 2 s whistle, 3 s quiet, 2 s whistle, 2 s quiet (frames ≈ 21.5/s)
        val seg = listOf(43 to 0.0, 43 to 3000.0, 65 to 0.0, 43 to 3050.0, 43 to 0.0)
        var count = 0
        seg.forEach { (frames, hz) -> count += run(det, frames) { i -> if (hz > 0) tone(i, hz) else 0.0 } }
        assertEquals(2, count)
    }

    @Test fun sputteringWhistleCountsOnce() {
        val det = WhistleDetector(sr, n)
        var count = run(det, 40) { 0.0 }
        // whistle 1 s, 0.2 s dropout, whistle 1 s again (same event)
        count += run(det, 22) { i -> tone(i, 2800.0) }
        count += run(det, 4) { 0.0 }
        count += run(det, 22) { i -> tone(i, 2800.0) }
        count += run(det, 40) { 0.0 }
        assertEquals(1, count)
    }

    @Test fun ignoresGlidingVoiceAndBroadbandClatter() {
        val det = WhistleDetector(sr, n)
        var count = run(det, 40) { 0.0 }
        // A sweeping tone (like a voice/siren glide) 1 → 4 kHz over 2 s.
        var phase = 0.0
        count += run(det, 43) { i -> val hz = 1000 + 3000.0 * (i % (43 * n)) / (43 * n); phase += 2 * PI * hz / sr; 0.3 * sin(phase) }
        assertEquals("glide", 0, count)
        count += run(det, 20) { 0.0 }
        // Loud broadband noise burst (pressure hiss / clatter).
        count += run(det, 30) { rnd.nextDouble(-0.4, 0.4) }
        assertEquals(0, count)
    }

    @Test fun steadyBackgroundHumIsLearnedAsNoise() {
        val det = WhistleDetector(sr, n)
        // A constant 2 kHz hum present the whole time (fan, fridge) is never a whistle…
        var count = run(det, 120) { i -> tone(i, 2000.0, 0.15) }
        assertEquals(0, count)
        // …but a real whistle over it still counts.
        count += run(det, 40) { i -> tone(i, 2000.0, 0.15) + tone(i, 3300.0, 0.4) }
        assertEquals(1, count)
    }

    @Test fun learnedPitchRejectsOtherTones() {
        val det = WhistleDetector(sr, n).apply { learnedHz = 3000.0 }
        var count = run(det, 40) { 0.0 }
        count += run(det, 43) { i -> tone(i, 1500.0) }
        assertEquals(0, count)
    }

    @Test fun timingPrediction() {
        val p = WhistleTiming.predict(listOf(0L, 120_000L, 230_000L))!!
        assertTrue(p.first in 110_000L..120_000L)
        assertEquals(230_000L + p.first, p.second)
    }
}
