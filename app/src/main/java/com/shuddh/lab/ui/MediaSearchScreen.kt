package com.shuddh.lab.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.MediaDoc
import com.shuddh.lab.core.MediaHit
import com.shuddh.lab.core.MediaIndex
import com.shuddh.lab.core.Vision
import com.shuddh.lab.core.stamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Small in-memory thumbnail cache shared by the media screens. */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(160)
    suspend fun get(idx: MediaIndex, uri: String, px: Int = 320): Bitmap? = cache.get("$uri@$px") ?: withContext(Dispatchers.IO) {
        runCatching { idx.thumbnail(Uri.parse(uri), px) }.getOrNull()?.also { cache.put("$uri@$px", it) }
    }
}

@Composable
fun Thumb(idx: MediaIndex, uri: String, modifier: Modifier, px: Int = 320) {
    val bmp by produceState<Bitmap?>(null, uri) { value = Thumbs.get(idx, uri, px) }
    val a by animateFloatAsState(if (bmp != null) 1f else 0f, tween(400), label = "thumb")
    Box(modifier.background(Palette.surface2)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(a), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun MediaSearchScreen(app: AppState) {
    val idx = app.media
    val ctx = app.ctx
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(app.mediaQuery ?: "") }
    var hits by remember { mutableStateOf<List<MediaHit>>(emptyList()) }
    var searchMs by remember { mutableStateOf(0L) }
    var open by remember { mutableStateOf<MediaHit?>(null) }
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    var aiTerms by remember { mutableStateOf("") }
    fun search(q: String, useAi: Boolean = false) {
        if (q.isBlank() || idx.docs.isEmpty()) { hits = emptyList(); return }
        scope.launch {
            val t0 = System.currentTimeMillis()
            if (useAi && app.llm.installed(com.shuddh.lab.core.ModelRole.TOOLS)) {
                aiTerms = runCatching {
                    app.llm.generate(
                        com.shuddh.lab.core.ModelRole.TOOLS,
                        com.shuddh.lab.core.LocalLlm.chatml("List 6 short words for objects or text that would be visible in a photo matching the request. Reply with only comma-separated words.", q),
                    ).lineSequence().first().take(120)
                }.getOrDefault("")
            } else if (!useAi) aiTerms = ""
            val expanded = MediaIndex.expand(q) + if (aiTerms.isNotBlank()) ", $aiTerms" else ""
            val qv = withContext(Dispatchers.Default) { idx.embed(expanded) }
            hits = MediaIndex.rank(expanded, qv, idx.docs.toList())
            searchMs = System.currentTimeMillis() - t0
        }
    }
    fun index() = scope.launch { idx.indexGallery(200); if (query.isNotBlank()) search(query) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { index() }
    fun start() {
        val granted = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
        if (granted) index() else launcher.launch(if (Build.VERSION.SDK_INT >= 34) arrayOf(perm, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else arrayOf(perm))
    }
    LaunchedEffect(Unit) { app.mediaQuery?.let { search(it); app.mediaQuery = null } }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Aurora(intensity = 0.5f)
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(span = { GridItemSpan(3) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { app.back() }, modifier = Modifier.size(40.dp).clip(CircleShape).background(Palette.glass)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.text)
                        }
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text("Instant Media Search", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Palette.text)
                            Text("Search your photos by meaning · on-device", color = Palette.muted, fontSize = 11.sp)
                        }
                    }
                    Glass(glow = Palette.violet, padding = 14) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    CountUp(idx.docs.size) { Text(it, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 30.sp, color = Palette.text) }
                                    Text("  photos indexed", color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
                                }
                                Text(idx.status.ifBlank { "ML Kit labels + OCR → sentence embeddings (USE, 6 MB)" }, color = Palette.muted, fontSize = 11.sp)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Btn(if (idx.indexing) "${(idx.progress * 100).toInt()}%" else if (idx.docs.isEmpty()) "Index gallery" else "Update", enabled = !idx.indexing) { start() }
                                if (idx.docs.isNotEmpty() && !idx.indexing) Text("Rebuild", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.clickable { idx.clear(); start() }.padding(4.dp))
                            }
                        }
                        if (idx.indexing) {
                            val p by animateFloatAsState(idx.progress, tween(300), label = "p")
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.line)) {
                                Box(Modifier.fillMaxWidth(p).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient(SpectrumColors)))
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(idx.docs.take(12), key = { it.key }) { d -> Thumb(idx, d.uri, Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).enter(0)) }
                            }
                        }
                    }
                    // Search bar
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.verticalGradient(listOf(Palette.veil(0x26), Palette.veil(0x14))))
                            .border(1.dp, Palette.line, RoundedCornerShape(28.dp)).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🔎", fontSize = 18.sp)
                        BasicTextField(
                            query, { query = it; search(it) }, singleLine = true, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            textStyle = TextStyle(color = Palette.text, fontSize = 16.sp, fontFamily = Body), cursorBrush = SolidColor(Palette.cyan),
                            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Describe a photo… \"milk packet expiry\"", color = Palette.muted, fontSize = 15.sp); inner() } },
                        )
                        if (query.isNotEmpty()) Text("✕", color = Palette.muted, modifier = Modifier.clickable { query = ""; hits = emptyList() }.padding(10.dp))
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("milk packet", "expired label", "FSSAI licence", "fruit and vegetables", "receipt", "honey jar", "water bottle", "spices")) { s ->
                            Text(s, color = Palette.text, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.glass)
                                .border(1.dp, Palette.line, RoundedCornerShape(50)).clickable { query = s; search(s) }.padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                    }
                    if (query.isNotBlank() && idx.docs.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${hits.size} results · ranked in $searchMs ms", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            if (app.llm.installed(com.shuddh.lab.core.ModelRole.TOOLS)) {
                                Text("✨ AI expand", color = Palette.violet, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.violet.copy(alpha = 0.15f)).clickable { search(query, useAi = true) }.padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                        }
                        val syn = MediaIndex.expand(query).substringAfter(", ", "")
                        if (syn.isNotBlank() || aiTerms.isNotBlank()) Text("Also looking for: " + listOf(syn, aiTerms).filter { it.isNotBlank() }.joinToString(", ").take(140), color = Palette.cyan, fontSize = 11.sp)
                    }
                    if (idx.docs.isEmpty() && !idx.indexing) {
                        Note("Tap \"Index gallery\" — Shuddh reads your latest 200 photos once, on this phone, and builds a private search index. Nothing is uploaded: the app has no internet permission.")
                    }
                }
            }
            val shown = if (query.isBlank()) idx.docs.take(60).map { MediaHit(it, 0.0, emptyList()) } else hits
            items(shown, key = { it.doc.key }) { h ->
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable { open = h }) {
                    Thumb(idx, h.doc.uri, Modifier.fillMaxSize())
                    if (query.isNotBlank()) {
                        Text(
                            "${(((h.score + 0.2) / 1.2).coerceIn(0.0, 1.0) * 100).toInt()}%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (h.doc.expiry != null && h.doc.expiry < System.currentTimeMillis()) {
                        Text("EXPIRED", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Palette.red).padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                }
            }
        }
    }

    open?.let { h -> MediaDetail(app, h) { open = null } }
}

