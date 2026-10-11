package com.shuddh.lab.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.shuddh.lab.core.Fix
import com.shuddh.lab.core.Geo
import com.shuddh.lab.core.Level
import com.shuddh.lab.core.Place
import com.shuddh.lab.core.fmt
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/** A heat point: position, level, label. */
data class HeatPt(val lat: Double, val lon: Double, val level: Level, val label: String)

private fun levelArgb(l: Level) = when (l) { Level.UNSAFE -> 0xFFF43F5E.toInt(); Level.CAUTION -> 0xFFFBBF24.toInt(); else -> 0xFF34D399.toInt() }

private data class Cat(val label: String, val emoji: String, val color: Int, val match: (String) -> Boolean)

private val cats = listOf(
    Cat("All", "📍", 0xFF22D3EE.toInt()) { true },
    Cat("Dairy", "🥛", 0xFFEFF4FA.toInt()) { it.contains("dairy") },
    Cat("Grocery", "🛒", 0xFF34D399.toInt()) { Regex("supermarket|convenience|greengrocer|general").containsMatchIn(it) },
    Cat("Water", "💧", 0xFF60A5FA.toInt()) { it.contains("drinking water") },
    Cat("Market", "🧺", 0xFFFBBF24.toInt()) { it.contains("marketplace") },
    Cat("Food", "🍽", 0xFFF43F5E.toInt()) { Regex("restaurant|fast food|cafe|bakery|butcher").containsMatchIn(it) },
)

private fun catFor(kind: String) = cats.drop(1).firstOrNull { it.match(kind) } ?: cats[0]

/** Round pin bitmap with an emoji, used as the map marker icon. */
private fun pinIcon(ctx: android.content.Context, emoji: String, color: Int, selected: Boolean): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val s = ((if (selected) 46 else 36) * d).toInt()
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = ACanvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0x66000000; c.drawCircle(s / 2f, s / 2f + d, s / 2f - d, p)
    p.color = color; c.drawCircle(s / 2f, s / 2f, s / 2f - 2 * d, p)
    p.color = 0xFF0E1522.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f - 4.5f * d, p)
    p.textSize = s * 0.42f; p.textAlign = Paint.Align.CENTER
    c.drawText(emoji, s / 2f, s / 2f + p.textSize * 0.36f, p)
    return BitmapDrawable(ctx.resources, b)
}

/** Blue "you are here" dot with a heading wedge. */
private fun meIcon(ctx: android.content.Context): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val s = (56 * d).toInt()
    val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = ACanvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = RadialGradient(s / 2f, s / 2f, s / 2f, 0x553B82F6, 0x003B82F6, Shader.TileMode.CLAMP)
    c.drawCircle(s / 2f, s / 2f, s / 2f, p); p.shader = null
    val wedge = android.graphics.Path().apply { moveTo(s / 2f, 2 * d); lineTo(s / 2f - 9 * d, s / 2f - 4 * d); lineTo(s / 2f + 9 * d, s / 2f - 4 * d); close() }
    p.color = 0xAA3B82F6.toInt(); c.drawPath(wedge, p)
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(s / 2f, s / 2f, 10 * d, p)
    p.color = 0xFF3B82F6.toInt(); c.drawCircle(s / 2f, s / 2f, 7.5f * d, p)
    return BitmapDrawable(ctx.resources, b)
}

/** Heatmap drawn as radial gradients sized in metres, so it scales with zoom. */
private class HeatOverlay : Overlay() {
    var pts: List<HeatPt> = emptyList()
    var showHeat = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Point()
    override fun draw(c: ACanvas, mv: MapView, shadow: Boolean) {
        if (shadow || !showHeat) return
        val r = mv.projection.metersToPixels(90f).coerceIn(28f, 420f)
        pts.forEach { h ->
            mv.projection.toPixels(GeoPoint(h.lat, h.lon), p)
            val col = levelArgb(h.level)
            paint.shader = RadialGradient(p.x.toFloat(), p.y.toFloat(), r, intArrayOf((col and 0x00FFFFFF) or (0xA0 shl 24), col and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), r, paint)
        }
    }
}

