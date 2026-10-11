package com.shuddh.lab.core

/**
 * Health hazards of food adulterants, for the verdict screen and the PDF report. Effects are
 * summarised from FSSAI, WHO and published toxicology; severity is for repeated household exposure.
 */
object Hazards {
    enum class Organ(val label: String) { BRAIN("Brain & nerves"), HEART("Heart"), LUNGS("Lungs"), STOMACH("Stomach & gut"), LIVER("Liver"), KIDNEY("Kidneys"), BONES("Bones & growth"), BLOOD("Blood"), SKIN("Skin & eyes") }

    data class Effect(val organ: Organ, val text: String, val severity: Int)   // severity 1 mild … 3 serious

    data class Profile(
        val key: String, val title: String, val emoji: String,
        val effects: List<Effect>,
        val atRisk: List<String>,
        val now: List<String>,
        val longTerm: String,
        val law: String,
    ) {
        val worst get() = effects.maxOfOrNull { it.severity } ?: 0
    }

    private val kids = "Children under 5"; private val preg = "Pregnant women"; private val old = "Elderly"; private val sick = "People with kidney or liver disease"

    val profiles = listOf(
        Profile("water", "Added water", "💧",
            listOf(Effect(Organ.STOMACH, "Water from unsafe sources carries E. coli, cholera, typhoid and hepatitis A", 3),
                Effect(Organ.BONES, "Less protein, calcium and fat per glass — children's growth suffers when milk is their main food", 2),
                Effect(Organ.BLOOD, "Dehydrating diarrhoea in infants from contaminated water", 2)),
            listOf(kids, preg, old),
            listOf("Boil the milk for at least 1 minute before use.", "Don't give it to infants — use a trusted packaged brand meanwhile.", "Re-test tomorrow's milk; two results make a pattern."),
            "Months of watered milk mean children miss up to a third of the protein and calcium their parents think they get.",
            "Selling diluted milk violates FSSA 2006 (sub-standard food). Complain on the FSSAI Food Safety Connect app or to the district Food Safety Officer."),
        Profile("detergent", "Detergent", "🫧",
            listOf(Effect(Organ.STOMACH, "Irritates the stomach and gut lining — nausea, vomiting, diarrhoea", 3),
                Effect(Organ.LIVER, "Surfactants strain the liver with repeated exposure", 2),
                Effect(Organ.KIDNEY, "Can damage kidney tubules over months", 2)),
            listOf(kids, sick),
            listOf("Stop using this milk at once.", "Keep a sample sealed in the fridge as evidence.", "Report the seller — detergent usually means synthetic milk."),
            "Usually a sign of 'synthetic milk' made from oil, urea and detergent — none of milk's nutrition, plus chemical harm.",
            "Unsafe food under FSSA 2006 — punishable with fine and imprisonment."),
        Profile("starch", "Starch", "🌾",
            listOf(Effect(Organ.STOMACH, "Bloating and indigestion; contaminated starch water can carry germs", 1),
                Effect(Organ.BLOOD, "Raises blood sugar — a risk for diabetics who think they drink plain milk", 2)),
            listOf("People with diabetes", kids),
            listOf("Don't use for infants.", "Diabetics should avoid it.", "Starch hides added water — test for water too."),
            "Starch itself is food, but it is added to hide watering, so the milk is both diluted and misleading.",
            "Adulterated food under FSSA 2006."),
        Profile("urea", "Urea", "🧪",
            listOf(Effect(Organ.KIDNEY, "Extra urea overloads the kidneys", 3), Effect(Organ.STOMACH, "Gastric irritation and vomiting", 2), Effect(Organ.BRAIN, "Headache and confusion at high intake", 1)),
            listOf(sick, kids, old),
            listOf("Stop using it.", "Keep a sample as evidence.", "Report the supplier."),
            "Urea is used to fake protein (SNF) in synthetic milk.",
            "Unsafe food under FSSA 2006."),
        Profile("spoiled", "Spoiled milk", "🦠",
            listOf(Effect(Organ.STOMACH, "Salmonella, Listeria and E. coli — vomiting, cramps, diarrhoea, fever", 3),
                Effect(Organ.BLOOD, "Listeria can cross the placenta — dangerous in pregnancy", 3)),
            listOf(preg, kids, old),
            listOf("Throw it away — boiling won't undo souring toxins.", "Clean the container with hot water.", "Store milk below 5 °C and use within 24 h of opening."),
            "Repeated mild food poisoning, and serious infection risk for pregnant women and infants.",
            "If sold spoiled, it's unsafe food under FSSA 2006."),
        Profile("syrup", "Sugar syrup in honey", "🍯",
            listOf(Effect(Organ.BLOOD, "Spikes blood sugar like table sugar — none of honey's benefits", 2), Effect(Organ.LIVER, "High-fructose syrup adds liver fat over time", 1)),
            listOf("People with diabetes"),
            listOf("Don't use it as medicine or for diabetics.", "Buy honey with an FSSAI licence and NMR/C4 test claim."),
            "You pay honey prices for sugar water.",
            "Mis-branded and adulterated food under FSSA 2006."),
        Profile("oil", "Adulterated oil", "🫗",
            listOf(Effect(Organ.HEART, "Argemone oil causes epidemic dropsy — swelling and heart failure", 3), Effect(Organ.LIVER, "Mineral oil and reused oil damage the liver", 2), Effect(Organ.SKIN, "Skin rashes and eye damage (glaucoma) from argemone", 2)),
            listOf(kids, old),
            listOf("Stop cooking with it.", "Keep the bottle and bill as evidence.", "Report to the Food Safety Officer."),
            "Argemone contamination has caused mass poisonings in India.",
            "Unsafe food under FSSA 2006."),
        Profile("reused_oil", "Over-reused frying oil", "🍳",
            listOf(Effect(Organ.HEART, "Trans fats and polar compounds raise heart-disease risk", 2), Effect(Organ.LIVER, "Oxidised oil stresses the liver", 2), Effect(Organ.STOMACH, "Acrolein irritates the gut", 1)),
            listOf(old, "People with heart disease"),
            listOf("Discard oil after 2–3 fries.", "Don't top up old oil with new."),
            "FSSAI caps total polar compounds at 25% — above that, oil must be thrown away.",
            "FSSAI (2017) TPC limit 25% for food businesses."),
        Profile("dye", "Synthetic colour (metanil yellow / lead chromate)", "🟡",
            listOf(Effect(Organ.BRAIN, "Lead damages the developing brain — lower IQ, learning problems", 3), Effect(Organ.BLOOD, "Lead causes anaemia", 2), Effect(Organ.LIVER, "Metanil yellow is toxic to the liver", 2)),
            listOf(kids, preg),
            listOf("Stop using the spice.", "Children exposed for months should get a blood-lead test."),
            "Lead has no safe level for children.",
            "Banned colours under FSSA 2006."),
        Profile("chlorine", "Too much chlorine / bleach", "🧴",
            listOf(Effect(Organ.STOMACH, "Stomach irritation, nausea", 1), Effect(Organ.SKIN, "Dry skin and eye irritation", 1)),
            listOf(kids),
            listOf("Let water stand uncovered for 30 min, or boil it.", "Use 2 drops of bleach per litre — not more."),
            "Over-chlorinated water is unpleasant but rarely dangerous; under-chlorinated water is the bigger risk.",
            "BIS IS 10500: residual chlorine 0.2–1 mg/L."),
        Profile("nitrate", "Nitrate", "⚗️",
            listOf(Effect(Organ.BLOOD, "'Blue baby syndrome' — blood can't carry oxygen in infants", 3)),
            listOf(kids, preg),
            listOf("Don't use this water for infant formula.", "Boiling concentrates nitrate — use RO or another source."),
            "Common in farm wells near fertilised fields.",
            "BIS IS 10500 limit 45 mg/L."),
        Profile("germs", "Germs in water", "🦠",
            listOf(Effect(Organ.STOMACH, "Diarrhoea, cholera, typhoid, hepatitis A", 3)),
            listOf(kids, old, preg),
            listOf("Boil for 1 minute, or use RO / chlorine tablets.", "Give ORS at the first sign of diarrhoea."),
            "Diarrhoea is still a leading cause of death of Indian children under 5.",
            "BIS IS 10500: zero coliforms."),
        Profile("wax", "Wax / polish on fruit", "🍎",
            listOf(Effect(Organ.STOMACH, "Petroleum wax upsets digestion", 1), Effect(Organ.LIVER, "The film can seal pesticide residue onto the peel", 1)),
            listOf(kids),
            listOf("Wash in warm water and rub, or peel."),
            "Food-grade waxes are allowed only when labelled.",
            "FSSAI permits only listed waxes, with labelling."),
        Profile("unknown", "Unknown substance", "❓",
            listOf(Effect(Organ.STOMACH, "Unknown chemicals can cause poisoning", 2)),
            listOf(kids, preg, old),
            listOf("Don't consume it.", "Get it tested at an FSSAI-notified lab.", "Keep a sealed sample."),
            "Phone sensors can tell it isn't normal, but not what it is.",
            "You can request a lab test through your district Food Safety Officer."),
    )

