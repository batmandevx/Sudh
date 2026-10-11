package com.shuddh.lab

import com.shuddh.lab.core.Hazards
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.PurityGuess
import com.shuddh.lab.core.PurityGuess.Kind
import com.shuddh.lab.core.Txt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the real reference readings stored on the test phone (PurityDump). */
class PurityGuessTest {
    private fun f(r: Double, g: Double, b: Double, tex: Double) = mapOf("flash_r" to r, "flash_g" to g, "flash_b" to b, "tex" to tex)

    @Test fun phoneReferenceSamplesAreRecognised() {
        val pure = PurityGuess.guess(f(0.732, 0.813, 0.827, 0.034))
        assertEquals(Kind.MILK, pure.kind); assertTrue(pure.waterPct!! < 5)
        val half = PurityGuess.guess(f(0.542, 0.552, 0.573, 0.045))
        assertEquals(Kind.WATERED_MILK, half.kind); assertEquals(50.0, half.waterPct!!, 6.0)
        assertEquals(Kind.WATER, PurityGuess.guess(f(-0.077, 0.0, 0.0, 0.093)).kind)
        assertEquals(Kind.SPOILED, PurityGuess.guess(f(1.098, 1.018, 0.656, 0.061)).kind)
        assertEquals(Kind.NOT_MILK, PurityGuess.guess(f(0.982, 0.478, 0.092, 0.122)).kind)  // honey
        assertEquals(Kind.NOT_MILK, PurityGuess.guess(f(0.863, 0.120, 0.082, 0.058)).kind)  // juice
    }

    @Test fun curveIsMonotonicAndInvertible() {
        for (w in listOf(0.0, 20.0, 40.0, 60.0, 80.0)) assertEquals(w, PurityGuess.waterFrom(PurityGuess.reflFrom(w)), 1.0)
        assertTrue(PurityGuess.reflFrom(10.0, 6.0) > PurityGuess.reflFrom(10.0, 3.0))   // full cream is brighter
    }

    @Test fun hazardsMatchTheVerdict() {
        fun o(id: String, unit: String, lv: Level) = Outcome("Shuddh Purity", id, Txt(id), 40.0, unit, lv, "", emptyList(), emptyList())
        assertEquals("water", Hazards.forOutcome(o("purity_mix", "% water", Level.UNSAFE)).first().key)
        assertEquals("spoiled", Hazards.forOutcome(o("purity_spoiled_milk", "% sure", Level.UNSAFE)).first().key)
        assertEquals("detergent", Hazards.forOutcome(o("purity_detergent_milk", "% sure", Level.UNSAFE)).first().key)
        assertEquals("unknown", Hazards.forOutcome(o("purity_not_milk", "% sure", Level.UNSAFE)).first().key)
        assertTrue(Hazards.forOutcome(o("purity_pure_milk", "% sure", Level.SAFE)).isEmpty())
    }

    @Test fun meshImagePiecesFitAPacketAndReassemble() {
        val jpeg = ByteArray(1500) { (it * 7).toByte() }
        val chunks = com.shuddh.lab.core.MeshImage.chunks(2_000_000_000, jpeg)
        assertTrue(chunks.all { it.length <= 170 })
        val back = chunks.map { com.shuddh.lab.core.MeshImage.parse(it)!! }.sortedBy { it.index }.fold(ByteArray(0)) { a, p -> a + p.bytes }
        assertTrue(back.contentEquals(jpeg))
        assertEquals(chunks.size, com.shuddh.lab.core.MeshImage.parse(chunks[0])!!.total)
    }
}
