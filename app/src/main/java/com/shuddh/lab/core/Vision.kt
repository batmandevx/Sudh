package com.shuddh.lab.core

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume

/** What the phone could read from a food label. Everything is extracted on-device. */
data class LabelLens(
    val fssai: String?,
    val dates: List<Long>,
    val expiry: Long?,
    val expirySource: String,
    val mrp: String?,
    val veg: Boolean?,
) {
    val daysLeft: Long? get() = expiry?.let { (it - System.currentTimeMillis()) / 86_400_000L }
}

data class ImageFacts(
    val labels: List<Pair<String, Float>>,
    val text: String,
    val colours: List<Int>,
    val lens: LabelLens,
    val millis: Long,
) {
    /** Compact description handed to the language model. */
    fun asFacts(): String = buildString {
        append("Objects seen (on-device image labeller): ")
        append(labels.take(6).joinToString { "${it.first} ${(it.second * 100).toInt()}%" }.ifBlank { "none confident" })
        append(". ")
        if (text.isNotBlank()) append("Printed text (OCR, first 300 chars): \"${text.replace('\n', ' ').take(300)}\". ")
        lens.fssai?.let { append("FSSAI licence number found: $it. ") } ?: append("No FSSAI licence number found. ")
        lens.expiry?.let { append("Expiry/best-before: ${stamp(it).substringBefore(",")} (${lens.daysLeft} days from today, ${lens.expirySource}). ") }
        lens.mrp?.let { append("MRP: ₹$it. ") }
        lens.veg?.let { append(if (it) "Vegetarian mark mentioned. " else "Non-veg mark mentioned. ") }
    }
}

