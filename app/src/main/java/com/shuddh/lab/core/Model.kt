package com.shuddh.lab.core

import java.util.Locale

enum class Lang(val label: String, val locale: Locale) {
    EN("English", Locale("en", "IN")),
    HI("हिन्दी", Locale("hi", "IN")),
    KN("ಕನ್ನಡ", Locale("kn", "IN")),
    TE("తెలుగు", Locale("te", "IN")),
    TA("தமிழ்", Locale("ta", "IN")),
}

/** A phrase in every language the verdict engine speaks. */
data class Txt(val en: String, val hi: String = en, val kn: String = en, val te: String? = null, val ta: String? = null) {
    fun get(lang: Lang) = when (lang) {
        Lang.EN -> en
        Lang.HI -> hi
        Lang.KN -> kn
        Lang.TE -> te ?: I18n.phrase(en, lang) ?: en
        Lang.TA -> ta ?: I18n.phrase(en, lang) ?: en
    }
}

enum class Level(val label: Txt, val argb: Long) {
    SAFE(Txt("SAFE", "सुरक्षित", "ಸುರಕ್ಷಿತ"), 0xFF2FBF71),
    CAUTION(Txt("CAUTION", "सावधान", "ಎಚ್ಚರಿಕೆ"), 0xFFF2B33D),
    UNSAFE(Txt("UNSAFE", "असुरक्षित", "ಅಸುರಕ್ಷಿತ"), 0xFFE5484D),
    INCONCLUSIVE(Txt("INCONCLUSIVE", "अनिर्णायक", "ಖಚಿತವಿಲ್ಲ"), 0xFF8A96A3),
}

/** One rung of the evidence ladder shown under every verdict. */
data class Evidence(val step: String, val text: String, val ok: Boolean? = null)

/** What an instrument hands to the verdict screen. */
data class Outcome(
    val instrument: String,
    val analyteId: String,
    val analyte: Txt,
    val value: Double?,
    val unit: String,
    val level: Level,
    val headline: String,
    val advice: List<Txt>,
    val evidence: List<Evidence>,
    val limitNote: String = "",
    /** Overrides SAFE/UNSAFE wording for non-safety instruments (e.g. "FULL" for a coconut). */
    val levelLabel: Txt? = null,
) {
    fun levelText(lang: Lang) = (levelLabel ?: level.label).get(lang)

    fun valueText(): String = value?.let { "${fmt(it)} $unit".trim() } ?: "—"

    /**
     * Confidence 0–100 from the instrument's own checks: every evidence step marked ok/not-ok
     * counts (quality & calibration checks weigh 1.5×). Inconclusive results are capped at 40.
     */
    fun confidence(): Int {
        val checks = evidence.filter { it.ok != null }
        val base = if (checks.isEmpty()) 60.0 else {
            val w = checks.sumOf { if (it.step == "QUALITY" || it.step == "CALIBRATION") 1.5 else 1.0 }
            val pass = checks.filter { it.ok == true }.sumOf { if (it.step == "QUALITY" || it.step == "CALIBRATION") 1.5 else 1.0 }
            35 + 65 * pass / w
        }
        return (if (level == Level.INCONCLUSIVE) minOf(base, 40.0) else base).toInt().coerceIn(5, 99)
    }

    fun spoken(lang: Lang): String {
        val v = value?.let { "${fmt(it)} ${Words.unitWord(unit, lang)}. " } ?: ""
        val adv = advice.joinToString(" ") { it.get(lang) }
        return "${analyte.get(lang)}: $v${levelText(lang)}. $adv"
    }
}

fun fmt(v: Double): String = when {
    v == 0.0 -> "0"
    kotlin.math.abs(v) >= 100 -> String.format(Locale.US, "%.0f", v)
    kotlin.math.abs(v) >= 10 -> String.format(Locale.US, "%.1f", v)
    else -> String.format(Locale.US, "%.2f", v)
}

