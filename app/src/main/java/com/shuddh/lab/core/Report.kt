package com.shuddh.lab.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import java.io.File

/**
 * A designed, detailed single-verdict report (A4): branded header, verdict badge, the sample,
 * health hazards with a body map, what to do, how the test works, the evidence and a QR code
 * that any Shuddh phone can scan to import the alert.
 */
object Report {
    private const val W = 595; private const val H = 842; private const val M = 36f

    fun build(ctx: Context, o: Outcome, rec: ScanRecord, qr: Bitmap?, chainOk: Boolean): File {
        val doc = PdfDocument()
        val hazards = Hazards.forOutcome(o)
        val lvCol = o.level.argb.toInt()
        val ink = Color.rgb(15, 23, 42); val muted = Color.rgb(100, 116, 139)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 10.5f }
        val pm = Paint(p).apply { color = muted; textSize = 9f }
        val pb = Paint(p).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val h1 = Paint(pb).apply { textSize = 22f }
        val h2 = Paint(pb).apply { textSize = 13.5f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        var pageNo = 0
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
        var c: Canvas = page.canvas
        var y = 0f

        fun footer() {
            fill.color = Color.rgb(241, 245, 249); c.drawRect(0f, H - 34f, W.toFloat(), H.toFloat(), fill)
            c.drawText("Shuddh · The People's Lab · made offline on ${Build.MANUFACTURER} ${Build.MODEL} · record #${rec.id} · page $pageNo", M, H - 15f, pm)
        }
        fun newPage() { footer(); doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create()); c = page.canvas; y = M }
        fun need(h: Float) { if (y + h > H - 50f) newPage() }
        fun wrap(text: String, paint: Paint, x: Float, maxW: Float, lineGap: Float = 3.5f): Float {
            var rest = text; var lines = 0
            while (rest.isNotEmpty()) {
                var n = paint.breakText(rest, true, maxW, null)
                if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) n = sp + 1 }
                need(paint.textSize + lineGap)
                c.drawText(rest.substring(0, n).trimEnd(), x, y + paint.textSize, paint)
                y += paint.textSize + lineGap; rest = rest.substring(n); lines++
            }
            return y
        }
        fun section(title: String, accent: Int) {
            need(40f); y += 14f
            fill.color = accent; c.drawRoundRect(RectF(M, y, M + 4f, y + 16f), 2f, 2f, fill)
            c.drawText(title, M + 12f, y + 13f, h2); y += 24f
        }

        // ── Header band ──
        fill.shader = LinearGradient(0f, 0f, W.toFloat(), 0f, Color.rgb(6, 95, 70), Color.rgb(14, 116, 144), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), 96f, fill); fill.shader = null
        val white = Paint(h1).apply { color = Color.WHITE }
        c.drawText("Shuddh Purity Report", M, 44f, white)
        c.drawText("${o.instrument} · ${stamp(rec.time)}", M, 66f, Paint(p).apply { color = Color.rgb(209, 250, 229) })
        c.drawText("Phone-sensor screening — an educated estimate, not a lab certificate", M, 82f, Paint(pm).apply { color = Color.rgb(167, 243, 208) })
        y = 116f

        // ── Verdict badge + key numbers ──
        fill.color = lvCol; fill.alpha = 30; c.drawRoundRect(RectF(M, y, W - M, y + 92f), 14f, 14f, fill); fill.alpha = 255
        fill.color = lvCol; c.drawRoundRect(RectF(M + 14f, y + 16f, M + 150f, y + 76f), 12f, 12f, fill)
        val badge = Paint(h1).apply { color = Color.WHITE; textSize = 20f; textAlign = Paint.Align.CENTER }
        c.drawText(o.levelLabel?.en ?: o.level.name, M + 82f, y + 53f, badge)
        c.drawText(o.analyte.en, M + 166f, y + 34f, Paint(h2).apply { textSize = 16f })
        c.drawText(o.valueText(), M + 166f, y + 56f, Paint(pb).apply { textSize = 14f; color = lvCol })
        c.drawText(o.headline.take(80), M + 166f, y + 74f, pm)
        y += 104f

        // ── The sample ──
        section("The sample", Color.rgb(14, 116, 144))
        listOf("Sample" to rec.sampleTag.ifBlank { "—" }, "Vendor / brand" to rec.vendor.ifBlank { "—" }, "Area" to rec.area.ifBlank { "—" },
            "Location" to (rec.lat?.let { "%.5f, %.5f".format(it, rec.lon) } ?: "—"), "Tested" to stamp(rec.time)).forEach { (k, v) ->
            need(16f); c.drawText(k, M, y + 10f, pm); c.drawText(v, M + 110f, y + 10f, p); y += 16f
        }

        // ── Health hazards with a body map ──
        if (hazards.isNotEmpty()) {
            section("Health hazards", Color.rgb(225, 29, 72))
            need(170f)
            val top = y
            drawBody(c, M + 10f, top, hazards.flatMap { h -> h.effects.map { it.organ to it.severity } })
            val x0 = M + 150f; val tw = W - M - x0
            hazards.forEach { h ->
                c.drawText("${h.title}", x0, y + 12f, Paint(pb).apply { textSize = 12f; color = Color.rgb(190, 18, 60) }); y += 18f
                h.effects.forEach { e ->
                    need(14f)
                    fill.color = when (e.severity) { 3 -> Color.rgb(225, 29, 72); 2 -> Color.rgb(217, 119, 6); else -> Color.rgb(100, 116, 139) }
                    for (k in 0 until 3) { fill.alpha = if (k < e.severity) 255 else 50; c.drawCircle(x0 + 4f + k * 8f, y + 6f, 3f, fill) }
                    fill.alpha = 255
                    val keepX = y; c.drawText(e.organ.label + ":", x0 + 30f, y + 10f, pb)
                    val lw = pb.measureText(e.organ.label + ": ")
                    y = keepX; wrap(e.text, p, x0 + 30f + lw, tw - 30f - lw)
                }
                need(14f); c.drawText("Most at risk: " + h.atRisk.joinToString(", "), x0, y + 10f, pm); y += 18f
            }
            y = maxOf(y, top + 160f)
        }

        // ── What to do ──
        section("What to do now", Color.rgb(22, 163, 74))
        (hazards.flatMap { it.now }.ifEmpty { o.advice.map { it.en } }).distinct().forEachIndexed { i, s ->
            need(16f); fill.color = Color.rgb(22, 163, 74); c.drawCircle(M + 7f, y + 7f, 7f, fill)
            c.drawText("${i + 1}", M + 7f, y + 10.5f, Paint(pb).apply { color = Color.WHITE; textSize = 9f; textAlign = Paint.Align.CENTER })
            wrap(s, p, M + 22f, W - 2 * M - 22f)
            y += 2f
        }
        hazards.firstOrNull()?.let { h ->
            need(30f); wrap("Long term: ${h.longTerm}", pm, M, W - 2 * M)
            wrap("Your rights: ${h.law}", pm, M, W - 2 * M)
        }

        // ── How the test works ──
        section("How this test works", Color.rgb(124, 58, 237))
        wrap(guide(o), p, M, W - 2 * M)
        wrap(o.limitNote, pm, M, W - 2 * M)

        // ── Evidence ──
        section("Evidence", Color.rgb(37, 99, 235))
        o.evidence.forEach { e ->
            need(16f)
            fill.color = when (e.ok) { true -> Color.rgb(22, 163, 74); false -> Color.rgb(225, 29, 72); null -> Color.rgb(37, 99, 235) }
            c.drawCircle(M + 4f, y + 7f, 4f, fill)
            c.drawText(e.step, M + 14f, y + 10f, Paint(pb).apply { textSize = 8.5f; color = fill.color })
            val lw = 86f
            wrap(e.text, Paint(p).apply { textSize = 9.5f }, M + 14f + lw, W - 2 * M - 14f - lw, 2.5f)
        }

        // ── QR + integrity ──
        need(150f); y += 12f
        fill.color = Color.rgb(248, 250, 252); c.drawRoundRect(RectF(M, y, W - M, y + 132f), 12f, 12f, fill)
        qr?.let { c.drawBitmap(Bitmap.createScaledBitmap(it, 116, 116, false), M + 8f, y + 8f, null) }
        val tx = M + 136f; var ty = y + 22f
        c.drawText("Share this result", tx, ty, h2); ty += 18f
        listOf("Scan with any Shuddh phone: Hive → Scan, to import this alert.", "Or forward this PDF to family, your RWA or the Food Safety Officer.",
            "Record hash: ${rec.hash.take(40)}…", if (chainOk) "Tamper check: history chain intact ✓" else "Tamper check: history chain BROKEN — treat with caution").forEach {
            c.drawText(it, tx, ty, pm); ty += 15f
        }
        y += 140f
        footer(); doc.finishPage(page)
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        val f = File(dir, "Shuddh_${o.analyte.en.replace(Regex("[^A-Za-z0-9]+"), "_")}_${rec.id}.pdf")
        f.outputStream().use { doc.writeTo(it) }; doc.close()
        return f
    }

    /** Body silhouette with the affected organs filled by severity. */
    private fun drawBody(c: Canvas, x: Float, y: Float, hits: List<Pair<Hazards.Organ, Int>>) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 232, 240) }
        c.drawCircle(x + 60f, y + 18f, 14f, p)                                      // head
        c.drawRoundRect(RectF(x + 38f, y + 34f, x + 82f, y + 104f), 14f, 14f, p)   // torso
        c.drawRoundRect(RectF(x + 22f, y + 38f, x + 34f, y + 98f), 6f, 6f, p); c.drawRoundRect(RectF(x + 86f, y + 38f, x + 98f, y + 98f), 6f, 6f, p)
        c.drawRoundRect(RectF(x + 42f, y + 104f, x + 56f, y + 156f), 6f, 6f, p); c.drawRoundRect(RectF(x + 64f, y + 104f, x + 78f, y + 156f), 6f, 6f, p)
        val spot = mapOf(Hazards.Organ.BRAIN to (60f to 16f), Hazards.Organ.LUNGS to (50f to 48f), Hazards.Organ.HEART to (66f to 52f), Hazards.Organ.LIVER to (52f to 66f),
            Hazards.Organ.STOMACH to (68f to 70f), Hazards.Organ.KIDNEY to (60f to 84f), Hazards.Organ.BONES to (49f to 130f), Hazards.Organ.BLOOD to (28f to 64f), Hazards.Organ.SKIN to (92f to 64f))
        hits.groupBy { it.first }.forEach { (o, l) ->
            val s = l.maxOf { it.second }; val (dx, dy) = spot[o] ?: return@forEach
            p.color = when (s) { 3 -> Color.rgb(225, 29, 72); 2 -> Color.rgb(245, 158, 11); else -> Color.rgb(148, 163, 184) }
            p.alpha = 70; c.drawCircle(x + dx, y + dy, 11f, p); p.alpha = 255; c.drawCircle(x + dx, y + dy, 5.5f, p)
        }
    }

    fun guide(o: Outcome): String {
        val t = (o.instrument + " " + o.analyteId).lowercase()
        return when {
            "purity" in t -> "The phone lit the sample with several colours of light and read how much came back (milk's fat and protein scatter light; added water makes it darker and bluish), pinged it with a 2–18 kHz sound sweep and listened to the echo, and watched the magnetometer for metal particles. The readings were matched against samples of known purity trained on this same phone, which also estimates % water."
            "starch" in t -> "Iodine forms a blue–black complex with starch. The camera compared the sample's colour with a pure-milk control in CIELAB colour space; a shift towards blue means starch (FSSAI DART method)."
            "detergent" in t -> "Milk and water were shaken and the camera timed how long the foam stood. Detergent stabilises bubbles, so the foam survives far longer than with real milk (FSSAI lather test)."
            else -> "The phone's own sensors measured the sample and compared it with references; see the evidence below for each step."
        }
    }
}
