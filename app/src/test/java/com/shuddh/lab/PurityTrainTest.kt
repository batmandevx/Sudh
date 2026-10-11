package com.shuddh.lab

import com.shuddh.lab.core.PurityTrain
import com.shuddh.lab.core.PurityTrain.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PurityTrainTest {
    private val rnd = Random(9)
    // Class means: (amb_r, amb_g, amb_b, tex)
    private val means = mapOf(
        "water" to doubleArrayOf(0.20, 0.21, 0.24, 0.03),
        "pure_milk" to doubleArrayOf(0.85, 0.84, 0.80, 0.03),
        "half" to doubleArrayOf(0.70, 0.69, 0.66, 0.03),
        "fresh_milk" to doubleArrayOf(0.85, 0.84, 0.80, 0.03),
        "spoiled_milk" to doubleArrayOf(0.83, 0.82, 0.74, 0.14),
        "honey" to doubleArrayOf(0.62, 0.40, 0.12, 0.04),
    )
    private fun sample(lb: String, i: Int) = Sample("$lb$i", lb, i.toLong(),
        listOf("amb_r", "amb_g", "amb_b", "tex").mapIndexed { k, n -> n to means[lb]!![k] + rnd.nextDouble(-0.008, 0.008) }.toMap(), null)

    private val train = means.keys.flatMap { lb -> (0 until 3).map { sample(lb, it) } }

    @Test fun separatesSpoiledFromFreshAndHoney() {
        assertEquals("spoiled_milk", PurityTrain.classify(sample("spoiled_milk", 9).f, train)!!.ranked.first().label)
        assertEquals("honey", PurityTrain.classify(sample("honey", 9).f, train)!!.ranked.first().label)
        assertEquals("half", PurityTrain.classify(sample("half", 9).f, train)!!.ranked.first().label)
    }

    @Test fun waterCurveFromTraining() {
        val (key, m) = PurityTrain.waterCurve(train, PurityTrain.presets)!!
        assertTrue(key.startsWith("amb"))
        assertEquals(50.0, m.waterPct(0.70), 6.0)
    }

    @Test fun mixtureBetweenTrainedKindsGivesRange() {
        // ~70 % water: between "half" (0.70) and "water" (0.20) on amb channels.
        val mix = mapOf("amb_r" to 0.45, "amb_g" to 0.45, "amb_b" to 0.45, "tex" to 0.03)
        val r = PurityTrain.classify(mix, train)!!
        val (key, m) = PurityTrain.waterCurve(train, PurityTrain.presets)!!
        val w = m.waterPct(mix[key]!!)
        assertTrue("w=$w", w in 60.0..85.0)
        val (lo, hi) = PurityTrain.range(w, 4.0)
        assertTrue(lo < w && hi > w)
        val fit = PurityTrain.dilutionFit(mix, train, PurityTrain.presets)!!
        assertTrue("dLine ${fit.second}", PurityTrain.onDilutionLine(fit, r))
    }

    @Test fun farFromEverythingIsUnknown() {
        val weird = mapOf("amb_r" to 0.05, "amb_g" to 0.9, "amb_b" to 0.05, "tex" to 0.4)
        assertTrue(PurityTrain.unknown(PurityTrain.classify(weird, train)!!))
    }

    @Test fun boxOnPaperIsCaught() {
        assertTrue(PurityTrain.captureProblem(mapOf("flash_r" to 1.04, "flash_g" to 1.05, "flash_b" to 0.99))!!.contains("paper"))
        assertEquals(null, PurityTrain.captureProblem(mapOf("flash_r" to 0.73, "flash_g" to 0.81, "flash_b" to 0.83)))
    }

    @Test fun aiOnlyBreaksTies() {
        val r = PurityTrain.Result(listOf(PurityTrain.Ranked("fresh_milk", 0.55, 1.0), PurityTrain.Ranked("spoiled_milk", 0.4, 1.2)), null, emptyList())
        assertEquals("spoiled_milk", PurityTrain.decide(r, "spoiled_milk", 80.0).label)       // unsure sensors → AI tie-break
        val sure = r.copy(ranked = listOf(PurityTrain.Ranked("fresh_milk", 0.9, 0.5), PurityTrain.Ranked("spoiled_milk", 0.1, 2.0)))
        val d = PurityTrain.decide(sure, "spoiled_milk", 90.0)
        assertEquals("fresh_milk", d.label); assertEquals(false, d.aiAgrees)                    // sure sensors win, flagged
    }
}

class PurityAiTest {
    @Test fun parsesModelLine() {
        val o = com.shuddh.lab.core.PurityAi.parse("Answer: CLASS=spoiled_milk; SAFE=no; CONFIDENCE=85; REASON=high texture like curdled milk", listOf("fresh_milk", "spoiled_milk"))
        assertEquals("spoiled_milk", o.label); assertEquals(false, o.safe); assertEquals(85.0, o.confidence!!, 1e-9)
        val messy = com.shuddh.lab.core.PurityAi.parse("**CLASS: Fresh_Milk**; safe: yes; confidence: 70%", listOf("fresh_milk", "spoiled_milk"))
        assertEquals("fresh_milk", messy.label); assertEquals(true, messy.safe)
    }
}
