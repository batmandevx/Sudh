package com.shuddh.lab.core

/**
 * Ask Shuddh — an offline assistant that only answers from evidence on this phone:
 * the scan log, vendor memory, community alerts and a fixed fact sheet. If nothing on
 * the phone supports an answer, it says so instead of guessing.
 *
 * Understands English, Hindi (Devanagari) and Hinglish keywords.
 */
object Assistant {
    data class Answer(val text: String, val spoken: String = text, val grounded: Boolean = true, val source: String = "")

    private fun has(q: String, vararg words: String) = words.any { q.contains(it) }

    internal val facts = listOf(
        listOf("wax", "polish", "waxed", "shiny apple") to Txt(
            "Fruit wax: food-grade waxes (carnauba, shellac, beeswax) are allowed in India only when labelled. Petroleum or mineral-oil wax is not food-grade — it can upset digestion and seals pesticide residue onto the peel. Rinse in warm water and rub, soak in baking-soda water, or peel. Use Fruit Shine Check to test with the flash.",
            "फलों पर वैक्स: खाद्य-ग्रेड वैक्स केवल लेबल के साथ अनुमत है। पेट्रोलियम वैक्स पाचन बिगाड़ सकता है और कीटनाशक को छिलके पर सील कर देता है। गुनगुने पानी में रगड़कर धोएँ या छिलका उतारें।",
        ),
        listOf("nitrate", "नाइट्रेट") to Txt(
            "Nitrate above 45 mg/L (BIS) can cause 'blue baby syndrome' in infants because it reduces the blood's ability to carry oxygen. Boiling does NOT remove it — it concentrates it. Use RO or another source for infant formula.",
            "45 mg/L से ज़्यादा नाइट्रेट शिशुओं में 'ब्लू बेबी सिंड्रोम' कर सकता है। उबालने से नाइट्रेट नहीं हटता, बल्कि बढ़ता है। शिशु के दूध के लिए RO या दूसरा स्रोत इस्तेमाल करें।",
        ),
        listOf("chlorine", "क्लोरीन") to Txt(
            "Free chlorine of 0.2–1.0 mg/L means tap water is disinfected. Below 0.2 germs may survive; above 4–5 mg/L it irritates and tastes strongly. Let high-chlorine water stand uncovered for an hour.",
            "0.2 से 1.0 mg/L क्लोरीन का मतलब है पानी कीटाणुरहित है। 0.2 से कम पर कीटाणु बच सकते हैं; ज़्यादा होने पर पानी को एक घंटा खुला रखें।",
        ),
        listOf("detergent", "डिटर्जेंट", "sabun", "साबुन") to Txt(
            "Detergent is added to watered or synthetic milk to make it look frothy and white. It can cause stomach upset and long-term gut damage. FSSAI requires milk to be free of detergent.",
            "पानी मिले या नकली दूध को झागदार दिखाने के लिए डिटर्जेंट मिलाया जाता है। इससे पेट खराब होता है। FSSAI के अनुसार दूध में डिटर्जेंट नहीं होना चाहिए।",
        ),
        listOf("urea", "यूरिया") to Txt(
            "Urea is added to raise the apparent protein (SNF) of diluted milk. Excess urea burdens the kidneys. Milk naturally has a little; the DMAB test flags extra.",
            "पतले दूध में प्रोटीन ज़्यादा दिखाने के लिए यूरिया मिलाया जाता है। इससे किडनी पर बोझ पड़ता है।",
        ),
        listOf("starch", "स्टार्च") to Txt(
            "Starch thickens watered milk to fool the lactometer. A drop of iodine turns starchy milk blue-black — Shuddh Spectrum measures that colour.",
            "पानी मिले दूध को गाढ़ा करने के लिए स्टार्च मिलाया जाता है। आयोडीन डालने पर ऐसा दूध नीला-काला हो जाता है।",
        ),
        listOf("arsenic", "आर्सेनिक") to Txt(
            "Arsenic in groundwater (Bengal, Bihar, UP, Assam belts) above 10 µg/L causes skin lesions and cancers over years. It has no taste or smell — only a test reveals it.",
            "भूजल में 10 µg/L से ज़्यादा आर्सेनिक सालों में त्वचा रोग और कैंसर करता है। इसका कोई स्वाद या गंध नहीं होती।",
        ),
        listOf("fluoride", "फ्लोराइड") to Txt(
            "Fluoride above 1.5 mg/L causes dental and skeletal fluorosis — mottled teeth and stiff joints. Common in parts of Rajasthan, Telangana, Karnataka.",
            "1.5 mg/L से ज़्यादा फ्लोराइड से दाँतों पर धब्बे और हड्डियों में अकड़न होती है।",
        ),
        listOf("honey", "शहद", "syrup", "सिरप") to Txt(
            "Fake honey is diluted with sugar or rice syrup. Pure honey is fructose-rich and rotates polarised light one way; cane-sugar syrup rotates it the other. Shuddh Polar measures that rotation.",
            "नकली शहद में चीनी या चावल का सिरप मिलाया जाता है। शुद्ध शहद ध्रुवित प्रकाश को एक दिशा में घुमाता है; Shuddh Polar यही मापता है।",
        ),
        listOf("aflatoxin", "fungus", "फफूंद", "mould", "mold") to Txt(
            "Aflatoxin comes from mould on damp peanuts, maize and chillies. It damages the liver and is invisible. Keep grain dry — Shuddh Nami checks storage moisture.",
            "एफ्लाटॉक्सिन नम मूंगफली, मक्का और मिर्च पर फफूंद से बनता है और लिवर को नुकसान करता है। अनाज सूखा रखें — Shuddh Nami नमी जाँचता है।",
        ),
        listOf("steel", "स्टील", "utensil", "बर्तन", "magnet") to Txt(
            "Food-grade 304/316 stainless steel is non-magnetic. Cheaper ferritic grades and plated iron are magnetic and can rust or leach. Shuddh Magneto checks this with the phone's compass sensor.",
            "फूड-ग्रेड 304/316 स्टील चुंबकीय नहीं होता। सस्ता स्टील या लोहा चुंबकीय होता है। Shuddh Magneto फ़ोन के कम्पास से यह जाँचता है।",
        ),
    )

