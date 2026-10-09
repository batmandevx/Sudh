package com.shuddh.lab.core

import java.util.Calendar

/** Pure analytics over the scan log — every number on the Insights tab comes from here. */
object Insights {
    private const val DAY = 86_400_000L

    fun score(rs: List<ScanRecord>): Int? {
        val v = rs.filter { it.level != Level.INCONCLUSIVE }
        if (v.isEmpty()) return null
        return v.sumOf { r: ScanRecord -> if (r.level == Level.SAFE) 100.toInt() else if (r.level == Level.CAUTION) 50 else 0 } / v.size
    }

    /** Kitchen score per day for the last [days] days (null = no scans that day). */
    fun daily(rs: List<ScanRecord>, days: Int = 30, now: Long = System.currentTimeMillis()): List<Int?> {
        val start = startOfDay(now) - (days - 1) * DAY
        return (0 until days).map { d ->
            val from = start + d * DAY
            score(rs.filter { it.time in from until from + DAY })
        }
    }

    data class Tally(val safe: Int, val caution: Int, val unsafe: Int) {
        val total get() = safe + caution + unsafe
    }

    fun byTest(rs: List<ScanRecord>): List<Pair<String, Tally>> =
        rs.filter { it.level != Level.INCONCLUSIVE }.groupBy { it.analyte }.map { (k, v) ->
            k to Tally(v.count { it.level == Level.SAFE }, v.count { it.level == Level.CAUTION }, v.count { it.level == Level.UNSAFE })
        }.sortedByDescending { it.second.unsafe * 1000 + it.second.total }

    /** Failure rate per calendar month (index 0 = January); null where there are no scans. */
    fun monthly(rs: List<ScanRecord>): List<Double?> {
        val cal = Calendar.getInstance()
        val groups = rs.filter { it.level != Level.INCONCLUSIVE }.groupBy { cal.timeInMillis = it.time; cal.get(Calendar.MONTH) }
        return (0..11).map { m -> groups[m]?.let { g -> g.count { it.level == Level.UNSAFE }.toDouble() / g.size } }
    }

    /** "Monsoon watch": compares failure rate in Jun–Sep against the rest of the year. */
    fun monsoonNote(rs: List<ScanRecord>): String? {
        val valid = rs.filter { it.level != Level.INCONCLUSIVE }
        val cal = Calendar.getInstance()
        val (wet, dry) = valid.partition { cal.timeInMillis = it.time; cal.get(Calendar.MONTH) in 5..8 }
        if (wet.size < 5 || dry.size < 5) return null
        val w = wet.count { it.level == Level.UNSAFE }.toDouble() / wet.size
        val d = dry.count { it.level == Level.UNSAFE }.toDouble() / dry.size
        return when {
            w > d * 1.2 + 0.02 -> "Monsoon months fail ${fmt((w - d) * 100)} points more often here. Start weekly testing from June."
            else -> "No monsoon spike in your history so far."
        }
    }

    data class AreaStat(val area: String, val fails: Int, val total: Int, val community: Int) {
        val rate get() = if (total == 0) 0.0 else fails.toDouble() / total
    }

    fun areaBoard(rs: List<ScanRecord>, community: List<CommunityItem>): List<AreaStat> {
        val own = rs.filter { it.area.isNotBlank() && it.level != Level.INCONCLUSIVE }.groupBy { it.area.trim().lowercase() }
        val comm = community.filter { it.area.isNotBlank() }.groupBy { it.area.trim().lowercase() }
        return (own.keys + comm.keys).map { k ->
            val o = own[k].orEmpty(); val c = comm[k].orEmpty()
            val name = (o.firstOrNull()?.area ?: c.first().area).trim()
            AreaStat(
                name,
                o.count { it.level == Level.UNSAFE } + c.sumOf { it.fails },
                o.size + c.sumOf { it.total },
                c.size,
            )
        }.sortedByDescending { it.rate }
    }

    data class Dossier(val tag: String, val items: List<ScanRecord>, val consensus: Level, val agree: Int, val conflict: Boolean)

    /**
     * Corroboration engine: groups the latest reading from each instrument for the same sample
     * (same tag, within 24 h). A verdict is corroborated when 2+ independent instruments agree.
     */
    fun dossiers(rs: List<ScanRecord>, now: Long = System.currentTimeMillis()): List<Dossier> =
        rs.filter { it.sampleTag.isNotBlank() && now - it.time < DAY }
            .groupBy { it.sampleTag.trim().lowercase() }
            .mapNotNull { (_, g) ->
                val latest = g.groupBy { it.instrument }.map { it.value.maxBy { r -> r.time } }
                if (latest.size < 2) return@mapNotNull null
                val votes = latest.filter { it.level != Level.INCONCLUSIVE }
                val worst = votes.maxByOrNull { it.level.ordinal }?.level ?: Level.INCONCLUSIVE
                val agree = votes.count { it.level == worst }
                val conflict = votes.any { it.level == Level.SAFE } && votes.any { it.level == Level.UNSAFE }
                Dossier(latest.first().sampleTag, latest.sortedBy { it.instrument }, worst, agree, conflict)
            }

