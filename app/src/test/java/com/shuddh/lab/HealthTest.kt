package com.shuddh.lab

import com.shuddh.lab.core.AnaemiaIndex
import com.shuddh.lab.core.CommunityItem
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.OutbreakWatch
import com.shuddh.lab.core.OutbreakWatch.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L
    private fun case(sym: String, ago: Long, people: Int = 1, home: String = "h$ago", area: String = "Ward 12") =
        CommunityItem("case", "", area, sym, Level.CAUTION, "", now - ago, people, 0, home)
    private fun water(ago: Long, level: Level = Level.UNSAFE, area: String = "Ward 12") =
        CommunityItem("alert", "Borewell 5th Cross", area, "Nitrate in water", level, "", now - ago, 1, 1, "x")

    @Test fun quietAreaIsClear() {
        val a = OutbreakWatch.assess(listOf(case("diarrhoea", 200 * h)), "Ward 12", now)
        assertEquals(Status.CLEAR, a.status)
    }

    @Test fun clusterOfStomachIllnessAboveBaselineIsAlert() {
        val items = (1..6).map { case("diarrhoea", it * 8 * h) } + case("vomiting", 20 * h)
        val a = OutbreakWatch.assess(items, "Ward 12", now)
        assertEquals(Status.ALERT, a.status)
        assertEquals(OutbreakWatch.Symptom.DIARRHOEA, a.dominant)
        assertTrue(a.recentCases >= 6)
    }

    @Test fun waterReportPlusCasesEscalates() {
        val items = listOf(case("diarrhoea", 5 * h), case("diarrhoea", 10 * h), case("vomiting", 30 * h), water(40 * h))
        val a = OutbreakWatch.assess(items, "Ward 12", now)
        assertEquals(Status.ALERT, a.status)
        assertTrue(a.waterLinked)
    }

    @Test fun jaundiceTwoCasesAlert() {
        val a = OutbreakWatch.assess(listOf(case("jaundice", 5 * h), case("jaundice", 50 * h)), "Ward 12", now)
        assertEquals(Status.ALERT, a.status)
    }

    @Test fun otherAreasIgnoredAndHighBaselineDamps() {
        val other = (1..6).map { case("diarrhoea", it * 6 * h, area = "Ward 9") }
        assertEquals(Status.CLEAR, OutbreakWatch.assess(other, "Ward 12", now).status)
        // A ward that always has ~4 cases per 3 days isn't an outbreak at 4.
        val usual = (0 until 18).map { case("fever", (80 + it * 16) * h) }
        val recent = (1..4).map { case("fever", it * 10 * h) }
        assertTrue(OutbreakWatch.assess(usual + recent, "Ward 12", now).status != Status.ALERT)
    }

    @Test fun compactCaseRoundTrip() {
        val back = CommunityItem.parseCompact(case("diarrhoea", 0, people = 3).compact())!!
        assertEquals("case", back.kind); assertEquals(3, back.total)
    }

    @Test fun pallorBands() {
        // Pink nail bed vs a pale one, both against the same white card.
        val pink = AnaemiaIndex.erythema(215.0, 140.0, 240.0, 240.0)
        val pale = AnaemiaIndex.erythema(228.0, 192.0, 240.0, 240.0)
        assertEquals(Level.SAFE, AnaemiaIndex.band(pink).level)
        assertEquals(Level.UNSAFE, AnaemiaIndex.band(pale).level)
    }
}

class HbIndexTest {
    /** Fingertip PPG at 30 fps: DC levels per channel, pulse depth per channel. */
    private fun synth(rDc: Double, gDc: Double, rDepth: Double, gDepth: Double): Triple<List<Double>, List<Double>, List<Double>> {
        val t = mutableListOf<Double>(); val r = mutableListOf<Double>(); val g = mutableListOf<Double>()
        var tt = 0.0
        while (tt < 12) {
            val ph = (tt * 72 / 60) % 1.0
            val p = kotlin.math.exp(-((ph - 0.2) * (ph - 0.2)) / 0.01)
            t += tt; r += rDc * (1 - rDepth * p); g += gDc * (1 - gDepth * p)
            tt += 1 / 30.0
        }
        return Triple(t, r, g)
    }

    @org.junit.Test fun moreHaemoglobinGivesHigherIndex() {
        val (t1, r1, g1) = synth(200.0, 12.0, 0.01, 0.04)   // dark green transmission, strong green pulse
        val (t2, r2, g2) = synth(200.0, 50.0, 0.01, 0.015)  // pale: green passes through
        val rich = com.shuddh.lab.core.HbIndex.analyse(t1, r1, g1)!!
        val pale = com.shuddh.lab.core.HbIndex.analyse(t2, r2, g2)!!
        org.junit.Assert.assertTrue("rich ${rich.index} pale ${pale.index}", rich.index > pale.index + 15)
        org.junit.Assert.assertEquals(72.0, rich.bpm!!, 4.0)
        org.junit.Assert.assertEquals(com.shuddh.lab.core.Level.SAFE, com.shuddh.lab.core.HbIndex.band(rich.index, null).level)
        org.junit.Assert.assertNotEquals(com.shuddh.lab.core.Level.SAFE, com.shuddh.lab.core.HbIndex.band(pale.index, null).level)
    }

    @org.junit.Test fun calibrationScalesAroundLabValue() {
        org.junit.Assert.assertEquals(12.5, com.shuddh.lab.core.HbIndex.estimateHb(60.0, 12.5, 60.0), 1e-9)
        org.junit.Assert.assertTrue(com.shuddh.lab.core.HbIndex.estimateHb(45.0, 12.5, 60.0) < 12.5)
    }
}
