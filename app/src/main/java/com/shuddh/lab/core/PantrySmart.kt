package com.shuddh.lab.core

import java.util.Calendar

/**
 * Pantry brain: learns how fast the household uses each staple (from when it was bought), predicts
 * when it runs out, and knows India's typical seasonal price cycle to say when stocking up is
 * cheapest. Everything is computed on the phone from the user's own pantry log.
 */
object PantrySmart {
    data class Staple(val key: String, val label: String, val emoji: String, val days: Double, val words: List<String>)

    /** Typical household use-up interval (days) — replaced by the learned one after two purchases. */
    val staples = listOf(
        Staple("milk", "Milk", "🥛", 1.0, listOf("milk", "doodh", "dudh")),
        Staple("curd", "Curd", "🥣", 3.0, listOf("curd", "dahi", "yogurt")),
        Staple("bread", "Bread", "🍞", 4.0, listOf("bread", "pav")),
        Staple("eggs", "Eggs", "🥚", 7.0, listOf("egg", "eggs", "anda")),
        Staple("paneer", "Paneer", "🧀", 5.0, listOf("paneer")),
        Staple("tomato", "Tomato", "🍅", 5.0, listOf("tomato", "tamatar")),
        Staple("onion", "Onion", "🧅", 10.0, listOf("onion", "pyaz", "pyaaz")),
        Staple("potato", "Potato", "🥔", 12.0, listOf("potato", "aloo", "alu")),
        Staple("atta", "Atta", "🌾", 21.0, listOf("atta", "wheat", "flour")),
        Staple("rice", "Rice", "🍚", 30.0, listOf("rice", "chawal", "basmati")),
        Staple("dal", "Dal", "🫘", 21.0, listOf("dal", "daal", "tur", "toor", "arhar", "moong", "masoor", "chana")),
        Staple("oil", "Cooking oil", "🫗", 30.0, listOf("oil", "tel", "mustard", "sunflower", "groundnut")),
        Staple("sugar", "Sugar", "🍬", 30.0, listOf("sugar", "cheeni", "chini")),
        Staple("tea", "Tea", "🍵", 30.0, listOf("tea", "chai", "chaipatti")),
        Staple("ghee", "Ghee", "🧈", 45.0, listOf("ghee")),
        Staple("salt", "Salt", "🧂", 60.0, listOf("salt", "namak")),
        Staple("garlic", "Garlic", "🧄", 20.0, listOf("garlic", "lehsun", "lahsun")),
    )

    fun stapleOf(name: String): Staple? {
        val n = name.lowercase()
        return staples.firstOrNull { s -> s.words.any { Regex("\\b$it\\b").containsMatchIn(n) } }
    }

    /** History key for a product name — the staple it belongs to, or its cleaned first word. */
    fun keyOf(name: String): String = stapleOf(name)?.key
        ?: name.lowercase().replace(Regex("[^a-z ]"), " ").split(" ").firstOrNull { it.length > 2 } ?: name.lowercase().trim()

    /** Median gap between purchases, in days, or null with fewer than two purchases. */
    fun interval(times: List<Long>): Double? {
        val t = times.sorted()
        if (t.size < 2) return null
        val gaps = t.zipWithNext { a, b -> (b - a) / 86_400_000.0 }.filter { it >= 0.3 }
        if (gaps.isEmpty()) return null
        return gaps.sorted()[gaps.size / 2]
    }

    data class Restock(val key: String, val label: String, val emoji: String, val dueInDays: Double, val everyDays: Double, val learned: Boolean) {
        val words get() = when {
            dueInDays <= 0 -> "Probably run out — buy today"
            dueInDays < 1.5 -> "Runs out tomorrow"
            else -> "Runs out in ~${dueInDays.toInt()} days"
        }
    }

    /**
     * What to restock soon: for each item bought before, last purchase + usual interval − now.
     * Items with an unexpired pack still in the pantry are skipped.
     */
    fun restock(history: Map<String, List<Long>>, inPantry: Set<String>, now: Long = System.currentTimeMillis(), horizonDays: Double = 3.0): List<Restock> =
        history.mapNotNull { (key, times) ->
            if (times.isEmpty() || key in inPantry) return@mapNotNull null
            val st = staples.firstOrNull { it.key == key }
            val learned = interval(times)
            val every = learned ?: st?.days ?: return@mapNotNull null
            val due = (times.max() + every * 86_400_000.0 - now) / 86_400_000.0
            if (due > horizonDays) null
            else Restock(key, st?.label ?: key.replaceFirstChar { it.uppercase() }, st?.emoji ?: PantryItem.emojiFor(key), due, every, learned != null)
        }.sortedBy { it.dueInDays }

