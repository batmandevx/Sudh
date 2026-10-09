package com.shuddh.lab.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Purity Passport: a PDF report generated on-device — an infographic summary page followed by
 * every record with its evidence and SHA-256 chain links. Editing or deleting any earlier record
 * breaks every hash after it.
 */
object Passport {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    private fun levelColor(l: Level) = when (l) {
        Level.SAFE -> Color.rgb(22, 163, 106)
        Level.CAUTION -> Color.rgb(217, 140, 0)
        Level.UNSAFE -> Color.rgb(220, 38, 76)
        Level.INCONCLUSIVE -> Color.rgb(120, 130, 145)
    }

    fun build(ctx: Context, records: List<ScanRecord>, chainOk: Boolean, title: String): File {
        val doc = PdfDocument()
        val body = Paint().apply { textSize = 10.5f; isAntiAlias = true; color = Color.rgb(30, 35, 45) }
        val muted = Paint(body).apply { color = Color.rgb(110, 120, 135); textSize = 9f }
        val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        val mono = Paint(body).apply { textSize = 7.5f; typeface = Typeface.MONOSPACE; color = Color.rgb(110, 120, 135) }
        var pageNo = 0
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
        var c: Canvas = page.canvas
        var y = M

        fun footer() {
            c.drawText("Shuddh · The People's Lab · generated offline on ${Build.MANUFACTURER} ${Build.MODEL} · page $pageNo", M, H - 20f, muted)
        }
        fun newPage() {
            footer(); doc.finishPage(page)
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create()); c = page.canvas; y = M
        }
        fun wrap(text: String, p: Paint, x: Float, maxW: Float, gap: Float = 3f) {
            var rest = text
            while (rest.isNotEmpty()) {
                var n = p.breakText(rest, true, maxW, null)
                if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) n = sp + 1 }
                if (y + p.textSize + gap > H - 50) newPage()
                c.drawText(rest.substring(0, n).trimEnd(), x, y + p.textSize, p)
                y += p.textSize + gap
                rest = rest.substring(n)
            }
        }

        // ---------- Summary page ----------
        val header = Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), 0f,
                intArrayOf(Color.rgb(139, 92, 246), Color.rgb(34, 211, 238), Color.rgb(52, 211, 153)), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), 120f, header)
        val white = Paint().apply { color = Color.WHITE; isAntiAlias = true; textSize = 30f; typeface = Typeface.DEFAULT_BOLD }
        c.drawText("Shuddh Purity Passport", M, 58f, white)
        c.drawText(title, M, 86f, Paint(white).apply { textSize = 14f; typeface = Typeface.DEFAULT })
        c.drawText(stamp(System.currentTimeMillis()), M, 106f, Paint(white).apply { textSize = 10f; typeface = Typeface.DEFAULT })
        y = 150f

        val valid = records.filter { it.level != Level.INCONCLUSIVE }
        val safe = valid.count { it.level == Level.SAFE }
        val caution = valid.count { it.level == Level.CAUTION }
        val unsafe = valid.count { it.level == Level.UNSAFE }

        // Donut
        val cx = M + 80f; val cy = y + 80f; val r = 62f
        val ring = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 22f; isAntiAlias = true }
        ring.color = Color.rgb(230, 233, 238); c.drawCircle(cx, cy, r, ring)
        var start = -90f
        val tot = (safe + caution + unsafe).coerceAtLeast(1).toFloat()
        listOf(safe to Level.SAFE, caution to Level.CAUTION, unsafe to Level.UNSAFE).forEach { (n, l) ->
            if (n > 0) {
                val sweep = 360f * n / tot
                ring.color = levelColor(l)
                c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, false, ring)
                start += sweep
            }
        }
        val big = Paint(bold).apply { textSize = 26f; textAlign = Paint.Align.CENTER }
        c.drawText("${records.size}", cx, cy + 6f, big)
        c.drawText("scans", cx, cy + 22f, Paint(muted).apply { textAlign = Paint.Align.CENTER })

        // Stat column
        val sx = M + 190f
        fun stat(label: String, v: String, col: Int, dy: Float) {
            c.drawText(v, sx, y + dy, Paint(bold).apply { textSize = 20f; color = col })
            c.drawText(label, sx + 70f, y + dy - 2f, muted)
        }
        stat("safe", "$safe", levelColor(Level.SAFE), 30f)
        stat("caution", "$caution", levelColor(Level.CAUTION), 62f)
        stat("unsafe", "$unsafe", levelColor(Level.UNSAFE), 94f)
        stat("confirmed by 2 scans", "${records.count { it.confirmed }}", Color.rgb(59, 130, 246), 126f)
        val chipP = Paint().apply { color = if (chainOk) Color.rgb(220, 252, 231) else Color.rgb(254, 226, 226) }
        c.drawRoundRect(RectF(sx + 200f, y + 14f, W - M, y + 60f), 10f, 10f, chipP)
        c.drawText(if (chainOk) "HASH CHAIN INTACT" else "CHAIN BROKEN", sx + 212f, y + 34f, Paint(bold).apply { color = if (chainOk) levelColor(Level.SAFE) else levelColor(Level.UNSAFE) })
        c.drawText("SHA-256 linked records", sx + 212f, y + 50f, muted)
        y += 180f

        // Per-test bars
        val byTest = Insights.byTest(records)
        if (byTest.isNotEmpty()) {
            c.drawText("Results by test", M, y, Paint(bold).apply { textSize = 14f }); y += 14f
            val barX = M + 170f; val barW = W - M - barX - 60f
            byTest.take(12).forEach { (name, t) ->
                c.drawText(name.take(30), M, y + 11f, body)
                var x = barX
                listOf(t.safe to Level.SAFE, t.caution to Level.CAUTION, t.unsafe to Level.UNSAFE).forEach { (n, l) ->
                    if (n > 0) {
                        val w = barW * n / t.total
                        c.drawRoundRect(RectF(x, y + 2f, x + w, y + 14f), 4f, 4f, Paint().apply { color = levelColor(l) })
                        x += w
                    }
                }
                c.drawText("${t.unsafe}/${t.total} fail", barX + barW + 8f, y + 11f, muted)
                y += 20f
            }
            y += 10f
        }

        val vendors = records.map { it.vendor }.filter { it.isNotBlank() }.distinct()
        if (vendors.isNotEmpty()) {
            c.drawText("Vendors", M, y, Paint(bold).apply { textSize = 14f }); y += 8f
            vendors.take(8).forEach { v ->
                val rs = records.filter { it.vendor == v && it.level != Level.INCONCLUSIVE }
                wrap("• $v — ${rs.count { it.level == Level.UNSAFE }} of ${rs.size} scans failed", body, M, W - 2 * M)
            }
            y += 6f
        }
        wrap("Screening-grade results from phone-based instruments calibrated by the user. Confirm critical findings with an accredited (NABL) laboratory before legal action.", muted, M, W - 2 * M)
        newPage()

        // ---------- Records ----------
        records.forEach { rec ->
            if (y > H - 200) newPage()
            val top = y
            val stripe = Paint().apply { color = levelColor(rec.level) }
            y += 10f
            c.drawText("#${rec.id}  ${rec.analyte}", M + 14f, y + 12f, Paint(bold).apply { textSize = 13f })
            val chip = rec.level.name + if (rec.confirmed) " ✓" else ""
            val chipW = bold.measureText(chip) + 16f
            c.drawRoundRect(RectF(W - M - chipW - 10f, y, W - M - 10f, y + 16f), 8f, 8f, stripe)
            c.drawText(chip, W - M - chipW - 2f, y + 12f, Paint(bold).apply { color = Color.WHITE })
            y += 20f
            wrap("${stamp(rec.time)} · ${rec.instrument} · ${rec.value?.let { "${fmt(it)} ${rec.unit}" } ?: "—"}" +
                (if (rec.vendor.isNotBlank()) " · ${rec.vendor}" else "") + (if (rec.area.isNotBlank()) " · ${rec.area}" else ""), muted, M + 14f, W - 2 * M - 28f)
            wrap(rec.headline, body, M + 14f, W - 2 * M - 28f)
            rec.evidence.forEach { wrap("› $it", Paint(body).apply { textSize = 9f }, M + 20f, W - 2 * M - 34f, 2f) }
            wrap("prev ${rec.prevHash}", mono, M + 14f, W - 2 * M - 28f, 1f)
            wrap("hash ${rec.hash}", mono, M + 14f, W - 2 * M - 28f, 1f)
            y += 8f
            if (y > top) c.drawRect(M, top, M + 4f, y, stripe)
            y += 10f
        }
        footer(); doc.finishPage(page)

        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        val f = File(dir, "Shuddh_Passport_${System.currentTimeMillis()}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    /** Copies the PDF into the public Downloads/Shuddh folder. Returns a human-readable location. */
    fun saveToDownloads(ctx: Context, f: File): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Shuddh")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the download")
            ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            "Downloads/Shuddh/${f.name}"
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
            f.copyTo(File(dir, f.name), overwrite = true)
            "${dir.absolutePath}/${f.name}"
        }
    }

    fun share(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "com.shuddh.lab.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share Purity Passport").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens the SMS app pre-filled; the user presses send. */
    fun sms(ctx: Context, phone: String, body: String) {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone)}"))
            .putExtra("sms_body", body).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
