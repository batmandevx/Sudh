package com.shuddh.lab.instruments

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.camera.core.CameraSelector
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.camera.CameraHandle
import com.shuddh.lab.camera.CameraView
import com.shuddh.lab.core.Gesture
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.LiveVision
import com.shuddh.lab.core.P
import com.shuddh.lab.core.PointTracker
import com.shuddh.lab.core.Scene
import com.shuddh.lab.core.SeenFace
import com.shuddh.lab.core.SeenHand
import com.shuddh.lab.core.SeenObject
import com.shuddh.lab.core.SeenPose
import com.shuddh.lab.core.VisionCues
import com.shuddh.lab.ui.AppState
import com.shuddh.lab.ui.Btn
import com.shuddh.lab.ui.Chips
import com.shuddh.lab.ui.Display
import com.shuddh.lab.ui.Glass
import com.shuddh.lab.ui.HowItWorks
import com.shuddh.lab.ui.Note
import com.shuddh.lab.ui.Palette
import com.shuddh.lab.ui.Screen
import com.shuddh.lab.ui.ScreenFrame
import com.shuddh.lab.ui.Section
import kotlin.math.abs

private enum class VMode(val label: String, val objects: Boolean, val hands: Boolean, val face: Boolean, val pose: Boolean, val front: Boolean) {
    ALL("✨ All", true, true, true, true, true),
    OBJECTS("📦 Objects", true, false, false, false, false),
    HANDS("✋ Hands", false, true, false, false, true),
    FACE("🙂 Face", false, false, true, false, true),
    BODY("🧍 Body", false, false, false, true, true),
}

/** Kalman trackers for every landmark, so overlays glide between frames instead of jittering. */
private class Smoother {
    val hands = Array(2) { Array(21) { PointTracker() } }
    val face = Array(478) { PointTracker(q = 30.0, r = 2e-5) }
    val pose = Array(33) { PointTracker() }
    data class Track(val label: String, val tl: PointTracker, val br: PointTracker, var box: RectF, var seen: Long)
    val tracks = mutableListOf<Track>()

