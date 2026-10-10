package com.shuddh.lab

import com.shuddh.lab.core.Spoilage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MilkChecksTest {
    private fun film(w: Int, h: Int, flecks: Int, rnd: Random): FloatArray {
        val l = FloatArray(w * h) { 180f + rnd.nextFloat() * 6 }         // smooth film with sensor noise
        repeat(flecks) { val x = rnd.nextInt(w); val y = rnd.nextInt(h); for (dy in 0..1) for (dx in 0..1) { val i = ((y + dy).coerceAtMost(h - 1)) * w + (x + dx).coerceAtMost(w - 1); l[i] = 240f } }
        return l
    }

    @Test fun curdFlecksRaiseSpeckle() {
        val rnd = Random(2)
        val fresh = Spoilage.speckle(film(120, 90, 0, rnd), 120, 90)
        val curdled = Spoilage.speckle(film(120, 90, 900, rnd), 120, 90)
        assertTrue("fresh $fresh", fresh < 1.0)
        assertTrue("curdled $curdled", curdled > 5.0)
        assertEquals(Spoilage.Call.FRESH, Spoilage.call(fresh, fresh))
        assertEquals(Spoilage.Call.SPOILED, Spoilage.call(curdled, fresh))
    }
}
