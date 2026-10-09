package com.shuddh.lab.core

/** A goal earned from real use. [progress] is 0..1; unlocked when it reaches 1. */
data class Badge(val id: String, val emoji: String, val title: String, val how: String, val progress: Float, val detail: String) {
    val unlocked get() = progress >= 1f
}

/**
 * Achievements computed from the scan log and usage counters — nothing here is faked or
 * pre-unlocked. Levels are just the number of badges earned.
 */
object Badges {
    fun all(store: Store, prefs: Prefs): List<Badge> {
        val rs = store.records.toList()
        val (_, longest) = Insights.streaks(rs)
        val instruments = rs.map { it.instrument }.toSet().size
        fun b(id: String, emoji: String, title: String, how: String, have: Int, need: Int) =
            Badge(id, emoji, title, how, (have.toFloat() / need).coerceIn(0f, 1f), "${have.coerceAtMost(need)}/$need")
        return listOf(
            b("first", "🧪", "First Test", "Save your first verdict", rs.size, 1),
            b("regular", "🔬", "Lab Regular", "Save 10 verdicts", rs.size, 10),
            b("streak3", "🔥", "On Fire", "Test 3 days in a row", longest, 3),
            b("streak7", "🏆", "Week Warrior", "Test 7 days in a row", longest, 7),
            b("explorer", "🧭", "Explorer", "Use 5 different instruments", instruments, 5),
            b("detective", "🕵️", "Detective", "Confirm an unsafe result with a 2nd scan", rs.count { it.level == Level.UNSAFE && it.confirmed }, 1),
            b("watchdog", "🏪", "Vendor Watchdog", "Track 3 vendors", store.vendors().size, 3),
            b("mapper", "📍", "Cartographer", "Save 5 GPS-tagged scans", rs.count { it.lat != null }, 5),
            b("label", "🏷️", "Label Reader", "Save a Label Lens verdict", rs.count { it.analyteId == "label" }, 1),
            b("mesh", "📡", "Mesh Pioneer", "Join the Bluetooth mesh", if (prefs.flag("mesh_joined")) 1 else 0, 1),
            b("ai", "✨", "AI Curious", "Ask Shuddh 5 questions", prefs.count("ai_questions"), 5),
            b("polyglot", "🗣️", "Polyglot", "Use Shuddh in Hindi or Kannada", if (prefs.flag("polyglot")) 1 else 0, 1),
        )
    }

    private val titles = listOf("Curious Cook", "Kitchen Tester", "Lab Assistant", "Food Inspector", "Purity Guardian", "Chief Scientist")

    /** (level number, title, progress to next level 0..1). */
    fun level(badges: List<Badge>): Triple<Int, String, Float> {
        val n = badges.count { it.unlocked }
        val lvl = n / 2
        val nearest = badges.filter { !it.unlocked }.maxOfOrNull { it.progress } ?: 0f
        return Triple(lvl + 1, titles[lvl.coerceAtMost(titles.lastIndex)], ((n % 2 + nearest) / 2f).coerceIn(0f, 1f))
    }
}
