package com.shuddh.lab

import com.shuddh.lab.core.Agent
import com.shuddh.lab.core.WebSearch
import org.junit.Assert.*
import org.junit.Test

class WebSearchTest {
    @Test fun searchesDoNotHijackLocalQuestions() {
        assertEquals("web_search", Agent.ruleRoute("Search the web for food recalls", false).name)
        assertEquals("web_search", Agent.ruleRoute("weather in Delhi today", false).name)
        assertEquals("phone_status", Agent.ruleRoute("what time is it?", false).name)
        assertEquals("last_scan", Agent.ruleRoute("my latest scan", false).name)
        assertEquals("search_photos", Agent.ruleRoute("search my gallery for milk", false).name)
        assertNotEquals("web_search", Agent.parse("""{"tool":"web_search","args":{}}""", "hello", false).name)
        assertEquals("food recalls", WebSearch.query("Search the web for food recalls"))
    }

    @Test fun parsesSourcesAndRejectsUnsafeOrDuplicateLinks() {
        val xml = """<rss><channel>
            <item><title>Food &amp; water</title><link>https://example.org/check</link><description>&lt;b&gt;An excerpt&lt;/b&gt;</description></item>
            <item><title>Duplicate</title><link>https://example.org/check</link></item>
            <item><title>Script</title><link>javascript:alert(1)</link></item>
            <item><title>Insecure</title><link>http://example.org/other</link></item>
            </channel></rss>"""
        val results = WebSearch.parse(xml)
        assertEquals(1, results.size)
        assertEquals("Food & water", results.single().title)
        assertEquals("An excerpt", results.single().snippet)
        assertFalse(WebSearch.safeUrl("https://user:pass@example.org"))
        assertFalse(WebSearch.safeUrl("https://127.0.0.1/test"))
    }

    @Test fun externalEntitiesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WebSearch.parse("""<!DOCTYPE rss [<!ENTITY e SYSTEM "file:///etc/passwd">]><rss>&e;</rss>""")
        }
        assertThrows(IllegalArgumentException::class.java) { WebSearch.parse("x".repeat(600_000)) }
    }
}
