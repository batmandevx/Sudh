package com.shuddh.lab.instruments

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.core.Evidence
import com.shuddh.lab.core.GrainScan
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Txt
import com.shuddh.lab.core.fmt
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.BtnRow
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Donut
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.StepTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val grainName = Txt("Grain purity", "अनाज की शुद्धता", "ಧಾನ್ಯ ಶುದ್ಧತೆ", "ధాన్యం స్వచ్ఛత", "தானியத் தூய்மை")
private val kindColor = mapOf(
    GrainScan.Kind.FOREIGN to Color(0xFFF43F5E), GrainScan.Kind.DISCOLOURED to Color(0xFFF97316),
    GrainScan.Kind.BROKEN to Color(0xFFFBBF24), GrainScan.Kind.CLUMP to Color(0xFF60A5FA), GrainScan.Kind.GRAIN to Color(0xFF34D399),
)

/** Grain Scan — counts grains on paper/cloth and circles stones, husk, insects and broken grains. */
@Composable
fun GrainScreen(app: AppState) {
    val ctx = app.ctx
    val cam = remember { CameraHandle() }
    val scope = rememberCoroutineScope()
    var latest by remember { mutableStateOf<Bitmap?>(null) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<GrainScan.Result?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun scan() {
        val b = latest ?: return
        shot = b; busy = true; result = null
        scope.launch {
            val r = withContext(Dispatchers.Default) {
                // Work at ≤480 px wide: plenty for grain-sized blobs, fast on any phone.
                val k = (480f / b.width).coerceAtMost(1f)
                val s = Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
                val px = IntArray(s.width * s.height); s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
                GrainScan.analyse(px, s.width, s.height, minArea = 10)
            }
            kotlinx.coroutines.delay(900) // let the scan sweep finish
            result = r; busy = false
            val (lvl, label, adv) = GrainScan.grade(r)
            Haptics.result(ctx, lvl)
            app.voice.speak("$label. ${r.total} grains, ${r.foreign + r.discoloured} impurities. $adv", app.lang)
        }
    }

    fun verdict(r: GrainScan.Result): Outcome {
        val (lvl, label, adv) = GrainScan.grade(r)
        val ev = listOf(
            Evidence("OBSERVATION", "${r.total} pieces counted on a ${if (r.darkBackground) "dark" else "light"} background (Otsu threshold + connected components)"),
            Evidence("QUALITY", "Enough grains for a reliable share (≥ 30)", r.total >= 30),
            Evidence("PATTERN", "${r.foreign} foreign (much darker/lighter), ${r.discoloured} discoloured, ${r.broken} broken (<55 % of median size)"),
            Evidence("HYPOTHESIS", "Impurities ${fmt(r.impurityPct)} % · broken ${fmt(r.brokenPct)} %", lvl == com.shuddh.lab.core.Level.SAFE),
        )
        return Outcome("Shuddh Grain", "grain_purity", grainName, r.purityPct, "%", lvl, "$label — purity ${fmt(r.purityPct)} %",
            listOf(Txt(adv)), ev, "Visual screening by count, not weight. Stones weigh more than grains, so weight-based impurity can be higher.", levelLabel = Txt(label.uppercase()))
    }

    ScreenFrame("Grain Scan", "Camera → stones, husk, insects & broken grains", onBack = { app.back() }) {
        StepTracker(listOf("Spread one layer" to (latest != null), "Scan" to (result != null), "Verdict" to false))
        Glass(glow = result?.let { Color(GrainScan.grade(it).first.argb) } ?: Palette.amber, padding = 10) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(16.dp))) {
                if (shot == null) CameraView(cam, Modifier.fillMaxWidth(), widthFraction = 1f, target = android.util.Size(1440, 1920)) { latest = it }
                else {
                    Image(shot!!.asImageBitmap(), "Grains", Modifier.matchParentSize(), contentScale = ContentScale.Fit)
                    if (busy) ScanSweep(Modifier.matchParentSize())
                    result?.let { BlobOverlay(it, Modifier.matchParentSize()) }
                }
            }
            BtnRow {
                Btn(if (shot == null) "🔍 Scan grains" else "Scan again", enabled = !busy) { if (shot == null) scan() else { shot = null; result = null } }
                Btn(if (cam.torchOn) "Torch: ON" else "Torch", primary = false) { cam.torch(!cam.torchOn) }
            }
            if (result == null) Note("Spread a handful in ONE layer, grains not touching. White rice → dark cloth; dal, wheat, spices → white paper. Hold the phone flat ~20 cm above.")
        }
        result?.let { r ->
            val (lvl, label, adv) = GrainScan.grade(r)
            Glass(glow = Color(lvl.argb)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Donut(r.grains, r.broken, r.foreign + r.discoloured, Modifier.size(110.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${r.purityPct.toInt()}%", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                            Text("pure", color = Palette.muted, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, color = Color(lvl.argb), fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
                        Count(kindColor[GrainScan.Kind.GRAIN]!!, "${r.grains}", "good grains")
                        Count(kindColor[GrainScan.Kind.BROKEN]!!, "${r.broken}", "broken")
                        Count(kindColor[GrainScan.Kind.FOREIGN]!!, "${r.foreign}", "stones / husk / insects")
                        Count(kindColor[GrainScan.Kind.DISCOLOURED]!!, "${r.discoloured}", "discoloured")
                    }
                }
                Text(adv, color = Palette.text, fontSize = 13.sp)
                Btn("Get verdict", Modifier.fillMaxWidth()) { app.show(verdict(r)) }
            }
        }
        HowItWorks(listOf(
            "The image border tells Shuddh whether the background is light paper or dark cloth.",
            "Otsu's method finds the brightness that best separates grains from background; touching pixels are grouped into blobs.",
            "Every blob is compared with the median grain: much darker or lighter = stone, husk or insect; a different hue = discoloured or dyed; under 55 % of the size = broken; much bigger = touching grains, counted by area.",
            "Because it compares against your own grains, it works for rice, dal, wheat or spices without training.",
        ))
    }
}

