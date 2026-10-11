package com.shuddh.lab.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A farmer's field and what was grown on it before. */
data class Farm(
    val name: String = "My farm",
    val lat: Double = 0.0, val lon: Double = 0.0,
    val state: String = "", val district: String = "",
    val acres: Double = 2.0,
    val soil: String = "alluvial",
    val ph: Double? = null,
    val irrigation: Irrigation = Irrigation.LIMITED,
    val history: List<Past> = emptyList(),
    val plans: List<Plan> = emptyList(),
) {
    val located get() = lat != 0.0 || lon != 0.0

    fun toJson(): JSONObject = JSONObject().put("name", name).put("lat", lat).put("lon", lon).put("state", state).put("district", district)
        .put("acres", acres).put("soil", soil).put("ph", ph ?: JSONObject.NULL).put("irr", irrigation.name)
        .put("hist", JSONArray().apply { history.forEach { put(JSONObject().put("y", it.year).put("c", it.crop).put("q", it.yieldQ)) } })
        .put("plans", JSONArray().apply { plans.forEach { put(JSONObject().put("c", it.crop).put("a", it.acres).put("s", it.sowDay).put("t", JSONArray(it.traits.toList())).put("x", it.combo ?: "")) } })

    companion object {
        fun from(o: JSONObject) = Farm(
            o.optString("name", "My farm"), o.optDouble("lat", 0.0), o.optDouble("lon", 0.0), o.optString("state"), o.optString("district"),
            o.optDouble("acres", 2.0), o.optString("soil", "alluvial"), if (o.isNull("ph")) null else o.optDouble("ph"),
            runCatching { Irrigation.valueOf(o.optString("irr")) }.getOrDefault(Irrigation.LIMITED),
            o.optJSONArray("hist")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { h -> Past(h.optInt("y"), h.optString("c"), h.optDouble("q")) } } } ?: emptyList(),
            o.optJSONArray("plans")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { p ->
                Plan(p.optString("c"), p.optDouble("a"), p.optLong("s"), p.optJSONArray("t")?.let { t -> (0 until t.length()).map { t.getString(it) }.toSet() } ?: emptySet(), p.optString("x").ifBlank { null })
            } } } ?: emptyList(),
        )
    }
}

enum class Irrigation(val label: String) { RAINFED("Rain only"), LIMITED("Some irrigation"), FULL("Assured irrigation") }

/** A past season: year, crop key, yield in quintal per acre (0 = unknown). */
data class Past(val year: Int, val crop: String, val yieldQ: Double)

/** One planned plot in the twin. [sowDay] is LocalDate.toEpochDay(). */
data class Plan(val crop: String, val acres: Double, val sowDay: Long, val traits: Set<String> = emptySet(), val combo: String? = null)

/** Daily weather. */
data class Wx(val day: Long, val tmax: Double, val tmin: Double, val rain: Double, val et0: Double, val source: Char)

enum class Scenario(val label: String, val emoji: String) {
    LIVE("Forecast + last year", "🌤️"), DROUGHT("Weak monsoon", "🏜️"), HEAT("Heatwave", "🔥"), FLOOD("Flood spell", "🌊"), LATE("Late monsoon", "⏳"),
}

object FarmTwin {
    // ── Weather ───────────────────────────────────────────────────────────────────────────────

    /** Typical Indian monthly climate, north plains vs south peninsula; blended by latitude. */
    private val northTmax = doubleArrayOf(21.0, 24.0, 30.0, 36.0, 40.0, 39.0, 34.0, 33.0, 33.0, 32.0, 27.0, 22.0)
    private val northTmin = doubleArrayOf(7.0, 10.0, 15.0, 21.0, 26.0, 28.0, 27.0, 26.0, 24.0, 18.0, 12.0, 8.0)
    private val northRain = doubleArrayOf(20.0, 20.0, 15.0, 10.0, 20.0, 70.0, 230.0, 250.0, 130.0, 15.0, 5.0, 10.0)
    private val southTmax = doubleArrayOf(30.0, 32.0, 35.0, 36.0, 36.0, 32.0, 30.0, 30.0, 31.0, 31.0, 30.0, 29.0)
    private val southTmin = doubleArrayOf(18.0, 19.0, 21.0, 24.0, 25.0, 23.0, 22.0, 22.0, 22.0, 22.0, 20.0, 18.0)
    private val southRain = doubleArrayOf(5.0, 5.0, 10.0, 30.0, 60.0, 120.0, 150.0, 140.0, 160.0, 150.0, 60.0, 15.0)

    /** Hargreaves reference evapotranspiration (mm/day) with a mid-latitude radiation term. */
    fun et0(tmax: Double, tmin: Double, ra: Double = 13.5) = (0.0023 * ra * ((tmax + tmin) / 2 + 17.8) * sqrt(max(0.0, tmax - tmin))).coerceIn(0.5, 10.0)

