package com.shuddh.lab.core

/**
 * Central government farm schemes (figures as published by the ministries; states add their own
 * top-ups). Eligibility is a first check from the farm profile — the portal makes the final call.
 */
object Schemes {
    data class Scheme(
        val key: String, val name: String, val emoji: String, val benefit: String, val who: String,
        val docs: List<String>, val how: String, val url: String,
        val fits: (Farm, Int) -> Boolean,
        val deadline: String = "",
    )

    val all = listOf(
        Scheme("pmkisan", "PM-KISAN", "💰", "₹6,000 a year in three ₹2,000 instalments, straight to your bank.",
            "Farmer families owning cultivable land. Not for income-tax payers, government employees or pensioners above ₹10,000/month.",
            listOf("Aadhaar (with e-KYC)", "Land record (khatauni / 7-12)", "Bank passbook linked to Aadhaar"),
            "Self-register on the portal or at a CSC; finish e-KYC with OTP.", "https://pmkisan.gov.in", { f, _ -> f.acres > 0 }),
        Scheme("pmfby", "PM Fasal Bima Yojana", "🛡️", "Crop insurance: you pay only 2% (kharif), 1.5% (rabi) or 5% (horticulture/commercial) of the sum insured.",
            "All farmers growing notified crops, including tenants and sharecroppers.",
            listOf("Aadhaar", "Land record or tenancy agreement", "Bank passbook", "Sowing declaration"),
            "Through your bank, CSC or the portal before the season cut-off.", "https://pmfby.gov.in", { _, _ -> true },
            deadline = "Usually 31 July (kharif) and 31 December (rabi)."),
        Scheme("kcc", "Kisan Credit Card", "💳", "Crop loan at 7%; pay on time and it falls to 4%. Covers animal husbandry and fisheries too.",
            "Farmers, tenants, sharecroppers, SHGs; dairy and fish farmers.",
            listOf("Aadhaar", "Land record", "Passport photo", "Bank account"),
            "Apply at any bank branch (one-page form) or through PM-KISAN's KCC link.", "https://www.myscheme.gov.in/schemes/kcc", { _, _ -> true }),
        Scheme("shc", "Soil Health Card", "🧪", "Free soil test with nutrient-by-nutrient fertiliser advice for your plot.",
            "All farmers.", listOf("Aadhaar", "Plot details"),
            "Ask the agriculture office / KVK to sample your field.", "https://soilhealth.dac.gov.in", { _, _ -> true }),
        Scheme("pmksy", "PMKSY – Per Drop More Crop", "💧", "Drip / sprinkler subsidy: 55% for small & marginal farmers, 45% for others.",
            "Farmers with a water source who want micro-irrigation.",
            listOf("Aadhaar", "Land record", "Water source proof", "Bank passbook", "Quotation from a registered supplier"),
            "Apply on your state's micro-irrigation portal or horticulture/agriculture office.", "https://pmksy.gov.in", { f, _ -> f.irrigation != Irrigation.FULL || f.acres <= 5 }),
        Scheme("kusum", "PM-KUSUM (solar pump)", "☀️", "Solar pump: 30% central + 30% state subsidy, 30% bank loan — you pay about 10%.",
            "Individual farmers; diesel-pump users get priority.",
            listOf("Aadhaar", "Land record", "Bank passbook", "Self-declaration of no existing solar pump"),
            "Apply on your state renewable-energy agency portal.", "https://pmkusum.mnre.gov.in", { f, _ -> f.irrigation != Irrigation.RAINFED }),
        Scheme("enam", "e-NAM", "🏪", "Sell online to buyers across mandis — more bidders, transparent price, payment to bank.",
            "Farmers selling in an e-NAM-linked APMC.", listOf("Aadhaar", "Bank passbook", "Mobile number"),
            "Register on the app/portal or at the mandi gate.", "https://enam.gov.in", { _, _ -> true }),
        Scheme("midh", "MIDH (horticulture)", "🍉", "Subsidy (typically 40–50% of cost, capped per hectare) for new orchards, dragon fruit, polyhouses, cold storage.",
            "Farmers planting fruit, vegetables, flowers or spices.", listOf("Aadhaar", "Land record", "Project estimate", "Bank passbook"),
            "District horticulture office.", "https://midh.gov.in", { f, _ -> f.plans.any { Agro.crop(it.crop)?.let { c -> c.exotic || c.season == Agro.Season.PERENNIAL || c.key in setOf("tomato", "onion", "potato", "chilli", "turmeric") } == true } }),
        Scheme("pmmsy", "PM Matsya Sampada Yojana", "🐟", "Fish ponds and integrated rice–fish: 40% subsidy (60% for SC/ST/women).",
            "Fish farmers and farmers adding fish to paddy.", listOf("Aadhaar", "Land/pond record", "Project report", "Bank passbook"),
            "District fisheries office or the PMMSY portal.", "https://pmmsy.dof.gov.in", { f, _ -> f.plans.any { it.crop == "rice" || it.combo == "ricefish" } || f.soil == "clay" }),
        Scheme("nadcp", "NADCP free vaccination", "🐄", "Free FMD and brucellosis vaccination for cattle and buffalo, door to door.",
            "All cattle and buffalo owners.", listOf("Animal ear-tag (Pashu Aadhaar) — tagged free at vaccination"),
            "Contact the local veterinary hospital / Pashu Sakhi.", "https://dahd.gov.in", { _, herd -> herd > 0 }),
        Scheme("pmkmy", "PM Kisan Maan-Dhan (pension)", "👴", "₹3,000 a month pension after 60; you pay ₹55–200/month, the government matches it.",
            "Small & marginal farmers (up to 2 hectares ≈ 5 acres) aged 18–40.", listOf("Aadhaar", "Savings account / PM-KISAN account"),
            "Enrol at a CSC.", "https://maandhan.in", { f, _ -> f.acres <= 4.94 }),
        Scheme("aif", "Agriculture Infrastructure Fund", "🏚️", "3% interest subvention on loans up to ₹2 crore for warehouses, cold storage, sorting units.",
            "Farmers, FPOs, cooperatives, agri-entrepreneurs.", listOf("Project report", "KYC", "Land documents"),
            "Apply on the AIF portal; the bank appraises the project.", "https://agriinfra.dac.gov.in", { f, _ -> f.acres >= 5 }),
    )

