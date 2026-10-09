package com.shuddh.lab.instruments

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.ImageFacts
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.Vision
import com.shuddh.lab.core.Words
import com.shuddh.lab.core.stamp
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Badge
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.OnWave
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.Screen
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import com.shuddh.lab.ui.SteadyBar
import com.shuddh.lab.ui.rememberMotion
import kotlinx.coroutines.launch

private val lensName = Txt("Food label check", "लेबल जाँच", "ಲೇಬಲ್ ಪರೀಕ್ಷೆ")
private val expired = Txt("This product is past its expiry date. Do not eat it; return it to the shop.", "इसकी एक्सपायरी निकल चुकी है। न खाएँ, दुकान पर लौटाएँ।", "ಇದರ ಅವಧಿ ಮುಗಿದಿದೆ. ತಿನ್ನಬೇಡಿ, ಅಂಗಡಿಗೆ ಹಿಂತಿರುಗಿಸಿ.")
private val expiring = Txt("Expires within a week. Use it soon.", "एक हफ्ते में एक्सपायर होगा। जल्दी इस्तेमाल करें।", "ಒಂದು ವಾರದಲ್ಲಿ ಅವಧಿ ಮುಗಿಯುತ್ತದೆ.")
private val noLicence = Txt("No FSSAI licence number found. Packaged food must print one.", "FSSAI लाइसेंस नंबर नहीं मिला। पैक्ड खाने पर यह ज़रूरी है।", "FSSAI ಪರವಾನಗಿ ಸಂಖ್ಯೆ ಸಿಕ್ಕಿಲ್ಲ.")

fun lensVerdict(f: ImageFacts): Outcome {
    val l = f.lens
    val ev = mutableListOf(
        Evidence("OBSERVATION", "On-device OCR read ${f.text.length} characters; image labeller saw ${f.labels.take(3).joinToString { it.first }.ifBlank { "nothing confident" }} (${f.millis} ms)"),
        Evidence("PATTERN", l.fssai?.let { "FSSAI licence $it — 14 digits, format valid" } ?: "No 14-digit FSSAI licence number on the label", l.fssai != null),
        Evidence("PATTERN", l.expiry?.let { "Expiry ${stamp(it).substringBefore(",")} from ${l.expirySource}" } ?: "No expiry date could be read (${l.dates.size} dates found)", l.expiry != null),
    )
    l.mrp?.let { ev += Evidence("OBSERVATION", "MRP ₹$it") }
    val days = l.daysLeft
    val (lvl, adv) = when {
        days != null && days < 0 -> Level.UNSAFE to listOf(expired, Words.report)
        days != null && days <= 7 -> Level.CAUTION to listOf(expiring)
        l.fssai == null -> Level.CAUTION to listOf(noLicence)
        days == null -> Level.INCONCLUSIVE to listOf(Words.retest)
        else -> Level.SAFE to listOf(Words.ok)
    }
    ev += Evidence("HYPOTHESIS", when (lvl) {
        Level.UNSAFE -> "Expired ${-days!!} days ago"
        Level.CAUTION -> if (days != null && days <= 7) "Expires in $days days" else "Licence number missing"
        Level.SAFE -> "Licensed and in date ($days days left)"
        else -> "Couldn't read the date — photograph the date area closer, in good light"
    }, lvl == Level.SAFE)
    return Outcome(
        "Shuddh Lens", "label", lensName, days?.toDouble(), "days", lvl,
        days?.let { if (it < 0) "Expired ${-it} days ago" else "$it days until expiry" } ?: "Expiry not readable",
        adv, ev, "Reads what is printed; it cannot verify a licence against FSSAI's online register while offline.",
    )
}

@Composable
fun LensScreen(app: AppState) {
    val ctx = app.ctx
    val cam = remember { CameraHandle() }
    val motion = rememberMotion()
    val scope = rememberCoroutineScope()
    var latest by remember { mutableStateOf<Bitmap?>(null) }
    // Sharpest recent frame: blur is the main cause of misread dates, so snap uses the crispest frame of the last ~1.5 s.
    val best = remember { arrayOfNulls<Any>(3) } // bitmap, score, time
    var sharp by remember { mutableStateOf(0.0) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var facts by remember { mutableStateOf<ImageFacts?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun analyse(b: Bitmap) {
        shot = b; busy = true; facts = null
        scope.launch { facts = Vision.analyse(b); busy = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val b = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        else @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
        analyse(b.copy(Bitmap.Config.ARGB_8888, false))
    }
    OnWave(motion) { latest?.let { analyse(it) } }

    ScreenFrame("Label Lens", "Camera + on-device OCR → expiry, FSSAI, MRP", onBack = { app.back() }) {
        if (shot == null) {
            CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 0.9f, target = android.util.Size(1920, 1440)) { bmp ->
                latest = bmp
                val sc = com.shuddh.lab.camera.Frames.sharpness(bmp)
                val now = System.currentTimeMillis()
                synchronized(best) {
                    val old = best[1] as Double?; val t = best[2] as Long?
                    if (old == null || sc >= old || now - (t ?: 0L) > 1500) { best[0] = bmp; best[1] = sc; best[2] = now }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post { sharp = sc }
            }
            Note(if (sharp > 60) "🔍 Sharp — ready to snap" else "Hold steady / move closer — text is blurry (sharpness ${sharp.toInt()})", if (sharp > 60) Palette.accent else Palette.amber)
            SteadyBar(motion, "snap the label")
        } else {
            Image(shot!!.asImageBitmap(), "Captured label", Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
        }
        BtnRow {
            Btn(if (shot == null) "📸 Snap label" else "Retake") { if (shot == null) ((synchronized(best) { best[0] } as Bitmap?) ?: latest)?.let { analyse(it) } else { shot = null; facts = null } }
            Btn("🖼 Pick photo", primary = false) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            Btn(if (cam.torchOn) "Torch on" else "Torch", primary = false) { cam.torch(!cam.torchOn) }
        }
        if (busy) Note("Reading the label on-device…", Palette.cyan)

        facts?.let { f ->
            val l = f.lens
            Glass(glow = Palette.cyan) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExpiryRing(l.daysLeft)
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            l.daysLeft?.let { if (it < 0) "EXPIRED" else "$it days left" } ?: "No expiry read",
                            color = l.daysLeft?.let { if (it < 0) Palette.red else if (it <= 7) Palette.amber else Palette.accent } ?: Palette.muted,
                            fontSize = 22.sp, fontWeight = FontWeight.Black,
                        )
                        l.expiry?.let { Note("Best before ${stamp(it).substringBefore(",")}") }
                        Badge(l.fssai?.let { "FSSAI $it" } ?: "NO FSSAI NUMBER", if (l.fssai != null) Palette.accent else Palette.amber)
                        l.mrp?.let { Badge("MRP ₹$it", Palette.blue) }
                        l.veg?.let { Badge(if (it) "VEG" else "NON-VEG", if (it) Palette.accent else Palette.red) }
                    }
                }
            }
            Section("What the phone sees") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    f.colours.forEach { c -> Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(c))) }
                }
                if (f.labels.isNotEmpty()) BtnRow { f.labels.take(8).forEach { (t, c) -> Badge("$t ${(c * 100).toInt()}%", Palette.violet) } }
                if (f.text.isNotBlank()) Text(f.text.take(600), color = Palette.muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Note("Analysed in ${f.millis} ms, fully offline (ML Kit bundled models).")
            }
            BtnRow {
                Btn("Get verdict") { app.show(lensVerdict(f)) }
                Btn("✨ Ask AI about this", primary = false) { app.pendingImage = f; app.pendingBitmap = shot; app.go(Screen.ASSISTANT) }
                if (l.expiry != null) Btn("🥫 Add to pantry", primary = false) {
                    app.pendingPantryName = f.labels.firstOrNull()?.first?.replaceFirstChar { it.uppercase() } ?: "Packaged food"
                    app.pendingPantryExpiry = l.expiry
                    app.go(Screen.PANTRY)
                }
            }
        }
        HowItWorks(listOf(
            "Google ML Kit's text recogniser (Latin + Devanagari) and image labeller are bundled inside the app — they run on the phone's processor with no download.",
            "Shuddh searches the text for a 14-digit FSSAI licence number, dates near 'EXP / Best Before / Use By', and the MRP.",
            "If only 'best before N months' is printed, the expiry is computed from the manufacturing date.",
            "Every frame is only captured while the phone is steady, so the text is sharp.",
        ))
    }
}

@Composable
private fun ExpiryRing(days: Long?) {
    val target = when { days == null -> 0f; days < 0 -> 1f; else -> (days / 180f).coerceIn(0.03f, 1f) }
    val a = remember { Animatable(0f) }
    LaunchedEffect(days) { a.animateTo(target, tween(1000)) }
    val col = when { days == null -> Palette.muted; days < 0 -> Palette.red; days <= 7 -> Palette.amber; else -> Palette.accent }
    Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(110.dp)) {
            val st = 16f
            drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, Offset(st, st), Size(size.width - 2 * st, size.height - 2 * st), style = Stroke(st))
            drawArc(col, -90f, 360f * a.value, false, Offset(st, st), Size(size.width - 2 * st, size.height - 2 * st), style = Stroke(st, cap = StrokeCap.Round))
        }
        Text(days?.let { if (it < 0) "✕" else "$it" } ?: "?", color = col, fontSize = 28.sp, fontWeight = FontWeight.Black)
    }
}
