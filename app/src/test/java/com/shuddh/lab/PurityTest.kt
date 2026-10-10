package com.shuddh.lab

import com.shuddh.lab.core.Fusion
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Purity
import com.shuddh.lab.core.Purity.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class PurityTest {
    // Simulated milk in a dark cap: per channel different scattering / background.
    private val sx = doubleArrayOf(6.0, 7.0, 8.0); private val ks = doubleArrayOf(0.004, 0.01, 0.02); private val rg = doubleArrayOf(0.12, 0.08, 0.15)
    private fun milk(w: Double) = Purity.Refl(DoubleArray(3) { Purity.km(sx[it] * (1 - w / 100), ks[it], rg[it]) })

    @Test fun milkRecoveredAcrossRangeWithThreeRefs() {
        val m = Purity.calibrate(Kind.MILK, milk(100.0), milk(50.0), milk(0.0))!!
        for (w in listOf(0.0, 10.0, 20.0, 30.0, 40.0, 70.0)) assertEquals("w=$w", w, m.waterPct(milk(w)[m.channel]), 0.6)
    }

    @Test fun milkNoiseGivesHonestUncertainty() {
        val m = Purity.calibrate(Kind.MILK, milk(100.0), milk(50.0), milk(0.0))!!
        val per = kotlin.math.abs(m.waterPerR(milk(25.0)[m.channel]))
        assertTrue("sensitivity $per", per in 50.0..2000.0) // 1 % reflectance noise → 0.5–20 % water
    }

    private fun honey(w: Double) = Purity.Refl(doubleArrayOf(0.9 * exp(-0.4 * (1 - w / 100)), 0.9 * exp(-1.2 * (1 - w / 100)), 0.9 * exp(-2.5 * (1 - w / 100))))

    @Test fun honeyBeerLambert() {
        val m = Purity.calibrate(Kind.HONEY, honey(100.0), honey(50.0), honey(0.0))!!
        for (w in listOf(0.0, 20.0, 35.0)) assertEquals(w, m.waterPct(honey(w)[m.channel]), 0.5)
        assertEquals(Level.UNSAFE, Purity.verdict(Kind.HONEY, 30.0).level)
    }

    @Test fun badSetupRejected() {
        val flat = Purity.Refl(doubleArrayOf(0.5, 0.5, 0.5))
        assertNull(Purity.calibrate(Kind.MILK, flat, flat, flat))
    }

    @Test fun fusionWeightsAndDisagreement() {
        val f = Fusion.combine(listOf(Fusion.Estimate("a", 10.0, 1.0), Fusion.Estimate("b", 12.0, 2.0)))!!
        assertEquals(10.4, f.value, 0.01); assertTrue(f.agreement > 0.9); assertEquals(0.8, f.weights[0], 1e-9)
        val bad = Fusion.combine(listOf(Fusion.Estimate("a", 10.0, 1.0), Fusion.Estimate("b", 30.0, 1.0)))!!
        assertTrue(bad.agreement < 0.3); assertTrue(bad.sigma > 5); assertNotNull(bad.outlier)
    }

    @Test fun genericThreePointAndScalarSensors() {
        // Juice-like: reflectance rises with water, slightly curved.
        fun juice(w: Double) = Purity.Refl(DoubleArray(3) { 0.3 + 0.5 * (w / 100) - 0.1 * (w / 100) * (w / 100) })
        val m = Purity.calibrate(Kind.JUICE, juice(100.0), juice(50.0), juice(0.0))!!
        assertEquals(50.0, m.waterPct(juice(50.0)[m.channel]), 0.01)
        assertEquals(25.0, m.waterPct(juice(25.0)[m.channel]), 2.0)
        // Magnetometer-like scalar: 38 / 39 / 40 µT.
        val sc = Purity.Scalar3(38.0, 39.0, 40.0)
        assertTrue(sc.monotonic); assertEquals(50.0, sc.adulterantPct(39.0), 1e-9); assertEquals(50.0, sc.pctPerUnit(39.5), 1e-9)
        assertTrue(!Purity.Scalar3(38.0, 40.0, 39.0).monotonic)
        assertEquals(Level.UNSAFE, Purity.verdict(Kind.OIL, 30.0).level)
    }

    @Test fun brightPureMilkFallsBackToThreePointCurve() {
        // Pure milk as bright as the paper (R ≈ 1): Kubelka–Munk can't fit, the 3-point curve must.
        val m = Purity.calibrate(Kind.MILK, Purity.Refl(doubleArrayOf(0.2, 0.2, 0.2)), Purity.Refl(doubleArrayOf(0.85, 0.85, 0.85)), Purity.Refl(doubleArrayOf(0.999, 0.999, 0.999)))
        assertNotNull(m)
        assertEquals(50.0, m!!.waterPct(0.85), 0.01)
    }
}
