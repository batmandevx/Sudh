package com.shuddh.lab.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import javax.xml.parsers.SAXParserFactory

data class WebSource(val title: String, val url: String, val snippet: String)
data class WebResults(val query: String, val sources: List<WebSource>, val fetchedAt: Long)

/** A search-feed reader. Only the user's current search text is transmitted, never model arguments
 * or local facts. Results are displayed as excerpts, never executed as agent instructions. */
object WebSearch {
    private const val MAX_BYTES = 512 * 1024

    fun requested(q: String): Boolean = Regex(
        "(?i)\\b(search (the )?(web|internet|online)|look (it |this )?up (online|on the web)|search online for|web search|latest news|current weather|weather (in|today)|news today)\\b",
    ).containsMatchIn(q)

    fun query(text: String): String = text.trim().replace(
        Regex("(?i)^(search (the )?(web|internet|online)|web search|look up)( for)?[: ,]*"), "",
    ).trim().take(400)

    fun browserUrl(query: String) = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")

    suspend fun search(text: String, enabled: Boolean): WebResults {
        check(enabled) { "Web search is off" }
        val q = query(text)
        require(q.isNotBlank()) { "Enter something to search for" }
        return withContext(Dispatchers.IO) {
            val connection = URL(browserUrl(q) + "&format=rss").openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Shuddh/3.6 (personal search feed reader)")
                connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
                check(connection.responseCode == 200) { "Search provider unavailable" }
                val data = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(out.size() + count <= MAX_BYTES) { "Search response too large" }
                        out.write(buffer, 0, count)
                    }
                    out.toString("UTF-8")
                }
                WebResults(q, parse(data), System.currentTimeMillis())
            } finally { connection.disconnect() }
        }
    }

    fun safeUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.host.contains('.') && !uri.host.matches(Regex("[0-9.]+")) && !uri.host.endsWith(".local")
    }.getOrDefault(false)

    fun parse(xml: String): List<WebSource> {
        require(xml.length <= MAX_BYTES && !xml.contains("<!DOCTYPE", ignoreCase = true)) { "Unsupported search response" }
        val sources = mutableListOf<WebSource>()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val reader = factory.newSAXParser().xmlReader
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.contentHandler = object : DefaultHandler() {
            var fields: MutableMap<String, String>? = null
            var field = ""
            val text = StringBuilder()
            override fun startElement(uri: String?, localName: String?, name: String, attrs: Attributes?) {
                if (name == "item") fields = mutableMapOf()
                field = name
                text.setLength(0)
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, name: String) {
                if (field == name) fields?.set(name, text.toString().trim())
                if (name == "item") {
                    val f = fields.orEmpty()
                    val url = f["link"].orEmpty()
                    val title = clean(f["title"].orEmpty()).take(180)
                    if (safeUrl(url) && title.isNotBlank() && sources.none { it.url == url } && sources.size < 3) {
                        sources.add(WebSource(title, url, clean(f["description"].orEmpty()).take(350)))
                    }
                    fields = null
                }
            }
        }
        reader.parse(InputSource(StringReader(xml)))
        return sources
    }

    private fun clean(s: String) = s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
}
