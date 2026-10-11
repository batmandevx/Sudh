package com.shuddh.lab.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.sqrt

/**
 * Livestock health: a vet-sign symptom checker (standard clinical signs, not a trained model),
 * camera breathing-rate measurement, a herd book with vaccination, heat and calving dates.
 */
object Livestock {
    enum class Species(val label: String, val emoji: String, val breathLo: Int, val breathHi: Int, val gestation: Int, val tempLo: Double, val tempHi: Double) {
        COW("Cow", "🐄", 26, 50, 283, 101.5, 103.5),
        BUFFALO("Buffalo", "🐃", 18, 34, 310, 98.6, 102.4),
        GOAT("Goat", "🐐", 15, 30, 150, 101.5, 104.0),
        SHEEP("Sheep", "🐑", 16, 34, 147, 102.0, 104.0),
    }

    /** A clinical sign a farmer can see, feel or measure. */
    data class Sign(val key: String, val label: String, val group: String)

    val signs = listOf(
        Sign("fever", "Hot to touch / fever", "General"), Sign("dull", "Dull, off feed", "General"), Sign("milkdrop", "Sudden drop in milk", "General"),
        Sign("sudden_death", "Animal died suddenly", "General"), Sign("down", "Can't stand up", "General"), Sign("cold_ears", "Cold ears / body", "General"),
        Sign("mouth_blisters", "Blisters or sores in mouth / tongue", "Mouth & nose"), Sign("drool", "Drooling, ropey saliva", "Mouth & nose"),
        Sign("nasal", "Nose / eye discharge", "Mouth & nose"), Sign("cough", "Cough", "Breathing"), Sign("fast_breath", "Fast or hard breathing", "Breathing"),
        Sign("grunt", "Grunting, open-mouth breathing", "Breathing"), Sign("throat_swelling", "Swelling of throat / brisket", "Skin & body"),
        Sign("skin_nodules", "Round lumps all over skin", "Skin & body"), Sign("limb_swelling_crackle", "Hot swelling on thigh/shoulder that crackles", "Skin & body"),
        Sign("lymph", "Swollen glands in front of shoulder", "Skin & body"), Sign("pale", "Pale eyes / gums", "Skin & body"), Sign("ticks", "Many ticks", "Skin & body"),
        Sign("hoof_blisters", "Blisters between hooves", "Legs"), Sign("lame", "Lameness", "Legs"),
        Sign("udder_hot", "Hot, hard, painful udder", "Udder & milk"), Sign("milk_clots", "Clots, flakes or watery milk", "Udder & milk"), Sign("blood_milk", "Blood in milk", "Udder & milk"),
        Sign("left_bloat", "Left side blown up like a drum", "Belly"), Sign("diarrhoea", "Diarrhoea", "Belly"), Sign("bleeding", "Dark blood from nose/anus, not clotting", "Belly"),
        Sign("recent_calving", "Gave birth in last 3 days", "Context"), Sign("young", "Young (6–24 months)", "Context"), Sign("lush_legume", "Ate lush green legume/wet fodder", "Context"),
        Sign("monsoon", "Rainy season", "Context"), Sign("grain_overfeed", "Ate a lot of grain", "Context"),
    )

    /** A disease with sign weights (higher = more specific). Urgency: 3 = emergency/notifiable. */
    data class Disease(val key: String, val name: String, val species: Set<Species>, val w: Map<String, Double>, val urgency: Int, val action: String, val prevent: String, val milkSafe: Boolean = true)

    private val all = Species.entries.toSet()
    private val bovine = setOf(Species.COW, Species.BUFFALO)
    private val small = setOf(Species.GOAT, Species.SHEEP)