    fun smooth(s: Scene, now: Long): Scene {
        val hs = s.hands.sortedBy { it.pts.firstOrNull()?.x ?: 0f }.take(2).mapIndexed { slot, h ->
            h.copy(pts = h.pts.mapIndexed { i, p -> hands[slot][i].update(p.x, p.y, now).let { P(it.first, it.second) } })
        }
        if (s.hands.isEmpty()) hands.forEach { a -> a.forEach { it.reset() } }
        val fs = s.faces.take(1).map { f -> f.copy(pts = f.pts.mapIndexed { i, p -> if (i < face.size) face[i].update(p.x, p.y, now).let { P(it.first, it.second) } else p }) }
        if (s.faces.isEmpty()) face.forEach { it.reset() }
        val ps = s.poses.take(1).map { pz -> pz.copy(pts = pz.pts.mapIndexed { i, p -> if (i < pose.size) pose[i].update(p.x, p.y, now).let { P(it.first, it.second) } else p }) }
        if (s.poses.isEmpty()) pose.forEach { it.reset() }
        // Objects: match each detection to the nearest live track of the same label (IoU), else start a new one.
        val used = mutableSetOf<Track>()
        val os = s.objects.map { o ->
            val t = tracks.filter { it.label == o.label && it !in used }.maxByOrNull { iou(it.box, o.box) }?.takeIf { iou(it.box, o.box) > 0.2f }
                ?: Track(o.label, PointTracker(25.0, 6e-5), PointTracker(25.0, 6e-5), o.box, now).also { tracks += it }
            used += t
            val (l, tp) = t.tl.update(o.box.left, o.box.top, now)
            val (r, b) = t.br.update(o.box.right, o.box.bottom, now)
            t.box = RectF(l, tp, r, b); t.seen = now
            o.copy(box = t.box)
        }
        tracks.removeAll { now - it.seen > 600_000_000L }
        return s.copy(objects = os, hands = hs, faces = fs, poses = ps)
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val iy = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val inter = ix * iy
        val u = a.width() * a.height() + b.width() * b.height() - inter
        return if (u <= 0f) 0f else inter / u
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisionScreen(app: AppState) {
    val ctx = app.ctx
    val engine = remember { LiveVision(ctx) }
    val smoother = remember { Smoother() }
    val cam = remember { CameraHandle() }
    var mode by remember { mutableStateOf(VMode.ALL) }
    var front by remember { mutableStateOf(true) }
    var kalman by remember { mutableStateOf(true) }
    var voiceOn by remember { mutableStateOf(true) }
    var scene by remember { mutableStateOf(Scene()) }
    var fps by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    val trail = remember { mutableStateListOf<P>() }
    var blinks by remember { mutableIntStateOf(0) }
    var reps by remember { mutableIntStateOf(0) }
    var smiles by remember { mutableIntStateOf(0) }
    val gesturesSeen = remember { mutableStateListOf<Gesture>() }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val flags = remember { BooleanArray(3) } // blinking, armsUp, smiling — edge detectors

    DisposableEffect(Unit) { onDispose { engine.close() } }
    LaunchedEffect(mode) { front = mode.front; trail.clear() }

    val guide = guideFor(mode, scene)
    // Voice: speak a new guidance line once it has been stable for ~1 s, at most every 3.5 s.
    var lastSpoken by remember { mutableStateOf("") }
    var lastSpokenAt by remember { mutableStateOf(0L) }
    LaunchedEffect(guide, voiceOn) {
        if (!voiceOn) return@LaunchedEffect
        kotlinx.coroutines.delay(1000)
        val now = System.currentTimeMillis()
        if (guide != lastSpoken && now - lastSpokenAt > 3500) {
            app.voice.speak(guide.replace(Regex("[^\\p{L}\\p{N}\\s,.!?'%-]"), "").trim(), app.lang)
            lastSpoken = guide; lastSpokenAt = now
        }
    }

    ScreenFrame("Vision Lab", "On-device machine vision · Kalman-tracked", onBack = { app.back() }) {
        Chips(VMode.entries.toList(), mode, { it.label }) { mode = it }
        Glass(glow = Palette.cyan, padding = 10) {
            Box {
                key(front) {
                    CameraView(
                        cam, Modifier.fillMaxWidth(), lensFacing = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
                        widthFraction = 1f, target = android.util.Size(480, 640),
                        overlay = { drawScene(scene, front, trail) },
                    ) { bmp ->
                        val t0 = System.nanoTime()
                        val raw = runCatching { engine.process(bmp, mode.objects, mode.hands, mode.face, mode.pose) }
                            .onFailure { e -> main.post { error = e.message ?: e.javaClass.simpleName } }.getOrNull() ?: return@CameraView
                        val s = if (kalman) smoother.smooth(raw, System.nanoTime()) else raw
                        val dt = (System.nanoTime() - t0) / 1e9f
                        main.post {
                            scene = s; error = null
                            fps = if (fps == 0f) 1 / dt.coerceAtLeast(0.001f) else fps * 0.85f + 0.15f / dt.coerceAtLeast(0.001f)
                            // Counters and air-drawing.
                            s.faces.firstOrNull()?.let { f ->
                                val blink = f.blinkL > 0.55f && f.blinkR > 0.55f
                                if (blink && !flags[0]) { blinks++; Haptics.tick(ctx, 0.3f) }; flags[0] = blink
                                val sm = f.smile > 0.5f
                                if (sm && !flags[2]) { smiles++; Haptics.click(ctx) }; flags[2] = sm
                            }
                            s.poses.firstOrNull()?.let { p -> val up = VisionCues.armsUp(p.pts); if (up && !flags[1]) { reps++; Haptics.thud(ctx) }; flags[1] = up }
                            s.hands.firstOrNull()?.let { h ->
                                if (h.gesture != Gesture.NONE && gesturesSeen.lastOrNull() != h.gesture) { gesturesSeen += h.gesture; if (gesturesSeen.size > 8) gesturesSeen.removeAt(0); Haptics.tick(ctx, 0.8f) }
                                when (h.gesture) {
                                    Gesture.POINT -> { trail += h.pts[8]; if (trail.size > 160) trail.removeAt(0) }
                                    Gesture.FIST -> trail.clear()
                                    else -> {}
                                }
                            }
                        }
                    }
                }
                // Live guidance banner on top of the camera.
                Text(
                    guide, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 7.dp),
                )
                Text(
                    "${fps.toInt()} fps · ${engine.backend}" + if (kalman) " · Kalman" else " · raw",
                    color = Palette.cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle(if (front) "🤳 Front camera" else "📷 Back camera", true) { front = !front; trail.clear() }
                Toggle("〰 Kalman smoothing", kalman) { kalman = !kalman }
                Toggle("🔊 Voice guide", voiceOn) { voiceOn = !voiceOn }
            }
            error?.let { Note("Vision error: $it", Palette.amber) }
        }

        if (mode.objects) ObjectsCard(scene.objects) { app.go(it) }
        if (mode.hands) HandsCard(scene.hands, gesturesSeen, trail.isNotEmpty())
        if (mode.face) FaceCard(scene.faces.firstOrNull(), blinks, smiles)
        if (mode.pose) BodyCard(scene.poses.firstOrNull(), reps)
        if (scene.ms.isNotEmpty()) Section("On-device inference") {
            scene.ms.forEach { (k, v) -> Bar(k, (v / 60f).coerceIn(0.02f, 1f), "$v ms", Palette.cyan) }
            Note("Every model runs on this phone's processor through MediaPipe — no frame ever leaves the device.")
        }
        HowItWorks(listOf(
            "Objects: EfficientDet-Lite0 finds 80 everyday things (bottles, cups, fruit, utensils, people…) and Shuddh suggests which lab test fits each one.",
            "Hands: a 21-joint hand model per hand; Shuddh reads finger extension geometrically to recognise 👍 ✌️ ☝️ 👌 ✊ ✋ 🤘 🤙. Point ☝️ to draw in the air, make a fist ✊ to erase.",
            "Face: a 478-point face mesh with blendshapes gives smile, blinks, mouth opening and head turn.",
            "Body: 33 pose joints → posture (shoulder level) and an arm-raise rep counter.",
            "Kalman filters: every joint and box corner is tracked with a constant-velocity Kalman filter, which predicts motion and blends it with each new detection — turn it off to see the raw jitter.",
            "Voice guide: Shuddh tells you what to do next — show your hand, face the camera, step back — and speaks only when the guidance changes.",
        ))
    }
}

private fun guideFor(mode: VMode, s: Scene): String {
    val hand = s.hands.firstOrNull(); val face = s.faces.firstOrNull(); val pose = s.poses.firstOrNull()
    return when (mode) {
        VMode.OBJECTS -> s.objects.maxByOrNull { it.score }?.let { o -> "I see ${article(o.label)} ${o.label}" + (VisionCues.suggestion(o.label)?.let { " — ${it.first.lowercase()}" } ?: "") }
            ?: "Point the camera at food, bottles or utensils"
        VMode.HANDS -> when {
            hand == null -> "✋ Show your hand to the camera"
            offCentre(hand.pts) -> "Move your hand to the centre"
            hand.gesture == Gesture.NONE -> "${hand.fingers} finger${if (hand.fingers == 1) "" else "s"} up"
            else -> "${hand.gesture.emoji} ${hand.gesture.label}"
        }
        VMode.FACE -> when {
            face == null -> "🙂 Look at the camera"
            abs(face.yawDeg) > 22 -> "Turn your head toward the camera"
            face.smile > 0.5f -> "😄 Nice smile!"
            face.jawOpen > 0.45f -> "😮 Mouth open"
            else -> "Face locked — try smiling or blinking"
        }
        VMode.BODY -> when {
            pose == null -> "🧍 Step back so your body is visible"
            VisionCues.armsUp(pose.pts) -> "🙌 Arms up — great!"
            abs(VisionCues.shoulderTilt(pose.pts)) > 8 -> "Level your shoulders"
            else -> "Raise both arms to count a rep"
        }
        VMode.ALL -> when {
            hand != null && hand.gesture != Gesture.NONE -> "${hand.gesture.emoji} ${hand.gesture.label}"
            face != null && face.smile > 0.5f -> "😄 Nice smile!"
            s.objects.any { it.label != "person" } -> s.objects.filter { it.label != "person" }.maxBy { it.score }.let { "I see ${article(it.label)} ${it.label}" }
            face != null -> "I see you — try a 👍 or ✌️"
            pose != null -> "I see a person"
            else -> "Point the camera at people or objects"
        }
    }
}

private fun article(w: String) = if ((w.firstOrNull()?.lowercaseChar() ?: 'x') in "aeiou") "an" else "a"
private fun offCentre(p: List<P>): Boolean = p.firstOrNull()?.let { it.x < 0.12f || it.x > 0.88f || it.y > 0.97f } ?: false

private val handColor = Color(0xFF22D3EE)
private val faceColor = Color(0xFFA78BFA)
private val poseColor = Color(0xFF34D399)
private val objColor = Color(0xFFFBBF24)

private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; typeface = Typeface.DEFAULT_BOLD; color = android.graphics.Color.BLACK }

private fun DrawScope.drawScene(s: Scene, mirror: Boolean, trail: List<P>) {
    fun X(x: Float) = (if (mirror) 1 - x else x) * size.width
    fun Y(y: Float) = y * size.height
    fun o(p: P) = Offset(X(p.x), Y(p.y))

    s.objects.forEach { ob ->
        val l = X(if (mirror) ob.box.right else ob.box.left); val t = Y(ob.box.top)
        val w = abs(X(ob.box.right) - X(ob.box.left)); val h = Y(ob.box.bottom) - t
        drawRoundRect(objColor, Offset(l, t), Size(w, h), CornerRadius(18f), style = Stroke(5f))
        val txt = "${ob.label} ${(ob.score * 100).toInt()}%"
        val tw = labelPaint.measureText(txt) + 24f
        drawRoundRect(objColor, Offset(l, (t - 46f).coerceAtLeast(0f)), Size(tw, 42f), CornerRadius(12f))
        drawContext.canvas.nativeCanvas.drawText(txt, l + 12f, (t - 46f).coerceAtLeast(0f) + 31f, labelPaint)
    }
    s.poses.forEach { p ->
        VisionCues.POSE_EDGES.forEach { (a, b) ->
            if (p.vis.getOrElse(a) { 1f } > 0.5f && p.vis.getOrElse(b) { 1f } > 0.5f) drawLine(poseColor, o(p.pts[a]), o(p.pts[b]), 7f, StrokeCap.Round)
        }
        p.pts.forEachIndexed { i, q -> if (i > 10 && p.vis.getOrElse(i) { 1f } > 0.5f) { drawCircle(Color.White, 8f, o(q)); drawCircle(poseColor, 5f, o(q)) } }
    }
    s.faces.forEach { f ->
        f.pts.forEachIndexed { i, q -> if (i % 2 == 0) drawCircle(faceColor.copy(alpha = 0.75f), 2.2f, o(q)) }
    }
    s.hands.forEach { h ->
        VisionCues.HAND_EDGES.forEach { (a, b) -> drawLine(handColor, o(h.pts[a]), o(h.pts[b]), 6f, StrokeCap.Round) }
        h.pts.forEachIndexed { i, q ->
            val tip = i in setOf(4, 8, 12, 16, 20)
            drawCircle(handColor.copy(alpha = 0.3f), if (tip) 22f else 14f, o(q))
            drawCircle(Color.White, if (tip) 9f else 6f, o(q))
        }
        val c = o(h.pts[0])
        val txt = "${h.gesture.emoji} ${h.fingers}"
        drawContext.canvas.nativeCanvas.drawText(txt, c.x - 30f, c.y + 60f, Paint(labelPaint).apply { color = android.graphics.Color.WHITE; textSize = 44f })
    }
    if (trail.size > 1) {
        val path = Path().apply { moveTo(X(trail[0].x), Y(trail[0].y)); trail.drop(1).forEach { lineTo(X(it.x), Y(it.y)) } }
        drawPath(path, Brush.linearGradient(listOf(Color(0xFFF472B6), Color(0xFF22D3EE), Color(0xFFFBBF24))), style = Stroke(12f, cap = StrokeCap.Round))
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    val c = if (on) Palette.cyan else Palette.muted
    Text(text, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun Bar(label: String, f: Float, value: String, c: Color) {
    val a by animateFloatAsState(f, tween(400), label = "bar$label")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, fontSize = 12.sp, modifier = Modifier.width(78.dp), maxLines = 1)
        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.06f))) {
            Box(Modifier.fillMaxWidth(a).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.5f), c))))
        }
        Text(value, color = Palette.muted, fontSize = 11.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp), maxLines = 1)
    }
}

