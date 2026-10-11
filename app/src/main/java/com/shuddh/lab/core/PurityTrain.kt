package com.shuddh.lab.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Purity training: labelled samples captured with the phone's own sensors (camera spectrum under room light
 * and torch, verified ring-light colours, film texture, echo, magnetic field) plus a photo. A test sample is
 * classified by nearest class centroid in a standardised feature space; % water comes from the samples with
 * a known water fraction (water, 50/50, pure milk) through a monotone calibration curve.
 */
object PurityTrain {
    /** A class you can train. [water] = known % water (for the milk curve), [safe] = is this class OK to consume? */
    data class Label(val id: String, val title: String, val emoji: String, val water: Double?, val safe: Boolean, val family: String)

    val presets = listOf(
        Label("water", "Water (baseline)", "💧", 100.0, true, "milk"),
        Label("pure_milk", "Pure milk", "🥛", 0.0, true, "milk"),
        Label("half", "50% milk + 50% water", "🌗", 50.0, false, "milk"),
        Label("fresh_milk", "Fresh milk", "🍼", 0.0, true, "milk"),
        Label("spoiled_milk", "Spoiled milk", "🦠", null, false, "milk"),
        Label("starch_milk", "Milk + rice water (starch)", "🍚", null, false, "adulterant"),
        Label("detergent_milk", "Milk + detergent", "🫧", null, false, "adulterant"),
        Label("honey", "Honey", "🍯", null, true, "honey"),
        Label("juice", "Juice", "🧃", null, true, "juice"),
    )

    fun label(id: String, custom: List<Label>) = (presets + custom).firstOrNull { it.id == id }

    data class Sample(val id: String, val label: String, val at: Long, val f: Map<String, Double>, val image: String?)

    /** Features used for classification — optical + texture; echo and magnetometer are too weak to decide. */
    fun classFeatures(f: Map<String, Double>) = f.filterKeys { !it.startsWith("echo") && it != "mag" }

    /** Realistic repeatability floors: re-pouring the cap and re-holding the phone moves reflectance by ~0.04. */
    fun floor(k: String) = if (k == "tex") 0.02 else 0.04

    data class Ranked(val label: String, val p: Double, val dist: Double)

    data class Result(val ranked: List<Ranked>, val nearest: Sample?, val features: List<String>, val unsure: Boolean = false)

    /** A capture is usable if the camera produced any reflectance reading at all. */
    fun usable(f: Map<String, Double>) = f.keys.any { it.startsWith("amb_") || it.startsWith("flash_") }

    /** Setup warning (shown with the result, never blocking): paper in the box, cap brighter than paper, etc. */
    fun captureProblem(f: Map<String, Double>): String? {
        val refl = f.filterKeys { it.startsWith("amb_") || it.startsWith("flash_") }
        if (refl.isEmpty()) return "The camera couldn't read the cap — more light, and keep the paper and cap inside their boxes."
        // All channels ≈ 1 with no colour = the blue box is looking at the white paper, not the cap.
        val fl = listOf("flash_r", "flash_g", "flash_b").mapNotNull { f[it] }.ifEmpty { listOf("amb_r", "amb_g", "amb_b").mapNotNull { f[it] } }
        if (fl.size == 3 && fl.all { it in 0.90..1.10 } && fl.max() - fl.min() < 0.12) return "The blue box is on the paper, not the cap — move the phone so the cap fills the blue box."
        if (refl.values.average() > 1.08) return "The cap looks brighter than the paper — put WHITE paper in the white box, and use a DARK cap (black, blue or red) so water looks dark."
        return null
    }