    val diseases = listOf(
        Disease("fmd", "Foot-and-mouth disease (FMD)", all, mapOf("mouth_blisters" to 4.0, "hoof_blisters" to 4.0, "drool" to 2.5, "lame" to 1.5, "fever" to 1.0, "milkdrop" to 1.0, "dull" to 0.5), 3,
            "Isolate the animal at once; wash sores with 1% potassium permanganate, apply boroglycerine in the mouth; soft feed. Report to the vet — FMD is notifiable.", "FMD vaccine every 6 months (free under NADCP).", milkSafe = false),
        Disease("lsd", "Lumpy skin disease", bovine, mapOf("skin_nodules" to 5.0, "lymph" to 1.5, "fever" to 1.0, "nasal" to 1.0, "milkdrop" to 1.0, "dull" to 0.5), 3,
            "Isolate; control flies and mosquitoes; vet for antibiotics against secondary infection and anti-inflammatories. Report — LSD is notifiable.", "Goat-pox (Uttarkashi) vaccine yearly; fly control.", milkSafe = false),
        Disease("hs", "Haemorrhagic septicaemia (galghontu)", bovine, mapOf("throat_swelling" to 4.5, "grunt" to 2.5, "fever" to 1.5, "drool" to 1.0, "fast_breath" to 1.0, "monsoon" to 1.0, "dull" to 0.5), 3,
            "Emergency — can kill within 24 h. Call the vet now for antibiotics (e.g. sulpha / oxytetracycline).", "HS vaccine every year before the monsoon (May–June)."),
        Disease("bq", "Black quarter (blackleg)", bovine, mapOf("limb_swelling_crackle" to 5.0, "lame" to 1.5, "young" to 1.5, "fever" to 1.0, "dull" to 0.5), 3,
            "Emergency — penicillin early saves the animal. Call the vet now.", "BQ vaccine yearly before monsoon for animals 6 months–2 years."),
        Disease("anthrax", "Anthrax", all, mapOf("sudden_death" to 4.0, "bleeding" to 5.0, "fever" to 1.0), 3,
            "Do NOT open the carcass — spores spread and infect people. Keep everyone away; call the vet and panchayat. Bury deep with lime.", "Anthrax vaccine yearly in affected districts.", milkSafe = false),
        Disease("mastitis", "Mastitis", setOf(Species.COW, Species.BUFFALO, Species.GOAT, Species.SHEEP), mapOf("udder_hot" to 3.5, "milk_clots" to 4.0, "blood_milk" to 2.0, "milkdrop" to 1.0, "fever" to 0.5), 2,
            "Strip the affected quarter often; vet for intramammary antibiotic. Discard that milk until the withdrawal period ends. Check milk with the Purity test.", "Clean, dry sheds; full-hand milking; teat dip after milking; dry-cow therapy.", milkSafe = false),
        Disease("milkfever", "Milk fever (low calcium)", bovine, mapOf("recent_calving" to 3.0, "down" to 3.0, "cold_ears" to 2.5, "dull" to 0.5), 3,
            "Emergency — vet gives calcium borogluconate into the vein. Keep her sitting upright on her chest, not flat.", "Low-calcium diet in the last 3 weeks of pregnancy; oral calcium gel at calving."),
        Disease("bloat", "Bloat (tympany)", all, mapOf("left_bloat" to 5.0, "lush_legume" to 2.0, "fast_breath" to 1.0, "grunt" to 0.5), 3,
            "Walk the animal; give 250–500 ml vegetable/linseed oil or an anti-bloat drench; raise front legs. If severe, the vet may need to puncture the rumen.", "Wilt legumes before feeding; mix with dry straw."),
        Disease("theileria", "Theileriosis (tick fever)", bovine, mapOf("lymph" to 3.0, "ticks" to 2.0, "fever" to 1.5, "pale" to 2.0, "dull" to 0.5, "milkdrop" to 0.5), 2,
            "Vet for buparvaquone injection; remove ticks.", "Tick control; theileria vaccine for crossbreds in endemic areas."),
        Disease("pneumonia", "Pneumonia", all, mapOf("cough" to 2.5, "nasal" to 2.0, "fast_breath" to 2.0, "fever" to 1.5, "dull" to 0.5), 2,
            "Keep warm and dry with fresh air; vet for antibiotics.", "Avoid crowded, damp, draughty sheds."),
        Disease("scours", "Calf scours / diarrhoea", all, mapOf("diarrhoea" to 3.5, "young" to 1.0, "dull" to 0.5), 2,
            "Oral rehydration: 1 L water + 1 tbsp salt + 4 tbsp sugar, several times a day; keep feeding milk; vet if blood or the calf can't stand.", "Colostrum within 2 hours of birth; clean calf pen."),
        Disease("ppr", "PPR (goat plague)", small, mapOf("mouth_blisters" to 2.5, "nasal" to 2.5, "diarrhoea" to 2.0, "cough" to 1.5, "fever" to 1.5), 3,
            "Isolate; supportive care and antibiotics for secondary infection. Report — PPR is notifiable.", "PPR vaccine once every 3 years."),
        Disease("et", "Enterotoxaemia", small, mapOf("sudden_death" to 3.0, "grain_overfeed" to 3.0, "diarrhoea" to 1.0, "down" to 1.0), 3,
            "Emergency — vet for antitoxin; stop grain.", "ET vaccine yearly before monsoon; change feed gradually."),
    )