    // ── Best time to buy ───────────────────────────────────────────────────────────────────────

    /** Typical Indian retail price season (months 1–12) from mandi arrival cycles. */
    data class Season(val key: String, val label: String, val emoji: String, val cheap: Set<Int>, val costly: Set<Int>, val storable: Boolean, val why: String)

    val seasons = listOf(
        Season("tomato", "Tomato", "🍅", setOf(12, 1, 2, 3), setOf(6, 7, 8, 10, 11), false, "winter harvest floods mandis; monsoon damages crop"),
        Season("onion", "Onion", "🧅", setOf(3, 4, 5), setOf(9, 10, 11, 12), true, "rabi onion arrives Mar–May and stores for months"),
        Season("potato", "Potato", "🥔", setOf(1, 2, 3), setOf(8, 9, 10, 11), true, "fresh harvest Jan–Mar, cold-store stock runs low by autumn"),
        Season("atta", "Atta / wheat", "🌾", setOf(4, 5, 6), setOf(11, 12, 1, 2), true, "new wheat harvested Apr–May"),
        Season("rice", "Rice", "🍚", setOf(11, 12, 1), setOf(7, 8, 9), true, "kharif paddy harvest Oct–Dec"),
        Season("dal", "Tur / arhar dal", "🫘", setOf(1, 2, 3), setOf(7, 8, 9, 10), true, "new tur crop arrives Dec–Feb"),
        Season("oil", "Mustard oil", "🫗", setOf(3, 4, 5), setOf(10, 11, 12, 1), true, "mustard seed harvested Feb–Apr"),
        Season("garlic", "Garlic", "🧄", setOf(3, 4, 5), setOf(10, 11, 12), true, "new garlic crop in spring"),
        Season("peas", "Green peas", "🫛", setOf(12, 1, 2), setOf(6, 7, 8, 9), false, "winter crop — freeze some for the monsoon"),
        Season("mango", "Mango", "🥭", setOf(5, 6), setOf(3, 4), false, "early-season mango is often carbide-ripened — wait for peak season"),
        Season("milk", "Milk", "🥛", setOf(11, 12, 1, 2), setOf(4, 5, 6, 7), false, "summer lean season: less milk, more watering and adulteration — test more often"),
    )

    enum class Advice { STOCK_UP, GOOD, AVOID }

    data class BuyTip(val season: Season, val advice: Advice, val text: String)

    fun monthName(m: Int) = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[(m - 1).mod(12)]

    /** Months until the next cheap month (0 if this one is cheap). */
    fun monthsToCheap(s: Season, month: Int): Int = (0..11).first { ((month - 1 + it) % 12 + 1) in s.cheap }

    fun tips(month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1): List<BuyTip> = seasons.map { s ->
        when {
            month in s.cheap && s.storable -> BuyTip(s, Advice.STOCK_UP, "Cheapest now — stock up for 2–3 months (${s.why}).")
            month in s.cheap -> BuyTip(s, Advice.GOOD, "In season now — best price and quality (${s.why}).")
            month in s.costly -> BuyTip(s, Advice.AVOID, "Usually costly now — buy only what you need; cheaper from ${monthName((month - 1 + monthsToCheap(s, month)) % 12 + 1)} (${s.why}).")
            else -> BuyTip(s, Advice.GOOD, "Normal price now — cheapest from ${monthName((month - 1 + monthsToCheap(s, month)) % 12 + 1)}.")
        }
    }.sortedBy { it.advice.ordinal }

    /** One-line summary for a notification or the assistant. */
    fun brief(restock: List<Restock>, month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1): String {
        val r = restock.take(4).joinToString { "${it.label} (${it.words.lowercase()})" }
        val stock = tips(month).filter { it.advice == Advice.STOCK_UP }.joinToString { it.season.label }
        return listOfNotNull(
            if (r.isNotBlank()) "Restock: $r." else "Nothing is running out in the next 3 days.",
            if (stock.isNotBlank()) "Cheapest this month — good time to stock up: $stock." else null,
        ).joinToString(" ")
    }
}
