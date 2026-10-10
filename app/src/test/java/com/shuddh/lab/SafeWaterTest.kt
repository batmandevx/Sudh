package com.shuddh.lab

import com.shuddh.lab.core.SafeWater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeWaterTest {
    @Test fun epaDoseTable() {
        assertEquals(2, SafeWater.bleachDose(1.0, 6.0, cloudy = false).drops)       // EPA: 2 drops/L at 6 %
        assertEquals(4, SafeWater.bleachDose(1.0, 6.0, cloudy = true).drops)        // doubled when cloudy
        assertEquals(8, SafeWater.bleachDose(3.785, 6.0, cloudy = false).drops)     // 8 drops per gallon
        assertEquals(48, SafeWater.bleachDose(20.0, 5.0, cloudy = false).drops)     // weaker bleach → more drops
        assertEquals(30, SafeWater.bleachDose(1.0, 6.0, false).contactMin)
    }

    @Test fun clarityAndH2s() {
        assertEquals(0.5, SafeWater.clarity(0.3, 0.6), 1e-9)
        assertEquals(true, SafeWater.h2sPositive(75.0, 30.0))
        assertEquals(false, SafeWater.h2sPositive(75.0, 70.0))
        assertNull(SafeWater.h2sPositive(75.0, 58.0))
    }

    @Test fun whoOrs() {
        val o = SafeWater.ors(0.5)
        assertEquals(3.0, o.sugarTsp, 1e-9); assertEquals(0.25, o.saltTsp, 1e-9)
        assertEquals(50..100, SafeWater.orsPerStool(1.0))
    }
}