    /** Climatological day for when neither forecast nor last year's record exists. */
    fun climate(lat: Double, day: Long): Wx {
        val d = LocalDate.ofEpochDay(day)
        val m = d.monthValue - 1
        val w = ((lat - 15.0) / 13.0).coerceIn(0.0, 1.0)  // 0 = south, 1 = north
        fun mix(a: DoubleArray, b: DoubleArray) = a[m] * w + b[m] * (1 - w)
        val tmax = mix(northTmax, southTmax); val tmin = mix(northTmin, southTmin)
        val monthRain = mix(northRain, southRain)
        // Deterministic rain days: wetter months rain more often.
        val chance = (monthRain / 250.0).coerceIn(0.03, 0.75)
        val r = java.util.Random(day * 31 + (lat * 100).toLong())
        val rain = if (r.nextDouble() < chance) monthRain / (30 * chance) * (0.4 + 1.2 * r.nextDouble()) else 0.0
        return Wx(day, tmax + (r.nextDouble() - 0.5) * 2, tmin + (r.nextDouble() - 0.5) * 2, rain, et0(tmax, tmin), 'c')
    }

    /** Season weather: forecast where available, else the same day last year, else climatology. */
    fun series(lat: Double, from: Long, days: Int, forecast: Map<Long, Wx>, lastYear: Map<Long, Wx>): List<Wx> = (0 until days).map { i ->
        val d = from + i
        forecast[d] ?: lastYear[d - 365]?.copy(day = d, source = 'h') ?: lastYear[d - 730]?.copy(day = d, source = 'h') ?: climate(lat, d)
    }

    /** Applies a what-if scenario to the weather. */
    fun scenario(wx: List<Wx>, s: Scenario): List<Wx> = when (s) {
        Scenario.LIVE -> wx
        Scenario.DROUGHT -> wx.map { it.copy(rain = it.rain * 0.5, tmax = it.tmax + 1.0, et0 = it.et0 * 1.08) }
        Scenario.HEAT -> wx.map { it.copy(tmax = it.tmax + 3.0, tmin = it.tmin + 2.0, et0 = it.et0 * 1.15) }
        Scenario.FLOOD -> wx.mapIndexed { i, w -> if (i in setOf(wx.size / 4, wx.size / 4 + 1, wx.size / 2, wx.size / 2 + 1, wx.size / 2 + 2)) w.copy(rain = w.rain + 140.0) else w }
        Scenario.LATE -> wx.mapIndexed { i, w -> if (i < 35) w.copy(rain = w.rain * 0.15, tmax = w.tmax + 1.5) else w }
    }

    // ── Simulation ────────────────────────────────────────────────────────────────────────────

    data class DayState(val day: Long, val progress: Double, val soilWater: Double, val waterStress: Double, val hot: Boolean, val flooded: Boolean, val rain: Double, val tmax: Double, val irrigated: Double)

    data class Result(
        val crop: Agro.Crop, val plan: Plan, val scenario: Scenario,
        val days: List<DayState>, val harvestDay: Long, val matured: Boolean,
        val yieldQ: Double, val factors: Map<String, Double>,
        val irrigationMm: Double, val heatDays: Int, val floodDays: Int, val dryDays: Int,
        val price: Double, val surge: Double, val revenue: Double, val cost: Double,
        /** For orchards: steady yearly yield (q/acre) and profit once the trees bear fully. */
        val matureQ: Double = yieldQ, val matureProfit: Double = revenue - cost,
    ) {
        val orchard get() = crop.firstYear < 1.0
        val profit get() = revenue - cost
        val profitPerAcre get() = if (plan.acres > 0) profit / plan.acres else profit
        val totalQ get() = yieldQ * plan.acres
        /** 0 (safe) … 1 (very risky). */
        val risk get() = (1 - factors.values.fold(1.0) { a, b -> a * b }).coerceIn(0.0, 1.0)
    }

    fun stage(p: Double) = when {
        p < 0.08 -> "Germination"; p < 0.42 -> "Vegetative"; p < 0.65 -> "Flowering"; p < 0.92 -> "Grain / fruit fill"; else -> "Ready to harvest"
    }

    private val pestLoss = mapOf("cotton" to 0.2, "tomato" to 0.15, "potato" to 0.12, "chilli" to 0.15, "rice" to 0.08, "banana" to 0.08)

    /**
     * Daily crop growth: degree-day development, a soil-water bucket, heat and flood stress.
     * Yield = calibrated potential × water × heat × flood × soil × rotation × pest × traits.
     */
    fun simulate(farm: Farm, plan: Plan, weather: List<Wx>, s: Scenario = Scenario.LIVE, newsAdj: Double = 1.0): Result {
        val crop = Agro.crop(plan.crop) ?: Agro.crops.first()
        val traits = plan.traits.mapNotNull { k -> Agro.traits.firstOrNull { it.key == k } }
        val soil = Agro.soil(farm.soil)
        val tMax = crop.tMax + traits.sumOf { it.heatPlus }
        val gddNeed = crop.gdd * (1 - traits.sumOf { it.daysCut } / crop.days.toDouble()).coerceAtLeast(0.5)
        val droughtSens = crop.droughtSens * (1 - traits.sumOf { it.droughtCut }).coerceIn(0.1, 1.0)
        val floodSens = crop.floodSens * (1 - traits.sumOf { it.floodCut }).coerceIn(0.05, 1.0)
        val wx = scenario(weather, s)
        var gdd = 0.0; var bucket = soil.awcMm * 0.6; var irrigation = 0.0
        var heatFlower = 0; var heatOther = 0; var flood = 0; var dry = 0
        var wsSum = 0.0; var wsW = 0.0
        val states = ArrayList<DayState>()
        val limit = (crop.days * 1.8).toInt().coerceAtMost(wx.size)
        var matured = false
        for (i in 0 until limit) {
            val w = wx[i]
            val p = gdd / gddNeed
            val tMean = (w.tmax + w.tmin) / 2
            gdd += (min(tMean, crop.tOpt) - crop.tBase).coerceAtLeast(0.0)
            val kc = when { p < 0.15 -> 0.5; p < 0.4 -> 0.5 + (p - 0.15) * 2.4; p < 0.8 -> 1.1; else -> 0.75 }
            val etc = kc * w.et0 * (crop.waterMm / 650.0).coerceIn(0.6, 1.6)
            bucket += w.rain
            // Irrigation policy.
            var irr = 0.0
            when (farm.irrigation) {
                Irrigation.FULL -> if (bucket < soil.awcMm * 0.55) irr = soil.awcMm * 0.8 - bucket
                Irrigation.LIMITED -> if (bucket < soil.awcMm * 0.3 && irrigation < 250) irr = min(60.0, soil.awcMm * 0.5 - bucket)
                Irrigation.RAINFED -> {}
            }
            if (crop.key == "rice" && farm.irrigation != Irrigation.RAINFED && bucket < soil.awcMm * 0.8) irr = max(irr, soil.awcMm * 0.9 - bucket)
            irr = irr.coerceAtLeast(0.0); bucket += irr; irrigation += irr
            bucket -= etc
            // Waterlogging: very heavy rain on poorly drained soil; rice tolerates standing water.
            val flooded = w.rain >= 100 || (w.rain >= 50 && bucket > soil.awcMm * 1.2 && soil.drainage < 0.5)
            if (flooded && crop.key != "rice") flood++
            if (crop.key == "rice" && w.rain >= 150) flood++
            bucket = bucket.coerceIn(0.0, soil.awcMm * (if (crop.key == "rice") 1.5 else 1.0) + (1 - soil.drainage) * 40)
            val ws = (bucket / (soil.awcMm * 0.5)).coerceIn(0.0, 1.0)
            if (ws < 0.4) dry++
            val weight = if (p in 0.4..0.7) 2.0 else 1.0
            wsSum += ws * weight; wsW += weight
            val hot = w.tmax > tMax
            if (hot) { if (p in 0.4..0.7) heatFlower++ else heatOther++ }
            states += DayState(w.day, min(1.0, gdd / gddNeed), (bucket / soil.awcMm).coerceIn(0.0, 1.5), 1 - ws, hot, flooded, w.rain, w.tmax, irr)
            if (gdd >= gddNeed) { matured = true; break }
        }
        val prog = states.lastOrNull()?.progress ?: 0.0
        val wsAvg = if (wsW > 0) wsSum / wsW else 1.0
        val fWater = (1 - droughtSens * (1 - wsAvg) * 1.2).coerceIn(0.1, 1.0)
        val fHeat = (1 - 0.06 * heatFlower - 0.012 * heatOther).coerceIn(0.3, 1.0)
        val fFlood = (1 - floodSens * 0.12 * flood).coerceIn(0.15, 1.0)
        var fSoil = if (farm.soil in crop.soils) 1.0 else 0.8
        val ph = farm.ph ?: soil.ph
        if (ph < crop.phMin) fSoil -= 0.1 * (crop.phMin - ph)
        if (ph > crop.phMax) fSoil -= 0.1 * (ph - crop.phMax)
        if (traits.any { it.saltOk } && ph > 8.0) fSoil = max(fSoil, 0.95)
        fSoil = fSoil.coerceIn(0.4, 1.0)
        val last = farm.history.maxByOrNull { it.year }
        val fRot = when {
            last == null -> 1.0
            last.crop == crop.key -> 0.92
            !crop.legume && Agro.crop(last.crop)?.legume == true -> 1.06
            else -> 1.0
        }
        val fPest = 1 - (pestLoss[crop.key] ?: 0.05) * (1 - traits.sumOf { it.pestCut }.coerceIn(0.0, 0.9))
        val fTrait = 1 + traits.sumOf { it.yieldPlus }
        val fDone = if (matured) 1.0 else prog * prog
        val calib = calibration(farm, crop)
        val matureQ = crop.yieldQ * calib * fWater * fHeat * fFlood * fSoil * fRot * fPest * fTrait * fDone
        val yieldQ = matureQ * crop.firstYear
        val harvest = states.lastOrNull()?.day ?: plan.sowDay
        val (price, surge) = harvestPrice(crop, LocalDate.ofEpochDay(harvest).monthValue, s, newsAdj)
        val cost = crop.cost * plan.acres + irrigation * 15 * plan.acres  // ~₹15 per mm·acre pumping
        val factors = linkedMapOf("Water" to fWater, "Heat" to fHeat, "Flood" to fFlood, "Soil" to fSoil, "Rotation" to min(1.0, fRot), "Pests" to fPest, "Maturity" to fDone)
        // Orchards: year 1 carries the planting cost; later years cost ~40% (upkeep, picking).
        val matureProfit = matureQ * plan.acres * price - crop.cost * 0.4 * plan.acres - irrigation * 15 * plan.acres
        return Result(crop, plan, s, states, harvest, matured, yieldQ, factors, irrigation, heatFlower + heatOther, flood, dry, price, surge, yieldQ * plan.acres * price, cost, matureQ, matureProfit)
    }