@Composable
private fun Count(c: Color, v: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c))
        Spacer(Modifier.width(6.dp))
        Text(v, color = Palette.text, fontWeight = FontWeight.Black, fontSize = 14.sp); Spacer(Modifier.width(4.dp))
        Text(label, color = Palette.muted, fontSize = 12.sp)
    }
}

/** A glowing line sweeping down the photo while it is analysed. */
@Composable
private fun ScanSweep(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "sweep")
    val y by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "y")
    val c = Palette.cyan
    Canvas(modifier) {
        val yy = size.height * y
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.copy(alpha = 0.35f)), yy - 80f, yy), Offset(0f, yy - 80f), androidx.compose.ui.geometry.Size(size.width, 80f))
        drawLine(c, Offset(0f, yy), Offset(size.width, yy), 4f)
    }
}

/** Rings around every flagged piece; good grains get a small green dot. Rings pop in one by one. */
@Composable
private fun BlobOverlay(r: GrainScan.Result, modifier: Modifier) {
    val pop = remember(r) { Animatable(0f) }
    LaunchedEffect(r) { pop.animateTo(1f, tween(1200)) }
    Canvas(modifier) {
        // The image is drawn with Fit inside a 3:4 box; map normalised blob centres into that rect.
        val ar = r.w.toFloat() / r.h
        val boxAr = size.width / size.height
        val (iw, ih) = if (ar > boxAr) size.width to size.width / ar else size.height * ar to size.height
        val ox = (size.width - iw) / 2; val oy = (size.height - ih) / 2
        r.blobs.forEachIndexed { i, b ->
            if (i.toFloat() / r.blobs.size > pop.value) return@forEachIndexed
            val p = Offset(ox + b.cx * iw, oy + b.cy * ih)
            val col = kindColor[b.kind]!!
            if (b.kind == GrainScan.Kind.GRAIN || b.kind == GrainScan.Kind.CLUMP) drawCircle(col, 3.5f, p)
            else {
                val rad = 10f + kotlin.math.sqrt(b.area.toFloat()) * iw / r.w
                drawCircle(col.copy(alpha = 0.25f), rad, p); drawCircle(col, rad, p, style = Stroke(4f))
            }
        }
    }
}
