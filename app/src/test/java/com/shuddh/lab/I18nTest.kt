package com.shuddh.lab

import com.shuddh.lab.core.I18n
import com.shuddh.lab.core.Lang
import com.shuddh.lab.core.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class I18nTest {
    @Test fun verdictLabelsInEveryLanguage() {
        for (l in Lang.entries) for (lv in Level.entries) {
            val t = lv.label.get(l)
            if (l != Lang.EN) assertNotEquals("$l $lv", lv.label.en, t)
        }
        assertEquals("సురక్షితం", Level.SAFE.label.get(Lang.TE))
        assertEquals("பாதுகாப்பானது", Level.SAFE.label.get(Lang.TA))
    }

    @Test fun uiLabelsKeepEmojiPrefix() {
        assertEquals("⬇ PDF డౌన్‌లోడ్", I18n.ui("⬇ Download PDF", Lang.TE))
        assertEquals("முடிவைப் பெறு", I18n.ui("Get verdict", Lang.TA))
        assertEquals("Unknown label", I18n.ui("Unknown label", Lang.TA))
        assertEquals("Get verdict", I18n.ui("Get verdict", Lang.EN))
    }
}

class HiveTest {
    @org.junit.Test fun compactAlertRoundTrip() {
        val i = com.shuddh.lab.core.CommunityItem("alert", "Sri Ram Dairy", "HSR", "Detergent in milk", com.shuddh.lab.core.Level.UNSAFE, "", 0L, 1, 1, "x")
        val c = i.compact()
        org.junit.Assert.assertTrue(c.toByteArray().size < 100)
        val back = com.shuddh.lab.core.CommunityItem.parseCompact(c)!!
        org.junit.Assert.assertEquals("Sri Ram Dairy", back.vendor)
        org.junit.Assert.assertEquals(com.shuddh.lab.core.Level.UNSAFE, back.level)
        org.junit.Assert.assertEquals("HSR", back.area)
    }
}
