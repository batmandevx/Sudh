package com.shuddh.lab

import com.shuddh.lab.camera.Frames
import com.shuddh.lab.camera.Profile
import com.shuddh.lab.camera.Rgb
import com.shuddh.lab.core.Analytes
import com.shuddh.lab.core.Dsp
import com.shuddh.lab.core.Lang
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Words
import com.shuddh.lab.core.sha256
import com.shuddh.lab.instruments.analyseTap
import com.shuddh.lab.instruments.autoCalibrate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class CoreMathTest {

    @Test fun beerLambertRoundTrip() {
        val fit = Dsp.linearFit(listOf(0.0, 1.0, 2.0), listOf(0.01, 0.21, 0.41))!!
        assertEquals(0.2, fit.slope, 1e-9)
        assertEquals(1.5, fit.invert(0.31), 1e-9)
        assertEquals(1.0, fit.r2, 1e-9)
    }

    @Test fun malusFitRecoversAxisWithNoise() {
        val rnd = Random(1)
        val angles = (0..200 step 4).map { it.toDouble() }
        val i = angles.map { 100 + 60 * cos(2 * Math.toRadians(it - 37.0)) + rnd.nextDouble(-3.0, 3.0) }
        val f = Dsp.malusFit(angles, i)!!
        assertEquals(0.0, Dsp.wrap90(f.maxAngleDeg - 37.0), 1.0)
        assertEquals(0.6, f.contrast, 0.03)
    }

    @Test fun wrap90() {
        assertEquals(-10.0, Dsp.wrap90(170.0), 1e-9)
        assertEquals(10.0, Dsp.wrap90(-170.0), 1e-9)
        assertEquals(90.0, Dsp.wrap90(90.0), 1e-9)
    }

    @Test fun tapPeakFound() {
        val sr = 44100
        val x = FloatArray(8192) { n -> (exp(-n / 2000.0) * sin(2 * PI * 437.0 * n / sr)).toFloat() * 0.5f }
        val tap = analyseTap(x)
        assertEquals(437.0, tap.peakHz, 3.0)
        assertTrue(tap.decayMs in 50.0..250.0)
    }

    /** Synthetic CFL: 160 bins spanning 380–720 nm, Gaussian lines coloured like a real camera sees them. */
    @Test fun autoCalibrateOnSyntheticCfl() {
        val n = 160
        fun nm(i: Int) = 380.0 + 340.0 * i / (n - 1)
        fun line(i: Int, c: Double, h: Double) = h * exp(-((nm(i) - c) / 3.0).let { it * it })
        val r = FloatArray(n) { (8 + line(it, 611.6, 180.0) + line(it, 546.1, 30.0)).toFloat() }
        val g = FloatArray(n) { (8 + line(it, 546.1, 220.0) + line(it, 435.8, 20.0)).toFloat() }
        val b = FloatArray(n) { (8 + line(it, 435.8, 160.0)).toFloat() }
        val cal = autoCalibrate(Profile(r, g, b, 0f)).getOrThrow()
        for (target in listOf(435.8, 546.1, 611.6)) {
            val i = ((target - 380) / 340 * (n - 1)).toInt()
            assertEquals(nm(i), cal.nm(i, n), 2.0)
        }
    }

    @Test fun whiteIsNeutralInLab() {
        val w = Rgb(200f, 190f, 180f)
        val lab = Frames.relativeLab(w, w)
        assertEquals(100.0, lab.l, 0.5)
        assertEquals(0.0, lab.a, 0.5)
        assertEquals(0.0, lab.b, 0.5)
    }

    @Test fun drinkingWaterLimits() {
        assertEquals(Level.CAUTION, Analytes.chlorine.judge(0.1).first)
        assertEquals(Level.SAFE, Analytes.chlorine.judge(0.5).first)
        assertEquals(Level.UNSAFE, Analytes.chlorine.judge(5.0).first)
        assertEquals(Level.UNSAFE, Analytes.nitrate.judge(50.0).first)
        assertEquals(Level.UNSAFE, Analytes.detergent.judge(0.2).first)
        assertEquals(Level.SAFE, Analytes.detergent.judge(0.02).first)
    }

    @Test fun spokenVerdictIsInChosenLanguage() {
        val o = Outcome("Shuddh Spectrum", "cl2", Analytes.chlorine.name, 1.8, "mg/L", Level.CAUTION, "", listOf(Words.highChlorine), emptyList())
        val hi = o.spoken(Lang.HI)
        assertTrue(hi, hi.contains("क्लोरीन") && hi.contains("सावधान") && hi.contains("1.80"))
    }

    @Test fun sha256Known() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc"))
    }
}
