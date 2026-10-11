package com.shuddh.lab.core

/**
 * Second opinion from the on-device language model. The models on the phone are text-only, so the sensor
 * readings of every training class (and of the photos, as colour and texture numbers) are written out as a
 * compact table, followed by the test sample and the classifier's ranking. The model answers in one line.
 */
object PurityAi {
    data class Opinion(val label: String?, val safe: Boolean?, val confidence: Double?, val reason: String, val raw: String)

    private fun f3(v: Double) = String.format(java.util.Locale.US, "%.3f", v)

    fun prompt(samples: List<PurityTrain.Sample>, labels: List<PurityTrain.Label>, test: Map<String, Double>, r: PurityTrain.Result, water: Double?): String {
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
        sb.append("\nWhich training class is the test sample, and is it safe to consume? Reply in exactly one line:\n")
        sb.append("CLASS=<one of: ${samples.map { it.label }.distinct().joinToString("|")}>; SAFE=<yes|no>; CONFIDENCE=<0-100>; REASON=<max 15 words>")
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
        return Opinion(cls, safe, conf, reason, text)
    }
}
