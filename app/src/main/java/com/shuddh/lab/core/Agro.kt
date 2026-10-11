package com.shuddh.lab.core

/**
 * Agronomy knowledge for the Farm Twin: crop physiology, soils, breeding traits, exotic crops and
 * integrated farming systems. Values are typical Indian figures (ICAR / FAO-56 / MSP 2025-26),
 * used as starting points; the twin calibrates them to the farmer's own history.
 */
object Agro {
    enum class Season { KHARIF, RABI, ZAID, PERENNIAL }

    data class Crop(
        val key: String, val name: String, val emoji: String,
        val tBase: Double, val tOpt: Double, val tMax: Double,
        /** Growing degree-days (°C·day above tBase, capped at tOpt) from sowing to harvest. */
        val gdd: Double,
        val days: Int,
        /** Seasonal crop water need (mm). */
        val waterMm: Double,
        /** Good-farm yield, quintal per acre. */
        val yieldQ: Double,
        /** Typical farm-gate price ₹/quintal. */
        val price: Double,
        /** Cultivation cost ₹/acre. */
        val cost: Double,
        val sow: Set<Int>,
        val season: Season,
        /** 0 = tolerant … 1 = very sensitive. */
        val droughtSens: Double, val floodSens: Double,
        val soils: Set<String>, val phMin: Double, val phMax: Double,
        val legume: Boolean = false,
        /** Months (1-12) when mandi prices usually peak / crash. */
        val pricePeak: Set<Int> = emptySet(), val priceLow: Set<Int> = emptySet(),
        val storable: Boolean = false,
        val exotic: Boolean = false,
        val note: String = "",
        /** Share of the mature yield harvested in the first year (orchards bear late). */
        val firstYear: Double = 1.0,
    )

