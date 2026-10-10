package com.shuddh.lab

import com.shuddh.lab.instruments.AccelTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

class AccelTapTest {
    private fun knock(hz: Double, tauMs: Double, amp: Double): AccelTap {
        val a = AccelTap(); val rate = 500.0
        for (i in 0 until 225) { // 450 ms
            val t = i / rate; val k = t - 0.15
            val v = if (k < 0) 0.0 else amp * exp(-k * 1000 / tauMs) * sin(2 * Math.PI * hz * k + 0.3)
            a.add((t * 1e9).toLong(), floatArrayOf(0.1f, (0.05 + v).toFloat(), 9.81f))
        }
        return a
    }

    @Test fun measuresFrequencyAndDamping() {
        val full = knock(60.0, 15.0, 3.0).features()!!
        val empty = knock(60.0, 60.0, 3.0).features()!!
        assertEquals(60.0, exp(full[2]), 12.0)
        assertTrue("liquid damps faster", full[1] < empty[1] - ln(2.0))
    }

    @Test fun noKnockNoFeatures() = assertNull(knock(60.0, 20.0, 0.05).features())
}