    /** The farmer's own past yields pull the potential towards reality (the "twin" calibration). */
    fun calibration(farm: Farm, crop: Agro.Crop): Double {
        val past = farm.history.filter { it.crop == crop.key && it.yieldQ > 0 }
        if (past.isEmpty()) return 1.0
        val avg = past.map { it.yieldQ }.average()
        // A typical season reaches ~85% of potential; weight history more as seasons accumulate.
        val ratio = (avg / (crop.yieldQ * 0.85)).coerceIn(0.5, 1.4)
        val w = min(0.8, 0.35 * past.size)
        return 1 + (ratio - 1) * w
    }

    /** Seasonal price at harvest, region-wide supply shock from the scenario and a news nudge. */
    fun harvestPrice(crop: Agro.Crop, month: Int, s: Scenario, newsAdj: Double = 1.0): Pair<Double, Double> {
        val seasonal = seasonal(crop, month)
        val shock = when (s) {
            Scenario.DROUGHT -> 1 + 0.18 * crop.droughtSens
            Scenario.FLOOD -> 1 + 0.12 * crop.floodSens
            Scenario.HEAT -> 1.08
            Scenario.LATE -> 1.05
            Scenario.LIVE -> 1.0
        }
        var p = crop.price * seasonal * shock * newsAdj.coerceIn(0.85, 1.15)
        // Government procurement holds paddy and wheat at MSP even in the harvest glut.
        if (crop.key in setOf("rice", "wheat")) p = max(p, crop.price)
        return p to (p / crop.price - 1)
    }

    fun seasonal(crop: Agro.Crop, month: Int) = when (month) { in crop.pricePeak -> 1.18; in crop.priceLow -> 0.86; else -> 1.0 }

    /** Best month to sell a storable crop: seasonal price minus ~1.2% storage loss per month held. */
    fun bestSellMonth(crop: Agro.Crop, harvestMonth: Int): Pair<Int, Double> =
        (0..if (crop.storable) 6 else 0).map { k ->
            val m = (harvestMonth - 1 + k) % 12 + 1
            m to seasonal(crop, m) * (1 - 0.012 * k)
        }.maxBy { it.second }

    // ── Planning ──────────────────────────────────────────────────────────────────────────────

    /** Next sowing date for [crop] on or after [today]: the 10th of the first allowed month. */
    fun nextSow(crop: Agro.Crop, today: LocalDate): LocalDate {
        for (k in 0..12) {
            val d = today.plusMonths(k.toLong()).withDayOfMonth(10)
            if (d.monthValue in crop.sow && !d.isBefore(today.minusDays(20))) return if (d.isBefore(today)) today else d
        }
        return today
    }

    data class Pick(val result: Result, val score: Double)

    /** Ranks crops for this farm: profit adjusted for risk, using each crop's next sowing window. */
    fun rank(farm: Farm, wxFor: (Long, Int) -> List<Wx>, s: Scenario, today: LocalDate = LocalDate.now(), exotic: Boolean = false, horizonMonths: Int = 4): List<Pick> =
        Agro.crops.filter { exotic || !it.exotic }.mapNotNull { c ->
            val sow = nextSow(c, today)
            if (sow.isAfter(today.plusMonths(horizonMonths.toLong()))) return@mapNotNull null
            val plan = Plan(c.key, 1.0, sow.toEpochDay())
            val r = simulate(farm, plan, wxFor(sow.toEpochDay(), (c.days * 1.8).toInt()), s)
            // Orchards are judged on a 3-year average so a slow start isn't hidden or over-punished.
            val perYear = if (r.orchard) (r.profit + 2 * r.matureProfit) / 3 / plan.acres else r.profitPerAcre
            Pick(r, perYear * (1 - 0.5 * r.risk))
        }.sortedByDescending { it.score }

    /** Tries sowing 2 weeks earlier/later; returns the best day and its yield. */
    fun bestSowing(farm: Farm, plan: Plan, wxFor: (Long, Int) -> List<Wx>, s: Scenario): Pair<Long, Double> {
        val c = Agro.crop(plan.crop) ?: return plan.sowDay to 0.0
        return listOf(-14L, 0L, 14L, 28L).map { off ->
            val p = plan.copy(sowDay = plan.sowDay + off)
            p.sowDay to simulate(farm, p, wxFor(p.sowDay, (c.days * 1.8).toInt()), s).yieldQ
        }.maxBy { it.second }
    }

