package com.shuddh.lab.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shuddh.lab.core.MediaDoc
import com.shuddh.lab.core.MediaHit
import com.shuddh.lab.core.MediaIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Frame(val doc: MediaDoc, val bmp: Bitmap)

private fun mmss(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
fun VideoMomentScreen(app: AppState) {
    val ctx = app.ctx
    val idx = app.media
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf<Uri?>(null) }
    var duration by remember { mutableStateOf(0L) }
    val frames = remember { mutableStateListOf<Frame>() }
    var progress by remember { mutableStateOf(0f) }
    var working by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<MediaHit>>(emptyList()) }
    var player by remember { mutableStateOf<VideoView?>(null) }
    var focus by remember { mutableStateOf<Long?>(null) }

    fun search(q: String) {
        if (q.isBlank() || frames.isEmpty()) { hits = emptyList(); return }
        scope.launch {
            val qv = withContext(Dispatchers.Default) { idx.embed(q) }
            hits = MediaIndex.rank(q, qv, frames.map { it.doc }, top = frames.size)
            hits.firstOrNull()?.let { focus = it.doc.timeMs; player?.seekTo(it.doc.timeMs.toInt()) }
        }
    }

    fun indexVideo(uri: Uri) = scope.launch {
        working = true; frames.clear(); hits = emptyList(); progress = 0f
        val mmr = MediaMetadataRetriever()
        try {
            withContext(Dispatchers.IO) { mmr.setDataSource(ctx, uri) }
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val step = maxOf(1000L, duration / 90) // ≤ 90 frames
            var t = 0L
            while (t < duration) {
                val b = withContext(Dispatchers.IO) {
                    runCatching { mmr.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640) }.getOrNull()
                }
                if (b != null) {
                    val d = runCatching { idx.describe(b, "t$t", uri.toString(), t) }.getOrNull()
                    if (d != null) frames += Frame(d, Bitmap.createScaledBitmap(b, 160, (160f * b.height / b.width).toInt().coerceAtLeast(1), true))
                }
                t += step
                progress = (t.toFloat() / duration).coerceAtMost(1f)
            }
        } finally {
            runCatching { mmr.release() }
            working = false
            if (query.isNotBlank()) search(query)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { video = uri; indexVideo(uri) }
    }

    ScreenFrame("Video Moment Finder", "Find moments in a video by describing them", onBack = { app.back() }) {
        if (video == null) {
            Glass(glow = Palette.cyan) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { InstrumentGlyph(Glyph.SCATTER, Palette.cyan, Modifier.size(90.dp)) }
                Text("Pick a video — e.g. a vendor filling your milk can, or your kitchen while cooking.", color = Palette.text, fontSize = 14.sp)
                Note("Shuddh samples about one frame per second, reads each one on-device (objects + text), then lets you jump to any moment by describing it.")
                Btn("🎬 Pick a video", Modifier.fillMaxWidth()) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            }
        } else {
            AndroidView(
                factory = { c -> VideoView(c).apply { setVideoURI(video); setOnPreparedListener { it.isLooping = false; seekTo(1) }; player = this } },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(Color.Black),
            )
            BtnRow {
                Btn("▶ Play") { player?.start() }
                Btn("⏸ Pause", primary = false) { player?.pause() }
                Btn("Another video", primary = false) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
            }
            if (working) {
                val p by animateFloatAsState(progress, tween(250), label = "vp")
                Note("Reading frames on-device… ${frames.size} indexed", Palette.cyan)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.line)) {
                    Box(Modifier.fillMaxWidth(p).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient(SpectrumColors)))
                }
            }
            if (frames.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(frames, key = { it.doc.key }) { f ->
                        Box(Modifier.enter(0).clip(RoundedCornerShape(8.dp)).clickable { focus = f.doc.timeMs; player?.seekTo(f.doc.timeMs.toInt()) }
                            .border(2.dp, if (focus == f.doc.timeMs) Palette.accent else Color.Transparent, RoundedCornerShape(8.dp))) {
                            Image(f.bmp.asImageBitmap(), null, Modifier.size(64.dp, 40.dp), contentScale = ContentScale.Crop)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.verticalGradient(listOf(Color(0x26FFFFFF), Color(0x14FFFFFF))))
                    .border(1.dp, Palette.line, RoundedCornerShape(28.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🔎", fontSize = 18.sp)
                BasicTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    textStyle = TextStyle(color = Palette.text, fontSize = 16.sp, fontFamily = Body), cursorBrush = SolidColor(Palette.cyan),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("Describe a moment… \"bottle\", \"label\", \"person\"", color = Palette.muted, fontSize = 15.sp); inner() } },
                )
                Text("Find", color = Palette.cyan, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { search(query) })
            }
            BtnRow { listOf("bottle", "person pouring", "label text", "food", "hand").forEach { s -> Btn(s, primary = false) { query = s; search(s) } } }

            if (hits.isNotEmpty() && duration > 0) {
                Section("Relevance across the video") {
                    MomentTimeline(hits, duration, focus) { t -> focus = t; player?.seekTo(t.toInt()) }
                    Row { Text("0:00", color = Palette.muted, fontSize = 10.sp, modifier = Modifier.weight(1f)); Text(mmss(duration), color = Palette.muted, fontSize = 10.sp) }
                }
                Section("Top moments") {
                    hits.take(4).forEachIndexed { i, h ->
                        val f = frames.firstOrNull { it.doc.key == h.doc.key } ?: return@forEachIndexed
                        Row(
                            Modifier.fillMaxWidth().enter(i).clip(RoundedCornerShape(14.dp)).background(if (focus == h.doc.timeMs) Palette.accent.copy(alpha = 0.15f) else Palette.glass)
                                .clickable { focus = h.doc.timeMs; player?.seekTo(h.doc.timeMs.toInt()); player?.start() }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(f.bmp.asImageBitmap(), null, Modifier.size(84.dp, 52.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${mmss(h.doc.timeMs)}  ·  ${(((h.score + 0.2) / 1.2).coerceIn(0.0, 1.0) * 100).toInt()}% match", color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(h.doc.labels.take(4).joinToString().ifBlank { h.doc.text.take(50) }, color = Palette.muted, fontSize = 11.sp, maxLines = 1)
                            }
                            Text("▶", color = Palette.accent, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
        HowItWorks(listOf(
            "Android's MediaMetadataRetriever pulls one frame roughly every second (up to 90 frames).",
            "Each frame is read by ML Kit on the phone: objects it recognises plus any printed text.",
            "That description becomes a vector using the Universal Sentence Encoder (6 MB, bundled).",
            "Your words become a vector too; frames are ranked by meaning plus keyword overlap, and the player jumps to the best moment.",
        ))
    }
}

/** Bars over time, height = relevance, animated in; tap anywhere to seek. */
@Composable
private fun MomentTimeline(hits: List<MediaHit>, duration: Long, focus: Long?, onSeek: (Long) -> Unit) {
    val grow = remember(hits) { Animatable(0f) }
    LaunchedEffect(hits) { grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val maxS = (hits.maxOfOrNull { it.score } ?: 1.0).coerceAtLeast(0.01)
    val minS = hits.minOfOrNull { it.score } ?: 0.0
    Canvas(
        Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x55000000))
            .pointerInput(duration) { detectTapGestures { p -> onSeek((p.x / size.width * duration).toLong()) } },
    ) {
        val bw = (size.width / hits.size.coerceAtLeast(1)).coerceIn(3f, 18f)
        hits.forEach { h ->
            val x = h.doc.timeMs.toFloat() / duration * size.width
            val f = ((h.score - minS) / (maxS - minS).coerceAtLeast(1e-6)).toFloat()
            val hh = (size.height - 8f) * (0.06f + 0.94f * f) * grow.value
            val c = when { f > 0.8f -> Palette.accent; f > 0.5f -> Palette.cyan; else -> Palette.violet.copy(alpha = 0.6f) }
            drawRoundRect(c, Offset(x - bw / 2, size.height - hh), Size(bw * 0.8f, hh), CornerRadius(3f))
        }
        focus?.let { t ->
            val x = t.toFloat() / duration * size.width
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 3f)
        }
    }
}