@Composable
private fun ObjectsCard(objs: List<SeenObject>, open: (Screen) -> Unit) {
    Section("What I see") {
        if (objs.isEmpty()) Note("Nothing recognised yet. Try a bottle, cup, bowl, fruit, spoon or a person.")
        objs.sortedByDescending { it.score }.distinctBy { it.label }.take(6).forEach { o ->
            Bar(o.label, o.score, "${(o.score * 100).toInt()}%", objColor)
            VisionCues.suggestion(o.label)?.let { (hint, screen) ->
                Text("→ $hint", color = Palette.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 78.dp).clip(RoundedCornerShape(8.dp)).clickable { runCatching { open(Screen.valueOf(screen)) } }.padding(4.dp))
            }
        }
    }
}

@Composable
private fun HandsCard(hands: List<SeenHand>, seen: List<Gesture>, drawing: Boolean) {
    Section("Hands & joints") {
        if (hands.isEmpty()) Note("Show one or two hands. Try 👍 ✌️ ☝️ 👌 ✊ ✋ 🤘 🤙 — point ☝️ to draw in the air, fist ✊ to erase.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            hands.forEach { h ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(handColor.copy(alpha = 0.1f)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(h.gesture.emoji, fontSize = 40.sp)
                    Text(h.gesture.label, color = Palette.text, fontWeight = FontWeight.Bold, fontFamily = Display)
                    Text("${h.side.ifBlank { "Hand" }} · ${h.fingers} fingers · 21 joints", color = Palette.muted, fontSize = 11.sp)
                }
            }
        }
        if (seen.isNotEmpty()) Text("Recent: " + seen.joinToString(" ") { it.emoji }, color = Palette.text, fontSize = 16.sp)
        if (drawing) Note("✍️ Air-drawing with your index finger — make a fist to erase.", Palette.cyan)
    }
}