    fun classify(test: Map<String, Double>, samples0: List<Sample>): Result? {
        // Samples captured with a broken setup (cap brighter than the paper) are ignored.
        val samples = samples0.filter { usable(it.f) }
        val tf = classFeatures(test)
        val byLabel = samples.groupBy { it.label }
        if (byLabel.size < 2) return null
        // Features the test has and at least 70 % of the samples have (missing ones are skipped per sample).
        val keys = tf.keys.filter { k -> samples.count { k in it.f } >= 0.7 * samples.size }.sorted()
        if (keys.isEmpty()) return null
        val cent = byLabel.mapValues { (_, ss) -> keys.associateWith { k -> ss.mapNotNull { it.f[k] }.takeIf { v -> v.isNotEmpty() }?.average() } }
        val sd = keys.associateWith { k ->
            val dev = byLabel.flatMap { (lb, ss) -> ss.mapNotNull { s -> s.f[k]?.let { v -> cent[lb]!![k]?.let { c -> v - c } } } }
            val dof = (dev.size - byLabel.size).coerceAtLeast(1)
            maxOf(sqrt(dev.sumOf { it * it } / dof), floor(k))
        }
        fun d2(f: Map<String, Double?>): Double {
            val used = keys.filter { f[it] != null }
            if (used.isEmpty()) return 1e9
            return used.sumOf { k -> ((tf[k]!! - f[k]!!) / sd[k]!!).let { it * it } } / used.size
        }
        val d = cent.mapValues { (_, c) -> d2(c) }
        val m = d.values.min()
        // Evidence counts ~ one effective feature per light source, not one per colour channel (they're correlated).
        val eff = (keys.size / 3.0).coerceIn(1.0, 4.0)
        val w = d.mapValues { exp(-(it.value - m) * eff / 2) }
        val tot = w.values.sum()
        var ranked = d.keys.map { Ranked(it, w[it]!! / tot, sqrt(d[it]!!)) }.sortedByDescending { it.p }
        // Few samples per kind → we don't know the spread well: pull probabilities toward uniform.
        val minN = byLabel.values.minOf { it.size }
        val shrink = when { minN >= 3 -> 0.0; minN == 2 -> 0.2; else -> 0.35 }
        ranked = ranked.map { it.copy(p = (1 - shrink) * it.p + shrink / ranked.size) }
        val nearest = samples.minByOrNull { s -> d2(keys.associateWith { s.f[it] }) }
        // Unsure if the best match is far from everything trained, or barely ahead of the runner-up.
        val unsure = ranked.first().dist > 4 || ranked.first().p < 0.5
        return Result(ranked, nearest, keys, unsure)
    }

    /** Can the sensors tell water from pure milk with this training? (distance between their centroids, in σ) */
    fun separation(samples: List<Sample>, a: String, b: String): Double? {
        val sa = samples.filter { it.label == a }; val sb = samples.filter { it.label == b }
        if (sa.isEmpty() || sb.isEmpty()) return null
        val keys = (sa + sb).flatMap { it.f.keys }.distinct().filter { it.startsWith("amb_") || it.startsWith("flash_") }.filter { k -> (sa + sb).all { k in it.f } }
        if (keys.isEmpty()) return null
        return sqrt(keys.sumOf { k -> ((sa.map { it.f[k]!! }.average() - sb.map { it.f[k]!! }.average()) / floor(k)).let { it * it } } / keys.size)
    }

    /** % water from the milk-family samples with known water: best single feature via leave-one-out. */
    fun waterCurve(samples0: List<Sample>, labels: List<Label>): Pair<String, Ladder.Model>? {
        val samples = samples0.filter { usable(it.f) }
        val known = samples.mapNotNull { s -> labels.firstOrNull { it.id == s.label }?.water?.let { w -> w to s } }
        if (known.map { it.first }.distinct().size < 3) return null
        val keys = known.flatMap { it.second.f.keys }.distinct().filter { k -> !k.startsWith("echo") && k != "mag" && k != "tex" && known.all { k in it.second.f } }
        return keys.mapNotNull { k -> Ladder.fit(known.map { (w, s) -> w to s.f[k]!! })?.let { k to it } }
            .minByOrNull { (_, m) -> if (m.looMae.isNaN()) 1000 - (m.knots.maxOf { it.second } - m.knots.minOf { it.second }) * 100 else m.looMae }
    }

    // ── storage ──
    fun toJson(s: Sample) = JSONObject().put("id", s.id).put("label", s.label).put("at", s.at).put("img", s.image ?: JSONObject.NULL)
        .put("f", JSONObject().apply { s.f.forEach { (k, v) -> put(k, v) } })

    fun fromJson(o: JSONObject) = Sample(o.getString("id"), o.getString("label"), o.getLong("at"),
        o.getJSONObject("f").let { f -> f.keys().asSequence().associateWith { f.getDouble(it) } }, o.optString("img").takeIf { it.isNotEmpty() && it != "null" })

    fun load(o: JSONObject?): List<Sample> = o?.optJSONArray("s")?.let { a -> List(a.length()) { fromJson(a.getJSONObject(it)) } } ?: emptyList()
    fun save(list: List<Sample>) = JSONObject().put("s", JSONArray(list.map { toJson(it) }))