    val crops = listOf(
        Crop("rice", "Rice (paddy)", "🌾", 10.0, 30.0, 36.0, 2400.0, 120, 1100.0, 25.0, 2369.0, 26000.0, setOf(6, 7), Season.KHARIF, 0.9, 0.15,
            setOf("alluvial", "clay", "black", "laterite"), 5.0, 8.0, pricePeak = setOf(7, 8, 9), priceLow = setOf(10, 11), storable = true),
        Crop("wheat", "Wheat", "🌾", 4.0, 22.0, 32.0, 1650.0, 125, 450.0, 20.0, 2425.0, 21000.0, setOf(10, 11), Season.RABI, 0.6, 0.7,
            setOf("alluvial", "black", "loam", "clay"), 6.0, 8.0, pricePeak = setOf(11, 12, 1, 2), priceLow = setOf(4, 5), storable = true),
        Crop("maize", "Maize", "🌽", 10.0, 30.0, 38.0, 1900.0, 100, 550.0, 25.0, 2400.0, 19000.0, setOf(6, 7, 1, 2), Season.KHARIF, 0.7, 0.8,
            setOf("alluvial", "loam", "red", "black"), 5.5, 7.8, pricePeak = setOf(6, 7, 8), priceLow = setOf(10, 11), storable = true),
        Crop("cotton", "Cotton", "☁️", 15.0, 30.0, 40.0, 2000.0, 170, 700.0, 10.0, 7710.0, 30000.0, setOf(5, 6), Season.KHARIF, 0.5, 0.8,
            setOf("black", "alluvial", "red"), 6.0, 8.5, pricePeak = setOf(7, 8, 9), priceLow = setOf(11, 12), storable = true),
        Crop("sugarcane", "Sugarcane", "🎋", 12.0, 32.0, 40.0, 4800.0, 330, 1800.0, 400.0, 355.0, 60000.0, setOf(1, 2, 3, 10), Season.PERENNIAL, 0.8, 0.5,
            setOf("alluvial", "black", "loam"), 6.0, 8.0, note = "FRP price; paid by mill"),
        Crop("tomato", "Tomato", "🍅", 10.0, 25.0, 34.0, 1400.0, 110, 500.0, 100.0, 1500.0, 80000.0, setOf(6, 7, 10, 11, 1), Season.RABI, 0.7, 0.9,
            setOf("loam", "red", "alluvial"), 6.0, 7.5, pricePeak = setOf(6, 7, 8, 10, 11), priceLow = setOf(1, 2, 3)),
        Crop("onion", "Onion", "🧅", 7.0, 22.0, 32.0, 1800.0, 130, 450.0, 100.0, 1500.0, 55000.0, setOf(10, 11, 12, 6), Season.RABI, 0.6, 0.9,
            setOf("loam", "alluvial", "black", "red"), 6.0, 7.5, pricePeak = setOf(9, 10, 11, 12), priceLow = setOf(3, 4, 5), storable = true),
        Crop("potato", "Potato", "🥔", 5.0, 20.0, 29.0, 1400.0, 100, 450.0, 100.0, 1200.0, 60000.0, setOf(10, 11), Season.RABI, 0.7, 0.9,
            setOf("loam", "alluvial", "sandy"), 5.0, 7.0, pricePeak = setOf(8, 9, 10, 11), priceLow = setOf(2, 3), storable = true),
        Crop("chilli", "Chilli (dry)", "🌶️", 12.0, 28.0, 35.0, 2300.0, 160, 600.0, 10.0, 12000.0, 70000.0, setOf(6, 7, 8), Season.KHARIF, 0.6, 0.9,
            setOf("black", "red", "loam"), 6.0, 7.5, pricePeak = setOf(7, 8, 9, 10), priceLow = setOf(2, 3, 4), storable = true),
        Crop("mustard", "Mustard", "🌼", 5.0, 20.0, 30.0, 1650.0, 120, 300.0, 8.0, 5950.0, 12000.0, setOf(10, 11), Season.RABI, 0.4, 0.8,
            setOf("alluvial", "loam", "sandy"), 6.0, 8.0, pricePeak = setOf(9, 10, 11, 12), priceLow = setOf(3, 4), storable = true),
        Crop("soybean", "Soybean", "🫘", 10.0, 28.0, 35.0, 1800.0, 100, 500.0, 10.0, 5328.0, 15000.0, setOf(6, 7), Season.KHARIF, 0.6, 0.8,
            setOf("black", "loam", "alluvial"), 6.0, 7.5, legume = true, pricePeak = setOf(6, 7, 8), priceLow = setOf(10, 11), storable = true),
        Crop("groundnut", "Groundnut", "🥜", 12.0, 28.0, 38.0, 1900.0, 115, 500.0, 10.0, 7263.0, 20000.0, setOf(6, 7, 1, 2), Season.KHARIF, 0.5, 0.9,
            setOf("sandy", "red", "loam"), 6.0, 7.5, legume = true, pricePeak = setOf(6, 7, 8), priceLow = setOf(11, 12), storable = true),
        Crop("tur", "Tur / arhar", "🫛", 10.0, 28.0, 36.0, 2600.0, 170, 450.0, 6.0, 8000.0, 15000.0, setOf(6, 7), Season.KHARIF, 0.3, 0.9,
            setOf("black", "red", "loam", "alluvial"), 6.0, 8.0, legume = true, pricePeak = setOf(7, 8, 9, 10), priceLow = setOf(1, 2, 3), storable = true),
        Crop("turmeric", "Turmeric", "🟡", 15.0, 28.0, 40.0, 3600.0, 240, 1200.0, 25.0, 12000.0, 80000.0, setOf(5, 6), Season.KHARIF, 0.7, 0.8,
            setOf("red", "loam", "alluvial", "laterite"), 5.0, 7.5, pricePeak = setOf(9, 10, 11), priceLow = setOf(2, 3, 4), storable = true, note = "dry turmeric yield"),
        Crop("banana", "Banana", "🍌", 14.0, 27.0, 38.0, 4500.0, 330, 1800.0, 250.0, 1500.0, 100000.0, setOf(6, 7, 10), Season.PERENNIAL, 0.9, 0.8,
            setOf("alluvial", "loam", "black", "laterite"), 6.0, 7.5, pricePeak = setOf(8, 9, 10), priceLow = setOf(1, 2)),
        // Exotic / high-value crops a farmer might bring in.
        Crop("dragonfruit", "Dragon fruit", "🐉", 12.0, 30.0, 40.0, 3500.0, 365, 600.0, 40.0, 10000.0, 150000.0, setOf(2, 3, 6, 7), Season.PERENNIAL, 0.2, 0.95,
            setOf("sandy", "red", "laterite", "loam"), 5.5, 7.5, pricePeak = setOf(11, 12, 1, 2), priceLow = setOf(7, 8), exotic = true, note = "first full crop in year 2; cactus — loves heat, hates waterlogging", firstYear = 0.3),
        Crop("avocado", "Avocado", "🥑", 10.0, 24.0, 32.0, 3000.0, 365, 900.0, 30.0, 15000.0, 120000.0, setOf(6, 7), Season.PERENNIAL, 0.7, 0.95,
            setOf("loam", "red", "laterite"), 5.0, 7.0, exotic = true, note = "needs mild climate; Hass fails above ~35 °C; bears from year 3", firstYear = 0.0),
        Crop("blueberry", "Blueberry (low-chill)", "🫐", 7.0, 22.0, 30.0, 1800.0, 365, 700.0, 15.0, 60000.0, 250000.0, setOf(10, 11), Season.PERENNIAL, 0.8, 0.9,
            setOf("sandy", "loam"), 4.5, 5.5, exotic = true, note = "needs acidic soil (pH 4.5–5.5) or pots with cocopeat", firstYear = 0.3),
        Crop("strawberry", "Strawberry", "🍓", 5.0, 20.0, 28.0, 1800.0, 150, 400.0, 40.0, 15000.0, 200000.0, setOf(9, 10), Season.RABI, 0.7, 0.9,
            setOf("loam", "sandy", "red"), 5.5, 6.8, pricePeak = setOf(12, 1), exotic = true, note = "winter crop in plains; mulch + drip"),
        Crop("quinoa", "Quinoa", "🌾", 3.0, 20.0, 32.0, 1500.0, 110, 300.0, 6.0, 9000.0, 15000.0, setOf(10, 11), Season.RABI, 0.2, 0.8,
            setOf("sandy", "loam", "alluvial"), 6.0, 8.5, exotic = true, note = "salt- and drought-hardy grain"),
    )

