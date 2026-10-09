package com.shuddh.lab.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Grain Scan: counts grains spread on a plain background and flags impurities — stones, husk,
 * insects (much darker or differently coloured than the typical grain), broken grains (much
 * smaller) and touching clumps. Pure image analysis on an ARGB pixel array, unit-tested.
 *
 * 1. Background polarity is read from the image border (light paper or dark cloth).
 * 2. Otsu's threshold splits objects from background; 4-connected components become blobs.
 * 3. Each blob's area and mean colour are compared with the *median grain*, so the method adapts
 *    to rice, dal, wheat or spices without per-grain training.
 */
object GrainScan {
    enum class Kind { GRAIN, BROKEN, FOREIGN, DISCOLOURED, CLUMP }

    data class Blob(val cx: Float, val cy: Float, val area: Int, val r: Double, val g: Double, val b: Double, val kind: Kind) {
        val luma get() = 0.299 * r + 0.587 * g + 0.114 * b
    }

    data class Result(
        val blobs: List<Blob>,
        val w: Int,
        val h: Int,
        val darkBackground: Boolean,
        /** Estimated grain count (clumps split by area). */
        val grains: Int,
        val broken: Int,
        val foreign: Int,
        val discoloured: Int,
    ) {
        val total get() = grains + broken + foreign + discoloured
        /** Impurity share by count (foreign + discoloured), %. */
        val impurityPct get() = if (total == 0) 0.0 else 100.0 * (foreign + discoloured) / total
        val brokenPct get() = if (total == 0) 0.0 else 100.0 * broken / total
        val purityPct get() = 100.0 - impurityPct
    }

