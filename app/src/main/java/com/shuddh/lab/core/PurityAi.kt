package com.shuddh.lab.core

/**
 * Second opinion from the on-device language model. The models on the phone are text-only, so the sensor
 * readings of every training class (and of the photos, as colour and texture numbers) are written out as a
 * compact table, followed by the test sample and the classifier's ranking. The model answers in one line.
 */
object PurityAi {
    data class Opinion(val label: String?, val safe: Boolean?, val confidence: Double?, val reason: String, val raw: String,
                       val sample: String? = null, val waterPct: Double? = null)

    private fun f3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)

    fun prompt(samples: List<PurityTrain.Sample>, labels: List<PurityTrain.Label>, test: Map<String, Double>, r: PurityTrain.Result, water: Double?, photo: String? = null): String {
        val keys = r.features.take(8)
        val sb = StringBuilder()
        sb.append("You are a food-safety lab assistant. A phone measured liquid samples with its camera and lights.\n")
        sb.append("Features: amb_r/g/b = reflectance in room light (0..1, vs white paper), flash_r/g/b = with torch, ring* = under the phone's ring light, tex = surface texture (higher = curdled/lumpy).\n\n")
        sb.append("TRAINING (class: mean values for ${keys.joinToString(", ")}):\n")
        samples.groupBy { it.label }.forEach { (lb, ss) ->
            val t = labels.firstOrNull { it.id == lb }
            sb.append("- ${lb}${t?.let { if (it.safe) " [safe]" else " [unsafe]" } ?: ""}: ")
            sb.append(keys.joinToString(", ") { k -> f3(ss.map { it.f[k] ?: 0.0 }.average()) }).append("\n")
        }
        sb.append("\nTEST SAMPLE: ").append(keys.joinToString(", ") { k -> f3(test[k] ?: 0.0) }).append("\n")
        sb.append("Sensor classifier ranking: ").append(r.ranked.take(3).joinToString("; ") { "${it.label} ${(it.p * 100).toInt()}%" }).append("\n")
        water?.let { sb.append("Estimated added water: ${it.toInt()}%\n") }
        photo?.let { sb.append("\nPHOTO OF THE SAMPLE (described by the phone): ").append(it).append("\n") }
        sb.append("Hints: pure milk is opaque white with equal R,G,B near 0.85-0.95; watered milk turns thinner and bluish (B above R) and darker under torch; plain water is clear so the cap/paper shows through; spoiled milk is yellowish and lumpy (high tex).\n")
        sb.append("\nWhat is the test sample, is it safe, and roughly how much water is in it? Reply in exactly one line:\n")
        sb.append("CLASS=<one of: ${samples.map { it.label }.distinct().joinToString("|")}>; SAMPLE=<water|milk|diluted milk|adulterated|other>; WATER_PCT=<0-100>; SAFE=<yes|no>; CONFIDENCE=<0-100>; REASON=<max 15 words>")
        return sb.toString()
    }

    /** Reads "CLASS=…; SAFE=…; CONFIDENCE=…; REASON=…" — tolerant of extra text and casing. */
    fun parse(text: String, classes: Collection<String>): Opinion {
        val t = text.replace("*", "")
        fun field(name: String) = Regex("$name\\s*[=:]\\s*([^;\\n]+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.trim()
        val cls = field("CLASS")?.lowercase()?.let { c -> classes.firstOrNull { it.lowercase() == c } ?: classes.firstOrNull { c.contains(it.lowercase()) } }
        val safe = field("SAFE")?.lowercase()?.let { if (it.startsWith("y")) true else if (it.startsWith("n")) false else null }
        val conf = field("CONFIDENCE")?.let { Regex("\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }?.coerceIn(0.0, 100.0)
        val reason = field("REASON") ?: t.lines().firstOrNull { it.isNotBlank() }?.take(120).orEmpty()
        val sample = field("SAMPLE")?.lowercase()?.let { v -> listOf("diluted milk", "adulterated", "water", "milk", "other").firstOrNull { v.contains(it) } }
        val wp = field("WATER_PCT")?.let { Regex("\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }?.coerceIn(0.0, 100.0)
        return Opinion(cls, safe, conf, reason, text, sample, wp)
    }
}

/** Turns the sample photo and its colour readings into words the text-only model can reason about. */
object SamplePhoto {
    fun describe(labels: List<Pair<String, Float>>, f: Map<String, Double>): String {
        fun g(k: String) = f[k]?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "?"
        val r = f["amb_r"] ?: f["flash_r"]; val b = f["amb_b"] ?: f["flash_b"]
        val tint = if (r != null && b != null) when { b - r > 0.03 -> "bluish"; r - b > 0.04 -> "yellowish"; else -> "neutral white" } else "unknown"
        val bright = listOfNotNull(f["amb_r"], f["amb_g"], f["amb_b"]).takeIf { it.isNotEmpty() }?.average()
        val look = when { bright == null -> "unknown"; bright > 0.8 -> "bright, opaque"; bright > 0.55 -> "slightly see-through"; else -> "dark / translucent (paper shows through)" }
        val tex = f["tex"]?.let { if (it > 0.08) "lumpy/uneven surface" else "smooth surface" } ?: "surface unknown"
        return "Liquid in a small cap on white paper. On-device image labels: " +
            labels.take(5).joinToString { "${it.first} ${(it.second * 100).toInt()}%" }.ifBlank { "none confident" } +
            ". Looks $look, $tint, $tex. Colour vs white paper — room light R ${g("amb_r")} G ${g("amb_g")} B ${g("amb_b")}; torch R ${g("flash_r")} G ${g("flash_g")} B ${g("flash_b")}."
    }

    /** Blends the model's water guess into the sensor estimate as a light reference (never overrides it). */
    fun blendWater(sensor: Double?, ai: Double?): Double? = when {
        sensor == null -> null
        ai == null || kotlin.math.abs(ai - sensor) > 30 -> sensor
        else -> 0.8 * sensor + 0.2 * ai
    }
}
