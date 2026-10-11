package com.shuddh.lab

import com.shuddh.lab.core.Agro
import com.shuddh.lab.core.Farm
import com.shuddh.lab.core.FarmTwin
import com.shuddh.lab.core.Irrigation
import com.shuddh.lab.core.Past
import com.shuddh.lab.core.Plan
import com.shuddh.lab.core.Scenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FarmTwinTest {
    private val lat = 26.0
    private fun wx(from: Long, n: Int) = FarmTwin.series(lat, from, n, emptyMap(), emptyMap())
    private val kharif = LocalDate.of(2026, 6, 20).toEpochDay()
    private val rabi = LocalDate.of(2026, 11, 10).toEpochDay()

    @Test fun irrigatedRiceReachesMostOfPotential() {
        val farm = Farm(lat = lat, lon = 80.0, soil = "clay", irrigation = Irrigation.FULL)
        val r = FarmTwin.simulate(farm, Plan("rice", 2.0, kharif), wx(kharif, 260))
        assertTrue(r.matured)
        assertTrue("yield ${r.yieldQ}", r.yieldQ in 15.0..26.0)
        assertTrue(r.harvestDay - kharif in 90..170)
        assertEquals(r.yieldQ * 2, r.totalQ, 1e-9)
    }

    @Test fun droughtHurtsRainfedMaizeAndRaisesPrice() {
        val farm = Farm(lat = lat, lon = 80.0, soil = "alluvial", irrigation = Irrigation.RAINFED)
        val normal = FarmTwin.simulate(farm, Plan("maize", 1.0, kharif), wx(kharif, 200))
        val dry = FarmTwin.simulate(farm, Plan("maize", 1.0, kharif), wx(kharif, 200), Scenario.DROUGHT)
        assertTrue(dry.yieldQ < normal.yieldQ)
        assertTrue(dry.price > normal.price)
    }

    @Test fun sub1TraitProtectsRiceInFlood() {
        val farm = Farm(lat = lat, lon = 80.0, soil = "clay", irrigation = Irrigation.LIMITED)
        val plain = FarmTwin.simulate(farm, Plan("rice", 1.0, kharif), wx(kharif, 260), Scenario.FLOOD)
        val sub1 = FarmTwin.simulate(farm, Plan("rice", 1.0, kharif, setOf("sub1")), wx(kharif, 260), Scenario.FLOOD)
        assertTrue(sub1.factors["Flood"]!! >= plain.factors["Flood"]!!)
        val gn1a = FarmTwin.simulate(farm, Plan("rice", 1.0, kharif, setOf("gn1a")), wx(kharif, 260))
        val base = FarmTwin.simulate(farm, Plan("rice", 1.0, kharif), wx(kharif, 260))
        assertTrue(gn1a.harvestDay < base.harvestDay)
    }

    @Test fun historyCalibratesTheTwin() {
        val farm = Farm(lat = lat, history = listOf(Past(2024, "wheat", 13.0), Past(2025, "wheat", 13.0)))
        val c = Agro.crop("wheat")!!
        assertTrue(FarmTwin.calibration(farm, c) < 0.9)
        assertEquals(1.0, FarmTwin.calibration(Farm(), c), 1e-9)
        // Same crop again → rotation penalty; after a legume → bonus.
        val again = FarmTwin.simulate(farm, Plan("wheat", 1.0, rabi), wx(rabi, 230))
        assertTrue(again.factors["Rotation"]!! < 1.0)
    }

    @Test fun heatwaveCutsWheat() {
        val farm = Farm(lat = lat, soil = "alluvial", irrigation = Irrigation.FULL)
        val n = FarmTwin.simulate(farm, Plan("wheat", 1.0, rabi), wx(rabi, 230))
        val h = FarmTwin.simulate(farm, Plan("wheat", 1.0, rabi), wx(rabi, 230), Scenario.HEAT)
        assertTrue(h.heatDays >= n.heatDays)
        assertTrue(h.yieldQ <= n.yieldQ)
    }

    @Test fun rankingAndSoilsAndNews() {
        val farm = Farm(lat = lat, soil = "black", irrigation = Irrigation.LIMITED)
        val picks = FarmTwin.rank(farm, { d, n -> wx(d, n) }, Scenario.LIVE, LocalDate.of(2026, 5, 25))
        assertTrue(picks.isNotEmpty())
        assertTrue(picks.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertEquals("sandy", Agro.soilFrom(8.0, 80.0, 7.9))
        assertEquals("black", Agro.soilFrom(50.0, 15.0, 7.9))
        assertEquals("laterite", Agro.soilFrom(30.0, 40.0, 5.2))
        assertEquals(2, FarmTwin.newsScore(listOf("Onion prices surge on shortage", "Tomato rally continues")))
        assertEquals(-1, FarmTwin.newsScore(listOf("Bumper wheat crop expected")))
    }

    @Test fun storableCropWaitsForBetterMonth() {
        val onion = Agro.crop("onion")!!
        val (m, _) = FarmTwin.bestSellMonth(onion, 4)
        assertTrue(m in onion.pricePeak || m !in onion.priceLow)
        assertEquals(1, FarmTwin.bestSellMonth(Agro.crop("tomato")!!, 1).first)
    }

    @Test fun assistantOpensFarmTwin() {
        for (q in listOf("what should I grow this season", "where to sell my onion", "gene editing for drought rice", "how to do rice fish farming", "show my farm"))
            assertEquals(q, "open_instrument", com.shuddh.lab.core.Agent.ruleRoute(q, false).name)
        assertEquals("open_instrument", com.shuddh.lab.core.Agent.ruleRoute("stones in my rice", false).name)
        assertEquals("GRAIN", com.shuddh.lab.core.Agent.testScreen("stones in my rice"))
    }

    @Test fun waterfallEndsAtTheTwinsYield() {
        val farm = Farm(lat = lat, soil = "alluvial", irrigation = Irrigation.RAINFED)
        val r = FarmTwin.simulate(farm, Plan("maize", 1.0, kharif), wx(kharif, 200), Scenario.DROUGHT)
        val w = FarmTwin.waterfall(farm, r)
        assertEquals(r.yieldQ, w.last().second, 1e-6)
        assertTrue(w.zipWithNext().all { (a, b) -> b.second <= a.second + 1e-9 })
        assertEquals(r.cost, FarmTwin.costs(r).sumOf { it.second }, 1.0)
    }

    @Test fun rangeIsOrderedAndBacktestUsesArchive() {
        val farm = Farm(lat = lat, soil = "alluvial", irrigation = Irrigation.LIMITED, history = listOf(Past(2025, "rice", 20.0)))
        val archive = (LocalDate.of(2024, 10, 1).toEpochDay()..LocalDate.of(2026, 10, 1).toEpochDay()).associateWith { FarmTwin.climate(lat, it) }
        val rg = FarmTwin.range(farm, Plan("rice", 1.0, kharif), emptyMap(), archive, Scenario.LIVE)
        assertTrue(rg.low <= rg.mid && rg.mid <= rg.high)
        val bt = FarmTwin.backtest(farm, archive)
        assertEquals(1, bt.size); assertEquals(2025, bt[0].second.year); assertTrue(bt[0].second.twin > 0)
        assertTrue(FarmTwin.backtest(farm, emptyMap()).isEmpty())
    }
}