    fun digest(rs: List<ScanRecord>, now: Long = System.currentTimeMillis()): String? {
        val week = rs.filter { now - it.time < 7 * DAY }
        if (week.isEmpty()) return null
        val s = score(week)
        val tests = byTest(week)
        val worst = tests.firstOrNull { it.second.unsafe > 0 }
        val best = tests.filter { it.second.unsafe == 0 && it.second.caution == 0 }.maxByOrNull { it.second.total }
        return buildString {
            append("${week.size} scan${if (week.size == 1) "" else "s"} this week")
            if (s != null) append(", kitchen score $s/100")
            append(". ")
            if (worst != null) append("Weakest: ${worst.first} (${worst.second.unsafe} failure${if (worst.second.unsafe > 1) "s" else ""}). ")
            if (best != null) append("Cleanest: ${best.first} (${best.second.total}/${best.second.total} clean).")
        }.trim()
    }

    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val categories = listOf("Water", "Milk", "Honey", "Air", "Produce", "Surfaces", "Utensils")

    fun categoryOf(r: ScanRecord): String? = when {
        r.analyteId.startsWith("milk") -> "Milk"
        r.analyteId in setOf("cl2", "no3", "fe", "f", "water_turbidity") || r.analyteId.startsWith("strip_") -> "Water"
        r.analyteId == "honey_polar" || r.analyteId == "nir_water" -> "Honey"
        r.analyteId == "air_scatter" -> "Air"
        r.analyteId.startsWith("echo_") || r.analyteId == "fingerprint" -> "Produce"
        r.analyteId.startsWith("nami_") -> "Surfaces"
        r.analyteId == "magneto" -> "Utensils"
        else -> null
    }

    /** Share of safe verdicts per category (null where there is no data). */
    fun categoryScores(rs: List<ScanRecord>): List<Float?> = categories.map { c ->
        val v = rs.filter { categoryOf(it) == c && it.level != Level.INCONCLUSIVE }
        if (v.isEmpty()) null else v.sumOf { if (it.level == Level.SAFE) 1.0 else if (it.level == Level.CAUTION) 0.5 else 0.0 }.toFloat() / v.size
    }

    /** (safe, caution, unsafe) counts per day for the last [days] days. */
    fun activity(rs: List<ScanRecord>, days: Int = 14, now: Long = System.currentTimeMillis()): List<Triple<Int, Int, Int>> {
        val start = startOfDay(now) - (days - 1) * DAY
        return (0 until days).map { d ->
            val from = start + d * DAY
            val day = rs.filter { it.time in from until from + DAY }
            Triple(day.count { it.level == Level.SAFE }, day.count { it.level == Level.CAUTION }, day.count { it.level == Level.UNSAFE })
        }
    }

    /** Safe / caution / unsafe tally for each calendar day that has scans (key = start of day). */
    fun byDay(rs: List<ScanRecord>): Map<Long, Tally> =
        rs.filter { it.level != Level.INCONCLUSIVE }.groupBy { startOfDay(it.time) }.mapValues { (_, v) ->
            Tally(v.count { it.level == Level.SAFE }, v.count { it.level == Level.CAUTION }, v.count { it.level == Level.UNSAFE })
        }

    /** (current streak, longest streak) of consecutive days with at least one scan. */
    fun streaks(rs: List<ScanRecord>, now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val days = rs.map { startOfDay(it.time) }.toSortedSet()
        if (days.isEmpty()) return 0 to 0
        var longest = 1; var run = 1
        days.zipWithNext().forEach { (a, b) -> if (Math.round((b - a) / DAY.toDouble()) == 1L) { run++; longest = maxOf(longest, run) } else run = 1 }
        var cur = 0
        var d = startOfDay(now)
        if (d !in days) d -= DAY // a streak survives until the end of today
        while (d in days) { cur++; d -= DAY }
        return cur to longest
    }

    /** Scans per hour of day (0–23). */
    fun hours(rs: List<ScanRecord>): IntArray {
        val cal = Calendar.getInstance()
        return IntArray(24).also { h -> rs.forEach { cal.timeInMillis = it.time; h[cal.get(Calendar.HOUR_OF_DAY)]++ } }
    }

    fun instrumentMix(rs: List<ScanRecord>): List<Pair<String, Int>> =
        rs.groupingBy { it.instrument.removePrefix("Shuddh ") }.eachCount().toList().sortedByDescending { it.second }
}
