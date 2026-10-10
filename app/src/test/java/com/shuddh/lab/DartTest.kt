package com.shuddh.lab

import com.shuddh.lab.core.Dart
import com.shuddh.lab.core.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DartTest {
    @Test fun calibrationLinearWithHonestLeaveOneOut() {
        val rnd = Random(3)
        val pts = listOf(0.0, 0.0, 1.0, 2.0, 3.0, 5.0).map { c -> c to (2.0 + 4.5 * c + rnd.nextDouble(-0.8, 0.8)) }
        val cal = Dart.calibrate(pts)!!
        assertEquals(4.5, cal.slope, 0.4)
        assertTrue(cal.r2 > 0.98)
        assertTrue("mae ${cal.mae}", cal.mae < 0.4)
        assertEquals(3.0, cal.predict(2.0 + 4.5 * 3), 0.3)
        assertNull(Dart.calibrate(listOf(1.0 to 2.0, 1.0 to 3.0, 1.0 to 4.0)))
    }

    @Test fun lactometerToAddedWater() {
        // Pure cow milk: reading 28 at 27 °C, fat 4 → SNF 7 + 0.84 + 0.36 = 8.2 → ~3.5 % below 8.5.
        assertEquals(8.2, Dart.snf(Dart.correctedClr(28.0, 27.0), 4.0), 1e-9)
        // Same milk measured warm (32 °C reads 1 division low): correction restores it.
        assertEquals(29.0, Dart.correctedClr(28.0, 32.0), 1e-9)
        // 20 % water: CLR ~ 0.8×30 = 24, fat 3.2 → SNF 6.0+0.67+0.36 = 7.03 → ~17 % below 8.5.
        val w = Dart.addedWater(Dart.snf(24.0, 3.2), 8.5)
        assertTrue("w=$w", w in 14.0..20.0)
        assertEquals(Level.UNSAFE, Dart.lactoVerdict(w).first)
        assertEquals(Level.SAFE, Dart.lactoVerdict(Dart.addedWater(Dart.snf(30.0, 4.5), 8.5)).first)
    }

    @Test fun foamHalfLifeSeparatesDetergent() {
        val milk = (0..120).map { i -> val t = i * 0.5; t to Dart.simulateFoam(t, 6.0) }
        val soap = (0..120).map { i -> val t = i * 0.5; t to Dart.simulateFoam(t, 200.0) }
        assertEquals(6.0, Dart.foamHalfLife(milk)!!, 1.5)
        assertTrue(Dart.foamHalfLife(soap)!! > 60)
        val rm = Dart.foamRemaining(milk)!!; val rs = Dart.foamRemaining(soap)!!
        assertEquals(Level.SAFE, Dart.foamVerdict(rm, rm).first)
        assertEquals(Level.UNSAFE, Dart.foamVerdict(rs, rm).first)
    }

    @Test fun blueShiftPositiveForStarch() {
        assertTrue(Dart.blueShift(controlB = 25.0, sampleB = -5.0, controlL = 70.0, sampleL = 40.0) > Dart.COLOUR_THRESHOLD)
        assertTrue(Dart.blueShift(25.0, 24.0, 70.0, 70.0) < Dart.COLOUR_THRESHOLD)
    }

    @Test fun colourCallNeverGuesses() {
        assertEquals(true, Dart.colourCall(15.0, 1.5))
        assertEquals(false, Dart.colourCall(1.0, 1.0))
        assertNull(Dart.colourCall(7.0, 1.5))   // too close to call → retest
        assertNull(Dart.colourCall(12.0, 4.0))  // big signal but very noisy → retest
        assertTrue(Dart.plausibleClr(28.0)); assertTrue(!Dart.plausibleClr(12.0))
    }

    @Test fun dyeSpotsVsLeaves() {
        assertTrue(Dart.isDye(240.0, 140.0, 30.0, 240.0, 240.0, 235.0))   // orange dye on white paper
        assertTrue(!Dart.isDye(60.0, 45.0, 35.0, 240.0, 240.0, 235.0))   // dark tea leaf
        assertTrue(!Dart.isDye(230.0, 228.0, 220.0, 240.0, 240.0, 235.0)) // paper itself
        assertEquals(true, Dart.spotsCall(4.0, 0.3)); assertEquals(false, Dart.spotsCall(0.2, 0.3)); assertNull(Dart.spotsCall(1.0, 0.3))
    }

    @Test fun confidenceFromMargin() {
        assertEquals(0.8413, Dart.phi(1.0), 1e-3)
        assertEquals(50.0, Dart.confidence(0.0, 1.0), 1e-4)
        assertEquals(97.7, Dart.confidence(2.0, 1.0), 0.1)
        assertEquals(99.0, Dart.confidence(10.0, 1.0), 1e-9)
    }
}
