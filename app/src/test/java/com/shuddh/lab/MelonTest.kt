package com.shuddh.lab

import com.shuddh.lab.core.Melon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MelonTest {
    @Test fun stiffnessFallsWithLowerPitch() {
        assertTrue(Melon.stiffness(140.0, 5.0) < Melon.stiffness(180.0, 5.0))
        assertEquals(140.0 * 140.0, Melon.stiffness(140.0, 1.0), 1e-6)
    }

    @Test fun dyeCalls() {
        assertEquals(Melon.Dye.NATURAL, Melon.dyeCall(8.0))
        assertEquals(Melon.Dye.DYE, Melon.dyeCall(48.0))
        assertEquals(Melon.Dye.BORDERLINE, Melon.dyeCall(25.0))
        assertTrue(Melon.dyeConfidence(48.0) > 95)
    }

    @Test fun fieldSpot() {
        assertTrue(Melon.spotRipe(30.0) > 0.9); assertTrue(Melon.spotRipe(6.0) < 0.1)
        assertTrue(Melon.spotWords(28.0, 2.0).startsWith("creamy"))
        assertEquals(0.7, Melon.ripeness(0.8, 0.6)!!.first, 1e-9)
    }

    @Test fun organicSignsAreCappedGuess() {
        val small = Melon.organicScore(3.0, 0.2, 0.0); val big = Melon.organicScore(9.0, 0.05, 0.08)
        assertEquals("Organic-like signs", Melon.organicCall(small)); assertEquals("Conventional-like signs", Melon.organicCall(big))
        assertTrue(Melon.organicConfidence(small) <= 60.0)
    }
}
