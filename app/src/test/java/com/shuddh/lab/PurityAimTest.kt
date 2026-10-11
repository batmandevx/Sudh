package com.shuddh.lab

import com.shuddh.lab.core.PurityTrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the real readings from the "water was called honey" report (11 Oct 2026). */
class PurityAimTest {
    private fun s(label: String, vararg kv: Pair<String, Double>) = PurityTrain.Sample(label + kv.size, label, 0L, kv.toMap(), null)

    private val retrained = listOf(
        s("water", "amb_b" to 1.035, "amb_g" to 1.233, "amb_r" to 1.286, "flash_b" to 0.236, "flash_g" to 0.516, "flash_r" to 0.806, "tex" to 0.101),
        s("pure_milk", "amb_b" to 1.828, "amb_g" to 2.177, "amb_r" to 2.093, "tex" to 0.022),
        s("half", "amb_b" to 1.373, "amb_g" to 1.766, "amb_r" to 1.829, "tex" to 0.045),
        s("spoiled_milk", "amb_b" to 1.261, "amb_g" to 1.594, "amb_r" to 1.567, "flash_b" to 0.794, "flash_g" to 0.716, "flash_r" to 0.574, "tex" to 0.013),
        s("honey", "flash_b" to 0.902, "flash_g" to 0.886, "flash_r" to 0.898, "tex" to 0.096),
    )
    private val waterTest = mapOf("amb_b" to 1.369, "amb_g" to 1.765, "amb_r" to 2.034, "flash_b" to 1.007, "flash_g" to 1.070, "flash_r" to 1.024, "tex" to 0.049)

    @Test fun impossibleReadingsAreRejected() {
        assertNotNull(PurityTrain.aimProblem(mapOf("flash_r" to 1.7, "flash_g" to 1.6, "flash_b" to 1.5)))  // impossibly bright → retake
        // pure milk + 50/50 (room light only, shadowed), "honey" that was plain paper and "water" that read bright are rejected.
        assertEquals(4, retrained.count { PurityTrain.aimProblem(it.f, it.label) != null })
        assertNull(PurityTrain.aimProblem(mapOf("flash_r" to 0.73, "flash_g" to 0.81, "flash_b" to 0.83)))  // good pure milk
        // Pure milk in the torch hot-spot reads brighter than the paper — that's fine (the 11 Oct report).
        val hotMilk = mapOf("flash_r" to 1.274, "flash_g" to 1.258, "flash_b" to 1.076, "tex" to 0.038)
        assertNull(PurityTrain.aimProblem(hotMilk, "pure_milk")); assertNull(PurityTrain.aimProblem(hotMilk))
        // Water that reads like paper is a setup error.
        assertNotNull(PurityTrain.aimProblem(mapOf("flash_r" to 1.0, "flash_g" to 1.054, "flash_b" to 1.008), "water"))
        assertNull(PurityTrain.aimProblem(mapOf("flash_r" to -0.08, "flash_g" to 0.0, "flash_b" to 0.0)))   // good water
    }

    @Test fun badSamplesNeverDecideAndMissingFeaturesCantWin() {
        // The bad test reading never reaches the classifier (it gets "retake"), and bad samples never vote.
        // Only spoiled milk survives → too few kinds for a trained verdict (the physics + AI vote decides instead).
        assertNull(PurityTrain.classify(mapOf("flash_r" to 0.02, "flash_g" to 0.01, "flash_b" to 0.0, "tex" to 0.09), retrained))
        // A kind measured only on texture must not beat kinds measured on colour.
        val good = listOf(
            s("water", "flash_r" to 0.0, "flash_g" to 0.0, "flash_b" to 0.0, "tex" to 0.09),
            s("pure_milk", "flash_r" to 0.73, "flash_g" to 0.81, "flash_b" to 0.83, "tex" to 0.03),
            s("honey", "tex" to 0.05),
        )
        val r = PurityTrain.classify(mapOf("flash_r" to 0.02, "flash_g" to 0.01, "flash_b" to 0.0, "tex" to 0.05), good)!!
        assertEquals("water", r.ranked.first().label)
        assertTrue(r.ranked.none { it.label == "honey" && it.p > 0.3 })
    }

    @Test fun shadowedRoomLightIsDroppedNotTheWholeCapture() {
        // Torch reading fine (50/50 ≈ 0.55), room light spoiled by the phone's shadow (> 1.3).
        val f = mapOf("amb_r" to 1.83, "amb_g" to 1.77, "amb_b" to 1.37, "flash_r" to 0.54, "flash_g" to 0.55, "flash_b" to 0.57, "tex" to 0.045)
        assertNull(PurityTrain.aimProblem(f))
        assertTrue(PurityTrain.clean(f).keys.none { it.startsWith("amb_") })
        // No torch reading and a spoiled room-light one: still rejected.
        assertNotNull(PurityTrain.aimProblem(mapOf("amb_r" to 1.83, "amb_g" to 1.77, "amb_b" to 1.37)))
    }
}
