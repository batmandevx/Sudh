package com.shuddh.lab.core

import kotlin.math.log10

/**
 * Outbreak Watch — community early warning for water- and food-borne illness, fully offline.
 *
 * Households report symptoms anonymously ("case" items: area + symptom + people affected); they
 * travel phone-to-phone by QR or Bluetooth mesh like any Hive alert. For an area, recent cases
 * are compared with that area's own background rate, and corroborated by unsafe water/food reports
 * nearby. Rule-based like a district surveillance cell's first screen — transparent, not a model.
 */
object OutbreakWatch {
    enum class Symptom(val id: String, val emoji: String, val label: String, val likely: String) {
        DIARRHOEA("diarrhoea", "🚽", "Loose motions", "contaminated water or food (cholera, E. coli, rotavirus)"),
        VOMITING("vomiting", "🤢", "Vomiting", "food poisoning or contaminated water"),
        FEVER("fever", "🌡", "Fever", "typhoid (water) or dengue/malaria (mosquitoes)"),
        JAUNDICE("jaundice", "🟡", "Yellow eyes / jaundice", "hepatitis A or E — spread through contaminated water"),
    }

    enum class Status(val label: String, val argb: Long) { CLEAR("All clear", 0xFF2FBF71), WATCH("Watch", 0xFFF2B33D), ALERT("Outbreak alert", 0xFFE5484D) }

    data class Assessment(
        val status: Status,
        val recentCases: Int,
        val households: Int,
        val baselinePerWindow: Double,
        val dominant: Symptom?,
        val waterLinked: Boolean,
        val perDay: List<Pair<Long, Map<Symptom, Int>>>,
        val reasons: List<String>,
    )

    fun symptomOf(analyte: String): Symptom? = Symptom.entries.firstOrNull { analyte.contains(it.id, true) || analyte.contains(it.label, true) }

    private fun sameArea(a: String, b: String) = a.isNotBlank() && b.isNotBlank() && a.trim().equals(b.trim(), true)

    /**
     * [items] = everything in the Hive (cases + water/food alerts). Window = last 72 h; baseline =
     * the 72-h average over the preceding 14 days. Cases count people affected (item.total).
     */
    fun assess(items: List<CommunityItem>, area: String, now: Long = System.currentTimeMillis()): Assessment {
        val day = 86_400_000L
        val cases = items.filter { it.kind == "case" && (area.isBlank() || sameArea(it.area, area)) && symptomOf(it.analyte) != null }
        val recent = cases.filter { now - it.time in 0..3 * day }
        val before = cases.filter { now - it.time in (3 * day + 1)..17 * day }
        val people = recent.sumOf { it.total.coerceAtLeast(1) }
        val baseline = before.sumOf { it.total.coerceAtLeast(1) } / (14.0 / 3.0)
        val households = recent.map { it.ref }.distinct().size.coerceAtLeast(if (recent.isEmpty()) 0 else 1)
        val bySym = recent.groupBy { symptomOf(it.analyte)!! }.mapValues { (_, v) -> v.sumOf { it.total.coerceAtLeast(1) } }
        val dominant = bySym.maxByOrNull { it.value }?.key
        val water = items.any {
            it.kind != "case" && (it.level == Level.UNSAFE || it.level == Level.CAUTION) && now - it.time in 0..7 * day &&
                (area.isBlank() || sameArea(it.area, area)) && Regex("water|nitrate|chlorine|turbid|clarity|arsenic|fluoride|bore|tap", RegexOption.IGNORE_CASE).containsMatchIn(it.analyte + " " + it.vendor)
        }
        val gut = (bySym[Symptom.DIARRHOEA] ?: 0) + (bySym[Symptom.VOMITING] ?: 0) + (bySym[Symptom.JAUNDICE] ?: 0)
        val excess = people - baseline
        val reasons = mutableListOf<String>()
        val status = when {
            (bySym[Symptom.JAUNDICE] ?: 0) >= 2 -> { reasons += "${bySym[Symptom.JAUNDICE]} jaundice cases in 3 days — hepatitis spreads through water"; Status.ALERT }
            people >= 5 && excess >= 3 && households >= 3 -> { reasons += "$people people sick in $households homes in 3 days (usual ≈ ${"%.1f".format(baseline)})"; Status.ALERT }
            gut >= 3 && water -> { reasons += "$gut stomach cases + an unsafe water report in the area"; Status.ALERT }
            people >= 3 && excess >= 2 -> { reasons += "$people people sick in 3 days — above the usual ≈ ${"%.1f".format(baseline)}"; Status.WATCH }
            water && people >= 1 -> { reasons += "Unsafe water reported nearby and illness at home"; Status.WATCH }
            else -> Status.CLEAR
        }
        if (water && status != Status.CLEAR) reasons += "Unsafe water/food was reported in this area within 7 days"
        val start = now - 13 * day
        val perDay = (0 until 14).map { d ->
            val t0 = start + d * day - (start % day)
            t0 to cases.filter { it.time in t0 until t0 + day }.groupBy { symptomOf(it.analyte)!! }.mapValues { (_, v) -> v.sumOf { it.total.coerceAtLeast(1) } }
        }
        return Assessment(status, people, households, baseline, dominant, water, perDay, reasons)
    }

