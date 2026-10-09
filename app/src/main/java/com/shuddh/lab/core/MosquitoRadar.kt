package com.shuddh.lab.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Mosquito Radar — hears a mosquito's wingbeat and estimates which kind it is.
 *
 * A mosquito's buzz is a harmonic tone whose fundamental is its wingbeat frequency. Published
 * field data: female Aedes aegypti ≈ 455 ± 55 Hz, males ≈ 690 Hz (males don't bite); frequency
 * rises ≈ 8–13 Hz per °C. Species overlap, so — like Stanford's Abuzz — the estimate combines the
 * pitch likelihood with a time-of-day prior (Aedes bites by day; Culex and Anopheles mostly at
 * night). Mains/fan hum is rejected: it sits on exact multiples of 50 Hz and is unnaturally steady.
 */
object MosquitoRadar {
    enum class Kind(val label: String, val disease: String, val emoji: String, val muHz: Double, val sdHz: Double) {
        AEDES("Aedes (tiger mosquito)", "dengue, chikungunya, Zika", "🦟", 455.0, 55.0),
        ANOPHELES("Anopheles", "malaria", "🦟", 480.0, 60.0),
        CULEX("Culex (house mosquito)", "filariasis, Japanese encephalitis", "🦟", 375.0, 45.0),
        MALE("Male mosquito", "none — males don't bite", "🦗", 690.0, 70.0),
    }

    data class Estimate(val hz: Double, val probs: Map<Kind, Double>, val top: Kind)

    /** Gaussian pitch likelihood × time-of-day prior, normalised. Pitch is corrected to 28 °C. */
    fun classify(hz: Double, hour: Int, tempC: Double = 28.0): Estimate {
        val f = hz - 10.0 * (tempC - 28.0) // ≈10 Hz/°C, mid of the published 8–13
        val day = hour in 7..18
        val prior = mapOf(
            Kind.AEDES to if (day) 0.55 else 0.15,
            Kind.ANOPHELES to if (day) 0.1 else 0.3,
            Kind.CULEX to if (day) 0.2 else 0.45,
            Kind.MALE to 0.15,
        )
        val raw = Kind.entries.associateWith { k -> prior[k]!! * exp(-0.5 * ((f - k.muHz) / k.sdHz).pow(2)) / k.sdHz }
        val tot = raw.values.sum().coerceAtLeast(1e-300)
        val probs = raw.mapValues { it.value / tot }
        return Estimate(hz, probs, probs.maxBy { it.value }.key)
    }

    data class Frame(val f0: Double?, val strength: Double, val harmonic: Boolean)

    /**
     * Streaming detector. Feed magnitude spectra (dB) of consecutive frames; returns a detection
     * (median wingbeat Hz) once a buzz has been heard for ~1 s with stable-but-natural pitch.
     */
    class Detector(private val sampleRate: Int, private val n: Int) {
        private val hz = sampleRate.toDouble() / n
        private var noise: DoubleArray? = null
        private var frames = 0
        private val pitches = ArrayList<Double>()
        private var misses = 0
        var lastFrame: Frame = Frame(null, 0.0, false); private set

        fun push(specDb: FloatArray): Double? {
            frames++
            val nz = noise ?: DoubleArray(specDb.size) { specDb[it].toDouble() }.also { noise = it }
            val lo = (250 / hz).toInt(); val hi = (900 / hz).toInt()
            // SNR spectrum above the learned room noise.
            fun snr(i: Int) = if (i in specDb.indices) specDb[i] - nz[i] else -99.0
            // Harmonic sum: candidate f0 scored by its fundamental + 2nd + 3rd harmonics above noise.
            var best = -1; var bestScore = 0.0
            for (i in lo..hi) {
                val s = snr(i).coerceAtLeast(0.0) + 0.7 * peakNear(specDb, nz, 2 * i) + 0.5 * peakNear(specDb, nz, 3 * i)
                if (s > bestScore) { bestScore = s; best = i }
            }
            val f0 = if (best > 0) Dsp.parabolic(specDb, best) * hz else null
            val harmonic = best > 0 && snr(best) >= 10 && peakNear(specDb, nz, 2 * best) >= 6
            lastFrame = Frame(f0, bestScore, harmonic)
            // Learn the room for the first ~1 s and whenever nothing is buzzing.
            if (frames <= 6 || !harmonic) for (k in nz.indices) nz[k] = 0.9 * nz[k] + 0.1 * specDb[k]
            if (frames <= 6) return null
            if (harmonic && f0 != null) { pitches += f0; misses = 0 } else if (++misses > 3) pitches.clear()
            if (pitches.size >= 5) {
                val recent = pitches.takeLast(8)
                val med = Dsp.median(recent)
                val sd = sqrt(recent.map { (it - med) * (it - med) }.average())
                val mains = abs(med - 50 * round(med / 50)) < 2.0 && sd < 1.0 // fan / transformer hum
                val tooWobbly = sd > 0.08 * med                                // speech, music
                pitches.clear()
                return if (!mains && !tooWobbly) med else null
            }
            return null
        }

        /** Max SNR within ±2 bins of [i] (harmonics drift slightly). */
        private fun peakNear(s: FloatArray, nz: DoubleArray, i: Int): Double =
            (i - 2..i + 2).filter { it in s.indices }.maxOfOrNull { s[it] - nz[it] }?.coerceAtLeast(0.0) ?: 0.0
    }

    val breedingChecklist = listOf(
        "Desert cooler water changed this week",
        "Flower-pot saucers / plant trays emptied",
        "Water drums & tanks covered tightly",
        "Old tyres, coconut shells, cups cleared from roof & yard",
        "Fridge defrost tray cleaned",
        "Bird bath / pet bowl water changed",
    )

    fun advice(k: Kind): String = when (k) {
        Kind.AEDES -> "Dengue mosquito: it breeds in clean stored water and bites by day. Empty containers weekly; use repellent in daytime; see a doctor for high fever with body ache."
        Kind.ANOPHELES -> "Possible malaria mosquito: sleep under a treated bed net; fever with chills needs a free malaria test at the PHC."
        Kind.CULEX -> "House mosquito: breeds in dirty drains. Clear blocked drains and use nets or screens at night."
        Kind.MALE -> "A male — harmless, but females are probably breeding nearby. Check the breeding checklist."
    }
}