    fun otsu(l: IntArray): Int {
        val hist = IntArray(256); l.forEach { hist[it]++ }
        val total = l.size.toDouble()
        var sumAll = 0.0; for (i in 0..255) sumAll += i * hist[i].toDouble()
        var wB = 0.0; var sumB = 0.0; var best = 0.0; var thr = 127
        for (t in 0..255) {
            wB += hist[t]; if (wB == 0.0) continue
            val wF = total - wB; if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = t }
        }
        return thr
    }

    /** Analyses [px] (ARGB, row-major, w×h). Ignores blobs touching the border and specks < [minArea]. */
    fun analyse(px: IntArray, w: Int, h: Int, minArea: Int = 12): Result {
        val n = w * h
        val R = IntArray(n); val G = IntArray(n); val B = IntArray(n); val L = IntArray(n)
        for (i in 0 until n) {
            val c = px[i]
            R[i] = (c shr 16) and 0xff; G[i] = (c shr 8) and 0xff; B[i] = c and 0xff
            L[i] = ((299 * R[i] + 587 * G[i] + 114 * B[i]) / 1000)
        }
        // Background polarity from the border.
        var border = 0L; var bc = 0
        for (x in 0 until w) { border += L[x] + L[(h - 1) * w + x]; bc += 2 }
        for (y in 0 until h) { border += L[y * w] + L[y * w + w - 1]; bc += 2 }
        val bgMean = border.toDouble() / bc
        val thr = otsu(L)
        val dark = bgMean <= thr
        // Objects = pixels far enough from the background level. The margin is a quarter of the
        // background→object contrast, so mid-tone stones on dark cloth still count as objects.
        var objSum = 0L; var objN = 0
        for (v in L) if (if (dark) v > thr else v <= thr) { objSum += v; objN++ }
        val objMean = if (objN > 0) objSum.toDouble() / objN else thr.toDouble()
        val margin = maxOf(18.0, 0.25 * abs(objMean - bgMean))
        val fg = BooleanArray(n) { abs(L[it] - bgMean) > margin }

        // 4-connected components (iterative flood fill).
        val label = IntArray(n) { -1 }
        val stack = IntArray(n)
        data class Acc(var area: Int = 0, var sx: Long = 0, var sy: Long = 0, var r: Long = 0, var g: Long = 0, var b: Long = 0, var edge: Boolean = false)
        val accs = ArrayList<Acc>()
        for (start in 0 until n) {
            if (!fg[start] || label[start] >= 0) continue
            val id = accs.size; val a = Acc(); accs += a
            var sp = 0; stack[sp++] = start; label[start] = id
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                a.area++; a.sx += x; a.sy += y; a.r += R[p]; a.g += G[p]; a.b += B[p]
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) a.edge = true
                if (x > 0 && fg[p - 1] && label[p - 1] < 0) { label[p - 1] = id; stack[sp++] = p - 1 }
                if (x < w - 1 && fg[p + 1] && label[p + 1] < 0) { label[p + 1] = id; stack[sp++] = p + 1 }
                if (y > 0 && fg[p - w] && label[p - w] < 0) { label[p - w] = id; stack[sp++] = p - w }
                if (y < h - 1 && fg[p + w] && label[p + w] < 0) { label[p + w] = id; stack[sp++] = p + w }
            }
        }
        val raw = accs.filter { !it.edge && it.area >= minArea }
        if (raw.isEmpty()) return Result(emptyList(), w, h, dark, 0, 0, 0, 0)

        fun med(xs: List<Double>) = xs.sorted()[xs.size / 2]
        val areas = raw.map { it.area.toDouble() }
        val mArea = med(areas)
        // Typical grain colour: median over normal-sized blobs only.
        val typical = raw.filter { it.area in (mArea * 0.6).toInt()..(mArea * 1.6).toInt() }.ifEmpty { raw }
        val mr = med(typical.map { it.r.toDouble() / it.area }); val mg = med(typical.map { it.g.toDouble() / it.area }); val mb = med(typical.map { it.b.toDouble() / it.area })
        val mL = 0.299 * mr + 0.587 * mg + 0.114 * mb

        var grains = 0; var broken = 0; var foreign = 0; var disc = 0
        val blobs = raw.map { a ->
            val r = a.r.toDouble() / a.area; val g = a.g.toDouble() / a.area; val b = a.b.toDouble() / a.area
            val l = 0.299 * r + 0.587 * g + 0.114 * b
            // Colour distance after removing brightness (chroma shift) and brightness deviation.
            val dChroma = sqrt((r - l - (mr - mL)).let { it * it } + (g - l - (mg - mL)).let { it * it } + (b - l - (mb - mL)).let { it * it })
            val dLuma = abs(l - mL) / mL.coerceAtLeast(1.0)
            val kind = when {
                dLuma > 0.35 && a.area >= mArea * 0.25 -> Kind.FOREIGN
                dChroma > 28 -> Kind.DISCOLOURED
                a.area < mArea * 0.55 -> Kind.BROKEN
                a.area > mArea * 2.2 -> Kind.CLUMP
                else -> Kind.GRAIN
            }
            when (kind) {
                Kind.GRAIN -> grains++
                Kind.CLUMP -> grains += (a.area / mArea).toInt().coerceAtLeast(2)
                Kind.BROKEN -> broken++
                Kind.FOREIGN -> foreign++
                Kind.DISCOLOURED -> disc++
            }
            Blob(a.sx.toFloat() / a.area / w, a.sy.toFloat() / a.area / h, a.area, r, g, b, kind)
        }
        return Result(blobs, w, h, dark, grains, broken, foreign, disc)
    }

    fun grade(r: Result): Triple<Level, String, String> = when {
        r.total < 15 -> Triple(Level.INCONCLUSIVE, "Too few grains", "Spread at least a handful (30+ grains) in a single layer, not touching.")
        r.impurityPct <= 1.0 && r.brokenPct <= 10 -> Triple(Level.SAFE, "Clean", "Clean grain — no meaningful stones, husk or discoloured grains.")
        r.impurityPct <= 3.0 -> Triple(Level.CAUTION, "Some impurities", "Pick out the flagged pieces and wash well before cooking.")
        else -> Triple(Level.UNSAFE, "Heavily contaminated", "Many foreign or discoloured pieces — sort thoroughly or return it; check for insects and mould.")
    }
}
