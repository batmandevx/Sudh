package com.shuddh.lab

import com.shuddh.lab.core.Farm
import com.shuddh.lab.core.FieldKit
import com.shuddh.lab.core.LeafDoctor
import com.shuddh.lab.core.Livestock
import com.shuddh.lab.core.Schemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.sin

class CrispRootsTest {
    @Test fun gpsAreaOfOneAcreSquare() {
        // ~63.6 m square ≈ 1 acre at 20° N.
        val dLat = 63.6 / 111_195.0; val dLon = 63.6 / (111_195.0 * Math.cos(Math.toRadians(20.0)))
        val sq = listOf(20.0 to 74.0, 20.0 + dLat to 74.0, 20.0 + dLat to 74.0 + dLon, 20.0 to 74.0 + dLon)
        assertEquals(1.0, FieldKit.areaM2(sq) / FieldKit.M2_PER_ACRE, 0.02)
        assertEquals(254.4, FieldKit.perimeterM(sq), 1.5)
    }

    @Test fun slopeFromGravity() {
        assertEquals(0.0, FieldKit.slopePct(0f, 0f, 9.81f), 1e-6)
        assertEquals(10.0, FieldKit.slopePct(0.976f, 0f, 9.76f), 0.2)
    }

    @Test fun canopyCoverAndFertiliser() {
        val green = IntArray(300) { 0xFF2E9E3A.toInt() }; val soil = IntArray(300) { 0xFF8B5A2B.toInt() }
        assertTrue(FieldKit.canopy(green).cover > 0.9)
        assertTrue(FieldKit.canopy(soil).cover < 0.1)
        val f = FieldKit.fertiliser(48.0, 24.0, 16.0)
        assertEquals(52.2, f.dap, 0.2)        // 24 / 0.46
        assertEquals(84.0, f.urea, 0.6)       // (48 − 52.2×0.18) / 0.46
        assertEquals(26.7, f.mop, 0.1)
    }

    @Test fun sprayWindowAvoidsRainAndWind() {
        val start = LocalDateTime.of(2026, 10, 12, 0, 0)
        val hours = (0 until 48).map { i ->
            val t = start.plusHours(i.toLong())
            FieldKit.Hour(t, 26.0, 60.0, if (t.hour in 14..16) 70.0 else 5.0, 0.0, if (t.hour in 6..9) 8.0 else 20.0, null)
        }
        val w = FieldKit.sprayWindows(hours, start)
        assertTrue(w.isNotEmpty())
        assertTrue(w.all { it.start.hour in 6..9 })
        assertEquals(0, FieldKit.blightHours(hours))
    }

    @Test fun livestockSignsPointToTheRightDisease() {
        assertEquals("fmd", Livestock.check(Livestock.Species.COW, setOf("mouth_blisters", "hoof_blisters", "drool")).first().disease.key)
        assertEquals("lsd", Livestock.check(Livestock.Species.COW, setOf("skin_nodules", "fever")).first().disease.key)
        assertEquals("mastitis", Livestock.check(Livestock.Species.BUFFALO, setOf("udder_hot", "milk_clots")).first().disease.key)
        assertEquals("milkfever", Livestock.check(Livestock.Species.COW, setOf("recent_calving", "down", "cold_ears")).first().disease.key)
        assertEquals("ppr", Livestock.check(Livestock.Species.GOAT, setOf("nasal", "diarrhoea", "mouth_blisters", "fever")).first().disease.key)
        assertTrue(Livestock.check(Livestock.Species.COW, emptySet()).isEmpty())
    }

    @Test fun breathingRateFromFlankMotion() {
        val fps = 15.0; val bpm = 36.0
        val sig = (0 until (fps * 30).toInt()).map { i -> 100 + 3 * sin(2 * PI * bpm / 60 * i / fps) + (i % 7) * 0.05 }
        val r = Livestock.breathRate(sig, fps)
        assertNotNull(r); assertEquals(36.0, r!!.first.toDouble(), 2.5)
    }

    @Test fun leafLabelsAndSchemes() {
        assertEquals(80, LeafDoctor.labels.size)
        assertEquals("Tomato", LeafDoctor.labels[49].crop); assertEquals("Late blight", LeafDoctor.labels[49].problem)
        val farm = Farm(acres = 2.0, state = "Maharashtra", district = "Nashik", lat = 20.0, lon = 74.0)
        val pmk = Schemes.all.first { it.key == "pmkisan" }
        assertTrue(pmk.fits(farm, 0))
        assertTrue(Schemes.formText(pmk, farm, "Ramesh", "98xxxxxx").contains("Nashik"))
        assertTrue(!Schemes.all.first { it.key == "nadcp" }.fits(farm, 0))
    }
}