object Vision {
    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resume(null) }
    }

    suspend fun analyse(bmp: Bitmap): ImageFacts {
        val t0 = System.currentTimeMillis()
        val img = InputImage.fromBitmap(bmp, 0)
        val labels = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.45f).build())
            .process(img).await().orEmpty().map { it.text to it.confidence }
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img).await()?.text.orEmpty()
        val deva = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()).process(img).await()?.text.orEmpty()
        val text = if (deva.length > latin.length * 1.2) deva else latin
        return ImageFacts(labels, text, dominantColours(bmp), parseLabel(latin + "\n" + deva), System.currentTimeMillis() - t0)
    }

    /** k-means-lite: bucket pixels into a 4×4×4 colour cube and return the 5 most common buckets. */
    fun dominantColours(bmp: Bitmap): List<Int> {
        val s = Bitmap.createScaledBitmap(bmp, 64, 64, true)
        val px = IntArray(64 * 64).also { s.getPixels(it, 0, 64, 0, 0, 64, 64) }
        val counts = HashMap<Int, IntArray>()
        for (c in px) {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val key = (r / 64) * 16 + (g / 64) * 4 + (b / 64)
            val acc = counts.getOrPut(key) { IntArray(4) }
            acc[0] += r; acc[1] += g; acc[2] += b; acc[3]++
        }
        return counts.values.sortedByDescending { it[3] }.take(5).map { a ->
            (0xFF shl 24) or ((a[0] / a[3]) shl 16) or ((a[1] / a[3]) shl 8) or (a[2] / a[3])
        }
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** Pulls the FSSAI number, dates (→ expiry), MRP and veg mark out of OCR text. */
    /**
     * OCR often reads 0 as O, 1 as I/l, 5 as S, 8 as B inside printed dates ("O1/O6/2O26").
     * Repair those characters only inside date-shaped tokens.
     */
    fun repairDigits(s: String): String =
        Regex("""(?<![A-Za-z])[0-9OoIlSB]{1,2}[./\-][0-9OoIlSB]{1,2}[./\-][0-9OoIlSB]{2,4}(?![A-Za-z])|(?<![A-Za-z])[0-9OoIlSB]{1,2}[./\-][0-9OoIlSB]{4}(?![A-Za-z])""").replace(s) { m ->
            m.value.map { c -> when (c) { 'O', 'o' -> '0'; 'I', 'l' -> '1'; 'S' -> '5'; 'B' -> '8'; else -> c } }.joinToString("")
        }

    fun parseLabel(raw: String): LabelLens {
        val t = repairDigits(raw)
        val lower = t.lowercase(Locale.US)
        val digitsOnly = Regex("""(?<!\d)(\d[\d ]{12,18}\d)(?!\d)""").findAll(t).map { it.value.replace(" ", "") }.filter { it.length == 14 }
        val nearFssai = Regex("""(?i)(fssai|lic\.?\s*no|licen[cs]e)[^0-9]{0,25}(\d[\d ]{12,18}\d)""").find(t)?.groupValues?.get(2)?.replace(" ", "")
        val fssai = (nearFssai?.takeIf { it.length == 14 } ?: digitsOnly.firstOrNull { it[0] == '1' || it[0] == '2' })

        val dates = mutableListOf<Pair<Long, Int>>() // (time, position in text)
        Regex("""(?<!\d)(\d{1,2})[./\- ](\d{1,2})[./\- ](\d{2,4})(?!\d)""").findAll(lower).forEach { m ->
            val (d, mo, y) = m.destructured
            toTime(d.toInt(), mo.toInt(), y.toInt())?.let { dates += it to m.range.first }
        }
        Regex("""(?<![a-z])(${months.joinToString("|")})[a-z]*[ .,/\-']*(\d{2,4})""").findAll(lower).forEach { m ->
            val mo = months.indexOf(m.groupValues[1]) + 1
            toTime(1, mo, m.groupValues[2].toInt(), endOfMonth = true)?.let { dates += it to m.range.first }
        }
        Regex("""(?<!\d)(\d{1,2})[./\-](\d{4})(?!\d)""").findAll(lower).forEach { m ->
            toTime(1, m.groupValues[1].toInt(), m.groupValues[2].toInt(), endOfMonth = true)?.let { dates += it to m.range.first }
        }

        val expKw = Regex("""exp|use by|use before|best before|bb[: ]""").findAll(lower).map { it.range.first }.toList()
        var source = ""
        var expiry: Long? = null
        if (dates.isNotEmpty()) {
            val near = if (expKw.isNotEmpty()) dates.minByOrNull { (_, pos) -> expKw.minOf { kotlin.math.abs(it - pos) } } else null
            if (near != null && expKw.minOf { kotlin.math.abs(it - near.second) } < 40) {
                expiry = near.first; source = "date printed next to 'expiry/best before'"
            } else if (dates.size >= 2) {
                expiry = dates.maxOf { it.first }; source = "latest of ${dates.size} printed dates"
            }
        }
        // "Best before 6 months from manufacture"
        if (expiry == null && dates.isNotEmpty()) {
            Regex("""best before\D{0,20}(\d{1,2})\s*(months|month|days)""").find(lower)?.let { m ->
                val n = m.groupValues[1].toInt()
                val mfg = dates.minOf { it.first }
                expiry = Calendar.getInstance().apply {
                    timeInMillis = mfg
                    if (m.groupValues[2].startsWith("day")) add(Calendar.DAY_OF_YEAR, n) else add(Calendar.MONTH, n)
                }.timeInMillis
                source = "manufacture date + $n ${m.groupValues[2]}"
            }
        }
        val mrp = Regex("""(?i)m\.?r\.?p\.?[^0-9]{0,15}(\d{1,5}(?:[.,]\d{1,2})?)""").find(t)?.groupValues?.get(1)
        val veg = when {
            Regex("""non[- ]?veg""").containsMatchIn(lower) -> false
            Regex("""(?<!non[- ])\bveg(etarian)?\b""").containsMatchIn(lower) -> true
            else -> null
        }
        return LabelLens(fssai, dates.map { it.first }.distinct().sorted(), expiry, source, mrp, veg)
    }

    private fun toTime(d: Int, m: Int, y0: Int, endOfMonth: Boolean = false): Long? {
        val y = if (y0 < 100) 2000 + y0 else y0
        if (m !in 1..12 || y !in 2000..2099 || d !in 1..31) return null
        return Calendar.getInstance().apply {
            set(y, m - 1, 1, 23, 59, 0)
            set(Calendar.DAY_OF_MONTH, if (endOfMonth) getActualMaximum(Calendar.DAY_OF_MONTH) else d.coerceAtMost(getActualMaximum(Calendar.DAY_OF_MONTH)))
        }.timeInMillis
    }
}