object Words {
    val ok = Txt("Fine to use.", "उपयोग के लिए ठीक है।", "ಬಳಸಲು ಸರಿ.")
    val retest = Txt("Scan again to confirm.", "पुष्टि के लिए दोबारा जाँच करें।", "ಖಚಿತಪಡಿಸಲು ಮತ್ತೆ ಪರೀಕ್ಷಿಸಿ.")
    val dontConsume = Txt(
        "Do not consume. Do not give to children.",
        "इसका सेवन न करें। बच्चों को न दें।",
        "ಇದನ್ನು ಸೇವಿಸಬೇಡಿ. ಮಕ್ಕಳಿಗೆ ಕೊಡಬೇಡಿ.",
    )
    val waterTreat = Txt(
        "Do not drink untreated. Use a purifier or another source.",
        "बिना शुद्ध किए न पिएँ। फ़िल्टर या दूसरा स्रोत इस्तेमाल करें।",
        "ಶುದ್ಧೀಕರಿಸದೆ ಕುಡಿಯಬೇಡಿ. ಫಿಲ್ಟರ್ ಅಥವಾ ಬೇರೆ ಮೂಲ ಬಳಸಿ.",
    )
    val lowChlorine = Txt(
        "Chlorine too low, water may not be disinfected. Boil before drinking.",
        "क्लोरीन बहुत कम है, पानी कीटाणुरहित नहीं हो सकता। उबालकर पिएँ।",
        "ಕ್ಲೋರಿನ್ ತುಂಬಾ ಕಡಿಮೆ, ನೀರು ಸೋಂಕುರಹಿತವಾಗಿಲ್ಲದಿರಬಹುದು. ಕುದಿಸಿ ಕುಡಿಯಿರಿ.",
    )
    val highChlorine = Txt(
        "Chlorine above the limit. Let the water stand uncovered, or use another source.",
        "क्लोरीन सीमा से अधिक है। पानी को खुला रखें या दूसरा स्रोत इस्तेमाल करें।",
        "ಕ್ಲೋರಿನ್ ಮಿತಿಗಿಂತ ಹೆಚ್ಚಾಗಿದೆ. ನೀರನ್ನು ತೆರೆದಿಡಿ ಅಥವಾ ಬೇರೆ ಮೂಲ ಬಳಸಿ.",
    )
    val nitrateInfant = Txt(
        "Never use for infant formula. Boiling does not remove nitrate.",
        "शिशु के दूध के लिए कभी इस्तेमाल न करें। उबालने से नाइट्रेट नहीं हटता।",
        "ಶಿಶುವಿನ ಹಾಲಿಗೆ ಎಂದಿಗೂ ಬಳಸಬೇಡಿ. ಕುದಿಸುವುದರಿಂದ ನೈಟ್ರೇಟ್ ಹೋಗುವುದಿಲ್ಲ.",
    )
    val calibrate = Txt(
        "The instrument is not calibrated for this test.",
        "इस जाँच के लिए उपकरण कैलिब्रेट नहीं है।",
        "ಈ ಪರೀಕ್ಷೆಗೆ ಉಪಕರಣ ಮಾಪನಾಂಕ ಮಾಡಿಲ್ಲ.",
    )
    val report = Txt(
        "Keep the report and complain to the vendor or FSSAI.",
        "रिपोर्ट रखें और विक्रेता या FSSAI से शिकायत करें।",
        "ವರದಿಯನ್ನು ಇಟ್ಟುಕೊಳ್ಳಿ ಮತ್ತು ಮಾರಾಟಗಾರ ಅಥವಾ FSSAI ಗೆ ದೂರು ನೀಡಿ.",
    )
    val useCare = Txt(
        "Use with care and test again.",
        "सावधानी से इस्तेमाल करें और फिर से जाँचें।",
        "ಎಚ್ಚರಿಕೆಯಿಂದ ಬಳಸಿ ಮತ್ತು ಮತ್ತೆ ಪರೀಕ್ಷಿಸಿ.",
    )
    val adulterated = Txt(
        "Likely adulterated. Do not buy from this batch again.",
        "मिलावट की संभावना है। इस बैच से दोबारा न खरीदें।",
        "ಕಲಬೆರಕೆ ಸಾಧ್ಯತೆ ಇದೆ. ಈ ಬ್ಯಾಚ್‌ನಿಂದ ಮತ್ತೆ ಖರೀದಿಸಬೇಡಿ.",
    )
    val waterAdded = Txt(
        "Water has likely been added to this milk.",
        "इस दूध में पानी मिलाए जाने की संभावना है।",
        "ಈ ಹಾಲಿಗೆ ನೀರು ಸೇರಿಸಿರುವ ಸಾಧ್ಯತೆ ಇದೆ.",
    )
    val ventilate = Txt(
        "Air is heavy with particles. Ventilate the room and avoid smoke near children.",
        "हवा में कण बहुत हैं। कमरे को हवादार करें, बच्चों के पास धुआँ न करें।",
        "ಗಾಳಿಯಲ್ಲಿ ಕಣಗಳು ಹೆಚ್ಚು. ಕೋಣೆಗೆ ಗಾಳಿ ಬರಲಿ, ಮಕ್ಕಳ ಬಳಿ ಹೊಗೆ ಬೇಡ.",
    )
    val turbid = Txt(
        "Water is cloudy. Filter and boil before drinking.",
        "पानी गंदला है। छानकर और उबालकर पिएँ।",
        "ನೀರು ಮಬ್ಬಾಗಿದೆ. ಸೋಸಿ ಕುದಿಸಿ ಕುಡಿಯಿರಿ.",
    )
    val buyIt = Txt("Good one. Buy it.", "अच्छा है। ले लीजिए।", "ಚೆನ್ನಾಗಿದೆ. ತೆಗೆದುಕೊಳ್ಳಿ.")
    val skipIt = Txt("Pick another one.", "कोई दूसरा चुनिए।", "ಬೇರೆಯದನ್ನು ಆರಿಸಿ.")

    fun unitWord(unit: String, lang: Lang): String = when (unit) {
        "mg/L" -> Txt("milligrams per litre", "मिलीग्राम प्रति लीटर", "ಮಿಲಿಗ್ರಾಂ ಪ್ರತಿ ಲೀಟರ್").get(lang)
        "µg/L" -> Txt("micrograms per litre", "माइक्रोग्राम प्रति लीटर", "ಮೈಕ್ರೋಗ್ರಾಂ ಪ್ರತಿ ಲೀಟರ್").get(lang)
        "%" -> Txt("percent", "प्रतिशत", "ಶೇಕಡಾ").get(lang)
        "×" -> Txt("times baseline", "गुना", "ಪಟ್ಟು").get(lang)
        "°" -> Txt("degrees", "डिग्री", "ಡಿಗ್ರಿ").get(lang)
        "Hz" -> Txt("hertz", "हर्ट्ज़", "ಹರ್ಟ್ಜ್").get(lang)
        "AU" -> Txt("absorbance units", "अवशोषण", "ಹೀರಿಕೆ").get(lang)
        else -> unit
    }
}