@Composable
fun MapScreen(app: AppState) {
    val ctx = app.ctx
    val geo = app.geo
    val scope = rememberCoroutineScope()
    var online by remember { mutableStateOf(app.prefs.onlineMap) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf(cats[0]) }
    var selected by remember { mutableStateOf<Place?>(null) }
    var dark by remember { mutableStateOf(true) }
    var heat by remember { mutableStateOf(true) }
    var sheetOpen by remember { mutableStateOf(false) }
    var recentre by remember { mutableStateOf(0) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { geo.start() }

    DisposableEffect(Unit) {
        if (geo.hasPermission()) geo.start() else perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        onDispose { geo.stop() }
    }
    val fix = geo.fix
    val pts = app.store.records.filter { it.lat != null && it.lon != null }.map { HeatPt(it.lat!!, it.lon!!, it.level, it.analyte) }

    fun fetch() {
        val f = fix ?: run { status = "Waiting for your location…"; return }
        status = "Searching nearby…"
        scope.launch {
            runCatching { Geo.nearbyPlaces(f.lat, f.lon, 2000) }
                .onSuccess { places = it; status = ""; sheetOpen = true }
                .onFailure { status = it.message?.take(60) ?: "Couldn't reach OpenStreetMap" }
        }
    }
    LaunchedEffect(online, fix != null) { if (online && fix != null && places.isEmpty()) fetch() }
    val visible = places.filter { cat.match(it.kind) }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        if (online) {
            StreetMap(fix, geo.heading, pts, visible, selected, dark, heat, recentre) { selected = it; sheetOpen = true }
        } else {
            EnableMapCard(Modifier.align(Alignment.Center)) { online = true; app.prefs.onlineMap = true }
        }

        // Top overlay: back + title + categories
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FloatingRound(onClick = { app.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.text) }
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier.weight(1f).shadow(8.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(Palette.surface)
                        .clickable(enabled = online) { fetch() }.padding(horizontal = 18.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔎", fontSize = 15.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (status.isNotBlank()) status else "Search food & water nearby", color = if (status.isNotBlank()) Palette.cyan else Palette.muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    if (fix != null) Text("±${fmt(fix.accuracy.toDouble())}m", color = Palette.muted, fontSize = 11.sp)
                }
            }
            if (online) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cats) { c ->
                    val sel = c == cat
                    val n = if (c == cats[0]) places.size else places.count { c.match(it.kind) }
                    Text(
                        "${c.emoji} ${c.label}${if (places.isNotEmpty()) " $n" else ""}", fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) Palette.onAccent else Palette.text,
                        modifier = Modifier.shadow(4.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                            .background(if (sel) Palette.accent else Palette.surface).clickable { cat = c; selected = null }.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }

        // Right-side floating controls
        if (online) Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FloatingRound(onClick = { recentre++ }) { Text("◎", color = Palette.blue, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            FloatingRound(onClick = { dark = !dark }) { Text(if (dark) "☀" else "☾", color = Palette.text, fontSize = 18.sp) }
            FloatingRound(onClick = { heat = !heat }, active = heat) { Text("🔥", fontSize = 16.sp) }
        }

        // Bottom: selected place card or nearby sheet
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AnimatedVisibility(selected != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                selected?.let { p -> PlaceCard(p, fix, geo.heading, onClose = { selected = null }) { openDirections(ctx, p) } }
            }
            if (online && selected == null) NearbySheet(visible, fix, sheetOpen, { sheetOpen = !sheetOpen }) { selected = it }
        }
    }
}

private fun openDirections(ctx: android.content.Context, p: Place) {
    // Hands off to whatever maps app the user has (Google Maps, OsmAnd…).
    val uri = Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode(p.name)})")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { ctx.toastLong("No maps app installed") }
}

