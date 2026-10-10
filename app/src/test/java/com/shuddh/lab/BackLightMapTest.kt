package com.shuddh.lab

import com.shuddh.lab.core.BackLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackLightMapTest {
    @Test fun learnsSwappedChannelOrder() {
        // Ring wired BGR: slot 1 (red byte) lights blue, slot 3 (blue byte) lights red.
        val resp = arrayOf(doubleArrayOf(2.0, 4.0, 40.0), doubleArrayOf(3.0, 35.0, 5.0), doubleArrayOf(38.0, 6.0, 2.0))
        BackLight.learn(resp)
        assertEquals(true, BackLight.multicolour)
        assertEquals(0xFF0000FF.toInt(), BackLight.mapped(0xFFFF0000.toInt())) // ask red → send in blue slot
        assertEquals(0xFF00FF00.toInt(), BackLight.mapped(0xFF00FF00.toInt()))
    }

    @Test fun singleColourRingDetected() {
        val resp = arrayOf(doubleArrayOf(2.0, 4.0, 40.0), doubleArrayOf(1.0, 3.0, 30.0), doubleArrayOf(2.0, 5.0, 35.0))
        assertTrue(BackLight.learn(resp).contains("blue"))
        assertEquals(false, BackLight.multicolour)
    }
}
