package com.shuddh.lab.core

import kotlin.math.sqrt

/**
 * Fall detector on the accelerometer (m/s², including gravity). The classic three-phase signature:
 *  1. free fall — total acceleration drops well below 1 g (< 0.45 g) for ≥ 60 ms,
 *  2. impact   — a spike above 2.4 g within 1.2 s of the free fall,
 *  3. stillness — the following ~2.5 s are quiet (the person is not getting straight up).
 * A dropped phone shows (1)+(2) but then is picked up — usually *not* still for long, but a phone
 * dropped on a table is still too — so a fall always asks "Are you OK?" before any SOS is sent.
 */
class FallDetector {
    enum class Phase { NORMAL, FREEFALL, IMPACT }

    private val g = 9.81
    var phase = Phase.NORMAL; private set
    private var ffStart = 0L
    private var impactAt = 0L
    private var peakG = 0.0
    private val stillWindow = ArrayList<Double>()

    data class Event(val impactG: Double, val atMs: Long)

    /** Feeds one sample; returns a fall event when the full signature completes. */
    fun push(ax: Float, ay: Float, az: Float, tMs: Long): Event? {
        val a = sqrt((ax * ax + ay * ay + az * az).toDouble()) / g
        when (phase) {
            Phase.NORMAL -> if (a < 0.45) { if (ffStart == 0L) ffStart = tMs; if (tMs - ffStart >= 60) phase = Phase.FREEFALL } else ffStart = 0L
            Phase.FREEFALL -> when {
                a > 2.4 -> { phase = Phase.IMPACT; impactAt = tMs; peakG = a; stillWindow.clear() }
                tMs - ffStart > 1200 -> reset()
            }
            Phase.IMPACT -> {
                if (tMs - impactAt < 400) { peakG = maxOf(peakG, a); return null } // let the impact ring down
                stillWindow += a
                if (tMs - impactAt >= 2900) {
                    val m = stillWindow.average()
                    val sd = sqrt(stillWindow.sumOf { (it - m) * (it - m) } / stillWindow.size)
                    val ev = if (sd < 0.18) Event(peakG, impactAt) else null
                    reset()
                    return ev
                }
            }
        }
        return null
    }

    fun reset() { phase = Phase.NORMAL; ffStart = 0L; impactAt = 0L; peakG = 0.0; stillWindow.clear() }
}

/** SOS helpers shared by the Guardian screen and service. */
object Sos {
    /** International Morse "SOS" as on/off durations (ms) for the torch: ··· ——— ··· */
    val morse: List<Pair<Boolean, Long>> = buildList {
        val dot = 200L; val dash = 600L; val gap = 200L
        repeat(3) { add(true to dot); add(false to gap) }; add(false to 400L)
        repeat(3) { add(true to dash); add(false to gap) }; add(false to 400L)
        repeat(3) { add(true to dot); add(false to gap) }; add(false to 1400L)
    }

    /** Short text that fits a Bluetooth mesh packet and an SMS. */
    fun message(kind: String, lat: Double?, lon: Double?, name: String): String =
        "SOS · $kind · ${name.take(16)}" + (if (lat != null && lon != null) " · https://maps.google.com/?q=${"%.5f".format(lat)},${"%.5f".format(lon)}" else " · location unknown")
}
