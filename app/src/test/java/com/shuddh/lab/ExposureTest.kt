package com.shuddh.lab

import com.shuddh.lab.core.Exposure
import com.shuddh.lab.core.TruePrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureTest {
    @Test fun truePriceMath() {
        assertEquals(85.71, TruePrice.real(60.0, 30.0), 0.01)
        assertEquals(540.0, TruePrice.lostPerMonth(60.0, 30.0, 1.0), 1e-9)
        assertTrue(TruePrice.applies("milk_water", "%") && !TruePrice.applies("no3", "mg/L"))
    }

    @Test fun childNitrateLedger() {
        val day = 86_400_000L; val now = 100 * day
        // Tap water tested at 30 mg/L nitrate 40 days ago, then 60 mg/L 10 days ago.
        val s = listOf(Exposure.Sample(now - 40 * day, "no3", 30.0), Exposure.Sample(now - 10 * day, "no3", 60.0))
        val child = Exposure.Member("Child", "🧒", 18.0, 1.0)
        val r = Exposure.ledger(s, child, now).single()
        // 30 mg/L × 1 L / (3.7 × 18) = 45 %; 60 mg/L → 90 %. 19 days at 45 %, 11 days at 90 % (inclusive of the test day).
        assertEquals(90.1, r.peakPct, 0.2)
        assertEquals((19 * 45.05 + 11 * 90.09) / 30, r.avgPct, 0.5)
        assertTrue(Exposure.headline(child, listOf(r)).contains("nitrate"))
    }

    @Test fun adultDilutesExposureAndArsenicUsesMicrograms() {
        val now = 10 * 86_400_000L
        val s = listOf(Exposure.Sample(now - 1000, "strip_as", 10.0))
        val adult = Exposure.Member("Adult", "🧑", 60.0, 2.0)
        // 10 µg/L × 2 L = 20 µg/day; limit 0.3 µg/kg × 60 kg = 18 µg/day → 111 %.
        assertEquals(111.1, Exposure.ledger(s, adult, now).single().avgPct, 0.5)
    }
}
