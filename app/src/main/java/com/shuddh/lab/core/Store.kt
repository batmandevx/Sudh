package com.shuddh.lab.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScanRecord(
    val id: Long,
    val time: Long,
    val instrument: String,
    val analyteId: String,
    val analyte: String,
    val value: Double?,
    val unit: String,
    val level: Level,
    val headline: String,
    val evidence: List<String>,
    val sampleTag: String,
    val vendor: String,
    val confirmed: Boolean,
    val prevHash: String,
    val hash: String,
    val area: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
) {
    /** Canonical text the hash covers. `area` is appended only when set, so v1 records still verify. */
    fun payload(): String = listOf(
        id, time, instrument, analyteId, analyte, value?.toString() ?: "", unit, level.name, headline,
        evidence.joinToString("\u001f"), sampleTag, vendor, confirmed, prevHash,
    ).joinToString("|") + (if (area.isNotEmpty()) "|area=$area" else "") +
        (if (lat != null && lon != null) "|geo=%.5f,%.5f".format(java.util.Locale.US, lat, lon) else "")

    fun toJson() = JSONObject().apply {
        put("id", id); put("time", time); put("instrument", instrument); put("analyteId", analyteId)
        put("analyte", analyte); value?.let { put("value", it) }; put("unit", unit); put("level", level.name)
        put("headline", headline); put("evidence", JSONArray(evidence)); put("sampleTag", sampleTag)
        put("vendor", vendor); put("confirmed", confirmed); put("prevHash", prevHash); put("hash", hash)
        put("area", area)
        lat?.let { put("lat", it) }; lon?.let { put("lon", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): ScanRecord {
            val ev = o.getJSONArray("evidence")
            return ScanRecord(
                o.getLong("id"), o.getLong("time"), o.getString("instrument"), o.getString("analyteId"),
                o.getString("analyte"), if (o.has("value")) o.getDouble("value") else null, o.getString("unit"),
                Level.valueOf(o.getString("level")), o.getString("headline"),
                List(ev.length()) { ev.getString(it) }, o.getString("sampleTag"), o.getString("vendor"),
                o.getBoolean("confirmed"), o.getString("prevHash"), o.getString("hash"), o.optString("area"),
                if (o.has("lat")) o.getDouble("lat") else null, if (o.has("lon")) o.getDouble("lon") else null,
            )
        }
    }
}

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

fun stamp(t: Long): String = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(Date(t))

/** Hysteresis window: two agreeing scans of the same sample within this window = CONFIRMED. */
private const val CONFIRM_WINDOW_MS = 15 * 60 * 1000L

data class VendorMemory(
    val vendor: String,
    val total: Int,
    val failures: Int,
    val cautions: Int,
    val firstFailure: Long?,
    val lastFailure: Long?,
    val trend: String,
) {
    fun sentence(): String {
        if (total == 0) return "No record for \"$vendor\" yet."
        val base = "\"$vendor\": failed $failures of your last $total scans" +
            if (cautions > 0) " ($cautions more borderline)." else "."
        val first = firstFailure?.let { " First failure: ${stamp(it)}." } ?: ""
        return base + first + " Trend: $trend."
    }
}

/** Append-only, hash-chained scan log stored on the device. Nothing here ever leaves the phone. */
class Store(ctx: Context) {
    private val file = File(ctx.filesDir, "scans.json")
    val records = mutableStateListOf<ScanRecord>()

    init {
        runCatching {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) records.add(ScanRecord.fromJson(arr.getJSONObject(i)))
            }
        }
    }

    private fun persist() {
        val arr = JSONArray()
        records.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, "scans.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun previousFor(analyteId: String, tag: String, now: Long): ScanRecord? =
        records.lastOrNull { it.analyteId == analyteId && it.sampleTag.equals(tag, true) && now - it.time < CONFIRM_WINDOW_MS }

    fun add(o: Outcome, tag: String, vendor: String, area: String = "", lat: Double? = null, lon: Double? = null): ScanRecord {
        val now = System.currentTimeMillis()
        val prior = previousFor(o.analyteId, tag, now)
        val confirmed = prior != null && prior.level == o.level && o.level != Level.INCONCLUSIVE
        val prevHash = records.lastOrNull()?.hash ?: "GENESIS"
        val draft = ScanRecord(
            (records.lastOrNull()?.id ?: 0) + 1, now, o.instrument, o.analyteId, o.analyte.en, o.value, o.unit,
            o.level, o.headline, o.evidence.map { "${it.step}: ${it.text}" }, tag.trim(), vendor.trim(),
            confirmed, prevHash, "", area.trim(), lat, lon,
        )
        val rec = draft.copy(hash = sha256(prevHash + draft.payload()))
        records.add(rec)
        persist()
        return rec
    }

    /** Index of the first record whose hash does not verify, or -1 if the whole chain is intact. */
    fun verifyChain(): Int {
        var prev = "GENESIS"
        records.forEachIndexed { i, r ->
            if (r.prevHash != prev || sha256(prev + r.copy(hash = "").payload()) != r.hash) return i
            prev = r.hash
        }
        return -1
    }

    fun vendors(): List<String> =
        records.map { it.vendor }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }

    fun vendorMemory(vendor: String, last: Int = 20): VendorMemory {
        val rs = records.filter { it.vendor.equals(vendor, true) && it.level != Level.INCONCLUSIVE }.takeLast(last)
        val fails = rs.filter { it.level == Level.UNSAFE }
        val trend = if (rs.size < 4) "not enough scans" else {
            val half = rs.size / 2
            val early = rs.take(half).count { it.level == Level.UNSAFE }.toDouble() / half
            val late = rs.drop(half).count { it.level == Level.UNSAFE }.toDouble() / (rs.size - half)
            when {
                late > early + 0.1 -> "worsening"
                late < early - 0.1 -> "improving"
                else -> "steady"
            }
        }
        return VendorMemory(
            vendor, rs.size, fails.size, rs.count { it.level == Level.CAUTION },
            fails.firstOrNull()?.time, fails.lastOrNull()?.time, trend,
        )
    }

    /** Kitchen Health Score: share of clean scans in the last [days] days, 0–100. */
    fun kitchenScore(days: Int = 7): Pair<Int, String>? {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val rs = records.filter { it.time >= since && it.level != Level.INCONCLUSIVE }
        if (rs.isEmpty()) return null
        val score = rs.sumOf {
            when (it.level) { Level.SAFE -> 100; Level.CAUTION -> 50; else -> 0 }.toInt()
        } / rs.size
        val weakest = rs.filter { it.level == Level.UNSAFE }.groupBy { it.analyte }.maxByOrNull { it.value.size }
        val note = weakest?.let { "Weakest link: ${it.key} (${it.value.size} failure${if (it.value.size > 1) "s" else ""})" }
            ?: "No failures this week"
        return score to note
    }

    fun clear() {
        records.clear()
        persist()
    }
}

