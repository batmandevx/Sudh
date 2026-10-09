package com.shuddh.lab.core

/**
 * Reagent tests the spectrometer and strip reader understand.
 *
 * Limits are from BIS IS 10500:2012 (Indian drinking water) unless noted. Shuddh is a
 * screening tool: verdicts are only as good as the reagent and the user's calibration.
 */
enum class Kind { QUANT, PRESENCE }

data class Analyte(
    val id: String,
    val name: Txt,
    val matrix: String,
    val method: String,
    /** Absorption maximum of the coloured reaction product, nm. */
    val bandNm: Double,
    val kind: Kind,
    val unit: String,
    val limitNote: String,
    /** For PRESENCE tests: absorbance above the treated blank that counts as "detected". */
    val presenceThreshold: Double = 0.10,
    val judge: (Double) -> Pair<Level, List<Txt>>,
)

private val milkName = Txt("Milk", "दूध", "ಹಾಲು")

object Analytes {
    private fun presenceJudge(a: Double, thr: Double) = when {
        a >= thr -> Level.UNSAFE to listOf(Words.adulterated, Words.dontConsume, Words.report)
        a >= thr * 0.5 -> Level.CAUTION to listOf(Words.retest)
        else -> Level.SAFE to listOf(Words.ok)
    }

    val chlorine = Analyte(
        "cl2", Txt("Free chlorine", "क्लोरीन", "ಕ್ಲೋರಿನ್"), "Water", "DPD reagent (pink)", 515.0,
        Kind.QUANT, "mg/L", "BIS IS 10500: residual free chlorine ≥0.2 mg/L, ≤1.0 mg/L; WHO guideline 5 mg/L",
    ) { v ->
        when {
            v < 0.2 -> Level.CAUTION to listOf(Words.lowChlorine)
            v <= 1.0 -> Level.SAFE to listOf(Words.ok)
            v <= 4.0 -> Level.CAUTION to listOf(Words.highChlorine)
            else -> Level.UNSAFE to listOf(Words.highChlorine, Words.waterTreat)
        }
    }

    val nitrate = Analyte(
        "no3", Txt("Nitrate", "नाइट्रेट", "ನೈಟ್ರೇಟ್"), "Water", "Griess / azo dye (magenta)", 540.0,
        Kind.QUANT, "mg/L", "BIS IS 10500: nitrate (as NO₃) ≤45 mg/L, no relaxation",
    ) { v ->
        when {
            v <= 35 -> Level.SAFE to listOf(Words.ok)
            v <= 45 -> Level.CAUTION to listOf(Words.useCare, Words.nitrateInfant)
            else -> Level.UNSAFE to listOf(Words.nitrateInfant, Words.waterTreat)
        }
    }

    val iron = Analyte(
        "fe", Txt("Iron", "आयरन", "ಕಬ್ಬಿಣ"), "Water", "1,10-phenanthroline (orange-red)", 510.0,
        Kind.QUANT, "mg/L", "BIS IS 10500: iron ≤0.3 mg/L (taste/staining above this)",
    ) { v ->
        when {
            v <= 0.3 -> Level.SAFE to listOf(Words.ok)
            v <= 1.0 -> Level.CAUTION to listOf(Words.useCare)
            else -> Level.UNSAFE to listOf(Words.waterTreat)
        }
    }

    val fluoride = Analyte(
        "f", Txt("Fluoride", "फ्लोराइड", "ಫ್ಲೋರೈಡ್"), "Water", "SPADNS (colour bleaches with fluoride)", 570.0,
        Kind.QUANT, "mg/L", "BIS IS 10500: fluoride ≤1.0 mg/L acceptable, 1.5 mg/L permissible",
    ) { v ->
        when {
            v <= 1.0 -> Level.SAFE to listOf(Words.ok)
            v <= 1.5 -> Level.CAUTION to listOf(Words.useCare)
            else -> Level.UNSAFE to listOf(Words.waterTreat)
        }
    }

    val detergent = Analyte(
        "milk_detergent", Txt("Detergent in milk", "दूध में डिटर्जेंट", "ಹಾಲಿನಲ್ಲಿ ಡಿಟರ್ಜೆಂಟ್"), "Milk",
        "Methylene blue + chloroform, read the lower layer (FSSAI DART)", 665.0, Kind.PRESENCE, "AU",
        "FSSAI: milk must be free of detergent. Reference = known-pure milk run through the same test.",
        presenceThreshold = 0.10,
    ) { a -> presenceJudge(a, 0.10) }

    val starch = Analyte(
        "milk_starch", Txt("Starch in milk", "दूध में स्टार्च", "ಹಾಲಿನಲ್ಲಿ ಪಿಷ್ಟ"), "Milk",
        "Iodine tincture (blue-black)", 600.0, Kind.PRESENCE, "AU",
        "FSSAI: milk must be free of added starch. Reference = known-pure milk + iodine.",
        presenceThreshold = 0.12,
    ) { a -> presenceJudge(a, 0.12) }

    val urea = Analyte(
        "milk_urea", Txt("Added urea in milk", "दूध में यूरिया", "ಹಾಲಿನಲ್ಲಿ ಯೂರಿಯಾ"), "Milk",
        "DMAB reagent (distinct yellow)", 420.0, Kind.PRESENCE, "AU",
        "FSSAI: natural urea ≤70 mg/100 mL; the DMAB yellow beyond pure-milk blank flags added urea.",
        presenceThreshold = 0.10,
    ) { a -> presenceJudge(a, 0.10) }

    val spectrum = listOf(chlorine, nitrate, iron, fluoride, detergent, starch, urea)

    fun byId(id: String) = spectrum.firstOrNull { it.id == id }

    val milk = milkName
}

/** Tests read from a colour strip against a captured shade chart. */
data class StripTest(
    val id: String,
    val name: Txt,
    val unit: String,
    val limitNote: String,
    val judge: (Double) -> Pair<Level, List<Txt>>,
)

object StripTests {
    val all = listOf(
        StripTest("strip_ph", Txt("pH", "पीएच", "ಪಿಎಚ್"), "", "BIS IS 10500: pH 6.5 – 8.5") { v ->
            if (v in 6.5..8.5) Level.SAFE to listOf(Words.ok) else Level.CAUTION to listOf(Words.useCare)
        },
        StripTest(
            "strip_hardness", Txt("Total hardness", "कठोरता", "ಗಡಸುತನ"), "mg/L",
            "BIS IS 10500: ≤200 mg/L acceptable, ≤600 permissible",
        ) { v ->
            when {
                v <= 200 -> Level.SAFE to listOf(Words.ok)
                v <= 600 -> Level.CAUTION to listOf(Words.useCare)
                else -> Level.UNSAFE to listOf(Words.waterTreat)
            }
        },
        StripTest("strip_cl2", Analytes.chlorine.name, "mg/L", Analytes.chlorine.limitNote, Analytes.chlorine.judge),
        StripTest("strip_no3", Analytes.nitrate.name, "mg/L", Analytes.nitrate.limitNote, Analytes.nitrate.judge),
        StripTest(
            "strip_as", Txt("Arsenic", "आर्सेनिक", "ಆರ್ಸೆನಿಕ್"), "µg/L",
            "BIS IS 10500 / WHO: arsenic ≤10 µg/L",
        ) { v ->
            if (v <= 10) Level.SAFE to listOf(Words.ok) else Level.UNSAFE to listOf(Words.waterTreat, Words.dontConsume)
        },
        StripTest(
            "strip_f", Analytes.fluoride.name, "mg/L", Analytes.fluoride.limitNote, Analytes.fluoride.judge,
        ),
    )
}