    /** Picks hazard profiles for a verdict from its analyte id, title and headline. */
    fun forOutcome(o: Outcome): List<Profile> {
        if (o.level == Level.SAFE) return emptyList()
        val t = (o.analyteId + " " + o.analyte.en + " " + o.headline).lowercase()
        fun p(k: String) = profiles.first { it.key == k }
        val out = mutableListOf<Profile>()
        if ("detergent" in t || "synthetic" in t) out += p("detergent")
        if ("starch" in t) out += p("starch")
        if ("urea" in t) out += p("urea")
        if ("spoil" in t || "sour" in t || "curdl" in t) out += p("spoiled")
        if ("syrup" in t || ("honey" in t && "pure" !in t)) out += p("syrup")
        if ("argemone" in t || ("oil" in t && "mineral" in t)) out += p("oil")
        if ("tpc" in t || "frying" in t || "reuse" in t) out += p("reused_oil")
        if ("metanil" in t || "chromate" in t || "dye" in t || "colour" in t) out += p("dye")
        if ("chlorine" in t || "bleach" in t) out += p("chlorine")
        if ("nitrate" in t) out += p("nitrate")
        if ("h2s" in t || "germ" in t || "coliform" in t || "bacteria" in t) out += p("germs")
        if ("wax" in t || "polish" in t) out += p("wax")
        if ("not_milk" in t) out += p("unknown")
        if ("purity_mix" in t || "purity_half" in t) out += p("water")
        if ("water" in t && "not_milk" !in t && ("milk" in t || "%" in o.unit || "mix" in t || "half" in t)) out += p("water")
        if ("unknown" in t) out += p("unknown")
        if (out.isEmpty()) out += p(if ("milk" in t && "not_milk" !in t) "water" else "unknown")
        return out.distinctBy { it.key }
    }
}