    data class Match(val disease: Disease, val score: Double, val pct: Int)

    /**
     * Ranks diseases by summed sign weights, normalised by each disease's total so a disease with
     * many possible signs isn't favoured. Breathing rate (bpm) adds a "fast breathing" sign when high.
     */
    fun check(species: Species, picked: Set<String>, breathBpm: Int? = null): List<Match> {
        val s = picked.toMutableSet()
        if (breathBpm != null && breathBpm > species.breathHi) s += "fast_breath"
        if (s.isEmpty()) return emptyList()
        val raw = diseases.filter { species in it.species }.map { d ->
            val hit = d.w.filterKeys { it in s }.values.sum()
            val tot = d.w.values.sum()
            // Coverage of the disease's picture × how much of what the farmer saw it explains.
            val explained = s.count { it in d.w } / s.size.toDouble()
            d to (hit / tot) * (0.5 + 0.5 * explained) * (1 + 0.15 * d.w.filterKeys { it in s }.values.count { it >= 4.0 })
        }.filter { it.second > 0.08 }.sortedByDescending { it.second }
        val total = raw.sumOf { it.second }.coerceAtLeast(1e-9)
        return raw.take(4).map { (d, v) -> Match(d, v, (100 * v / maxOf(total, raw.first().second * 1.2)).toInt().coerceIn(1, 95)) }
    }

    fun breathVerdict(species: Species, bpm: Int): String = when {
        bpm < species.breathLo -> "Slower than normal (${species.breathLo}–${species.breathHi}/min) — re-measure while the animal rests calmly."
        bpm > species.breathHi + 15 -> "Very fast — heat stress, pneumonia, bloat or pain. Shade, water, and call the vet if it persists."
        bpm > species.breathHi -> "A bit fast — check for heat stress (move to shade, give water) and re-measure in 30 min."
        else -> "Normal (${species.breathLo}–${species.breathHi}/min)."
    }

    /**
     * Breathing rate from a flank-motion signal sampled at [fps]: detrend, then the strongest
     * autocorrelation peak between 8 and 90 breaths/min.
     */
    fun breathRate(signal: List<Double>, fps: Double): Pair<Int, Double>? {
        if (signal.size < fps * 8) return null
        // Remove slow drift with a 3-s moving average.
        val win = (fps * 3).toInt().coerceAtLeast(3)
        val x = signal.indices.map { i ->
            val a = (i - win / 2).coerceAtLeast(0); val b = (i + win / 2).coerceAtMost(signal.size - 1)
            signal[i] - signal.subList(a, b + 1).average()
        }
        val m = x.average(); val v = x.map { it - m }
        val e = v.sumOf { it * it }.coerceAtLeast(1e-12)
        val lagMin = (fps * 60 / 90).toInt().coerceAtLeast(1); val lagMax = (fps * 60 / 8).toInt().coerceAtMost(v.size / 2)
        var best = -1.0; var bestLag = -1
        for (lag in lagMin..lagMax) {
            var s = 0.0; for (i in 0 until v.size - lag) s += v[i] * v[i + lag]
            val r = s / e
            if (r > best) { best = r; bestLag = lag }
        }
        if (bestLag <= 0) return null
        return (60 * fps / bestLag).toInt() to best.coerceIn(0.0, 1.0)
    }