@Composable
private fun FaceCard(f: SeenFace?, blinks: Int, smiles: Int) {
    Section("Face") {
        if (f == null) { Note("Face the camera in good light."); return@Section }
        Bar("Smile", f.smile, "${(f.smile * 100).toInt()}%", Color(0xFFF472B6))
        Bar("Mouth", f.jawOpen, "${(f.jawOpen * 100).toInt()}%", faceColor)
        Bar("Eyes shut", (f.blinkL + f.blinkR) / 2, "${((f.blinkL + f.blinkR) * 50).toInt()}%", Palette.blue)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("👁", "$blinks", "blinks", Modifier.weight(1f))
            Stat("😄", "$smiles", "smiles", Modifier.weight(1f))
            Stat("↔", "${f.yawDeg.toInt()}°", "head turn", Modifier.weight(1f))
        }
        Note("478-point face mesh + 52 expression blendshapes, all on-device.")
    }
}

@Composable
private fun BodyCard(p: SeenPose?, reps: Int) {
    Section("Body & posture") {
        if (p == null) { Note("Step back until your shoulders and hips are in view."); return@Section }
        val tilt = VisionCues.shoulderTilt(p.pts)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("🙌", "$reps", "arm raises", Modifier.weight(1f))
            Stat("📐", "${abs(tilt).toInt()}°", if (abs(tilt) < 5) "level shoulders" else "shoulder tilt", Modifier.weight(1f))
            Stat("🦴", "${p.vis.count { it > 0.5f }}", "joints visible", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(icon: String, value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 20.sp)
        Text(value, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 20.sp)
        Text(label, color = Palette.muted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }
