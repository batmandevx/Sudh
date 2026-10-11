package com.shuddh.lab

import com.shuddh.lab.core.Agent
import com.shuddh.lab.core.PantrySmart
import com.shuddh.lab.core.WaxCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaxPantryTest {
    private fun px(n: Int, white: Int): IntArray = IntArray(n) { if (it < white) 0xFFFAFAFA.toInt() else 0xFFB4141E.toInt() }

    @Test fun shineCountsWhiteHighlightsOnly() {
        val s = WaxCheck.shine(px(1000, 50), step = 1)
        assertEquals(0.05, s.specular, 1e-9)
        assertTrue(s.sat > 0.8) // red peel stays saturated
    }

    @Test fun glossyUniformFruitReadsWaxed() {
        val waxed = WaxCheck.assess(listOf(3.4, 3.0, 3.6))
        val natural = WaxCheck.assess(listOf(0.6, 0.3, 0.9))
        assertTrue(waxed.waxed && !natural.waxed)
        assertTrue(waxed.probability <= 0.80) // shine alone is capped as an estimate
    }

    @Test fun hotWaterHazeRaisesCertainty() {
        val shineOnly = WaxCheck.assess(listOf(2.2, 2.0, 2.4))
        val withHaze = WaxCheck.assess(listOf(2.2, 2.0, 2.4), haze = 0.25)
        assertTrue(withHaze.probability > shineOnly.probability)
        assertTrue(withHaze.confidence >= shineOnly.confidence)
    }

    @Test fun referenceMakesItRelative() {
        // 1.6 gloss is ambiguous alone, but 4× a known-unwaxed fruit is clearly coated.
        assertTrue(WaxCheck.assess(listOf(1.6, 1.6, 1.6), reference = 0.4).waxed)
        assertTrue(!WaxCheck.assess(listOf(1.6, 1.6, 1.6), reference = 1.5).waxed)
    }

    @Test fun learnsBuyingRhythm() {
        val day = 86_400_000L
        val now = 100 * day
        val hist = mapOf("milk" to listOf(now - 6 * day, now - 4 * day, now - 2 * day), "rice" to listOf(now - 2 * day))
        val r = PantrySmart.restock(hist, emptySet(), now)
        assertEquals(listOf("milk"), r.map { it.key })
        assertEquals(2.0, r[0].everyDays, 1e-9)
        assertTrue(r[0].learned)
        assertTrue(PantrySmart.restock(hist, setOf("milk"), now).isEmpty())
    }

    @Test fun staplesAndSeasons() {
        assertEquals("dal", PantrySmart.keyOf("Tata Tur Dal 1kg"))
        assertEquals("milk", PantrySmart.keyOf("Amul Toned Milk"))
        val april = PantrySmart.tips(4).associateBy { it.season.key }
        assertEquals(PantrySmart.Advice.STOCK_UP, april["onion"]!!.advice)
        assertEquals(PantrySmart.Advice.STOCK_UP, april["atta"]!!.advice)
        assertEquals(PantrySmart.Advice.AVOID, PantrySmart.tips(7)["tomato".let { k -> PantrySmart.tips(7).indexOfFirst { it.season.key == k } }].advice)
        assertEquals(0, PantrySmart.monthsToCheap(PantrySmart.seasons.first { it.key == "tomato" }, 1))
    }

    @Test fun assistantOpensTheRightTest() {
        assertEquals("PURITY", Agent.testScreen("is my milk pure?"))
        assertEquals("PURITY", Agent.testScreen("check detergent in milk"))
        assertEquals("WAX", Agent.testScreen("is this apple waxed"))
        assertEquals("ECHO", Agent.testScreen("is this watermelon ripe"))
        assertEquals("DART", Agent.testScreen("is my drinking water safe"))
        assertEquals("GRAIN", Agent.testScreen("check rice for stones"))
        assertNull(Agent.testScreen("is wax on apples harmful"))
        assertNull(Agent.testScreen("recipe for milk tea"))
        assertEquals("open_instrument", Agent.ruleRoute("is my milk adulterated", false).name)
        assertEquals("pantry_brief", Agent.ruleRoute("what is running out in my pantry", false).name)
    }

    @Test fun problemDescriptionsOpenTheRightTest() {
        assertEquals("PURITY", Agent.testScreen("my milk tastes weird"))
        assertEquals("PURITY", Agent.testScreen("doodh ka swad ajeeb hai"))
        assertEquals("PURITY", Agent.testScreen("the milk is very watery these days"))
        assertEquals("PURITY", Agent.testScreen("my tea tastes soapy"))
        assertEquals("WAX", Agent.testScreen("these apples look too shiny"))
        assertEquals("ECHO", Agent.testScreen("watermelon is too red inside"))
        assertEquals("GRAIN", Agent.testScreen("I found stones in my rice"))
        assertEquals("OIL", Agent.testScreen("the frying oil turned dark and foamy"))
        assertEquals("DART", Agent.testScreen("tap water smells like bleach"))
        assertEquals("DART", Agent.testScreen("haldi leaves yellow colour in water"))
        assertEquals("NAMI", Agent.testScreen("my cupboard smells musty"))
        assertNull(Agent.testScreen("is milk good for health"))
        assertEquals("open_instrument", Agent.ruleRoute("my milk tastes weird", false).name)
    }
}
