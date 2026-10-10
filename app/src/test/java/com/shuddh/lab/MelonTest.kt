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
}
