package com.shuddh.lab.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/** Field measurements from the phone's own sensors, plus hourly field weather. */
object FieldKit {
    // ── GPS boundary walk ─────────────────────────────────────────────────────────────────────
    private const val R = 6_371_000.0

    /** Polygon area (m²) of a GPS track, on a local flat projection (fine for farm-sized plots). */
    fun areaM2(pts: List<Pair<Double, Double>>): Double {
        if (pts.size < 3) return 0.0
        val lat0 = pts.map { it.first }.average() * PI / 180
        val xy = pts.map { (la, lo) -> Pair(lo * PI / 180 * R * cos(lat0), la * PI / 180 * R) }
        var s = 0.0
        for (i in xy.indices) { val (x1, y1) = xy[i]; val (x2, y2) = xy[(i + 1) % xy.size]; s += x1 * y2 - x2 * y1 }
        return abs(s) / 2
    }

    fun distM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val lat = (a.first + b.first) / 2 * PI / 180
        val dx = (b.second - a.second) * PI / 180 * R * cos(lat); val dy = (b.first - a.first) * PI / 180 * R
        return sqrt(dx * dx + dy * dy)
    }

    fun perimeterM(pts: List<Pair<Double, Double>>) = if (pts.size < 2) 0.0 else pts.indices.sumOf { distM(pts[it], pts[(it + 1) % pts.size]) }

    const val M2_PER_ACRE = 4046.86
    /** Bigha differs by state; this is the common UP/Bihar/Rajasthan pucca bigha (~0.62 acre). */
    const val ACRES_PER_BIGHA = 0.625

    // ── Accelerometer slope & compass rows ────────────────────────────────────────────────────

    /** Slope of the surface the phone lies on, in percent, from the gravity vector. */
    fun slopePct(gx: Float, gy: Float, gz: Float): Double {
        val h = sqrt((gx * gx + gy * gy).toDouble())
        return 100 * h / abs(gz.toDouble()).coerceAtLeast(0.01)
    }

    fun slopeAdvice(p: Double) = when {
        p < 1 -> "Flat — ideal for paddy and flood/basin irrigation."
        p < 3 -> "Gentle slope — furrows along the contour; drip works well."
        p < 8 -> "Moderate slope — contour bunds or ridges across the slope to stop soil and water running off."
        p < 15 -> "Steep — terraces, grass strips or orchards; avoid flood irrigation."
        else -> "Very steep — trees, fodder grass or agroforestry only."
    }

    /** Compass heading (0 = north) from the rotation-vector azimuth in radians. */
    fun heading(azimuthRad: Float): Int = ((Math.toDegrees(azimuthRad.toDouble()) + 360) % 360).toInt()

    fun rowAdvice(heading: Int): String {
        val ns = minOf(abs(heading - 0), abs(heading - 180), abs(heading - 360))
        return if (ns <= 20) "Rows along north–south: both sides of each row get equal sun — best for tall crops (maize, cotton, sugarcane)."
        else "You're facing ${heading}°. Turn rows towards north–south (±20°) so tall crops don't shade their neighbours all afternoon."
    }

    // ── Light sensor sunlight ─────────────────────────────────────────────────────────────────

    /** Sunlight photon flux from lux (≈ 0.0185 µmol·m⁻²·s⁻¹ per lux for daylight). */
    fun ppfd(lux: Float) = lux * 0.0185

    /** Daily light integral (mol·m⁻²·day⁻¹) if this light lasted ~10 h of a sunny day's profile. */
    fun dli(lux: Float) = ppfd(lux) * 3600 * 10 * 0.64 / 1_000_000

    fun lightAdvice(dli: Double) = when {
        dli >= 30 -> "Full sun — fine for cereals, cotton, sugarcane, dragon fruit."
        dli >= 18 -> "Good sun — vegetables, pulses, fruit trees."
        dli >= 10 -> "Partial shade — leafy greens, turmeric, ginger, coffee, pepper."
        else -> "Shade — mushrooms, nursery, or shade-loving herbs. Re-measure at midday in the open."
    }

    // ── Camera canopy vigour ──────────────────────────────────────────────────────────────────

    data class Canopy(val cover: Double, val vari: Double, val exg: Double) {
        val words get() = when {
            cover < 0.15 -> "Mostly bare soil — young crop or poor stand."
            vari > 0.2 && cover > 0.6 -> "Dense, deep-green canopy — vigorous."
            vari > 0.08 -> "Healthy green canopy."
            vari > 0.0 -> "Pale green — check nitrogen (yellowing older leaves) or water."
            else -> "Yellow/brown canopy — stress, disease or ripening."
        }
    }

    /** Green cover (ExG > 0.05) and VARI = (G − R) / (G + R − B) averaged over plant pixels. */
    fun canopy(px: IntArray): Canopy {
        var green = 0; var n = 0; var variSum = 0.0; var exgSum = 0.0
        var i = 0
        while (i < px.size) {
            val c = px[i]; val r = ((c shr 16) and 0xff) / 255.0; val g = ((c shr 8) and 0xff) / 255.0; val b = (c and 0xff) / 255.0
            val sum = (r + g + b).coerceAtLeast(1e-6)
            val exg = 2 * g / sum - r / sum - b / sum
            n++
            if (exg > 0.05) { green++; val d = g + r - b; if (abs(d) > 0.02) variSum += ((g - r) / d).coerceIn(-1.0, 1.0); exgSum += exg }
            i += 3
        }
        val gN = green.coerceAtLeast(1)
        return Canopy(green / n.toDouble().coerceAtLeast(1.0), variSum / gN, exgSum / gN)
    }

    // ── Hourly field weather: spray window & disease-risk hours ──────────────────────────────

    data class Hour(val time: LocalDateTime, val temp: Double, val rh: Double, val rainProb: Double, val rain: Double, val wind: Double, val soilMoist: Double?)

    data class Now(val hours: List<Hour>, val aqi: Int?, val pm25: Double?, val uv: Double?)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try { c.connectTimeout = 15000; c.readTimeout = 15000; check(c.responseCode == 200); return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    suspend fun now(lat: Double, lon: Double): Now? = withContext(Dispatchers.IO) {
        runCatching {
            val la = "%.2f".format(java.util.Locale.US, lat); val lo = "%.2f".format(java.util.Locale.US, lon)
            val j = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&hourly=temperature_2m,relative_humidity_2m,precipitation_probability,precipitation,wind_speed_10m,soil_moisture_3_to_9cm&forecast_days=3&timezone=auto"))
                .getJSONObject("hourly")
            val t = j.getJSONArray("time")
            fun d(k: String, i: Int) = j.optJSONArray(k)?.let { if (it.isNull(i)) null else it.getDouble(i) }
            val hours = (0 until t.length()).map { i ->
                Hour(LocalDateTime.parse(t.getString(i)), d("temperature_2m", i) ?: 0.0, d("relative_humidity_2m", i) ?: 0.0, d("precipitation_probability", i) ?: 0.0,
                    d("precipitation", i) ?: 0.0, d("wind_speed_10m", i) ?: 0.0, d("soil_moisture_3_to_9cm", i))
            }
            val air = runCatching { JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$la&longitude=$lo&current=us_aqi,pm2_5,uv_index")).getJSONObject("current") }.getOrNull()
            Now(hours, air?.optInt("us_aqi"), air?.optDouble("pm2_5"), air?.optDouble("uv_index"))
        }.getOrNull()
    }

    data class SprayWindow(val start: LocalDateTime, val hours: Int)

    /**
     * Good spraying hours: daylight 6–18 h, wind 3–15 km/h (no drift, no inversion), rain chance
     * < 30% for this and the next 4 hours, temperature < 32 °C.
     */
    fun sprayWindows(h: List<Hour>, from: LocalDateTime = LocalDateTime.now()): List<SprayWindow> {
        val ok = h.indices.map { i ->
            val x = h[i]
            x.time >= from.withMinute(0) && x.time.hour in 6..17 && x.wind in 3.0..15.0 && x.temp < 32 &&
                (i until minOf(h.size, i + 5)).all { h[it].rainProb < 30 && h[it].rain < 0.2 }
        }
        val out = mutableListOf<SprayWindow>()
        var i = 0
        while (i < h.size) {
            if (ok[i]) { var j = i; while (j + 1 < h.size && ok[j + 1]) j++; out += SprayWindow(h[i].time, j - i + 1); i = j + 1 } else i++
        }
        return out
    }

    /** Hours favourable to fungal leaf diseases: RH ≥ 90% at 10–25 °C (late blight/blast pattern). */
    fun blightHours(h: List<Hour>) = h.count { it.rh >= 90 && it.temp in 10.0..25.0 }

    fun diseaseRisk(hours: Int) = when {
        hours >= 22 -> "High fungal-disease risk in the next 3 days — protective spray before the wet spell."
        hours >= 11 -> "Moderate risk — scout leaves for spots; keep a fungicide ready."
        else -> "Low fungal-disease risk."
    }

    // ── Crop calendar & fertiliser ────────────────────────────────────────────────────────────

    /** Recommended N–P–K (kg per acre) for a good crop (ICAR/SAU general recommendations). */
    val npk = mapOf(
        "rice" to Triple(48.0, 24.0, 16.0), "wheat" to Triple(48.0, 24.0, 16.0), "maize" to Triple(48.0, 24.0, 16.0), "cotton" to Triple(40.0, 20.0, 20.0),
        "sugarcane" to Triple(100.0, 32.0, 32.0), "tomato" to Triple(40.0, 24.0, 24.0), "onion" to Triple(40.0, 20.0, 32.0), "potato" to Triple(60.0, 32.0, 40.0),
        "chilli" to Triple(40.0, 24.0, 24.0), "mustard" to Triple(32.0, 16.0, 8.0), "soybean" to Triple(8.0, 24.0, 16.0), "groundnut" to Triple(8.0, 16.0, 20.0),
        "tur" to Triple(8.0, 20.0, 0.0), "turmeric" to Triple(24.0, 24.0, 24.0), "banana" to Triple(80.0, 40.0, 120.0),
    )

    data class Fert(val urea: Double, val dap: Double, val mop: Double)

    /** Converts N-P₂O₅-K₂O need into bags of DAP (18-46-0), urea (46-0-0) and MOP (0-0-60). */
    fun fertiliser(n: Double, p: Double, k: Double): Fert {
        val dap = p / 0.46
        val urea = ((n - dap * 0.18) / 0.46).coerceAtLeast(0.0)
        return Fert(urea, dap, k / 0.60)
    }

    data class Task(val day: Long, val emoji: String, val title: String, val detail: String)

    fun calendar(r: FarmTwin.Result): List<Task> {
        val c = r.crop; val sow = r.plan.sowDay; val ac = r.plan.acres
        fun at(p: Double) = r.days.firstOrNull { it.progress >= p }?.day ?: (sow + (c.days * p).toLong())
        val (n, p, k) = npk[c.key] ?: Triple(30.0, 20.0, 20.0)
        val total = fertiliser(n * ac, p * ac, k * ac)
        val t = mutableListOf(
            Task(sow - 20, "🧪", "Soil test", "Free under Soil Health Card — fertiliser below is a general dose; a test can cut it."),
            Task(sow - 10, "🚜", "Field preparation", "Plough, add 2–4 tonnes FYM/compost per acre, level the field."),
            Task(sow - 1, "🌱", "Seed treatment", "Trichoderma 4 g/kg or Carbendazim 2 g/kg seed${if (c.legume) "; then Rhizobium + PSB culture" else ""}."),
            Task(sow, "🌾", "Sow + basal dose", "DAP ${total.dap.toInt()} kg, MOP ${total.mop.toInt()} kg, urea ${(total.urea / 3).toInt()} kg for ${"%.1f".format(ac)} acre."),
            Task(at(0.2), "💚", "1st urea top-dress", "Urea ${(total.urea / 3).toInt()} kg when the soil is moist; weed first."),
            Task(at(0.4), "💚", "2nd urea top-dress", "Urea ${(total.urea / 3).toInt()} kg before flowering."),
        )
        var d = sow + 14
        while (d < r.harvestDay - 14) { t += Task(d, "🔍", "Scout for pests & disease", "Check 10 plants across the field; use Leaf Doctor on any spotted leaf."); d += 14 }
        r.days.filter { it.progress in 0.4..0.75 && it.waterStress > 0.5 }.firstOrNull()?.let { t += Task(it.day, "💧", "Critical irrigation", "Flowering under water stress — irrigate now (largest yield effect).") }
        t += Task(at(0.45), "🌼", "Flowering", "Avoid spraying insecticides at midday — protects pollinators.")
        t += Task(r.harvestDay, "🧺", "Harvest", "Expected ~${"%.1f".format(r.totalQ)} quintal.")
        val (sell, _) = FarmTwin.bestSellMonth(c, LocalDate.ofEpochDay(r.harvestDay).monthValue)
        t += Task(LocalDate.ofEpochDay(r.harvestDay).let { h -> if (sell == h.monthValue) h else h.withDayOfMonth(1).plusMonths(((sell - h.monthValue + 12) % 12).toLong()).withDayOfMonth(10) }.toEpochDay(),
            "💰", "Sell", "Best month by the price cycle — check today's mandi prices first.")
        return t.sortedBy { it.day }
    }

    /** Local stress weights for gene-trait compatibility: how much each stress costs this farm. */
    fun stressWeights(base: FarmTwin.Result): Map<Scenario, Double> = mapOf(
        Scenario.LIVE to 0.4,
        Scenario.DROUGHT to (1 - base.factors["Water"]!!) + 0.15,
        Scenario.HEAT to (1 - base.factors["Heat"]!!) + 0.15,
        Scenario.FLOOD to (1 - base.factors["Flood"]!!) + 0.1,
        Scenario.LATE to 0.1,
    )

    /** 0–100: expected yield gain from a trait under this farm's own stress mix. */
    fun compatibility(gains: Map<Scenario, Double>, w: Map<Scenario, Double>): Int {
        val tot = w.values.sum().coerceAtLeast(1e-6)
        val g = gains.entries.sumOf { (s, v) -> v * (w[s] ?: 0.0) } / tot
        return (g / 0.25 * 100).toInt().coerceIn(0, 100)
    }

    fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double = Math.toDegrees(atan2(b.second - a.second, b.first - a.first))
}