    fun crop(key: String) = crops.firstOrNull { it.key == key }

    // ── Soils ──────────────────────────────────────────────────────────────────────────────────
    data class Soil(val key: String, val name: String, val awcMm: Double, val ph: Double, val drainage: Double, val note: String)

    /** Available water capacity in the root zone (mm), typical pH, drainage 0..1. */
    val soils = listOf(
        Soil("alluvial", "Alluvial", 150.0, 7.3, 0.6, "Indo-Gangetic plains — fertile, good for most crops"),
        Soil("black", "Black (regur)", 200.0, 7.8, 0.3, "Deccan cotton soil — holds water, cracks when dry"),
        Soil("red", "Red", 100.0, 6.3, 0.75, "South & east — low nitrogen, drains well"),
        Soil("laterite", "Laterite", 90.0, 5.5, 0.8, "High-rainfall uplands — acidic, needs organic matter"),
        Soil("sandy", "Sandy / desert", 60.0, 7.8, 0.95, "Rajasthan & coast — drains fast, needs frequent water"),
        Soil("clay", "Heavy clay", 220.0, 7.0, 0.2, "Low-lying fields — waterlogs, ideal for paddy"),
        Soil("loam", "Loam", 160.0, 6.8, 0.6, "Balanced texture — the easiest soil"),
    )
    fun soil(key: String) = soils.firstOrNull { it.key == key } ?: soils.first()

    /** Texture class from SoilGrids clay / sand percentages and pH. */
    fun soilFrom(clayPct: Double, sandPct: Double, ph: Double): String = when {
        sandPct >= 70 -> "sandy"
        clayPct >= 45 -> if (ph >= 7.4) "black" else "clay"
        clayPct >= 30 && ph >= 7.5 -> "black"
        ph < 5.8 && clayPct >= 20 -> "laterite"
        sandPct >= 50 && ph < 7.0 -> "red"
        clayPct in 15.0..30.0 && sandPct in 25.0..55.0 -> "loam"
        else -> "alluvial"
    }

