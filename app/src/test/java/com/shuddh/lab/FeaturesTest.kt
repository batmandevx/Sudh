package com.shuddh.lab

import com.shuddh.lab.core.CommunityItem
import com.shuddh.lab.core.Insights
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.ScanRecord
import com.shuddh.lab.instruments.WaveCal
import com.shuddh.lab.instruments.compareFingerprints
import com.shuddh.lab.instruments.fingerprintOf
import com.shuddh.lab.instruments.fingerprintVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class FeaturesTest {
    private val now = System.currentTimeMillis()

    private fun rec(
        id: Long, level: Level, instrument: String = "Shuddh Spectrum", tag: String = "", vendor: String = "",
        area: String = "", ago: Long = 0, analyte: String = "Detergent in milk",
    ) = ScanRecord(id, now - ago, instrument, "x", analyte, 1.0, "AU", level, "", emptyList(), tag, vendor, false, "p", "h$id", area)

    @Test fun communityPayloadRoundTrips() {
        val item = CommunityItem("alert", "Ramesh Dairy; Gate 2", "JP Nagar", "Detergent in milk", Level.UNSAFE, "0.21 AU", 1_700_000_000_000, 1, 1, "abc123")
        val back = CommunityItem.parse(item.toPayload())!!
        assertEquals(item.copy(receivedAt = 0), back.copy(receivedAt = 0))
        assertNull(CommunityItem.parse("https://example.com"))
    }

    @Test fun corroborationNeedsTwoInstruments() {
        val rs = listOf(
            rec(1, Level.UNSAFE, "Shuddh Polar", tag = "Honey jar"),
            rec(2, Level.UNSAFE, "Shuddh NIR", tag = "honey jar"),
            rec(3, Level.SAFE, "Shuddh Spectrum", tag = "tap water"),
        )
        val d = Insights.dossiers(rs, now)
        assertEquals(1, d.size)
        assertEquals(Level.UNSAFE, d[0].consensus)
        assertEquals(2, d[0].agree)
        assertTrue(!d[0].conflict)
    }

    @Test fun conflictDetected() {
        val rs = listOf(rec(1, Level.SAFE, "Shuddh Polar", tag = "h"), rec(2, Level.UNSAFE, "Shuddh NIR", tag = "h"))
        assertTrue(Insights.dossiers(rs, now).single().conflict)
    }

    @Test fun areaBoardMergesCommunity() {
        val rs = listOf(rec(1, Level.UNSAFE, area = "JP Nagar"), rec(2, Level.SAFE, area = "jp nagar "), rec(3, Level.SAFE, area = "Indiranagar"))
        val comm = listOf(CommunityItem("seal", "Shop", "JP Nagar", "Vendor record", Level.UNSAFE, "", now, 10, 4, "r"))
        val board = Insights.areaBoard(rs, comm)
        val jp = board.first { it.area.equals("JP Nagar", true) }
        assertEquals(5, jp.fails)
        assertEquals(12, jp.total)
        assertEquals("JP Nagar", board.first().area)
    }

    @Test fun dailyScoreAndDigest() {
        val rs = listOf(rec(1, Level.SAFE), rec(2, Level.UNSAFE), rec(3, Level.SAFE, ago = 86_400_000L * 40))
        val daily = Insights.daily(rs, 30, now)
        assertEquals(50, daily.last())
        assertTrue(Insights.digest(rs, now)!!.startsWith("2 scans this week"))
    }

    @Test fun fingerprintMatchesItselfNotOthers() {
        val wave = WaveCal(400.0, 300.0, true, "")
        val n = 160
        fun band(c: Double) = FloatArray(n) { i -> (0.6 * exp(-((wave.nm(i, n) - c) / 25).let { it * it })).toFloat() }
        val ghee = fingerprintOf(band(480.0), wave)
        val same = fingerprintOf(band(482.0), wave)
        val other = fingerprintOf(band(620.0), wave)
        val s1 = compareFingerprints(ghee, same)!!
        val s2 = compareFingerprints(ghee, other)!!
        assertTrue(s1.first > 0.95)
        assertTrue(s2.first < 0.5)
        assertEquals(Level.SAFE, fingerprintVerdict("ghee", s1, 1).level)
        assertEquals(Level.UNSAFE, fingerprintVerdict("ghee", s2, 1).level)
        assertNotNull(fingerprintVerdict(null, null, 0))
    }

    @Test fun recordWithoutAreaKeepsV1Payload() {
        val r = rec(1, Level.SAFE)
        assertTrue(!r.copy(area = "").payload().contains("area="))
        assertTrue(r.copy(area = "X").payload().endsWith("|area=X"))
    }
}
