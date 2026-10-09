package com.shuddh.lab.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Turns a confirmed result into a ready-to-send complaint, in the user's language,
 * with the Purity Passport PDF attached. The user chooses the app and presses send.
 */
object Complaint {
    fun text(r: ScanRecord, lang: Lang): String {
        val value = r.value?.let { "${fmt(it)} ${r.unit}" } ?: "—"
        val conf = if (r.confirmed) "confirmed by two consecutive scans" else "single screening scan"
        val ev = r.evidence.joinToString("\n") { "  - $it" }
        return when (lang) {
            Lang.HI -> """
                सेवा में: खाद्य सुरक्षा अधिकारी / FSSAI
                विषय: ${r.vendor.ifBlank { "विक्रेता" }} से खरीदे गए उत्पाद में संदिग्ध मिलावट (${r.analyte})

                महोदय/महोदया,
                ${stamp(r.time)} को मैंने ${r.vendor.ifBlank { "विक्रेता" }}${if (r.area.isNotBlank()) ", ${r.area}" else ""} से खरीदे गए नमूने "${r.sampleTag.ifBlank { r.analyte }}" की जाँच Shuddh फ़ोन प्रयोगशाला से की।
                परिणाम: ${r.analyte} = $value — ${r.level.label.hi} ($conf)।
                ${r.headline}

                साक्ष्य:
                $ev

                रिकॉर्ड हैश: ${r.hash}
                कृपया खाद्य सुरक्षा एवं मानक अधिनियम, 2006 के तहत इस विक्रेता से आधिकारिक नमूना लेकर जाँच करें। Purity Passport रिपोर्ट संलग्न है।
                (यह स्क्रीनिंग परिणाम है, प्रयोगशाला प्रमाणपत्र नहीं।)

                नाम: ____________   संपर्क: ____________
            """.trimIndent()
            else -> """
                To: Food Safety Officer / FSSAI
                Subject: Suspected adulteration — ${r.analyte} — ${r.vendor.ifBlank { "vendor" }}${if (r.area.isNotBlank()) ", ${r.area}" else ""}

                Sir/Madam,
                On ${stamp(r.time)} I screened a sample ("${r.sampleTag.ifBlank { r.analyte }}") bought from ${r.vendor.ifBlank { "the vendor" }}${if (r.area.isNotBlank()) ", ${r.area}" else ""} using the Shuddh phone laboratory.
                Result: ${r.analyte} = $value — ${r.level.name} ($conf).
                ${r.headline}

                Evidence:
                $ev

                Record hash: ${r.hash}
                I request that an official sample be drawn from this vendor and tested under the Food Safety and Standards Act, 2006. The Purity Passport report is attached.
                (This is a screening result, not an accredited laboratory certificate.)

                Name: ____________   Contact: ____________
            """.trimIndent()
        }
    }

    fun share(ctx: Context, r: ScanRecord, lang: Lang, pdf: File?) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = if (pdf != null) "application/pdf" else "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Food adulteration complaint: ${r.analyte} (${r.vendor})")
            putExtra(Intent.EXTRA_TEXT, text(r, lang))
            if (pdf != null) {
                putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, "com.shuddh.lab.files", pdf))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        ctx.startActivity(Intent.createChooser(i, "Send complaint").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** CSV of the scan log, for the laptop console / spreadsheet. */
    fun csv(ctx: Context, records: List<ScanRecord>): File {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val rows = listOf("id,time,instrument,test,value,unit,level,confirmed,vendor,area,sample,headline,hash") +
            records.map {
                listOf(
                    it.id.toString(), q(stamp(it.time)), q(it.instrument), q(it.analyte), it.value?.toString() ?: "", q(it.unit),
                    it.level.name, it.confirmed.toString(), q(it.vendor), q(it.area), q(it.sampleTag), q(it.headline), it.hash,
                ).joinToString(",")
            }
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        return File(dir, "shuddh_scans_${System.currentTimeMillis()}.csv").apply { writeText(rows.joinToString("\n")) }
    }

    fun shareFile(ctx: Context, f: File, mime: String, title: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, "com.shuddh.lab.files", f))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
