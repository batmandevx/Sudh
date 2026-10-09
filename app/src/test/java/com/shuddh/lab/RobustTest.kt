package com.shuddh.lab

import com.shuddh.lab.camera.Rgb
import com.shuddh.lab.camera.Robust
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Txt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RobustTest {
    @Test fun outlierFrameIgnored() {
        val frames = List(15) { Rgb(120f + (it % 3), 80f, 60f) } + Rgb(20f, 15f, 10f) // a hand's shadow
        val avg = Rgb.average(frames)
        assertEquals(121f, avg.r, 1.5f)
    }

    @Test fun trimmedMeanResistsGlint() {
        assertEquals(10f, Robust.trimmedMean(listOf(10f, 10f, 10f, 10f, 10f, 10f, 255f)), 0.01f)
    }

    @Test fun confidenceFollowsChecks() {
        fun o(vararg ok: Boolean?, level: Level = Level.SAFE) = Outcome("x", "x", Txt("x"), 1.0, "", level, "", emptyList(), ok.map { Evidence("QUALITY", "", it) })
        assertTrue(o(true, true, true).confidence() >= 95)
        assertTrue(o(true, false, false).confidence() < 60)
        assertTrue(o(true, true, level = Level.INCONCLUSIVE).confidence() <= 40)
        assertEquals(60, o().confidence())
    }
}