    fun advice(a: Assessment): List<String> = buildList {
        if (a.status == Status.CLEAR) { add("No unusual illness reported. Keep boiling or purifying drinking water."); return@buildList }
        add("Drink only boiled (rolling boil 1 min) or purified water — use Boil Guard.")
        if (a.dominant == Symptom.DIARRHOEA || a.dominant == Symptom.VOMITING) add("Give ORS early to anyone with loose motions; see a doctor if there is blood, high fever or drowsiness.")
        if (a.dominant == Symptom.JAUNDICE) add("Jaundice: avoid street food and cut fruit; the patient should rest and see a doctor.")
        if (a.dominant == Symptom.FEVER) add("Fever: test for typhoid/dengue; empty standing water around homes (coolers, pots, tyres).")
        add("Tell your ASHA worker or call 104 so the health department can test the water source.")
        if (a.waterLinked) add("Test the suspected tap/borewell with Shuddh and share the result in Hive.")
    }
}

/**
 * Anaemia Screen — pallor of the fingernail bed / inner eyelid, measured against a white card
 * under the flash. Blood colour comes from haemoglobin; less haemoglobin = paler tissue. The
 * erythema index (log red − log green, white-normalised) is the standard colour measure of
 * redness used in pallor research. Screening only: it sends people for a real Hb test.
 */
object AnaemiaIndex {
    /** White-normalised erythema index of a tissue patch: 100·(log10 R′ − log10 G′). */
    fun erythema(r: Double, g: Double, wr: Double, wg: Double): Double {
        val rn = (r / wr.coerceAtLeast(1.0)).coerceIn(0.01, 2.0)
        val gn = (g / wg.coerceAtLeast(1.0)).coerceIn(0.01, 2.0)
        return 100 * (log10(rn) - log10(gn))
    }

    data class Band(val level: Level, val label: String, val advice: String)

    /** Screening bands on the erythema index (nail bed / conjunctiva under flash). */
    fun band(ei: Double, site: String = "nail"): Band {
        val (pale, borderline) = if (site == "eye") 13.0 to 19.0 else 10.0 to 15.0
        return when {
            ei < pale -> Band(Level.UNSAFE, "Pale — anaemia likely", "Get a haemoglobin test soon (free at PHCs/Anganwadi under Anaemia Mukt Bharat). Eat iron-rich food daily.")
            ei < borderline -> Band(Level.CAUTION, "Borderline", "Slightly pale. Add iron-rich food and recheck in a month, or get an Hb test.")
            else -> Band(Level.SAFE, "Healthy colour", "Not pale. Keep eating a varied diet with greens, dals and pulses.")
        }
    }

    val ironFoods = listOf(
        "🥬 Green leafy vegetables — palak, methi, amaranth (chaulai), drumstick leaves",
        "🫘 Pulses — rajma, chana, masoor, moong; sprouts",
        "🥚 Eggs, fish, chicken, liver (if non-vegetarian)",
        "🍋 Vitamin C with meals (lemon, amla, guava, orange) — boosts iron absorption",
        "☕ Avoid tea/coffee within an hour of meals — it blocks iron",
        "🌾 Ragi, bajra, poha, jaggery in moderation",
    )
}

