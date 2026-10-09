package com.shuddh.lab

import com.shuddh.lab.core.FallDetector
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GuardianTest {
    private val g = 9.81f
    private val rnd = Random(1)
    /** Feeds [ms] of samples at 50 Hz with total acceleration [gOf](t) along z. */
    private fun feed(d: FallDetector, t0: Long, ms: Long, gOf: (Long) -> Float): Pair<Long, FallDetector.Event?> {
        var ev: FallDetector.Event? = null
        var t = t0
        while (t < t0 + ms) { d.push(0f, 0f, gOf(t - t0) * g + rnd.nextFloat() * 0.05f, t)?.let { ev = it }; t += 20 }
        return t to ev
    }

    @Test fun realFallIsDetected() {
        val d = FallDetector()
        var (t, e) = feed(d, 0, 2000) { 1f }                // walking/standing
        assertNull(e)
        t = feed(d, t, 300) { 0.15f }.first                  // free fall
        t = feed(d, t, 80) { 3.5f }.first                    // impact
        val (_, ev) = feed(d, t, 3200) { 1f }                // lying still on the floor
        assertNotNull(ev); assertTrue(ev!!.impactG > 2.4)
    }

    @Test fun walkingAndSittingDownAreNotFalls() {
        val d = FallDetector()
        // Brisk walking: 0.7–1.4 g oscillation, never a free fall.
        assertNull(feed(d, 0, 6000) { t -> 1f + 0.35f * kotlin.math.sin(t / 80.0).toFloat() }.second)
        // Plopping onto a chair: short 2 g bump without a free fall first.
        assertNull(feed(d, 6000, 2000) { t -> if (t in 400..460) 2.2f else 1f }.second)
    }

    @Test fun droppedThenPickedUpIsNotAFall() {
        val d = FallDetector()
        var t = feed(d, 0, 1000) { 1f }.first
        t = feed(d, t, 250) { 0.1f }.first
        t = feed(d, t, 60) { 3f }.first
        // Picked straight up and moved around.
        val (_, ev) = feed(d, t, 3200) { tt -> 1f + 0.6f * kotlin.math.sin(tt / 50.0).toFloat() }
        assertNull(ev)
    }
}