    fun ask(q0: String, store: Store, community: CommunityStore, lang: Lang): Answer {
        val q = q0.lowercase().trim()
        val hi = lang == Lang.HI
        val rs = store.records
        if (q.isBlank()) return Answer("Ask me about your scans, a vendor, or what a contaminant does.", grounded = false)

        // 0. Greetings, thanks and "what can you do".
        if (Regex("^(hi|hello|hey|namaste|namaskar|नमस्ते|हेलो|good (morning|evening|afternoon))\\b").containsMatchIn(q) || has(q, "how are you", "kaise ho", "कैसे हो")) {
            val s = store.kitchenScore()
            return Answer(
                if (hi) "नमस्ते! मैं ठीक हूँ और आपकी रसोई पर नज़र रख रहा हूँ। फ़ोन पर ${rs.size} जाँच हैं" + (s?.let { ", इस हफ्ते का स्कोर ${it.first}/100।" } ?: "।") + " पूछिए — कोई विक्रेता, स्कोर, या कोई मिलावट।"
                else "Namaste! I'm doing well and watching over your kitchen. There are ${rs.size} scans on this phone" +
                    (s?.let { " and this week's score is ${it.first}/100." } ?: ".") + " Ask me about a vendor, your score, or any contaminant.",
                source = "${rs.size} scans",
            )
        }
        if (has(q, "thank", "dhanyavad", "धन्यवाद", "shukriya", "शुक्रिया")) {
            return Answer(if (hi) "आपका स्वागत है। शुद्ध खाइए, स्वस्थ रहिए!" else "You're welcome. Eat pure, stay well!", grounded = true, source = "—")
        }
        if (has(q, "what can you do", "help", "madad", "मदद", "who are you", "kaun ho", "कौन हो")) {
            return Answer(
                "I'm Shuddh's on-device assistant. I can tell you your kitchen score, what failed most, any vendor's track record, your last scan, area alerts, " +
                    "which instrument to use for milk, honey, water, fruit, walls or utensils, and why contaminants like nitrate, arsenic or detergent are harmful. " +
                    "Everything stays on this phone.",
                source = "Shuddh capabilities",
            )
        }
        if (has(q, "mesh", "bluetooth", "nearby", "पास")) {
            return Answer("Shuddh Mesh lets phones share alerts and messages over Bluetooth with no internet. Open Hive → Shuddh Mesh to join.", source = "Shuddh features")
        }
        if (has(q, "moist", "damp", "seelan", "सीलन", "नमी", "nami")) {
            return Answer(
                if (hi) "नमी जाँचने के लिए Shuddh Nami खोलें — फ़ोन का स्पीकर आवाज़ भेजता है और माइक उसकी गूँज सुनता है। मिट्टी, दीवार, अनाज और कपड़े के लिए।"
                else "Use Shuddh Nami: the speaker sends chirps and the mic listens to the echo off the surface. Works for soil, walls, stored grain and laundry.",
                source = "Shuddh instrument guide",
            )
        }

        // 1. A vendor named in the question.
        store.vendors().firstOrNull { q.contains(it.lowercase()) }?.let { v ->
            val m = store.vendorMemory(v)
            val base = m.sentence()
            val extra = if (m.failures > 0) " Last failure: ${m.lastFailure?.let { stamp(it) }}." else " No failures on record."
            return Answer(base + extra, source = "${m.total} scans of $v")
        }

        // 2. Facts about a contaminant.
        if (has(q, "what is", "why", "kya hai", "क्या है", "क्यों", "kyon", "danger", "खतरा", "khatra", "harm", "नुकसान", "about")) {
            facts.firstOrNull { (keys, _) -> keys.any { q.contains(it) } }?.let { (_, t) ->
                return Answer(t.get(if (hi) Lang.HI else Lang.EN), source = "Shuddh fact sheet (BIS IS 10500 / FSSAI)")
            }
        }

        // 3. Kitchen score / summary.
        if (has(q, "score", "health", "स्वास्थ्य", "swasthya", "summary", "week", "हफ्ते", "hafte", "kaisa", "कैसा", "how am i", "how is my")) {
            val s = store.kitchenScore() ?: return Answer(
                if (hi) "इस हफ्ते कोई जाँच नहीं हुई। पहले कोई टेस्ट चलाएँ।" else "No scans in the last 7 days, so I have no score to report yet.", grounded = false,
            )
            val d = Insights.digest(rs.toList()) ?: ""
            return Answer(
                if (hi) "आपकी रसोई का स्कोर ${s.first}/100 है। $d" else "Your kitchen health score is ${s.first}/100. $d",
                source = "last 7 days of scans",
            )
        }

        // 4. Worst / weakest item.
        if (has(q, "worst", "weakest", "kharab", "खराब", "sabse", "सबसे", "problem", "समस्या", "fail")) {
            val worst = Insights.byTest(rs.toList()).firstOrNull { it.second.unsafe > 0 }
                ?: return Answer(if (hi) "अब तक कोई जाँच असुरक्षित नहीं आई।" else "Nothing has failed so far — every scan on record passed.")
            val t = worst.second
            return Answer(
                if (hi) "सबसे कमज़ोर: ${worst.first} — ${t.unsafe} बार असुरक्षित, कुल ${t.total} जाँच।"
                else "Your weakest item is ${worst.first}: ${t.unsafe} unsafe out of ${t.total} scans.",
                source = "${rs.size} scans",
            )
        }

        // 5. Last scan.
        if (has(q, "last", "latest", "recent", "pichla", "पिछला", "aakhri", "आखिरी")) {
            val r = rs.lastOrNull() ?: return Answer(if (hi) "अभी कोई रिकॉर्ड नहीं है।" else "There are no scans on this phone yet.", grounded = false)
            return Answer(
                "${r.analyte}: ${r.value?.let { "${fmt(it)} ${r.unit}" } ?: "—"}, ${r.level.name}" +
                    (if (r.confirmed) " (confirmed)" else "") + " — ${stamp(r.time)}" + (if (r.vendor.isNotBlank()) ", ${r.vendor}" else "") + ".",
                source = "record #${r.id}",
            )
        }

        // 6. Counts.
        if (has(q, "how many", "kitne", "कितने", "count", "number of")) {
            val unsafe = rs.count { it.level == Level.UNSAFE }
            return Answer(
                if (hi) "कुल ${rs.size} जाँच, जिनमें $unsafe असुरक्षित और ${rs.count { it.confirmed }} पुष्ट।"
                else "${rs.size} scans in total: $unsafe unsafe, ${rs.count { it.confirmed }} confirmed by a second scan.",
            )
        }

        // 7. Monsoon / season.
        if (has(q, "monsoon", "rain", "baarish", "बारिश", "season", "मौसम")) {
            return Answer(Insights.monsoonNote(rs.toList()) ?: "I need at least 5 scans inside and outside the monsoon months to compare seasons.",
                grounded = Insights.monsoonNote(rs.toList()) != null)
        }

        // 8. Area / community.
        if (has(q, "area", "ward", "ilaka", "इलाका", "mohalla", "मोहल्ला", "community", "nearby", "alert")) {
            val board = Insights.areaBoard(rs.toList(), community.items)
            val batches = community.batchAlerts()
            if (board.isEmpty() && batches.isEmpty()) return Answer("No area data yet. Tag scans with your locality or import community QR alerts.", grounded = false)
            val top = board.firstOrNull()
            return Answer(
                (top?.let { "Highest failure rate: ${it.area}, ${it.fails} of ${it.total}. " } ?: "") +
                    (batches.firstOrNull()?.let { "Batch alert: ${it.first} flagged by ${it.second} reports." } ?: ""),
                source = "${community.items.size} community reports",
            )
        }

        // 9. Which instrument to use.
        if (has(q, "how to test", "how do i test", "kaise", "कैसे", "check", "jaanch", "जाँच")) {
            val tip = when {
                has(q, "milk", "doodh", "दूध") -> "Milk: Float (lactometer) for added water, then Spectrum for detergent, starch and urea."
                has(q, "honey", "shahad", "शहद") -> "Honey: Polar for sugar syrup, NIR for added water."
                has(q, "water", "paani", "पानी") -> "Water: Spectrum or Strips for chlorine, nitrate, iron, fluoride, arsenic; Hawa in turbidity mode for cloudiness."
                has(q, "coconut", "nariyal", "नारियल", "watermelon", "tarbooz") -> "Fruit: Echo — tap it near the mic."
                has(q, "wall", "soil", "grain", "deewar", "मिट्टी", "अनाज") -> "Moisture: Nami — the phone's speaker and mic act as sonar."
                has(q, "steel", "utensil", "बर्तन") -> "Utensils: Magneto — the compass sensor checks if steel is magnetic."
                has(q, "air", "hawa", "हवा", "smoke") -> "Air: Hawa — flash beam scatter in a dark box."
                else -> null
            }
            if (tip != null) return Answer(tip, source = "Shuddh instrument guide")
        }

        // 10. A contaminant mentioned without a question word.
        facts.firstOrNull { (keys, _) -> keys.any { q.contains(it) } }?.let { (_, t) ->
            return Answer(t.get(if (hi) Lang.HI else Lang.EN), source = "Shuddh fact sheet")
        }

        return Answer(
            if (hi) "मेरे पास इसका कोई रिकॉर्ड नहीं है। आप किसी विक्रेता, स्कोर, या किसी मिलावट के बारे में पूछ सकते हैं।"
            else "I don't have anything on this phone that answers that. Try asking about a vendor, your score, your last scan, or what a contaminant does.",
            grounded = false,
        )
    }

    val suggestions = listOf(
        "How is my kitchen this week?", "What failed the most?", "What was my last scan?",
        "Why is nitrate dangerous?", "How to test honey?", "Any alerts in my area?", "मेरा स्कोर कैसा है?",
    )
}