/** User settings and every calibration, kept in one SharedPreferences file as JSON blobs. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("shuddh", Context.MODE_PRIVATE)

    /** Incremented on every calibration write so UI that depends on calibration recomposes. */
    var version by mutableStateOf(0)
        private set

    var lang: Lang
        get() = runCatching { Lang.valueOf(sp.getString("lang", "EN")!!) }.getOrDefault(Lang.EN)
        set(v) = sp.edit().putString("lang", v.name).apply()
    var familyPhone: String
        get() = sp.getString("familyPhone", "")!!
        set(v) = sp.edit().putString("familyPhone", v).apply()
    var familyName: String
        get() = sp.getString("familyName", "")!!
        set(v) = sp.edit().putString("familyName", v).apply()
    var autoSpeak: Boolean
        get() = sp.getBoolean("autoSpeak", true)
        set(v) = sp.edit().putBoolean("autoSpeak", v).apply()
    var lastVendor: String
        get() = sp.getString("lastVendor", "")!!
        set(v) = sp.edit().putString("lastVendor", v).apply()
    var area: String
        get() = sp.getString("area", "")!!
        set(v) = sp.edit().putString("area", v).apply()
    /** Optional online street map (OSM tiles + nearby places). Off by default. */
    var onlineMap: Boolean
        get() = sp.getBoolean("onlineMap", false)
        set(v) = sp.edit().putBoolean("onlineMap", v).apply()
    var onlineAssistant by mutableStateOf(sp.getBoolean("onlineAssistant", false))
        private set

    fun updateWebAccess(enabled: Boolean) {
        sp.edit().putBoolean("onlineAssistant", enabled).apply()
        onlineAssistant = enabled
    }
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    fun has(key: String) = sp.contains(key)
    fun keys(prefix: String) = sp.all.keys.filter { it.startsWith(prefix) }

    var accent: String
        get() = sp.getString("accent", "EMERALD")!!
        set(v) = sp.edit().putString("accent", v).apply()
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()
    var reduceMotion: Boolean
        get() = sp.getBoolean("reduceMotion", false)
        set(v) = sp.edit().putBoolean("reduceMotion", v).apply()
    fun flag(k: String) = sp.getBoolean("flag_$k", false)
    fun setFlag(k: String) = sp.edit().putBoolean("flag_$k", true).apply()
    fun bump(k: String): Int { val n = sp.getInt("count_$k", 0) + 1; sp.edit().putInt("count_$k", n).apply(); return n }
    fun count(k: String) = sp.getInt("count_$k", 0)

    var meshRoom: String
        get() = sp.getString("meshRoom", "Public")!!
        set(v) = sp.edit().putString("meshRoom", v).apply()

    var meshName: String
        get() = sp.getString("meshName", null) ?: ("Shuddh-" + (1000..9999).random()).also { v -> sp.edit().putString("meshName", v).apply() }
        set(v) = sp.edit().putString("meshName", v).apply()

    var lastTag: String
        get() = sp.getString("lastTag", "")!!
        set(v) = sp.edit().putString("lastTag", v).apply()

    fun json(key: String): JSONObject? = sp.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun putJson(key: String, o: JSONObject?) {
        sp.edit().apply { if (o == null) remove(key) else putString(key, o.toString()) }.apply()
        version++
    }

    fun double(key: String): Double? = if (sp.contains(key)) sp.getFloat(key, 0f).toDouble() else null
    fun putDouble(key: String, v: Double?) {
        sp.edit().apply { if (v == null) remove(key) else putFloat(key, v.toFloat()) }.apply()
        version++
    }
}