@Composable
private fun FloatingRound(onClick: () -> Unit, active: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(if (active) Palette.surface2 else Palette.surface)
            .border(1.dp, if (active) Palette.amber.copy(alpha = 0.6f) else Palette.line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun EnableMapCard(modifier: Modifier, onEnable: () -> Unit) {
    Column(
        modifier.padding(24.dp).clip(RoundedCornerShape(28.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(28.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🗺", fontSize = 48.sp)
        Text("Turn on the map", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Shuddh will download OpenStreetMap streets and look up nearby dairies, grocers and water points around you. " +
                "Your scans, photos and voice never leave the phone. You can switch this off in Settings.",
            color = Palette.muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Btn("Enable map", Modifier.fillMaxWidth()) { onEnable() }
    }
}

@Composable
private fun PlaceCard(p: Place, fix: Fix?, heading: Float, onClose: () -> Unit, onDirections: () -> Unit) {
    val c = catFor(p.kind)
    val bearing = fix?.let { Geo.bearing(it.lat, it.lon, p.lat, p.lon) } ?: 0.0
    val walkMin = (p.metres / 80).toInt().coerceAtLeast(1)
    Column(
        Modifier.padding(12.dp).fillMaxWidth().shadow(16.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(Palette.surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Color(c.color).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Text(c.emoji, fontSize = 22.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2)
                Text(p.kind.replaceFirstChar { it.uppercase() }, color = Palette.muted, fontSize = 12.sp)
            }
            Text("✕", color = Palette.muted, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onClose).padding(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge("${fmt(p.metres)} m", Palette.cyan)
            Badge("🚶 $walkMin min", Palette.accent)
            Badge("${compass(bearing)} ${bearing.toInt()}°", Palette.violet)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("➤ Directions", Modifier.weight(1f)) { onDirections() }
        }
    }
}

private fun compass(b: Double) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((b + 22.5) % 360) / 45).toInt()]

@Composable
private fun NearbySheet(places: List<Place>, fix: Fix?, open: Boolean, onToggle: () -> Unit, onPick: (Place) -> Unit) {
    Column(
        Modifier.fillMaxWidth().shadow(18.dp, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(Palette.surface).padding(top = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.line))
            Spacer(Modifier.height(8.dp))
            Text(if (places.isEmpty()) "Nearby" else "Nearby · ${places.size} places", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        if (open) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                items(places.take(40)) { p ->
                    val c = catFor(p.kind)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(p) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(Color(c.color).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Text(c.emoji, fontSize = 17.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(p.kind, color = Palette.muted, fontSize = 11.sp)
                        }
                        Text("${fmt(p.metres)} m", color = Palette.muted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** OpenStreetMap view with the you-are-here marker, scan heatmap and place pins. */
@Composable
private fun StreetMap(fix: Fix?, heading: Float, pts: List<HeatPt>, places: List<Place>, selected: Place?, dark: Boolean, heat: Boolean, recentre: Int, onPick: (Place) -> Unit) {
    val ctx = LocalContext.current
    val heatOverlay = remember { HeatOverlay() }
    var centred by remember { mutableStateOf(false) }
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "osm"); osmdroidTileCache = File(ctx.cacheDir, "osm/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.5)
            minZoomLevel = 4.0; maxZoomLevel = 20.0
            overlays.add(heatOverlay)
        }
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    LaunchedEffect(dark) { map.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null); map.invalidate() }
    LaunchedEffect(heat, pts) { heatOverlay.showHeat = heat; heatOverlay.pts = pts; map.invalidate() }
    LaunchedEffect(recentre) { if (recentre > 0) fix?.let { map.controller.animateTo(GeoPoint(it.lat, it.lon), 17.0, 600L) } }
    LaunchedEffect(selected) { selected?.let { map.controller.animateTo(GeoPoint(it.lat, it.lon), 17.5, 600L) } }
    LaunchedEffect(fix, heading, places, selected) {
        map.overlays.removeAll { it is Marker }
        places.take(80).forEach { p ->
            val c = catFor(p.kind)
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(p.lat, p.lon); title = p.name
                icon = pinIcon(ctx, c.emoji, c.color, p == selected)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> onPick(p); true }
            })
        }
        fix?.let { f ->
            if (!centred) { map.controller.setCenter(GeoPoint(f.lat, f.lon)); centred = true }
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(f.lat, f.lon); icon = meIcon(ctx); rotation = -heading
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
            })
        }
        map.invalidate()
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ map }, Modifier.fillMaxSize())
        Text("© OpenStreetMap", color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 70.dp, start = 6.dp).background(Color(0x88000000)).padding(horizontal = 5.dp, vertical = 1.dp))
    }
}
