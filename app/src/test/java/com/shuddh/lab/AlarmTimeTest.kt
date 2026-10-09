package com.shuddh.lab

import com.shuddh.lab.core.Agent
import com.shuddh.lab.core.LocalLlm
import com.shuddh.lab.core.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AlarmTimeTest {
    private fun at(h: Int, m: Int) = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }

    @Test fun ambiguousTimePicksNextOccurrence() {
        assertEquals(14 to 34, When.nextUpcoming(2 to 34, "set alarm for 2:34", at(14, 20)))
        assertEquals(2 to 34, When.nextUpcoming(2 to 34, "set alarm for 2:34", at(0, 30)))
        assertEquals(2 to 34, When.nextUpcoming(2 to 34, "alarm 2:34 am", at(14, 20)))
        assertEquals(19 to 0, When.nextUpcoming(19 to 0, "alarm 19:00", at(8, 0)))
    }

    @Test fun followUpDetection() {
        assertTrue(Agent.isFollowUp("make it spicier"))
        assertFalse(Agent.isFollowUp("can you set alarm for 2:34"))
        assertFalse(Agent.isFollowUp("give me a recipe for masala chai"))
    }

    @Test fun phiTemplate() {
        assertEquals("<|system|>S<|end|><|user|>U<|end|><|assistant|>", LocalLlm.toPhi(LocalLlm.chatml("S", "U")))
    }

    @Test fun actionsAreNeverParaphrased() {
        assertTrue("set_alarm" in Agent.exactAnswer && "open_app" in Agent.exactAnswer && "recipe" !in Agent.exactAnswer)
    }
}
