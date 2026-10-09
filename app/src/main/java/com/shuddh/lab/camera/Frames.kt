package com.shuddh.lab.camera

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/** Column-binned RGB intensity across a horizontal band — the raw spectrum. Values 0..255. */
class Profile(val r: FloatArray, val g: FloatArray, val b: FloatArray, val saturated: Float) {
    val size get() = r.size
    val total: FloatArray by lazy { FloatArray(size) { r[it] + g[it] + b[it] } }

    fun scaled(k: Float) = Profile(
        FloatArray(size) { r[it] * k }, FloatArray(size) { g[it] * k }, FloatArray(size) { b[it] * k }, saturated,
    )

    companion object {
        /**
         * Robust average: whole frames whose overall brightness is an outlier (a hand's shadow,
         * a flicker, auto-exposure hunting) are dropped by the MAD rule, then each bin takes a
         * 20 %-trimmed mean so single hot pixels or glints can't drag the spectrum.
         */
        fun average(ps0: List<Profile>): Profile {
            val ps = Robust.keepInliers(ps0) { p -> p.total.sum().toDouble() }
            val n = ps.first().size
            fun avg(sel: (Profile) -> FloatArray) = FloatArray(n) { i -> Robust.trimmedMean(ps.map { sel(it)[i] }) }
            return Profile(avg { it.r }, avg { it.g }, avg { it.b }, ps.maxOf { it.saturated })
        }
    }
}

/** Mean colour of an ROI, 0..255 per channel, plus the fraction of clipped pixels. */
data class Rgb(val r: Float, val g: Float, val b: Float, val saturated: Float = 0f) {
    val luma get() = 0.299f * r + 0.587f * g + 0.114f * b

    companion object {
        /** Robust colour average: outlier frames dropped (MAD on luma), then a 20 %-trimmed mean per channel. */
        fun average(xs0: List<Rgb>): Rgb {
            val xs = Robust.keepInliers(xs0) { it.luma.toDouble() }
            return Rgb(Robust.trimmedMean(xs.map { it.r }), Robust.trimmedMean(xs.map { it.g }), Robust.trimmedMean(xs.map { it.b }), xs.maxOf { it.saturated })
        }
    }
}

/** Robust statistics shared by every camera instrument. */
object Robust {
    /** Mean after dropping the lowest and highest [trim] fraction (plain mean for < 5 values). */
    fun trimmedMean(v: List<Float>, trim: Double = 0.2): Float {
        if (v.size < 5) return v.average().toFloat()
        val s = v.sorted(); val k = (s.size * trim).toInt()
        return s.subList(k, s.size - k).average().toFloat()
    }

    /** Keeps items whose [key] lies within 3 scaled-MADs of the median (all of them if fewer than 5). */
    fun <T> keepInliers(xs: List<T>, key: (T) -> Double): List<T> {
        if (xs.size < 5) return xs
        val k = xs.map(key)
        val med = k.sorted()[k.size / 2]
        val mad = k.map { kotlin.math.abs(it - med) }.sorted()[k.size / 2] * 1.4826
        if (mad < 1e-9) return xs
        return xs.filterIndexed { i, _ -> kotlin.math.abs(k[i] - med) <= 3 * mad }.ifEmpty { xs }
    }
}

data class Lab(val l: Double, val a: Double, val b: Double) {
    fun dist(o: Lab) = sqrt((l - o.l).pow(2) + (a - o.a).pow(2) + (b - o.b).pow(2))
}

object Frames {
    private fun clampRect(bmp: Bitmap, roi: RectF): IntArray {
        val x0 = (roi.left * bmp.width).toInt().coerceIn(0, bmp.width - 2)
        val y0 = (roi.top * bmp.height).toInt().coerceIn(0, bmp.height - 2)
        val x1 = (roi.right * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
        val y1 = (roi.bottom * bmp.height).toInt().coerceIn(y0 + 1, bmp.height)
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    fun bandProfile(bmp: Bitmap, roi: RectF, bins: Int = 160): Profile {
        val (x0, y0, w, h) = clampRect(bmp, roi).let { listOf(it[0], it[1], it[2], it[3]) }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        val nb = minOf(bins, w)
        val r = FloatArray(nb); val g = FloatArray(nb); val b = FloatArray(nb); val cnt = IntArray(nb)
        var sat = 0
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val bin = x * nb / w
            val rr = (c shr 16) and 0xff; val gg = (c shr 8) and 0xff; val bb = c and 0xff
            r[bin] += rr.toFloat(); g[bin] += gg.toFloat(); b[bin] += bb.toFloat(); cnt[bin]++
            if (rr >= 250 || gg >= 250 || bb >= 250) sat++
        }
        for (i in 0 until nb) if (cnt[i] > 0) { r[i] /= cnt[i]; g[i] /= cnt[i]; b[i] /= cnt[i] }
        return Profile(r, g, b, sat.toFloat() / (w * h))
    }

    /**
     * Focus measure: variance of the Laplacian over a downsampled centre crop. Higher = sharper.
     * ~O(20k) pixels, cheap enough for every frame.
     */
    fun sharpness(bmp: Bitmap): Double {
        val w = 200; val h = 150
        val x0 = (bmp.width - bmp.width / 2) / 2; val y0 = (bmp.height - bmp.height / 2) / 2
        val sx = (bmp.width / 2) / w.toFloat(); val sy = (bmp.height / 2) / h.toFloat()
        val g = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = bmp.getPixel((x0 + x * sx).toInt().coerceIn(0, bmp.width - 1), (y0 + y * sy).toInt().coerceIn(0, bmp.height - 1))
            g[y * w + x] = 0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff)
        }
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
            sum += lap; sq += lap * lap; n++
        }
        val m = sum / n
        return sq / n - m * m
    }

    fun meanRgb(bmp: Bitmap, roi: RectF): Rgb {
        val (x0, y0, w, h) = clampRect(bmp, roi).let { listOf(it[0], it[1], it[2], it[3]) }
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        var r = 0L; var g = 0L; var b = 0L; var sat = 0
        for (c in px) {
            val rr = (c shr 16) and 0xff; val gg = (c shr 8) and 0xff; val bb = c and 0xff
            r += rr; g += gg; b += bb
            if (rr >= 250 || gg >= 250 || bb >= 250) sat++
        }
        val n = px.size.toFloat()
        return Rgb(r / n, g / n, b / n, sat / n)
    }

    /**
     * Colour of [sample] relative to a white reference patch in the same frame, as CIELAB.
     * Dividing by the white patch cancels illumination and camera white-balance drift.
     */
    fun relativeLab(sample: Rgb, white: Rgb): Lab {
        fun lin(v: Float, w: Float): Double {
            val c = (v / w.coerceAtLeast(1f)).toDouble().coerceIn(0.0, 1.2)
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(sample.r, white.r); val g = lin(sample.g, white.g); val b = lin(sample.b, white.b)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        return Lab(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
}

/** Averages the next N frames from the analyser thread and hands the result to the main thread. */
class Collector<T>(private val combine: (List<T>) -> T) {
    private val buf = mutableListOf<T>()
    private var need = 0
    private var done: ((T) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    @get:Synchronized
    val busy get() = need > 0

    @Synchronized
    fun start(n: Int, cb: (T) -> Unit) {
        buf.clear(); need = n; done = cb
    }

    @Synchronized
    fun offer(x: T) {
        if (need <= 0) return
        buf.add(x)
        if (buf.size >= need) {
            need = 0
            val r = combine(buf.toList())
            val cb = done
            done = null
            main.post { cb?.invoke(r) }
        }
    }
}
