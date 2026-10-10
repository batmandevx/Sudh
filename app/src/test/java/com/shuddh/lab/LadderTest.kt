package com.shuddh.lab

import com.shuddh.lab.core.Ladder
import com.shuddh.lab.core.Purity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LadderTest {
    // Realistic milk-in-a-cap response: saturating (flat near pure milk), steep near water.
    private fun milkR(w: Double) = Purity.km(8.0 * (1 - w / 100), 0.02, 0.12)

    @Test fun elevenPointLadderBeatsThreePoint() {
        val rnd = Random(5)
        fun noisy(w: Double) = milkR(w) + rnd.nextDouble(-0.004, 0.004)
        val ladder = Ladder.fit((0..100 step 10).flatMap { w -> List(2) { w.toDouble() to noisy(w.toDouble()) } })!!
        val three = Ladder.fit(listOf(0, 50, 100).flatMap { w -> List(2) { w.toDouble() to noisy(w.toDouble()) } })!!
        val tests = listOf(15.0, 25.0, 35.0, 45.0, 65.0, 85.0)
        val errLadder = tests.map { kotlin.math.abs(ladder.waterPct(milkR(it)) - it) }.average()
        val errThree = tests.map { kotlin.math.abs(three.waterPct(milkR(it)) - it) }.average()
        assertTrue("ladder $errLadder vs three $errThree", errLadder < errThree)
        assertTrue("ladder error $errLadder", errLadder < 6)
        assertTrue("honest LOO ${ladder.looMae}", !ladder.looMae.isNaN() && ladder.looMae < 10)
    }

    @Test fun monotoneDespiteNoise() {
        val fy = Ladder.isotonic(listOf(0.9, 0.85, 0.87, 0.7, 0.72, 0.5), List(6) { 1.0 }, up = false)
        for (i in 1 until fy.size) assertTrue(fy[i] <= fy[i - 1])
    }

    @Test fun needsThreeLevelsAndSignal() {
        assertNull(Ladder.fit(listOf(0.0 to 0.8, 100.0 to 0.2)))
        assertNull(Ladder.fit(listOf(0.0 to 0.5, 50.0 to 0.5, 100.0 to 0.5)))
        assertEquals("9 spoons + 1 spoon water", Ladder.recipe(10))
    }
}