    fun customLabels(o: JSONObject?): List<Label> = o?.optJSONArray("custom")?.let { a ->
        List(a.length()) { a.getJSONObject(it).let { j -> Label(j.getString("id"), j.getString("title"), "🏷️", null, j.getBoolean("safe"), "custom") } }
    } ?: emptyList()

    /** Final decision: sensors lead; the AI confirms (+), disagrees (−) or breaks a tie when sensors are unsure. */
    data class Decision(val label: String, val confidence: Double, val aiAgrees: Boolean?, val aiOverrode: Boolean)

    fun decide(r: Result, aiLabel: String?, aiConf: Double?): Decision {
        val top = r.ranked.first()
        val base = (top.p * 100).coerceIn(1.0, 99.0)
        if (aiLabel == null) return Decision(top.label, base, null, false)
        if (aiLabel == top.label) return Decision(top.label, (base + 0.15 * (100 - base)).coerceAtMost(99.0), true, false)
        val second = r.ranked.getOrNull(1)
        // Sensors unsure (top < 60 %) and the AI picks the runner-up → follow the AI, with modest confidence.
        if (top.p < 0.6 && second != null && second.label == aiLabel) return Decision(aiLabel, (second.p * 100 + (aiConf ?: 50.0)) / 2, true, true)
        return Decision(top.label, (base * 0.8).coerceAtLeast(30.0), false, false)
    }


    /** Kinds that lie on the milk ↔ water dilution line. */
    val dilution = setOf("water", "half", "pure_milk", "fresh_milk")

    /**
     * Milk–water mix check: estimate water % with the calibration curve, predict every optical feature at that
     * water % by interpolating the trained water / 50-50 / pure-milk readings, and measure how far the sample is
     * from that prediction (in σ). Returns (water %, distance to the dilution line) or null if not enough training.
     */
    fun dilutionFit(test: Map<String, Double>, samples0: List<Sample>, labels: List<Label>): Pair<Double, Double>? {
        val samples = samples0.filter { usable(it.f) }
        val curve = waterCurve(samples, labels) ?: return null
        val w = test[curve.first]?.let { curve.second.waterPct(it) } ?: return null
        val levels = samples.mapNotNull { s -> labels.firstOrNull { it.id == s.label && it.id in dilution }?.water?.let { it to s } }.groupBy({ it.first }, { it.second })
        if (levels.size < 2) return null
        val keys = classFeatures(test).keys.filter { k -> levels.values.flatten().all { k in it.f } }
        if (keys.isEmpty()) return null
        val xs = levels.keys.sorted()
        fun at(k: String): Double {
            val ys = xs.map { lv -> levels[lv]!!.map { it.f[k]!! }.average() }
            val i = xs.indexOfLast { it <= w }.coerceIn(0, xs.size - 2)
            val (x0, x1) = xs[i] to xs[i + 1]
            return ys[i] + (ys[i + 1] - ys[i]) * ((w - x0) / (x1 - x0)).coerceIn(-0.2, 1.2)
        }
        val d = sqrt(keys.sumOf { k -> ((test[k]!! - at(k)) / floor(k)).let { it * it } } / keys.size)
        return w to d
    }

    /** On the dilution line if close to it and closer to it than to any non-dilution kind. */
    fun onDilutionLine(fit: Pair<Double, Double>?, r: Result): Boolean {
        val dLine = fit?.second ?: return false
        val other = r.ranked.filter { it.label !in dilution }.minOfOrNull { it.dist } ?: Double.MAX_VALUE
        return dLine <= 3.0 && dLine < other
    }

    /** Far from every trained kind (and not a milk–water mix) → an unknown substance. */
    fun unknown(r: Result, fit: Pair<Double, Double>? = null): Boolean = r.ranked.first().dist > 6 && !onDilutionLine(fit, r)

    /** 95 % range of water %, rounded to 5 %. */
    fun range(w: Double, sd: Double): Pair<Int, Int> {
        fun r5(x: Double) = (Math.round(x / 5) * 5).toInt().coerceIn(0, 100)
        return r5(w - 1.96 * sd) to r5(w + 1.96 * sd).coerceAtLeast(r5(w - 1.96 * sd) + 5).coerceAtMost(100)
    }
}
