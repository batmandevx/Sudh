package com.shuddh.lab

import com.shuddh.lab.core.FarmData
import com.shuddh.lab.core.PurityAi
import com.shuddh.lab.core.SamplePhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurityPhotoAndSoilTest {
    @Test fun parsesWaterGuessAndSample() {
        val o = PurityAi.parse("CLASS=half; SAMPLE=diluted milk; WATER_PCT=45%; SAFE=no; CONFIDENCE=70; REASON=bluish and thin", listOf("half", "pure_milk", "water"))
        assertEquals("half", o.label); assertEquals("diluted milk", o.sample); assertEquals(45.0, o.waterPct!!, 1e-9); assertEquals(false, o.safe)
    }

    @Test fun aiWaterIsOnlyALightReference() {
        assertEquals(42.0, SamplePhoto.blendWater(40.0, 50.0)!!, 1e-9)
        assertEquals(40.0, SamplePhoto.blendWater(40.0, 90.0)!!, 1e-9)   // too far off → ignored
        assertNull(SamplePhoto.blendWater(null, 50.0))                    // never invents a value
        assertTrue(SamplePhoto.describe(listOf("Milk" to 0.8f), mapOf("amb_r" to 0.80, "amb_g" to 0.82, "amb_b" to 0.86)).contains("bluish"))
    }

    @Test fun soilMapping() {
        assertEquals("black", FarmData.soilFromWrb("Vertisols"))
        assertEquals("alluvial", FarmData.soilFromWrb("Fluvisols"))
        assertEquals("laterite", FarmData.soilFromWrb("Ferralsols"))
        assertEquals("sandy", FarmData.soilFromWrb("Arenosols"))
        assertEquals("black", FarmData.soilForState("Maharashtra"))
        assertEquals("alluvial", FarmData.soilForState("Uttar Pradesh"))
        assertEquals("sandy", FarmData.soilForState("Rajasthan"))
    }
}