    // ── Breeding / genome-editing traits ──────────────────────────────────────────────────────
    /**
     * A trait that changes how the crop responds in the twin. [real] lists varieties that already
     * carry it in India (seed you can actually buy); [kind] says how it was made.
     */
    data class Trait(
        val key: String, val name: String, val gene: String, val kind: String, val crops: Set<String>,
        val droughtCut: Double = 0.0, val floodCut: Double = 0.0, val heatPlus: Double = 0.0,
        val daysCut: Int = 0, val yieldPlus: Double = 0.0, val pestCut: Double = 0.0, val saltOk: Boolean = false,
        val real: String, val what: String,
    )

    val traits = listOf(
        Trait("sub1", "Flood survival", "SUB1A", "Marker-assisted breeding", setOf("rice"), floodCut = 0.8,
            real = "Swarna-Sub1, Samba Mahsuri-Sub1, Ciherang-Sub1", what = "Plant stays dormant under water and survives ~14 days fully submerged."),
        Trait("dst", "Drought + salt tolerance", "DST (edited)", "Genome edited (SDN-1, no foreign DNA)", setOf("rice"), droughtCut = 0.35, saltOk = true, yieldPlus = 0.05,
            real = "Pusa DST Rice 1 (ICAR, 2025)", what = "A switched-off DST gene lets leaves close pores faster — less water lost, tolerates saline soil."),
        Trait("gn1a", "More grains, earlier harvest", "Gn1a / CKX2 (edited)", "Genome edited (SDN-1, no foreign DNA)", setOf("rice"), daysCut = 20, yieldPlus = 0.19,
            real = "DRR Dhan 100 'Kamala' (ICAR, 2025)", what = "Edited cytokinin gene: more grains per panicle, ready ~20 days sooner."),
        Trait("bt", "Bollworm resistance", "Cry1Ac + Cry2Ab", "GM (approved in India)", setOf("cotton"), pestCut = 0.6,
            real = "Bollgard-II Bt cotton hybrids", what = "Plant makes a protein toxic to bollworm larvae; pink bollworm has partly adapted — use refuge rows."),
        Trait("ho", "High-oleic oil", "FAD2 mutation", "Mutation breeding", setOf("groundnut"), yieldPlus = 0.0,
            real = "Girnar 4, Girnar 5 (ICAR-DGR)", what = "Healthier oil with longer shelf life — sells at a premium to processors."),
        Trait("lb", "Late-blight resistance", "R genes (RB / Rpi)", "Conventional breeding", setOf("potato"), pestCut = 0.5,
            real = "Kufri Girdhari, Kufri Himalini (CPRI)", what = "Resists the blight that wipes out potato in cool, wet weeks."),
        Trait("tylcv", "Leaf-curl virus resistance", "Ty-2 / Ty-3", "Marker-assisted breeding", setOf("tomato"), pestCut = 0.5,
            real = "Arka Rakshak, Arka Samrat (IIHR)", what = "Triple disease resistance including leaf curl virus spread by whitefly."),
        Trait("qpm", "Quality protein", "opaque-2", "Mutation + breeding", setOf("maize"), yieldPlus = 0.0,
            real = "HQPM-1, Pusa HM-4 Improved", what = "Twice the lysine and tryptophan — better feed and food value."),
        // Design-lab traits: what an edit WOULD do, for research crops without released varieties.
        Trait("heat2", "Heat tolerance +2 °C", "HSP / HSF up-regulation", "Research target", setOf("*"), heatPlus = 2.0,
            real = "No released variety for most crops — ask your KVK / SAU about heat-tolerant lines", what = "Raises the temperature at which flowers abort."),
        Trait("drought", "Drought tolerance", "DREB / ERA1", "Research target", setOf("*"), droughtCut = 0.35,
            real = "Drought-tolerant hybrids exist for maize and rice; edited lines are in trials", what = "Plant keeps growing at lower soil moisture."),
        Trait("short", "Short duration", "Hd / flowering-time genes", "Research target", setOf("*"), daysCut = 15,
            real = "Short-duration varieties exist for rice, tur, mustard", what = "Harvest earlier — fits an extra crop or escapes late-season stress."),
    )

