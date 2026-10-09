package com.shuddh.lab

import com.shuddh.lab.core.Agent
import com.shuddh.lab.core.When
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionToolsTest {
    @Test fun durations() {
        assertEquals(600, When.durationSeconds("10 minutes"))
        assertEquals(4800, When.durationSeconds("1 hour 20 min"))
        assertEquals(1800, When.durationSeconds("half an hour"))
        assertEquals(90, When.durationSeconds("90 seconds"))
        assertEquals(300, When.durationSeconds("5"))
        assertEquals(900, When.durationSeconds("15 मिनट"))
        assertNull(When.durationSeconds("boil the water"))
    }

    @Test fun clockTimes() {
        assertEquals(6 to 30, When.clock("wake me at 6:30 am"))
        assertEquals(19 to 0, When.clock("remind me at 7 pm"))
        assertEquals(19 to 45, When.clock("19:45"))
        assertEquals(0 to 15, When.clock("12:15 am"))
        assertEquals(20 to 0, When.clock("raat 8 baje"))
        assertNull(When.clock("tomorrow"))
    }

    @Test fun ruleRouting() {
        assertEquals("set_timer", Agent.ruleRoute("set a timer for 10 minutes to boil water", false).name)
        assertEquals("set_timer", Agent.ruleRoute("remind me in 20 minutes", false).name)
        assertEquals("set_alarm", Agent.ruleRoute("remind me to buy milk at 7 pm", false).name)
        assertEquals("flashlight", Agent.ruleRoute("turn on the torch", false).name)
        assertEquals("off", Agent.ruleRoute("turn the flashlight off", false).args["state"])
        assertEquals("generate_qr", Agent.ruleRoute("make a qr code for 9876543210", false).name)
        assertEquals("whistle_counter", Agent.ruleRoute("count 3 cooker whistles", false).name)
        assertEquals("daily_tip", Agent.ruleRoute("teach me something new", false).name)
        assertEquals("set_language", Agent.ruleRoute("switch to hindi", false).name)
        assertEquals("phone_status", Agent.ruleRoute("what time is it?", false).name)
        assertEquals("phone_status", Agent.ruleRoute("show my phone battery", false).name)
        assertEquals("safety_brief", Agent.ruleRoute("what should I check today?", false).name)
    }

    @Test fun routerArgsFilteredToSpec() {
        val c = Agent.parse("""{"tool":"set_timer","args":{"duration":"10 minutes","label":"boil","bogus":"x"}}""", "set a timer for 10 minutes", false)
        assertEquals(setOf("duration", "label"), c.args.keys)
    }

    @Test fun smallTalkNeverTriggersActions() {
        listOf("hi", "Hi!", "HELLO", "Good evening", "thanks", "who are you", "नमस्ते").forEach { assertEquals(it, true, Agent.isSmallTalk(it)) }
        assertEquals(false, Agent.isSmallTalk("hi, set a timer for 5 minutes"))
        // A model that wrongly picks an action for chit-chat is overruled.
        val bad = Agent.parse("""{"tool":"whistle_counter","args":{"count":"3"}}""", "tell me a fun fact", false)
        assertEquals(false, bad.name == "whistle_counter")
        val ok = Agent.parse("""{"tool":"whistle_counter","args":{"count":"3"}}""", "count 3 cooker whistles", false)
        assertEquals("whistle_counter", ok.name)
    }
}
