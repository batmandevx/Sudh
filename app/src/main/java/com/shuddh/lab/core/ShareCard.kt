package com.shuddh.lab.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import java.io.File

/** A 1080×1350 "Kitchen Health" card for sharing on WhatsApp / Instagram. Drawn entirely on-device. */
object ShareCard {
    private val spectrum = intArrayOf(0xFF8B5CF6.toInt(), 0xFF3B82F6.toInt(), 0xFF22D3EE.toInt(), 0xFF34D399.toInt(), 0xFFFBBF24.toInt(), 0xFFF43F5E.toInt(), 0xFF8B5CF6.toInt())

    fun render(ctx: Context, store: Store): Bitmap {
        val w = 1080; val h = 1350
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val display = runCatching { ResourcesCompat.getFont(ctx, com.shuddh.lab.R.font.unbounded) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val body = runCatching { ResourcesCompat.getFont(ctx, com.shuddh.lab.R.font.manrope) }.getOrNull() ?: Typeface.DEFAULT
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background: deep navy with aurora glows.
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF0B1324.toInt(), 0xFF060A12.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        fun glow(x: Float, y: Float, r: Float, col: Int) { p.shader = RadialGradient(x, y, r, col, 0, Shader.TileMode.CLAMP); c.drawCircle(x, y, r, p) }
        glow(200f, 220f, 600f, 0x558B5CF6); glow(950f, 520f, 520f, 0x4422D3EE); glow(540f, 1250f, 700f, 0x4434D399)
        p.shader = null

        // Logo droplet + wordmark
        val drop = Path().apply {
            moveTo(110f, 70f); cubicTo(160f, 130f, 175f, 160f, 170f, 180f); cubicTo(160f, 215f, 135f, 225f, 110f, 225f)
            cubicTo(85f, 225f, 60f, 215f, 50f, 180f); cubicTo(45f, 160f, 60f, 130f, 110f, 70f); close()
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 10f
        p.shader = LinearGradient(50f, 70f, 170f, 225f, spectrum, null, Shader.TileMode.CLAMP); c.drawPath(drop, p)
        p.shader = null; p.style = Paint.Style.FILL
        p.color = Color.WHITE; p.typeface = display; p.textSize = 76f
        c.drawText("shuddh", 200f, 178f, p)
        p.typeface = body; p.textSize = 30f; p.color = 0xFF8C9AB0.toInt()
        c.drawText("The People's Lab", 204f, 222f, p)

        // Score ring
        val score = store.kitchenScore()
        val s = score?.first ?: 0
        val cx = 540f; val cy = 560f; val r = 230f
        p.style = Paint.Style.STROKE; p.strokeWidth = 46f; p.strokeCap = Paint.Cap.ROUND
        p.color = 0x22FFFFFF; c.drawCircle(cx, cy, r, p)
        p.shader = SweepGradient(cx, cy, spectrum, null)
        c.save(); c.rotate(-90f, cx, cy)
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 0f, 360f * s / 100f, false, p)
        c.restore(); p.shader = null; p.style = Paint.Style.FILL
        p.color = Color.WHITE; p.typeface = display; p.textSize = 170f; p.textAlign = Paint.Align.CENTER
        c.drawText(if (score == null) "—" else "$s", cx, cy + 58f, p)
        p.typeface = body; p.textSize = 36f; p.color = 0xFF8C9AB0.toInt()
        c.drawText("KITCHEN HEALTH / 100", cx, cy + 120f, p)

        // Stats row
        val rs = store.records.toList()
        val stats = listOf(
            "${rs.size}" to "scans", "${rs.count { it.level == Level.UNSAFE }}" to "unsafe caught",
            "${Insights.streaks(rs).first}" to "day streak", "${store.vendors().size}" to "vendors",
        )
        val boxW = 230f
        stats.forEachIndexed { i, (v, l) ->
            val x = 60f + i * (boxW + 20f)
            p.color = 0x18FFFFFF; c.drawRoundRect(RectF(x, 880f, x + boxW, 1060f), 36f, 36f, p)
            p.color = if (i == 1) 0xFFF43F5E.toInt() else Color.WHITE; p.typeface = display; p.textSize = 64f
            c.drawText(v, x + boxW / 2, 980f, p)
            p.color = 0xFF8C9AB0.toInt(); p.typeface = body; p.textSize = 28f
            c.drawText(l, x + boxW / 2, 1030f, p)
        }

        // Footer message
        p.color = Color.WHITE; p.typeface = body; p.textSize = 38f
        c.drawText(score?.second ?: "Tested at home with phone physics", cx, 1150f, p)
        p.color = 0xFF8C9AB0.toInt(); p.textSize = 28f
        c.drawText("Tested offline with Shuddh · ${stamp(System.currentTimeMillis()).substringBefore(",")}", cx, 1270f, p)
        return b
    }

    fun save(ctx: Context, b: Bitmap): File {
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        return File(dir, "shuddh_score_${System.currentTimeMillis()}.png").apply { outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
