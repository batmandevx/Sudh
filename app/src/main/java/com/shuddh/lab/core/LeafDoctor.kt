package com.shuddh.lab.core

import android.content.Context
import android.graphics.Bitmap
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Leaf Doctor: CrispRoots' MobileNetV2 plant-disease model (80 classes, 224×224, /255 input) run
 * on the phone, plus an independent colour measurement of how much of the leaf is damaged.
 * Checked on 60 PlantVillage photos: 85% top-1, 98% top-3 — field photos will score lower,
 * so the app always shows the top three with confidence.
 */
object LeafDoctor {
    enum class Kind(val label: String, val emoji: String) { FUNGUS("Fungus", "🍄"), BACTERIA("Bacteria", "🦠"), VIRUS("Virus", "🧬"), PEST("Insect / mite", "🐛"), HEALTHY("Healthy", "🌿") }

    data class Label(val crop: String, val problem: String, val kind: Kind)

    private val raw = listOf(
        "Cotton|American bollworm|P", "Cotton|Anthracnose|F", "Apple|Apple scab|F", "Apple|Black rot|F", "Apple|Cedar apple rust|F", "Apple|Healthy|H",
        "Rice|Army worm|P", "Rice|Bacterial blight|B", "Blueberry|Healthy|H", "Rice|Brown spot|F", "Cherry|Powdery mildew|F", "Cherry|Healthy|H",
        "Maize|Common rust|F", "Maize|Grey leaf spot|F", "Maize|Common rust|F", "Maize|Northern leaf blight|F", "Maize|Healthy|H", "Cotton|Aphid|P",
        "Wheat|Flag smut|F", "Grape|Black rot|F", "Grape|Esca (black measles)|F", "Grape|Leaf blight|F", "Grape|Healthy|H", "Maize|Grey leaf spot|F",
        "Maize|Healthy|H", "Wheat|Healthy|H", "Cotton|Healthy|H", "Cotton|Leaf curl virus|V", "Rice|Leaf smut|F", "Sugarcane|Mosaic virus|V",
        "Orange|Citrus greening (HLB)|B", "Peach|Bacterial spot|B", "Peach|Healthy|H", "Bell pepper|Bacterial spot|B", "Bell pepper|Healthy|H",
        "Potato|Early blight|F", "Potato|Late blight|F", "Potato|Healthy|H", "Raspberry|Healthy|H", "Sugarcane|Red rot|F", "Sugarcane|Red rust|F",
        "Rice|Blast|F", "Soybean|Healthy|H", "Squash|Powdery mildew|F", "Strawberry|Leaf scorch|F", "Strawberry|Healthy|H", "Sugarcane|Healthy|H",
        "Tomato|Bacterial spot|B", "Tomato|Early blight|F", "Tomato|Late blight|F", "Tomato|Leaf mould|F", "Tomato|Septoria leaf spot|F",
        "Tomato|Spider mites|P", "Tomato|Target spot|F", "Tomato|Yellow leaf curl virus|V", "Tomato|Mosaic virus|V", "Tomato|Healthy|H",
        "Rice|Tungro virus|V", "Wheat|Brown (leaf) rust|F", "Wheat|Stem fly|P", "Wheat|Aphid|P", "Wheat|Black (stem) rust|F", "Wheat|Leaf blight|F",
        "Wheat|Mite|P", "Wheat|Powdery mildew|F", "Wheat|Scab (head blight)|F", "Wheat|Yellow (stripe) rust|F", "Crop|Wilt|F", "Sugarcane|Yellow rust|F",
        "Cotton|Bacterial blight|B", "Cotton|Boll rot|F", "Cotton|Bollworm|P", "Cotton|Mealybug|P", "Cotton|Whitefly|P", "Maize|Ear rot|F",
        "Maize|Fall armyworm|P", "Maize|Stem borer|P", "Cotton|Pink bollworm|P", "Cotton|Red cotton bug|P", "Cotton|Thrips|P",
    )
    val labels: List<Label> = raw.map { r -> r.split("|").let { Label(it[0], it[1], when (it[2]) { "F" -> Kind.FUNGUS; "B" -> Kind.BACTERIA; "V" -> Kind.VIRUS; "P" -> Kind.PEST; else -> Kind.HEALTHY }) } }

    data class Guess(val label: Label, val p: Float)

    private var interp: org.tensorflow.lite.Interpreter? = null

    private fun load(ctx: Context): org.tensorflow.lite.Interpreter {
        interp?.let { return it }
        val fd = ctx.assets.openFd("leaf_doctor.tflite")
        val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        return org.tensorflow.lite.Interpreter(buf, org.tensorflow.lite.Interpreter.Options().setNumThreads(4)).also { interp = it }
    }

    /** Top guesses for a leaf photo (centre square, resized to 224). */
    fun classify(ctx: Context, bmp: Bitmap, top: Int = 3): List<Guess> {
        val side = min(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val s = Bitmap.createScaledBitmap(sq, 224, 224, true)
        val px = IntArray(224 * 224); s.getPixels(px, 0, 224, 0, 0, 224, 224)
        val input = ByteBuffer.allocateDirect(4 * 224 * 224 * 3).order(ByteOrder.nativeOrder())
        for (c in px) { input.putFloat(((c shr 16) and 0xff) / 255f); input.putFloat(((c shr 8) and 0xff) / 255f); input.putFloat((c and 0xff) / 255f) }
        input.rewind()
        val out = Array(1) { FloatArray(80) }
        load(ctx).run(input, out)
        // Merge duplicate classes (e.g. two "Maize · Common rust" outputs) before ranking.
        val merged = LinkedHashMap<Label, Float>()
        out[0].forEachIndexed { i, p -> labels.getOrNull(i)?.let { l -> merged[l] = (merged[l] ?: 0f) + p } }
        return merged.entries.sortedByDescending { it.value }.take(top).map { Guess(it.key, it.value) }
    }

    /** Colour health of the leaf: share of green, yellow (chlorosis) and brown/black (dead tissue). */
    data class Health(val green: Double, val yellow: Double, val brown: Double, val leafShare: Double) {
        val damaged get() = yellow + brown
        val severity get() = when { damaged < 0.05 -> "Healthy-looking"; damaged < 0.15 -> "Mild"; damaged < 0.35 -> "Moderate"; else -> "Severe" }
    }

    fun health(bmp: Bitmap): Health {
        val s = Bitmap.createScaledBitmap(bmp, 160, 160, true)
        val px = IntArray(160 * 160); s.getPixels(px, 0, 160, 0, 0, 160, 160)
        var g = 0; var y = 0; var b = 0; var leaf = 0
        val hsv = FloatArray(3)
        for (c in px) {
            android.graphics.Color.colorToHSV(c, hsv)
            val h = hsv[0]; val sat = hsv[1]; val v = hsv[2]
            // Background: very dark, very bright-white, or grey; skies and soils are mostly excluded by hue/sat.
            if (v < 0.12 || sat < 0.15) continue
            when {
                h in 70f..170f -> { g++; leaf++ }
                h in 45f..70f && v > 0.35 -> { y++; leaf++ }
                (h < 45f || h > 340f) && v < 0.75 -> { b++; leaf++ }
            }
        }
        val n = max(1, leaf).toDouble()
        return Health(g / n, y / n, b / n, leaf / px.size.toDouble())
    }

    /** What to do — Indian package-of-practice style. Always read the label; ask the KVK when unsure. */
    fun treatment(l: Label): Pair<List<String>, List<String>> {
        val p = l.problem.lowercase()
        val organic = mutableListOf<String>(); val chem = mutableListOf<String>()
        when {
            l.kind == Kind.HEALTHY -> organic += "No disease seen. Keep scouting weekly — check the underside of leaves."
            "rust" in p -> { organic += "Remove badly rusted leaves; avoid late, heavy nitrogen."; chem += "Propiconazole 25 EC 1 ml/L or Tebuconazole 25.9 EC 1 ml/L; repeat after 15 days if spreading." ; if (l.crop == "Wheat") organic += "Next season sow a rust-resistant variety (e.g. HD 3086, DBW 187 — check your zone)." }
            "powdery" in p -> { organic += "Spray 5 ml neem oil + 2 ml soap per litre, or 1:10 diluted buttermilk."; chem += "Wettable sulphur 80 WP 2.5 g/L or Hexaconazole 5 EC 2 ml/L." }
            "late blight" in p -> { organic += "Remove and burn infected plants; never compost them. Avoid overhead watering."; chem += "Metalaxyl 8% + Mancozeb 64% WP 2.5 g/L, then Cymoxanil + Mancozeb 3 g/L after 7 days." }
            "blight" in p && l.kind == Kind.BACTERIA -> { organic += "Drain standing water; avoid excess urea; remove infected stubble."; chem += "Streptocycline 0.1 g/L + Copper oxychloride 3 g/L." }
            "blast" in p -> { organic += "Balanced nitrogen in splits; keep bunds clean."; chem += "Tricyclazole 75 WP 0.6 g/L at first symptoms and at panicle emergence." }
            "smut" in p -> { organic += "Use certified seed; rogue out smutted plants before spores spread."; chem += "Seed treatment next season: Carbendazim 2 g/kg or Tebuconazole 1 g/kg seed." }
            "red rot" in p || "wilt" in p -> { organic += "Uproot and burn affected clumps/plants; don't ratoon a diseased field; apply Trichoderma 2.5 kg/acre with FYM."; chem += "Treat setts/seed with Carbendazim 1 g/L for 15 min before planting." }
            l.kind == Kind.BACTERIA -> { organic += "Remove spotted leaves; avoid working in wet fields; rotate crops."; chem += "Copper oxychloride 50 WP 3 g/L (+ Streptocycline 0.1 g/L for severe spread)." }
            l.kind == Kind.FUNGUS -> { organic += "Remove infected leaves; improve air flow; Trichoderma or Pseudomonas spray 5–10 g/L."; chem += "Mancozeb 75 WP 2.5 g/L or Chlorothalonil 2 g/L; switch to Azoxystrobin 1 ml/L if it continues." }
            l.kind == Kind.VIRUS -> { organic += "No cure — pull out and destroy infected plants early. Control the insect that spreads it (whitefly/aphid/leafhopper) with yellow sticky traps (10/acre) and neem oil 5 ml/L."; chem += "Vector control only: Imidacloprid 17.8 SL 0.3 ml/L or Thiamethoxam 25 WG 0.3 g/L."; organic += "Next season use a resistant variety." }
            "armyworm" in p || "bollworm" in p || "borer" in p -> { organic += "Pheromone traps 5/acre; hand-pick egg masses; spray Bt (Bacillus thuringiensis) 2 g/L or neem seed kernel extract 5%."; chem += "Emamectin benzoate 5 SG 0.4 g/L or Chlorantraniliprole 18.5 SC 0.3 ml/L, aimed into the whorl/boll." }
            "mite" in p -> { organic += "Strong water spray on leaf undersides; neem oil 5 ml/L."; chem += "Spiromesifen 22.9 SC 1 ml/L or Fenpyroximate 1 ml/L." }
            l.kind == Kind.PEST -> { organic += "Yellow/blue sticky traps; neem oil 5 ml/L + soap; encourage ladybirds."; chem += "Sucking pests: Imidacloprid 17.8 SL 0.3 ml/L or Flonicamid 50 WG 0.3 g/L; rotate chemicals." }
        }
        if (l.kind != Kind.HEALTHY) chem += "Wear gloves and a mask; keep the label's waiting period before harvest."
        return organic to chem
    }
}