    fun traitsFor(crop: String) = traits.filter { crop in it.crops || "*" in it.crops }

    // ── Integrated farming systems ────────────────────────────────────────────────────────────
    data class Combo(val key: String, val name: String, val emoji: String, val crops: Set<String>, val soils: Set<String>, val needsWater: Boolean,
                     val extraPerAcre: IntRange, val how: List<String>, val why: String)

    val combos = listOf(
        Combo("ricefish", "Rice + fish", "🌾🐟", setOf("rice"), setOf("clay", "alluvial", "black", "laterite"), true, 25000..60000,
            listOf("Dig a 1 m deep trench on 8–10% of the plot as a fish refuge.", "Stock 2,000–3,000 fingerlings/acre (rohu, catla, common carp) 15 days after transplanting.",
                "Keep 10–15 cm water; skip pesticides that kill fish.", "Harvest fish with the paddy or move them to the trench and grow on."),
            "Fish eat weeds and insects and their droppings feed the rice: 2–4 quintal fish per acre and usually higher paddy yield."),
        Combo("riceduck", "Rice + ducks", "🌾🦆", setOf("rice"), setOf("clay", "alluvial", "black", "laterite"), true, 15000..35000,
            listOf("Release 80–100 ducklings/acre 2 weeks after transplanting.", "Remove them at flowering.", "Sell eggs and ducks."),
            "Ducks weed and eat pests — less chemical spray, extra eggs and meat."),
        Combo("maizecowpea", "Maize + cowpea", "🌽🫘", setOf("maize"), setOf("alluvial", "loam", "red", "black"), false, 8000..15000,
            listOf("Sow 2 rows of cowpea between maize rows.", "Cowpea fixes nitrogen — cut urea by a quarter."),
            "Legume adds nitrogen and a second crop of pulses or fodder from the same land."),
        Combo("canegarlic", "Sugarcane + onion/garlic", "🎋🧄", setOf("sugarcane"), setOf("alluvial", "black", "loam"), false, 30000..70000,
            listOf("Plant onion or garlic between autumn cane rows.", "Harvest them before cane canopy closes (~4 months)."),
            "Uses the empty space of young cane — pays for most of the cane's cost."),
        Combo("turtur", "Soybean / groundnut + tur", "🫘🫛", setOf("soybean", "groundnut", "tur"), setOf("black", "red", "loam"), false, 10000..20000,
            listOf("4:2 rows of soybean or groundnut to tur.", "Short crop comes off first; tur continues on stored moisture."),
            "Two pulses or oilseeds from one season with less risk."),
        Combo("orchardturmeric", "Fruit orchard + turmeric", "🍌🟡", setOf("banana", "avocado", "dragonfruit"), setOf("red", "loam", "laterite", "alluvial"), false, 40000..90000,
            listOf("Grow turmeric or ginger in partial shade between young fruit trees."),
            "Shade crops earn while trees are still young."),
        Combo("fishpoultry", "Fish pond + poultry", "🐟🐔", emptySet(), setOf("clay", "alluvial", "black", "laterite", "loam"), true, 60000..150000,
            listOf("Build a poultry shed over or beside a 0.25-acre pond.", "Droppings fertilise the pond — no fish feed needed for plankton feeders."),
            "Waste of one is food for the other; works on low-lying land that floods."),
        Combo("dairyfodder", "Dairy + fodder + biogas", "🐄🌿", emptySet(), setOf("alluvial", "loam", "black", "red", "clay"), false, 50000..120000,
            listOf("Keep 2–3 cows on 0.5 acre of napier / berseem fodder.", "Dung → biogas for cooking → slurry back to fields."),
            "Daily cash from milk, free cooking gas and manure."),
    )
}