    /** Pre-filled application sheet from what the twin knows (the farmer adds personal IDs). */
    fun formText(s: Scheme, f: Farm, farmer: String, phone: String): String = buildString {
        appendLine("Application helper — ${s.name}")
        appendLine("Applicant: ${farmer.ifBlank { "________" }}   Mobile: ${phone.ifBlank { "________" }}")
        appendLine("Village/District: ${f.district.ifBlank { "________" }}, ${f.state.ifBlank { "________" }}")
        appendLine("Land: ${"%.2f".format(f.acres)} acres (${"%.2f".format(f.acres * 0.4047)} ha) · Soil: ${Agro.soil(f.soil).name} · Irrigation: ${f.irrigation.label}")
        f.plans.firstOrNull()?.let { p -> Agro.crop(p.crop)?.let { appendLine("Crop this season: ${it.name}, sowing ${FarmTwin.fmtDay(p.sowDay)}") } }
        if (f.located) appendLine("Plot GPS: ${"%.5f".format(f.lat)}, ${"%.5f".format(f.lon)}")
        appendLine()
        appendLine("Benefit: ${s.benefit}")
        if (s.deadline.isNotBlank()) appendLine("Deadline: ${s.deadline}")
        appendLine("Documents to carry:")
        s.docs.forEach { appendLine("  ☐ $it") }
        appendLine("How: ${s.how}")
        appendLine("Official site: ${s.url}")
    }
}