@Composable
private fun MediaDetail(app: AppState, h: MediaHit, onDismiss: () -> Unit) {
    val d: MediaDoc = h.doc
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val b = Thumbs.get(app.media, d.uri, 1280) ?: return@launch
                    app.pendingBitmap = b; app.pendingImage = Vision.analyse(b)
                    onDismiss(); app.go(Screen.ASSISTANT)
                }
            }) { Text("✨ Ask AI") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(stamp(d.timeMs), fontSize = 14.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Thumb(app.media, d.uri, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)), px = 900)
                if (d.labels.isNotEmpty()) Text("Sees: " + d.labels.joinToString(), fontSize = 12.sp)
                d.fssai?.let { Text("FSSAI $it", fontSize = 12.sp, color = Palette.accent) }
                d.expiry?.let { Text("Expiry ${stamp(it).substringBefore(",")}" + if (it < System.currentTimeMillis()) " — EXPIRED" else "", fontSize = 12.sp, color = if (it < System.currentTimeMillis()) Palette.red else Palette.text) }
                if (h.why.isNotEmpty()) Text("Why: " + h.why.joinToString(" · "), fontSize = 11.sp, color = Palette.cyan)
                if (d.text.isNotBlank()) Text(d.text.take(220), fontSize = 11.sp, color = Palette.muted)
                val sim = remember(d.key) { app.media.similar(d, 6) }
                if (sim.isNotEmpty()) {
                    Text("Looks similar (visual AI)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(sim) { (sd, c) ->
                            Box {
                                Thumb(app.media, sd.uri, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
                                Text("${(c * 100).toInt()}%", color = Color.White, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xAA000000)).padding(horizontal = 3.dp))
                            }
                        }
                    }
                }
            }
        },
    )
}
