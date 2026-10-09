package com.shuddh.lab.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Small, dependency-free numerics. Every "model" in Shuddh is one of these fits. */
object Dsp {

    data class LineFit(val slope: Double, val intercept: Double, val r2: Double) {
        fun at(x: Double) = slope * x + intercept
        fun invert(y: Double) = (y - intercept) / slope
    }

    fun linearFit(xs: List<Double>, ys: List<Double>): LineFit? {
        val n = xs.size
        if (n < 2) return null
        val mx = xs.average()
        val my = ys.average()
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        if (sxx == 0.0) return null
        val m = sxy / sxx
        val r2 = if (syy == 0.0) 1.0 else (sxy * sxy) / (sxx * syy)
        return LineFit(m, my - m * mx, r2)
    }

    fun smooth(a: FloatArray, w: Int): FloatArray {
        if (w <= 1) return a.copyOf()
        val out = FloatArray(a.size)
        val h = w / 2
        for (i in a.indices) {
            var s = 0f
            var c = 0
            for (j in max(0, i - h)..minOf(a.size - 1, i + h)) { s += a[j]; c++ }
            out[i] = s / c
        }
        return out
    }

    /** Local maxima with at least [minProminence] rise above both neighbouring minima. */
    fun peaks(a: FloatArray, minProminence: Float): List<Int> {
        val out = mutableListOf<Int>()
        for (i in 1 until a.size - 1) {
            if (a[i] < a[i - 1] || a[i] < a[i + 1]) continue
            var lMin = a[i]; var k = i
            while (k > 0 && a[k - 1] <= a[i]) { k--; lMin = minOf(lMin, a[k]) }
            var rMin = a[i]; k = i
            while (k < a.size - 1 && a[k + 1] <= a[i]) { k++; rMin = minOf(rMin, a[k]) }
            if (a[i] - max(lMin, rMin) >= minProminence) out.add(i)
        }
        return out
    }

    /** Sub-bin peak position by parabolic interpolation. */
    fun parabolic(a: FloatArray, i: Int): Double {
        if (i <= 0 || i >= a.size - 1) return i.toDouble()
        val y0 = a[i - 1]; val y1 = a[i]; val y2 = a[i + 1]
        val d = y0 - 2 * y1 + y2
        return if (d == 0f) i.toDouble() else i + 0.5 * (y0 - y2) / d
    }

    /** In-place radix-2 FFT. Sizes must be powers of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Magnitude spectrum (dB) of a Hann-windowed frame. */
    fun spectrumDb(x: FloatArray): FloatArray {
        val n = x.size
        val re = DoubleArray(n) { x[it] * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }
        val im = DoubleArray(n)
        fft(re, im)
        return FloatArray(n / 2) { (20 * log10(sqrt(re[it] * re[it] + im[it] * im[it]) + 1e-9)).toFloat() }
    }

    /**
     * Malus's-law fit: I(θ) = A + C·cos2θ + S·sin2θ.
     * Returns (mean, modulation depth 0..1, angle of maximum transmission in degrees, rms residual).
     */
    data class MalusFit(val mean: Double, val contrast: Double, val maxAngleDeg: Double, val rms: Double) {
        fun at(deg: Double): Double {
            val amp = contrast * mean
            return mean + amp * cos(2 * Math.toRadians(deg - maxAngleDeg))
        }
    }

    fun malusFit(anglesDeg: List<Double>, intensity: List<Double>): MalusFit? {
        if (anglesDeg.size < 8) return null
        val m = Array(3) { DoubleArray(3) }
        val v = DoubleArray(3)
        for (i in anglesDeg.indices) {
            val t = Math.toRadians(anglesDeg[i]) * 2
            val f = doubleArrayOf(1.0, cos(t), sin(t))
            for (r in 0..2) {
                v[r] += f[r] * intensity[i]
                for (c in 0..2) m[r][c] += f[r] * f[c]
            }
        }
        val p = solve3(m, v) ?: return null
        val amp = sqrt(p[1] * p[1] + p[2] * p[2])
        val phi = Math.toDegrees(0.5 * atan2(p[2], p[1]))
        var ss = 0.0
        val fit = MalusFit(p[0], if (p[0] > 0) amp / p[0] else 0.0, phi, 0.0)
        for (i in anglesDeg.indices) { val d = intensity[i] - fit.at(anglesDeg[i]); ss += d * d }
        return fit.copy(rms = sqrt(ss / anglesDeg.size))
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { r -> DoubleArray(4) { c -> if (c < 3) a[r][c] else b[r] } }
        for (col in 0..2) {
            var piv = col
            for (r in col + 1..2) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in 0..2) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                for (c in col..3) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    /** Wrap an angle difference into (-90°, 90°] — polarisation axes repeat every 180°. */
    fun wrap90(deg: Double): Double {
        var d = deg % 180.0
        if (d > 90) d -= 180
        if (d <= -90) d += 180
        return d
    }

    fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