    /** Plain-language advice from a simulation run. */
    fun insights(farm: Farm, r: Result, best: Pair<Long, Double>?): List<String> {
        val out = mutableListOf<String>()
        val c = r.crop
        if (r.factors["Water"]!! < 0.85) {
            val flower = r.days.firstOrNull { it.progress >= 0.4 && it.waterStress > 0.5 }
            out += if (flower != null) "💧 Water stress expected around ${fmtDay(flower.day)} (${stage(flower.progress).lowercase()}). One irrigation then protects ~${((1 - r.factors["Water"]!!) * 60).toInt()}% of the loss."
            else "💧 The crop runs dry in places — mulch, or a drip line, keeps soil moisture up."
        }
        if (r.heatDays > 3) out += "🔥 ${r.heatDays} days above ${c.tMax.toInt()} °C during the crop. Sow earlier, or choose a heat-tolerant variety (see Trait Lab)."
        if (r.floodDays > 0) out += if (c.key == "rice") "🌊 Heavy-rain spells ahead — a Sub1 variety survives up to 2 weeks under water." else "🌊 Waterlogging risk — make raised beds and clear field drains before the rains."
        val ph = farm.ph ?: Agro.soil(farm.soil).ph
        if (ph < c.phMin) out += "🧪 Soil is acidic (pH ${"%.1f".format(ph)}): add 1–2 quintal lime per acre a month before sowing."
        if (ph > c.phMax) out += "🧪 Soil is alkaline (pH ${"%.1f".format(ph)}): gypsum and farmyard manure bring it down."
        if (farm.soil !in c.soils) out += "🌱 ${c.name} isn't ideal for ${Agro.soil(farm.soil).name.lowercase()} soil — expect ~20% less. Check the crop ranking for better fits."
        val last = farm.history.maxByOrNull { it.year }
        if (last?.crop == c.key) out += "🔁 Same crop as last season — rotating with a pulse (tur, soybean, groundnut) cuts pests and adds nitrogen."
        best?.let { (d, y) -> if (d != r.plan.sowDay && y > r.yieldQ * 1.04) out += "📅 Sowing on ${fmtDay(d)} instead gives ~${"%.1f".format(y)} q/acre (+${((y / r.yieldQ - 1) * 100).toInt()}%)." }
        val month = LocalDate.ofEpochDay(r.harvestDay).monthValue
        val (sell, idx) = bestSellMonth(c, month)
        val gain = idx / seasonal(c, month) - 1
        if (c.storable && sell != month && gain > 0.04) out += "🏪 Don't sell in ${PantrySmart.monthName(month)} — store and sell in ${PantrySmart.monthName(sell)} for ~${(gain * 100).toInt()}% more (after storage losses)."
        if (r.surge >= 0.12) out += "📈 Surge window: harvest lands when ${c.name.lowercase()} prices usually run ${(r.surge * 100).toInt()}% above normal."
        Agro.combos.filter { c.key in it.crops && farm.soil in it.soils && (!it.needsWater || farm.irrigation != Irrigation.RAINFED) }.take(1).forEach {
            out += "${it.emoji} Try ${it.name.lowercase()}: ${it.why} Extra ₹${it.extraPerAcre.first / 1000}k–${it.extraPerAcre.last / 1000}k per acre."
        }
        if (out.isEmpty()) out += "✅ Conditions look good — keep to the plan and re-run the twin after each forecast update."
        return out
    }

    // ── Explaining & checking the twin ────────────────────────────────────────────────────────

    /** Step-by-step yield: best possible → after each limiting factor. Pairs of (label, q/acre). */
    fun waterfall(farm: Farm, r: Result): List<Pair<String, Double>> {
        val traits = r.plan.traits.mapNotNull { k -> Agro.traits.firstOrNull { it.key == k } }
        var y = r.crop.yieldQ * calibration(farm, r.crop) * (1 + traits.sumOf { it.yieldPlus })
        val out = mutableListOf("Best possible" to y)
        r.factors.forEach { (k, f) -> if (f < 0.999) { y *= f; out += k to y } }
        if (r.orchard) { y *= r.crop.firstYear; out += "Young trees (yr 1)" to y }
        out += "Your yield" to y
        return out
    }

    /** Weather variants for an uncertainty range: this year, the year before, climate, wetter/drier, hotter. */
    fun variants(lat: Double, from: Long, n: Int, forecast: Map<Long, Wx>, lastYear: Map<Long, Wx>): List<List<Wx>> {
        val base = series(lat, from, n, forecast, lastYear)
        val prev = (0 until n).map { i -> val d = from + i; forecast[d] ?: lastYear[d - 730]?.copy(day = d) ?: lastYear[d - 365]?.copy(day = d) ?: climate(lat, d) }
        val clim = (0 until n).map { i -> forecast[from + i] ?: climate(lat, from + i) }
        return listOf(base, prev, clim,
            base.map { it.copy(rain = it.rain * 0.75) }, base.map { it.copy(rain = it.rain * 1.25) },
            base.map { it.copy(tmax = it.tmax + 1.2, tmin = it.tmin + 1.0) }, prev.map { it.copy(rain = it.rain * 0.85, tmax = it.tmax + 0.6) })
    }

    data class Range(val low: Double, val mid: Double, val high: Double, val profitLow: Double, val profitMid: Double, val profitHigh: Double)

    /** P10 / P50 / P90 of yield and profit over the weather variants. */
    fun range(farm: Farm, plan: Plan, forecast: Map<Long, Wx>, lastYear: Map<Long, Wx>, s: Scenario, newsAdj: Double = 1.0): Range {
        val c = Agro.crop(plan.crop) ?: Agro.crops.first()
        val runs = variants(farm.lat, plan.sowDay, (c.days * 1.8).toInt(), forecast, lastYear).map { simulate(farm, plan, it, s, newsAdj) }
        val ys = runs.map { it.yieldQ }.sorted(); val ps = runs.map { it.profit }.sorted()
        fun q(x: List<Double>, p: Double) = x[((x.size - 1) * p).toInt()]
        return Range(q(ys, 0.1), q(ys, 0.5), q(ys, 0.9), q(ps, 0.1), q(ps, 0.5), q(ps, 0.9))
    }

