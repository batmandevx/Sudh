package com.shuddh.lab.core

/**
 * Kalman filters used across Shuddh's live sensors.
 *
 * - [Kalman1D]: random-walk state with measurement noise — smooths a scalar that drifts slowly
 *   (live heart rate, moisture over pings, light level) and reports its own uncertainty.
 * - [KalmanCV]: constant-velocity tracker for one coordinate — predicts where a moving point
 *   (a fingertip, a face, a box corner) will be, so overlays glide instead of jittering and
 *   survive a dropped detection frame.
 * - [PointTracker]: a 2-D point built from two [KalmanCV]s.
 */
class Kalman1D(
    /** Process noise: how much the true value may wander per step. */
    private val q: Double,
    /** Measurement noise: variance of each new reading. */
    private val r: Double,
) {
    var x = Double.NaN; private set
    var p = 1.0; private set
    val initialised get() = !x.isNaN()

    /** Folds in a measurement (optionally with its own variance) and returns the new estimate. */
    fun update(z: Double, rOverride: Double? = null): Double {
        if (x.isNaN()) { x = z; p = rOverride ?: r; return x }
        p += q
        val k = p / (p + (rOverride ?: r))
        x += k * (z - x)
        p *= (1 - k)
        return x
    }

    /** One-sigma uncertainty of the current estimate. */
    val sigma get() = kotlin.math.sqrt(p)

    fun reset() { x = Double.NaN; p = 1.0 }
}

/** Constant-velocity Kalman filter on one axis: state [position, velocity], 2×2 covariance. */
class KalmanCV(private val q: Double = 50.0, private val r: Double = 1e-4) {
    private var pos = Double.NaN
    private var vel = 0.0
    private var p00 = 1.0; private var p01 = 0.0; private var p11 = 1.0

    val position get() = pos
    val velocity get() = vel

    /** Advances the state by [dt] seconds without a measurement. */
    fun predict(dt: Double) {
        if (pos.isNaN()) return
        pos += vel * dt
        // P = F P Fᵀ + Q (white-acceleration model).
        val dt2 = dt * dt
        val n00 = p00 + dt * (2 * p01) + dt2 * p11 + q * dt2 * dt2 / 4
        val n01 = p01 + dt * p11 + q * dt2 * dt / 2
        val n11 = p11 + q * dt2
        p00 = n00; p01 = n01; p11 = n11
    }

    fun update(z: Double): Double {
        if (pos.isNaN()) { pos = z; vel = 0.0; p00 = r; p01 = 0.0; p11 = 1.0; return pos }
        val s = p00 + r
        val k0 = p00 / s; val k1 = p01 / s
        val y = z - pos
        pos += k0 * y; vel += k1 * y
        val n00 = (1 - k0) * p00
        val n01 = (1 - k0) * p01
        val n11 = p11 - k1 * p01
        p00 = n00; p01 = n01; p11 = n11
        return pos
    }

    fun reset() { pos = Double.NaN; vel = 0.0 }
}

/** Smooths a normalised 2-D point (0..1 image coordinates) with velocity prediction. */
class PointTracker(q: Double = 40.0, r: Double = 4e-5) {
    private val kx = KalmanCV(q, r)
    private val ky = KalmanCV(q, r)
    private var last = 0L

    fun update(x: Float, y: Float, nanos: Long): Pair<Float, Float> {
        val dt = if (last == 0L) 0.0 else ((nanos - last) / 1e9).coerceIn(0.0, 0.5)
        last = nanos
        kx.predict(dt); ky.predict(dt)
        return kx.update(x.toDouble()).toFloat() to ky.update(y.toDouble()).toFloat()
    }

    fun reset() { kx.reset(); ky.reset(); last = 0L }
}
