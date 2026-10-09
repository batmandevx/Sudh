package com.shuddh.lab

import com.shuddh.lab.core.Agent
import com.shuddh.lab.core.Calc
import com.shuddh.lab.core.Gesture
import com.shuddh.lab.core.Kalman1D
import com.shuddh.lab.core.KalmanCV
import com.shuddh.lab.core.P
import com.shuddh.lab.core.Planner
import com.shuddh.lab.core.Units
import com.shuddh.lab.core.VisionCues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AgentVisionTest {
    @Test fun kalman1dReducesNoise() {
        val rnd = Random(1); val k = Kalman1D(q = 0.01, r = 4.0)
        var rawErr = 0.0; var kfErr = 0.0
        repeat(300) { i ->
            val z = 72 + rnd.nextDouble(-4.0, 4.0)
            val x = k.update(z)
            if (i > 50) { rawErr += (z - 72) * (z - 72); kfErr += (x - 72) * (x - 72) }
        }
        assertTrue(kfErr < rawErr / 5)
    }

    @Test fun kalmanCvTracksMovingPoint() {
        val k = KalmanCV(q = 10.0, r = 1e-4)
        var x = 0.0
        repeat(60) { k.predict(1 / 30.0); x += 0.3 / 30; k.update(x) }
        assertEquals(x, k.position, 0.01)
        assertEquals(0.3, k.velocity, 0.05)
    }

    @Test fun calculator() {
        assertEquals(441.0, Calc.eval("what is 18% of 2450")!!, 1e-9)
        assertEquals(96.0, Calc.eval("12 times 8")!!, 1e-9)
        assertEquals(14.0, Calc.eval("2 + 3 * 4")!!, 1e-9)
        assertEquals(20.0, Calc.eval("(2+3)*4")!!, 1e-9)
        assertEquals(12.0, Calc.eval("square root of 144")!!, 1e-9)
        assertEquals(45.0, Calc.eval("15 x 3")!!, 1e-9)
        assertTrue(Calc.looksLikeMath("what is 25 plus 17"))
        assertTrue(!Calc.looksLikeMath("give me a recipe for dal"))
    }

    @Test fun unitConversion() {
        assertEquals("2 cup = 480 ml", Units.convert("convert 2 cups to ml"))
        assertEquals("350 °F = 176.7 °C", Units.convert("350 f to c"))
        assertEquals("5 km = 3.107 mile", Units.convert("5 km in miles"))
        assertTrue(Units.convert("1 cup to grams")!!.contains("density"))
    }

    @Test fun plannerSplitsCompoundRequests() {
        assertEquals(2, Planner.split("give me a recipe for dal and then set a timer for 20 minutes").size)
        assertEquals(2, Planner.split("add milk to my shopping list and open youtube").size)
        assertEquals(1, Planner.split("bread and butter recipe").size)
    }

    @Test fun routerHandlesNewTools() {
        assertEquals("recipe", Agent.ruleRoute("give me a recipe for paneer butter masala", false).name)
        assertEquals("paneer butter masala", Agent.ruleRoute("give me a recipe for paneer butter masala", false).args["dish"])
        assertEquals("recipe", Agent.ruleRoute("how do I make masala chai?", false).name)
        assertEquals("calculate", Agent.ruleRoute("what is 18% of 2450", false).name)
        assertEquals("convert_units", Agent.ruleRoute("convert 2 cups to ml", false).name)
        assertEquals("shopping_add", Agent.ruleRoute("add milk and eggs to my shopping list", false).name)
        assertEquals("milk, eggs", Agent.ruleRoute("add milk and eggs to my shopping list", false).args["items"])
        assertEquals("open_app", Agent.ruleRoute("open youtube", false).name)
        assertEquals("open_instrument", Agent.ruleRoute("open the honey test", false).name)
        assertEquals("call", Agent.ruleRoute("call 98765 43210", false).name)
        assertEquals("open_vision", Agent.ruleRoute("detect my hand gestures", false).name)
        assertEquals("general", Agent.ruleRoute("explain photosynthesis simply", false).name)
    }

    /** Synthetic hand: wrist at bottom, fingers pointing up; [ext] says which fingers are straight. */
    private fun hand(ext: BooleanArray, thumbUp: Boolean = false): List<P> {
        val p = MutableList(21) { P(0.5f, 0.8f) }
        p[0] = P(0.5f, 0.8f)
        val xs = floatArrayOf(0.38f, 0.44f, 0.5f, 0.56f, 0.62f)
        // Thumb 1..4
        if (thumbUp) { p[1] = P(0.42f, 0.72f); p[2] = P(0.38f, 0.64f); p[3] = P(0.37f, 0.56f); p[4] = P(0.36f, 0.48f) }
        else if (ext[0]) { p[1] = P(0.44f, 0.75f); p[2] = P(0.38f, 0.7f); p[3] = P(0.32f, 0.66f); p[4] = P(0.26f, 0.62f) }
        else { p[1] = P(0.45f, 0.75f); p[2] = P(0.44f, 0.7f); p[3] = P(0.47f, 0.67f); p[4] = P(0.5f, 0.66f) }
        for (f in 1..4) {
            val base = 1 + f * 4; val x = xs[f]
            p[base] = P(x, 0.62f) // MCP
            if (ext[f]) { p[base + 1] = P(x, 0.52f); p[base + 2] = P(x, 0.45f); p[base + 3] = P(x, 0.38f) }
            else { p[base + 1] = P(x, 0.56f); p[base + 2] = P(x, 0.63f); p[base + 3] = P(x, 0.68f) }
        }
        return p
    }

    @Test fun gestures() {
        assertEquals(Gesture.OPEN, VisionCues.gesture(hand(booleanArrayOf(true, true, true, true, true))).first)
        assertEquals(Gesture.FIST, VisionCues.gesture(hand(booleanArrayOf(false, false, false, false, false))).first)
        assertEquals(Gesture.VICTORY, VisionCues.gesture(hand(booleanArrayOf(false, true, true, false, false))).first)
        assertEquals(Gesture.POINT, VisionCues.gesture(hand(booleanArrayOf(false, true, false, false, false))).first)
        assertEquals(Gesture.ROCK, VisionCues.gesture(hand(booleanArrayOf(false, true, false, false, true))).first)
        assertEquals(Gesture.THUMBS_UP, VisionCues.gesture(hand(booleanArrayOf(true, false, false, false, false), thumbUp = true)).first)
        assertEquals(2, VisionCues.gesture(hand(booleanArrayOf(false, true, true, false, false))).second)
    }
}
