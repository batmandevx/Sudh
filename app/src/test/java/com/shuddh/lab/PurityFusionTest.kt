package com.shuddh.lab

import com.shuddh.lab.core.PurityAi
import com.shuddh.lab.core.PurityFusion
import com.shuddh.lab.core.PurityFusion.Fam
import com.shuddh.lab.core.PurityFusion.Vote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurityFusionTest {
    @Test fun twoAgreeingOpinionsOverruleAWrongSensorMatch() {
        // The reported case: sensors said honey; physics and AI both say water → water.
        val f = PurityFusion.fuse(listOf(Vote("sensors", Fam.NOT_MILK, 0.5), Vote("physics", Fam.WATER, 0.56), Vote("AI", Fam.WATER, 0.56)))
        assertEquals(Fam.WATER, f.fam); assertEquals(2, f.agree)
    }

    @Test fun threeWayDisagreementIsNotSure() {
        val f = PurityFusion.fuse(listOf(Vote("sensors", Fam.NOT_MILK, 0.5), Vote("physics", Fam.WATER, 0.56), Vote("AI", Fam.WATERED, 0.56)))
        assertTrue(f.unsure); assertNull(f.fam); assertTrue(f.confidence <= 45)
    }

    @Test fun agreementRaisesConfidence() {
        val one = PurityFusion.fuse(listOf(Vote("sensors", Fam.MILK, 0.8)))
        val all = PurityFusion.fuse(listOf(Vote("sensors", Fam.MILK, 0.8), Vote("physics", Fam.MILK, 0.5), Vote("AI", Fam.MILK, 0.5)))
        assertTrue(all.confidence > one.confidence)
    }

    @Test fun closeTwoWaySplitIsNotSure() {
        assertTrue(PurityFusion.fuse(listOf(Vote("physics", Fam.MILK, 0.5), Vote("AI", Fam.WATERED, 0.45))).unsure)
        assertEquals(Fam.MILK, PurityFusion.fuse(listOf(Vote("physics", Fam.MILK, 0.6), Vote("AI", Fam.WATERED, 0.3))).fam)
    }

    @Test fun waterBlendDropsOutliers() {
        assertEquals(42.5, PurityFusion.water(listOf(40.0 to 0.5, 45.0 to 0.5, 95.0 to 0.2))!!, 0.01)
    }

    @Test fun mapsAiAnswers() {
        assertEquals(Fam.WATERED, PurityFusion.famOfAi(PurityAi.Opinion(null, null, 70.0, "", "", "diluted milk", 40.0)))
        assertEquals(Fam.SPOILED, PurityFusion.famOfAi(PurityAi.Opinion(null, null, 70.0, "", "", "spoiled milk", null)))
        assertEquals(Fam.NOT_MILK, PurityFusion.famOfLabel("honey"))
        assertEquals(Fam.ADULTERANT, PurityFusion.famOfLabel("detergent_milk"))
        assertEquals(Fam.WATERED, PurityFusion.famOfLabel("mix", "milk", 40.0))
        val p = PurityAi.parse("SAMPLE=spoiled milk; WATER_PCT=0; CONFIDENCE=75; REASON=yellow lumpy", emptyList())
        assertEquals("spoiled milk", p.sample)
    }

    @Test fun waterReadingIsCalledWater() {
        // The 11 Oct water test: torch R 0.019 G 0.291 B 0.120 → physics says water (no milk), not "watered milk".
        val g = com.shuddh.lab.core.PurityGuess.guess(mapOf("flash_r" to 0.019, "flash_g" to 0.291, "flash_b" to 0.120, "tex" to 0.33))
        assertEquals(com.shuddh.lab.core.PurityGuess.Kind.WATER, g.kind)
        // Sensors (water, flagged unsure) + AI (water) + physics (water) → confident water.
        val f = PurityFusion.fuse(listOf(Vote("sensors", Fam.WATER, 0.73 * 0.55), Vote("physics", Fam.WATER, 0.56), Vote("AI", Fam.WATER, 0.59)))
        assertEquals(Fam.WATER, f.fam); assertEquals(3, f.agree); assertTrue(f.confidence > 80)
    }
}
