package com.shuddh.lab.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableStateListOf
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A result shared phone-to-phone by QR — the offline "Hive". Two kinds:
 *  - alert: one scan's verdict (vendor, test, level)
 *  - seal:  a vendor's track record (fails / total), shown at the shop counter
 * Nothing personal is included: no names, phone numbers, or raw camera data.
 */
data class CommunityItem(
    val kind: String,
    val vendor: String,
    val area: String,
    val analyte: String,
    val level: Level,
    val value: String,
    val time: Long,
    val total: Int,
    val fails: Int,
    val ref: String,
    val receivedAt: Long = System.currentTimeMillis(),
) {
    fun toPayload(): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        return listOf(
            "SHUDDH1", "k=${e(kind)}", "v=${e(vendor)}", "a=${e(area)}", "t=${e(analyte)}", "l=${level.name}",
            "x=${e(value)}", "ts=$time", "n=$total", "f=$fails", "r=${e(ref)}",
        ).joinToString(";")
    }

    fun describe(): String = when (kind) {
        "case" -> "Illness · $analyte · $total ${if (total == 1) "person" else "people"}" + (if (area.isNotBlank()) " · $area" else "")
        "seal" -> "Seal · $vendor: $fails of $total scans failed" + (if (area.isNotBlank()) " · $area" else "")
        else -> "Alert · $analyte ${value.ifBlank { "" }} ${level.name}" + (if (vendor.isNotBlank()) " · $vendor" else "") +
            (if (area.isNotBlank()) " · $area" else "")
    }

    fun toJson() = JSONObject().put("p", toPayload()).put("rx", receivedAt)

    /** Compact form that fits a Bluetooth mesh packet: "SH1|kind|vendor|analyte|LEVEL|value|area|fails/total". */
    fun compact(): String = listOf("SH1", kind.take(1), vendor.take(24), analyte.take(24), level.name.take(1), value.take(12), area.take(16), "$fails/$total")
        .joinToString("|") { it.replace("|", "/") }

    companion object {
        fun parse(text: String): CommunityItem? = runCatching {
            if (!text.startsWith("SHUDDH1;")) return null
            val m = text.split(";").drop(1).associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
            CommunityItem(
                m.getValue("k"), m["v"].orEmpty(), m["a"].orEmpty(), m["t"].orEmpty(), Level.valueOf(m.getValue("l")),
                m["x"].orEmpty(), m["ts"]!!.toLong(), m["n"]?.toInt() ?: 1, m["f"]?.toInt() ?: 0, m["r"].orEmpty(),
            )
        }.getOrNull()

        fun parseCompact(text: String, ref: String = "mesh"): CommunityItem? = runCatching {
            val f = text.split("|")
            if (f.size < 8 || f[0] != "SH1") return null
            val level = Level.entries.first { it.name.startsWith(f[4]) }
            val (fails, total) = f[7].split("/").map { it.toInt() }
            CommunityItem(when (f[1]) { "s" -> "seal"; "c" -> "case"; else -> "alert" }, f[2], f[6], f[3], level, f[5], System.currentTimeMillis(), total, fails, ref)
        }.getOrNull()

        fun alertFrom(r: ScanRecord) = CommunityItem(
            "alert", r.vendor, r.area, r.analyte, r.level, r.value?.let { "${fmt(it)} ${r.unit}" } ?: "", r.time,
            1, if (r.level == Level.UNSAFE) 1 else 0, r.hash.take(12),
        )

        fun sealFrom(store: Store, vendor: String, area: String): CommunityItem {
            val m = store.vendorMemory(vendor, last = 50)
            val level = when {
                m.total == 0 -> Level.INCONCLUSIVE
                m.failures == 0 -> Level.SAFE
                m.failures.toDouble() / m.total < 0.2 -> Level.CAUTION
                else -> Level.UNSAFE
            }
            return CommunityItem(
                "seal", vendor, area, "Vendor record", level, m.trend, System.currentTimeMillis(), m.total, m.failures,
                store.records.lastOrNull()?.hash?.take(12) ?: "",
            )
        }
    }
}

class CommunityStore(ctx: Context) {
    private val file = File(ctx.filesDir, "community.json")
    val items = mutableStateListOf<CommunityItem>()

    init {
        runCatching {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    CommunityItem.parse(o.getString("p"))?.let { items.add(it.copy(receivedAt = o.optLong("rx"))) }
                }
            }
        }
    }

    /** Adds an item unless an identical one was already imported. Returns false for duplicates. */
    fun add(item: CommunityItem): Boolean {
        if (items.any { it.toPayload() == item.toPayload() }) return false
        items.add(0, item)
        file.writeText(JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
        return true
    }

    fun remove(item: CommunityItem) {
        items.remove(item)
        file.writeText(JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
    }

    /** Batch alerts: the same vendor+test flagged UNSAFE by 2+ independent reports. */
    fun batchAlerts(): List<Pair<String, Int>> =
        items.filter { it.kind == "alert" && it.level == Level.UNSAFE && it.vendor.isNotBlank() }
            .groupBy { "${it.vendor} · ${it.analyte}" }
            .map { it.key to it.value.size }
            .filter { it.second >= 2 }
            .sortedByDescending { it.second }
}

object Qr {
    fun encode(text: String, size: Int = 720): Bitmap {
        val m = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        val px = IntArray(size * size) { if (m.get(it % size, it / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    fun decode(bmp: Bitmap): String? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return runCatching {
            QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px))),
                mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8"),
            ).text
        }.getOrNull()
    }
}