    // ── Herd book ─────────────────────────────────────────────────────────────────────────────

    data class Animal(val id: Long, val name: String, val species: Species, val breed: String, val born: Int, val female: Boolean,
                      val bred: Long? = null, val vaccines: Map<String, Long> = emptyMap(), val milk: List<Pair<Long, Double>> = emptyList()) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("n", name).put("s", species.name).put("b", breed).put("y", born).put("f", female).put("bred", bred ?: JSONObject.NULL)
            .put("v", JSONObject().apply { vaccines.forEach { (k, d) -> put(k, d) } })
            .put("m", JSONArray().apply { milk.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        companion object {
            fun from(o: JSONObject) = Animal(o.getLong("id"), o.optString("n"), runCatching { Species.valueOf(o.optString("s")) }.getOrDefault(Species.COW), o.optString("b"), o.optInt("y"), o.optBoolean("f", true),
                if (o.isNull("bred")) null else o.optLong("bred"),
                o.optJSONObject("v")?.let { v -> v.keys().asSequence().associateWith { v.getLong(it) } } ?: emptyMap(),
                o.optJSONArray("m")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getLong(0) to p.getDouble(1) } } } ?: emptyList())
        }
    }

    /** Vaccine schedule (India): key, label, repeat interval in days, species, best month (0 = any). */
    data class Vaccine(val key: String, val label: String, val everyDays: Int, val species: Set<Species>, val month: Int = 0)

    val vaccines = listOf(
        Vaccine("fmd", "FMD", 182, all), Vaccine("hs", "HS (galghontu)", 365, bovine, 5), Vaccine("bq", "Black quarter", 365, bovine, 5),
        Vaccine("lsd", "Lumpy skin", 365, bovine), Vaccine("ppr", "PPR", 1095, small), Vaccine("et", "Enterotoxaemia", 365, small, 5),
        Vaccine("deworm", "Deworming", 100, all),
    )

    data class Due(val animal: Animal, val what: String, val day: Long) { val inDays get() = day - LocalDate.now().toEpochDay() }

    /** Upcoming vaccinations and breeding events for the herd, soonest first. */
    fun dues(herd: List<Animal>, today: Long = LocalDate.now().toEpochDay()): List<Due> = herd.flatMap { a ->
        val v = vaccines.filter { a.species in it.species }.map { vac ->
            val last = a.vaccines[vac.key]
            Due(a, "💉 ${vac.label}", last?.plus(vac.everyDays) ?: today)
        }
        val b = a.bred?.let { bred ->
            listOf(Due(a, "🔁 Check for heat (repeat)", bred + 21), Due(a, "🔎 Pregnancy check", bred + 60), Due(a, "🍼 Expected ${if (a.species == Species.GOAT || a.species == Species.SHEEP) "kidding" else "calving"}", bred + a.species.gestation))
                .filter { it.day >= today - 3 }
        } ?: emptyList()
        v + b
    }.sortedBy { it.day }

    /** Milk trend: last 7 days vs previous 7 (fraction change) — an early mastitis/illness flag. */
    fun milkTrend(a: Animal): Double? {
        val today = LocalDate.now().toEpochDay()
        val last = a.milk.filter { it.first > today - 7 }.map { it.second }
        val prev = a.milk.filter { it.first in (today - 14)..(today - 7) }.map { it.second }
        if (last.size < 3 || prev.size < 3) return null
        return last.average() / prev.average().coerceAtLeast(0.1) - 1
    }

    fun std(x: List<Double>): Double { val m = x.average(); return sqrt(x.sumOf { (it - m) * (it - m) } / x.size.coerceAtLeast(1)) }
}

class HerdStore(ctx: Context) {
    private val file = File(ctx.filesDir, "herd.json")
    var herd: List<Livestock.Animal> = runCatching { JSONArray(file.readText()).let { a -> (0 until a.length()).map { Livestock.Animal.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        private set
    fun save(h: List<Livestock.Animal>) { herd = h; runCatching { file.writeText(JSONArray().apply { h.forEach { put(it.toJson()) } }.toString()) } }
}