    /** Where the money goes (₹ for the plan's acres) — typical shares of the cultivation cost. */
    fun costs(r: Result): List<Pair<String, Double>> {
        val c = r.crop.cost * r.plan.acres
        return listOf("Labour" to c * 0.35, "Fertiliser" to c * 0.20, "Machinery" to c * 0.18, "Seed" to c * 0.12, "Plant protection" to c * 0.15, "Irrigation" to r.cost - c)
            .filter { it.second > 1 }
    }

    /** 0..1 risks for the radar: water, heat, flood, pests, price (harvest-glut exposure). */
    fun risks(r: Result): List<Pair<String, Double>> = listOf(
        "Water" to (1 - r.factors["Water"]!!), "Heat" to (1 - r.factors["Heat"]!!), "Flood" to (1 - r.factors["Flood"]!!),
        "Pests" to (1 - r.factors["Pests"]!!) * 3, "Price" to (if (r.surge < 0) -r.surge * 3 else 0.05),
        "Soil" to (1 - r.factors["Soil"]!!) * 2,
    ).map { (k, v) -> k to v.coerceIn(0.0, 1.0) }

    data class Check(val year: Int, val actual: Double, val twin: Double)

    /**
     * Back-test: re-run each past season the farmer entered with that year's real weather (the archive),
     * WITHOUT calibrating on it, and compare with what they actually harvested.
     */
    fun backtest(farm: Farm, lastYear: Map<Long, Wx>): List<Pair<Agro.Crop, Check>> {
        if (lastYear.isEmpty()) return emptyList()
        val first = lastYear.keys.min(); val lastDay = lastYear.keys.max()
        val raw = farm.copy(history = emptyList())
        return farm.history.filter { it.yieldQ > 0 }.mapNotNull { p ->
            val c = Agro.crop(p.crop) ?: return@mapNotNull null
            val m = c.sow.filter { when (c.season) { Agro.Season.KHARIF -> it in 5..8; Agro.Season.RABI -> it in 9..12; else -> true } }.minOrNull() ?: c.sow.min()
            val sow = LocalDate.of(p.year, m, 10).toEpochDay()
            val n = (c.days * 1.8).toInt()
            if (sow < first || sow + c.days > lastDay) return@mapNotNull null
            val wx = (0 until n).map { i -> lastYear[sow + i] ?: climate(farm.lat, sow + i) }
            c to Check(p.year, p.yieldQ, simulate(raw, Plan(c.key, 1.0, sow), wx).yieldQ)
        }
    }

    fun fmtDay(d: Long): String = LocalDate.ofEpochDay(d).let { "${it.dayOfMonth} ${PantrySmart.monthName(it.monthValue)}" }

    // ── News signal ───────────────────────────────────────────────────────────────────────────

    private val up = Regex("\\b(shortage|deficit|rally|surge|soar|spike|hike|record high|demand|damage|losses?|crop loss|export (opens|allowed)|msp (hike|raised))\\b")
    private val down = Regex("\\b(bumper|glut|crash|plunge|slump|fall|record (crop|production|output)|surplus|imports?|export ban|ban on exports?|duty)\\b")

    /** Headline sentiment → price nudge (−2…+2 → ±10%). */
    fun newsScore(headlines: List<String>): Int = headlines.sumOf { h ->
        val t = h.lowercase(); (if (up.containsMatchIn(t)) 1 else 0) - (if (down.containsMatchIn(t)) 1 else 0)
    }.coerceIn(-2, 2)

    fun newsAdj(score: Int) = 1 + 0.05 * score
}

