package com.shuddh.lab

import com.shuddh.lab.core.LocalLlm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopCutTest {
    @Test fun cutsRepeatedLines() {
        val s = "## Ingredients\n- 2 cups water\n- 1/2 inch of whole fennel\n- 1/2 inch of whole clove\n- 1/2 inch of whole fennel\n- 1/2 inch of whole clove\n- 1/2 inch of whole fennel\n"
        val cut = LocalLlm.loopCut(s)!!
        val kept = s.substring(0, cut)
        assertEquals(1, Regex("fennel").findAll(kept).count())
        assertTrue(kept.contains("2 cups water"))
    }

    @Test fun cutsInlineLoop() {
        val s = "Boil the tea well. and stir and stir and stir and stir and stir and stir and stir"
        assertTrue(LocalLlm.loopCut(s) != null)
    }

    @Test fun leavesNormalRecipeAlone() {
        val s = "## Steps\n1. Boil 2 cups of water for 3 minutes.\n2. Add tea leaves and boil for 2 minutes.\n3. Add milk and boil for 2 minutes.\n4. Strain and serve hot."
        assertNull(LocalLlm.loopCut(s))
    }
}
