package com.shuddh.lab

import com.shuddh.lab.core.Agent
import com.shuddh.lab.core.LocalLlm
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmTextTest {
    /** Encodes text the way GPT-2 byte-level BPE displays raw bytes. */
    private fun byteLevel(s: String): String {
        val printable = (33..126) + (161..172) + (174..255)
        var n = 0
        val map = (0..255).associateWith { if (it in printable) it else 256 + n++ }
        return s.toByteArray(Charsets.UTF_8).joinToString("") { map.getValue(it.toInt() and 0xff).toChar().toString() }
    }

    @Test fun repairsHindiMojibake() {
        val hindi = "मैं ठीक हूँ। आपकी रसोई का स्कोर 62 है।"
        assertEquals(hindi, LocalLlm.fixBytes(byteLevel(hindi)))
    }

    @Test fun mixedDecodedAndRawRepairs() {
        val mixed = "दूध" + byteLevel(" में पानी")
        assertEquals("दूध में पानी", LocalLlm.fixBytes(mixed))
    }

    @Test fun englishUntouched() {
        val s = "Nitrate above 45 mg/L is unsafe for infants."
        assertEquals(s, LocalLlm.fixBytes(s))
    }

    @Test fun routerJsonParsesAndFallsBack() {
        val ok = Agent.parse("""{"tool":"vendor_history","args":{"vendor":"ramesh dairy"}}""", "has ramesh dairy failed", false)
        assertEquals("vendor_history", ok.name); assertEquals("ramesh dairy", ok.args["vendor"]); assertEquals(true, ok.byModel)
        val bad = Agent.parse("garbage output", "download my report", false)
        assertEquals("download_report", bad.name); assertEquals(false, bad.byModel)
        assertEquals("analyze_image", Agent.ruleRoute("is this packet expired?", true).name)
        assertEquals("vendor_history", Agent.ruleRoute("has ramesh dairy failed before?", false).name)
        assertEquals("mesh_send", Agent.ruleRoute("send to the mesh: water is dirty", false).name)
        assertEquals("contaminant_info", Agent.ruleRoute("why is nitrate dangerous", false).name)
    }
}