/** Network helpers for the twin. Only coordinates (rounded to ~1 km) and crop names leave the phone. */
object FarmData {
    private fun get(url: String, timeout: Int = 15000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeout; c.readTimeout = timeout
            c.setRequestProperty("User-Agent", "Shuddh-FarmTwin/1.0")
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun r2(x: Double) = "%.2f".format(java.util.Locale.US, x)

    fun parseDaily(json: String, source: Char): Map<Long, Wx> {
        val d = JSONObject(json).getJSONObject("daily")
        val t = d.getJSONArray("time"); val hi = d.getJSONArray("temperature_2m_max"); val lo = d.getJSONArray("temperature_2m_min")
        val pr = d.getJSONArray("precipitation_sum"); val et = d.optJSONArray("et0_fao_evapotranspiration")
        val out = HashMap<Long, Wx>()
        for (i in 0 until t.length()) {
            if (hi.isNull(i) || lo.isNull(i)) continue
            val day = LocalDate.parse(t.getString(i)).toEpochDay()
            val tx = hi.getDouble(i); val tn = lo.getDouble(i)
            out[day] = Wx(day, tx, tn, if (pr.isNull(i)) 0.0 else pr.getDouble(i), et?.takeIf { !it.isNull(i) }?.getDouble(i) ?: FarmTwin.et0(tx, tn), source)
        }
        return out
    }

    /** 16-day forecast and the last 365 days (used as "a typical year") from Open-Meteo, cached. */
    suspend fun weather(ctx: Context, lat: Double, lon: Double, force: Boolean = false): Pair<Map<Long, Wx>, Map<Long, Wx>> = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "farm").apply { mkdirs() }
        val fc = File(dir, "fc_${r2(lat)}_${r2(lon)}.json"); val hist = File(dir, "hist_${r2(lat)}_${r2(lon)}.json")
        val vars = "temperature_2m_max,temperature_2m_min,precipitation_sum,et0_fao_evapotranspiration"
        if (force || !fc.exists() || System.currentTimeMillis() - fc.lastModified() > 6 * 3600_000L) runCatching {
            fc.writeText(get("https://api.open-meteo.com/v1/forecast?latitude=${r2(lat)}&longitude=${r2(lon)}&daily=$vars&forecast_days=16&timezone=auto"))
        }
        if (force || !hist.exists() || System.currentTimeMillis() - hist.lastModified() > 7 * 86400_000L) runCatching {
            val end = LocalDate.now().minusDays(6); val start = end.minusDays(729)
            hist.writeText(get("https://archive-api.open-meteo.com/v1/archive?latitude=${r2(lat)}&longitude=${r2(lon)}&start_date=$start&end_date=$end&daily=$vars&timezone=auto", 25000))
        }
        val f = runCatching { parseDaily(fc.readText(), 'f') }.getOrDefault(emptyMap())
        val h = runCatching { parseDaily(hist.readText(), 'h') }.getOrDefault(emptyMap())
        f to h
    }

    /** Soil texture and pH from ISRIC SoilGrids (0–30 cm), or null if the service is unreachable. */
    suspend fun soil(lat: Double, lon: Double): Triple<String, Double, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(get("https://rest.isric.org/soilgrids/v2.0/properties/query?lon=${r2(lon)}&lat=${r2(lat)}&property=clay&property=sand&property=phh2o&depth=0-5cm&depth=5-15cm&depth=15-30cm&value=mean", 25000))
            val layers = o.getJSONObject("properties").getJSONArray("layers")
            val v = HashMap<String, Double>()
            for (i in 0 until layers.length()) {
                val l = layers.getJSONObject(i); val depths = l.getJSONArray("depths")
                val means = (0 until depths.length()).mapNotNull { depths.getJSONObject(it).getJSONObject("values").optDouble("mean").takeIf { m -> !m.isNaN() } }
                if (means.isNotEmpty()) v[l.getString("name")] = means.average() / 10.0  // g/kg → %, pH×10 → pH
            }
            val clay = v["clay"] ?: return@runCatching null; val sand = v["sand"] ?: return@runCatching null; val ph = v["phh2o"] ?: 7.0
            Triple(Agro.soilFrom(clay, sand, ph), ph, "clay ${clay.toInt()}% · sand ${sand.toInt()}% · pH ${"%.1f".format(ph)}")
        }.getOrNull()
    }

    /** WRB reference soil group → the farmer-facing Indian soil type used by the twin. */
    fun soilFromWrb(wrb: String): String = when (wrb.lowercase().removeSuffix("s")) {
        "vertisol" -> "black"
        "fluvisol", "cambisol", "solonchak", "solonetz" -> "alluvial"
        "luvisol", "lixisol", "nitisol", "leptosol" -> "red"
        "acrisol", "ferralsol", "plinthosol", "alisol", "umbrisol" -> "laterite"
        "arenosol", "calcisol", "gypsisol", "regosol" -> "sandy"
        "gleysol", "planosol", "histosol", "stagnosol" -> "clay"
        else -> "loam"
    }

    /** Dominant soil by state (NBSS&LUP soil map), used when the global soil map is unreachable. */
    fun soilForState(state: String): String? {
        val s = state.lowercase()
        return when {
            listOf("punjab", "haryana", "uttar pradesh", "bihar", "west bengal", "assam", "delhi", "uttarakhand", "tripura").any { it in s } -> "alluvial"
            listOf("maharashtra", "madhya pradesh", "gujarat").any { it in s } -> "black"
            listOf("karnataka", "tamil nadu", "andhra", "telangana", "odisha", "jharkhand", "chhattisgarh").any { it in s } -> "red"
            listOf("kerala", "goa", "meghalaya", "manipur", "mizoram", "nagaland", "arunachal").any { it in s } -> "laterite"
            "rajasthan" in s -> "sandy"
            else -> null
        }
    }

    data class SoilInfo(val key: String, val ph: Double?, val source: String)

    /** Soil for a point: texture from SoilGrids if it answers, else its WRB class, else the state map. */
    suspend fun soilAuto(lat: Double, lon: Double, state: String): SoilInfo? {
        soil(lat, lon)?.let { return SoilInfo(it.first, it.second, "Global soil map (ISRIC): ${it.third}") }
        val wrb = withContext(Dispatchers.IO) {
            runCatching { JSONObject(get("https://rest.isric.org/soilgrids/v2.0/classification/query?lon=${r2(lon)}&lat=${r2(lat)}&number_classes=3", 30000)) }.getOrNull()
        }
        wrb?.optString("wrb_class_name")?.takeIf { it.isNotBlank() }?.let { name ->
            val p = wrb.optJSONArray("wrb_class_probability")?.optJSONArray(0)?.optInt(1)
            val k = soilFromWrb(name)
            return SoilInfo(k, null, "Global soil map (ISRIC): $name${p?.let { " ($it% likely)" } ?: ""} → ${Agro.soil(k).name}")
        }
        return soilForState(state)?.let { SoilInfo(it, null, "Regional soil map for $state") }
    }

    data class Place(val village: String, val district: String, val state: String)

    /** Village / district / state from OpenStreetMap (Nominatim), free and keyless. */
    suspend fun place(lat: Double, lon: Double): Place? = withContext(Dispatchers.IO) {
        runCatching {
            val a = JSONObject(get("https://nominatim.openstreetmap.org/reverse?lat=${r2x(lat)}&lon=${r2x(lon)}&format=json&zoom=14&accept-language=en")).getJSONObject("address")
            Place(listOf("village", "town", "city", "suburb", "hamlet", "county").firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotBlank() } } ?: "",
                a.optString("state_district").ifBlank { a.optString("county") }.removeSuffix(" District"), a.optString("state"))
        }.getOrNull()
    }

    private fun r2x(x: Double) = "%.4f".format(java.util.Locale.US, x)

    data class Current(val temp: Double, val rh: Double, val rain: Double, val cloud: Double, val wind: Double, val windDir: Double, val gust: Double, val code: Int, val rain3d: Double)

    /** Live weather at the farm: temperature, humidity, cloud, wind speed/direction/gusts, rain now and next 3 days. */
    suspend fun current(lat: Double, lon: Double): Current? = withContext(Dispatchers.IO) {
        runCatching {
            val j = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=${r2(lat)}&longitude=${r2(lon)}&current=temperature_2m,relative_humidity_2m,precipitation,cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m,weather_code&daily=precipitation_sum&forecast_days=3&timezone=auto"))
            val c = j.getJSONObject("current"); val d = j.getJSONObject("daily").getJSONArray("precipitation_sum")
            Current(c.optDouble("temperature_2m"), c.optDouble("relative_humidity_2m"), c.optDouble("precipitation"), c.optDouble("cloud_cover"), c.optDouble("wind_speed_10m"),
                c.optDouble("wind_direction_10m"), c.optDouble("wind_gusts_10m"), c.optInt("weather_code"), (0 until d.length()).sumOf { if (d.isNull(it)) 0.0 else d.getDouble(it) })
        }.onFailure { android.util.Log.w("FarmTwin", "live weather failed: $it") }.getOrNull()
    }

    /** Plain advice from live weather — spraying, irrigation, heat. */
    fun advice(c: Current): List<String> = buildList {
        add(if (c.wind > 15 || c.gust > 25) "🌬 Too windy to spray now (${c.wind.toInt()} km/h) — drift wastes chemical." else if (c.rain > 0.2) "🌧 Raining — don't spray or apply urea now." else "✅ Good time to spray: light wind (${c.wind.toInt()} km/h), dry.")
        add(if (c.rain3d >= 20) "💧 ${c.rain3d.toInt()} mm rain expected in 3 days — skip irrigation and clear field drains." else if (c.rain3d < 2) "💧 No rain in the next 3 days — plan irrigation." else "💧 Light rain (${c.rain3d.toInt()} mm) coming — irrigate only sandy fields.")
        if (c.temp >= 38) add("🔥 ${c.temp.toInt()} °C — irrigate in the evening; avoid fertiliser and spraying at midday.")
        if (c.rh >= 90 && c.temp in 10.0..25.0) add("🍄 Humid and cool — fungal disease weather. Scout leaves with Leaf Doctor.")
    }

    data class Mandi(val market: String, val district: String, val state: String, val modal: Double, val min: Double, val max: Double, val date: String)

    /** Today's mandi prices (AGMARKNET via data.gov.in). Needs internet; returns empty on failure. */
    suspend fun mandi(commodity: String, state: String, apiKey: String = "579b464db66ec23bdd000001cdd3946e44ce4aad7209ff7b23ac571b"): List<Mandi> = withContext(Dispatchers.IO) {
        runCatching {
            val q = "https://api.data.gov.in/resource/9ef84268-d588-465a-a308-a864a43d0070?api-key=$apiKey&format=json&limit=50" +
                "&filters%5Bcommodity%5D=" + URLEncoder.encode(commodity, "UTF-8") + (if (state.isNotBlank()) "&filters%5Bstate.keyword%5D=" + URLEncoder.encode(state, "UTF-8") else "")
            val a = JSONObject(get(q, 20000)).getJSONArray("records")
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Mandi(it.optString("market"), it.optString("district"), it.optString("state"), it.optDouble("modal_price"), it.optDouble("min_price"), it.optDouble("max_price"), it.optString("arrival_date"))
            }.filter { !it.modal.isNaN() && it.modal > 0 }.sortedByDescending { it.modal }
        }.getOrDefault(emptyList())
    }

    /** AGMARKNET commodity names for our crop keys. */
    val mandiName = mapOf(
        "rice" to "Paddy(Dhan)(Common)", "wheat" to "Wheat", "maize" to "Maize", "cotton" to "Cotton", "sugarcane" to "Sugarcane", "tomato" to "Tomato",
        "onion" to "Onion", "potato" to "Potato", "chilli" to "Dry Chillies", "mustard" to "Mustard", "soybean" to "Soyabean", "groundnut" to "Groundnut",
        "tur" to "Arhar (Tur/Red Gram)(Whole)", "turmeric" to "Turmeric", "banana" to "Banana", "dragonfruit" to "Dragon fruit", "strawberry" to "Strawberry",
    )
}

/** Saves the farm on the phone. */
class FarmStore(ctx: Context) {
    private val file = File(ctx.filesDir, "farm.json")
    var farm: Farm = runCatching { if (file.exists()) Farm.from(JSONObject(file.readText())) else Farm() }.getOrDefault(Farm())
        private set

    fun save(f: Farm) { farm = f; runCatching { file.writeText(f.toJson().toString()) } }
}