/**
 * Fingertip haemoglobin index (after HemaApp, UW 2016): the flash shines through a fingertip on the
 * camera. Haemoglobin absorbs green light far more than red, so (a) the transmitted green/red
 * ratio and (b) how strongly each colour pulses with the heartbeat (AC/DC) both depend on how much
 * haemoglobin the blood carries. Uncalibrated it gives a screening band; once the user enters one
 * lab Hb value, readings become personal estimates (ratio scaling against that calibration).
 */
object HbIndex {
    data class Reading(
        /** Transmitted green ÷ red (DC). Higher = less absorption = paler blood. */
        val gr: Double,
        /** Pulsatile ratio (AC/DC)green ÷ (AC/DC)red. */
        val pulseRatio: Double,
        val bpm: Double?,
        /** 0..1 — periodicity of the pulse; low = finger moved / poor contact. */
        val quality: Double,
        /** Haemoglobin index 0–100 (higher = more haemoglobin). */
        val index: Double,
    )

    private fun acdc(x: DoubleArray): Double {
        val c = Ppg.clean(x)
        val s = c.sorted()
        val ac = (s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)] - s[(s.size * 0.05).toInt()]) / 2
        return ac / x.average().coerceAtLeast(1.0)
    }

    fun analyse(t: List<Double>, r: List<Double>, g: List<Double>): Reading? {
        if (t.size < 60) return null
        val rr = Ppg.resample(t, r); val gg = Ppg.resample(t, g)
        if (rr.size < Ppg.FS * 6) return null
        val gr = gg.average() / rr.average().coerceAtLeast(1.0)
        val pr = acdc(gg) / acdc(rr).coerceAtLeast(1e-6)
        val p = Ppg.analyse(t, g) ?: Ppg.analyse(t, r)
        // Index: absorption term dominates; pulsatile term refines. Mapped so typical healthy ≈ 60–80.
        val absorb = (1 - (gr / 0.30).coerceIn(0.0, 1.0)) // 0.30 = very pale/transparent
        val pulse = (pr / 4.0).coerceIn(0.0, 1.0)
        val index = (100 * (0.75 * absorb + 0.25 * pulse)).coerceIn(0.0, 100.0)
        return Reading(gr, pr, p?.bpm, p?.quality ?: 0.0, index)
    }

    /** Personal estimate from one lab calibration (labHb at calIndex): Hb ∝ index around that point. */
    fun estimateHb(index: Double, labHb: Double, calIndex: Double): Double =
        (labHb * (index / calIndex.coerceAtLeast(1.0)).let { 1 + (it - 1) * 0.6 }).coerceIn(4.0, 20.0)

    data class Band(val level: Level, val label: String, val advice: String)

    /** WHO thresholds when calibrated (women 12 g/dL); index bands otherwise. */
    fun band(index: Double, hb: Double?): Band = when {
        hb != null && hb < 10 -> Band(Level.UNSAFE, "Likely anaemia", "Estimated ${"%.1f".format(hb)} g/dL. Get a blood test and see a doctor soon.")
        hb != null && hb < 12 -> Band(Level.CAUTION, "Borderline", "Estimated ${"%.1f".format(hb)} g/dL — eat iron-rich food and recheck with a blood test.")
        hb != null -> Band(Level.SAFE, "Healthy range", "Estimated ${"%.1f".format(hb)} g/dL. Keep a balanced, iron-rich diet.")
        index < 45 -> Band(Level.UNSAFE, "Possible anaemia", "Your fingertip absorbs little light — get a free Hb blood test (PHC / Anganwadi).")
        index < 58 -> Band(Level.CAUTION, "Borderline", "Slightly low index. Eat iron-rich food and get an Hb test to be sure.")
        else -> Band(Level.SAFE, "Looks healthy", "Index in the healthy range. Recheck monthly if pregnant or often tired.")
    }
}
