package com.shuddh.lab.core

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A packaged food in the kitchen with a known expiry. */
data class PantryItem(
    val id: Long,
    val name: String,
    val emoji: String,
    val added: Long,
    val expiry: Long,
    val fssai: String? = null,
    val source: String = "manual",
) {
    val daysLeft: Long get() = Math.floorDiv(expiry - System.currentTimeMillis(), 86_400_000L)
    /** Share of shelf life remaining, 0..1. */
    val freshness: Float get() = ((expiry - System.currentTimeMillis()).toFloat() / (expiry - added).coerceAtLeast(1)).coerceIn(0f, 1f)

    fun toJson() = JSONObject().put("id", id).put("n", name).put("e", emoji).put("a", added).put("x", expiry).put("f", fssai ?: "").put("s", source)

    companion object {
        fun from(o: JSONObject) = PantryItem(o.getLong("id"), o.getString("n"), o.getString("e"), o.getLong("a"), o.getLong("x"), o.optString("f").ifBlank { null }, o.optString("s"))

        /** Guess a friendly emoji from the product name. */
        fun emojiFor(name: String): String {
            val n = name.lowercase()
            return listOf(
                "milk" to "🥛", "curd" to "🥛", "dahi" to "🥛", "paneer" to "🧀", "cheese" to "🧀", "butter" to "🧈", "ghee" to "🧈",
                "bread" to "🍞", "egg" to "🥚", "honey" to "🍯", "oil" to "🫗", "rice" to "🍚", "atta" to "🌾", "flour" to "🌾",
                "biscuit" to "🍪", "chips" to "🥔", "juice" to "🧃", "water" to "💧", "tea" to "🍵", "coffee" to "☕",
                "masala" to "🌶️", "chilli" to "🌶️", "turmeric" to "🟡", "sauce" to "🥫", "jam" to "🍓", "noodle" to "🍜", "chocolate" to "🍫",
            ).firstOrNull { n.contains(it.first) }?.second ?: "📦"
        }
    }
}

/** Kitchen pantry with expiry tracking and a simple food-waste score. Stored only on this phone. */
class PantryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "pantry.json")
    private val statsFile = File(ctx.filesDir, "pantry_stats.json")
    private val historyFile = File(ctx.filesDir, "pantry_history.json")
    val items = mutableStateListOf<PantryItem>()
    /** Purchase log: staple key → times bought (drives restock predictions). */
    val history = androidx.compose.runtime.mutableStateMapOf<String, List<Long>>()
    var usedInTime = 0; private set
    var wasted = 0; private set

    init {
        runCatching { if (file.exists()) JSONArray(file.readText()).let { a -> for (i in 0 until a.length()) items.add(PantryItem.from(a.getJSONObject(i))) } }
        runCatching { if (historyFile.exists()) JSONObject(historyFile.readText()).let { o -> o.keys().forEach { k -> val a = o.getJSONArray(k); history[k] = (0 until a.length()).map { a.getLong(it) } } } }
        runCatching { if (statsFile.exists()) JSONObject(statsFile.readText()).let { usedInTime = it.optInt("used"); wasted = it.optInt("wasted") } }
    }

    private fun save() {
        file.writeText(JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
        statsFile.writeText(JSONObject().put("used", usedInTime).put("wasted", wasted).toString())
        historyFile.writeText(JSONObject().apply { history.forEach { (k, v) -> put(k, JSONArray(v)) } }.toString())
    }

    /** Logs a purchase without tracking a pack (e.g. "bought milk" from the restock list). */
    fun bought(name: String, time: Long = System.currentTimeMillis()) {
        val k = PantrySmart.keyOf(name)
        history[k] = ((history[k] ?: emptyList()) + time).takeLast(20)
        save()
    }

    /** Keys of staples with an unexpired pack in the pantry. */
    fun stocked(): Set<String> = items.filter { it.daysLeft >= 0 }.map { PantrySmart.keyOf(it.name) }.toSet()

    fun restock(horizonDays: Double = 3.0) = PantrySmart.restock(history, stocked(), horizonDays = horizonDays)

    fun add(name: String, expiry: Long, fssai: String? = null, source: String = "manual"): PantryItem {
        val it = PantryItem(System.currentTimeMillis(), name.trim().ifBlank { "Food item" }, PantryItem.emojiFor(name), System.currentTimeMillis(), expiry, fssai, source)
        items.add(it); bought(name); return it
    }

    /** Removes an item, counting it as eaten in time or wasted. */
    fun finish(item: PantryItem, used: Boolean) {
        items.remove(item)
        if (used && item.daysLeft >= 0) usedInTime++ else wasted++
        save()
    }

    fun sorted() = items.sortedBy { it.expiry }

    /** Share of finished items that were eaten before expiry, or null if none finished yet. */
    fun wasteScore(): Int? = (usedInTime + wasted).takeIf { it > 0 }?.let { usedInTime * 100 / it }
}
