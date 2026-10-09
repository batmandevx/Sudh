package com.shuddh.lab.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Badges
import com.shuddh.lab.core.Complaint
import com.shuddh.lab.core.Lang
import com.shuddh.lab.core.ModelRole
import com.shuddh.lab.core.Passport
import com.shuddh.lab.core.ShareCard
import com.shuddh.lab.core.Txt
import java.io.File

@Composable
fun SettingsScreen(app: AppState) {
    val ctx = app.ctx
    var name by remember { mutableStateOf(app.prefs.meshName) }
    var area by remember { mutableStateOf(app.prefs.area) }
    var famName by remember { mutableStateOf(app.prefs.familyName) }
    var phone by remember { mutableStateOf(app.prefs.familyPhone) }
    var auto by remember { mutableStateOf(app.prefs.autoSpeak) }
    var online by remember { mutableStateOf(app.prefs.onlineMap) }
    var confirmClear by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val badges = Badges.all(app.store, app.prefs)
    val (lvl, title, _) = Badges.level(badges)

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Aurora(intensity = 0.5f)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Settings", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Palette.text)

            // Profile
            Row(
                Modifier.fillMaxWidth().enter(0).clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Palette.accent.copy(alpha = 0.22f), Palette.cyan.copy(alpha = 0.08f))))
                    .border(1.dp, Palette.accent.copy(alpha = 0.35f), RoundedCornerShape(26.dp)).clickable { app.go(Screen.BADGES) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(62.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Palette.accent, Palette.cyan))), contentAlignment = Alignment.Center) {
                    Text(name.split(" ", "-").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "S" }, color = Color(0xFF032016), fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Level $lvl · $title", color = Palette.amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${badges.count { it.unlocked }}/${badges.size} badges · ${app.store.records.size} scans", color = Palette.muted, fontSize = 11.sp)
                }
                Text("🏅", fontSize = 26.sp)
            }

            Group("Appearance", 1) {
                Text("Accent colour", color = Palette.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentTheme.entries.forEach { t ->
                        val sel = UiPrefs.accent == t
                        val ring by animateFloatAsState(if (sel) 1f else 0f, tween(250), label = "ring")
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { UiPrefs.accent = t; app.prefs.accent = t.name }) {
                            Box(Modifier.size(44.dp).clip(CircleShape).border((3 * ring).dp, Color.White, CircleShape).padding(4.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(t.primary, t.secondary))))
                            Text(t.label, color = if (sel) Palette.text else Palette.muted, fontSize = 10.sp)
                        }
                    }
                }
                ToggleRow("🌀", "Reduce motion", "Calmer screens: no aurora drift or entrance animations", UiPrefs.reduceMotion) {
                    UiPrefs.reduceMotion = it; app.prefs.reduceMotion = it
                }
                var hapticsOn by remember { mutableStateOf(app.prefs.haptics) }
                ToggleRow("📳", "Haptics", "Feel heartbeats, whistles, sonar pings, gestures and metal", hapticsOn) {
                    hapticsOn = it; app.prefs.haptics = it; com.shuddh.lab.core.Haptics.enabled = it
                    if (it) com.shuddh.lab.core.Haptics.heartbeat(app.ctx)
                }
                Text("Language", color = Palette.muted, fontSize = 12.sp)
                Chips(Lang.entries, app.lang, { it.label }) { app.setLanguage(it) }
            }

            Group("Voice", 2) {
                ToggleRow("🔊", "Speak verdicts", "Reads every result aloud in your language", auto) { auto = it; app.prefs.autoSpeak = it }
                LinkRow("🎙", "Test voice", "Hindi and Kannada need offline voice packs") {
                    app.voice.speak(Txt("Shuddh is ready.", "शुद्ध तैयार है।", "ಶುದ್ಧ ಸಿದ್ಧವಾಗಿದೆ.").get(app.lang), app.lang)?.let { ctx.toastLong(it) }
                }
            }

            Group("Privacy dashboard", 3) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (online) Palette.amber.copy(alpha = 0.10f) else Palette.accent.copy(alpha = 0.10f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(if (online) Palette.amber else Palette.accent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (online) "Online map enabled" else "Online map off", color = Palette.text, fontWeight = FontWeight.SemiBold)
                        Text("Map tiles and nearby places use the internet when enabled.", color = Palette.muted, fontSize = 11.sp)
                    }
                    Switch(online, { online = it; app.prefs.onlineMap = it }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.amber))
                }
                ToggleRow("🌐", "Assistant web access", "Search text goes to Bing. Chat history, scan records and photos are not attached.", app.prefs.onlineAssistant) {
                    app.prefs.updateWebAccess(it)
                }
                Text("Permissions", color = Palette.muted, fontSize = 12.sp)
                val perms = listOf(
                    Triple("📷", "Camera", Manifest.permission.CAMERA), Triple("🎤", "Microphone", Manifest.permission.RECORD_AUDIO),
                    Triple("📍", "Location", Manifest.permission.ACCESS_FINE_LOCATION),
                    Triple("📶", "Nearby devices (mesh)", if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_ADVERTISE else Manifest.permission.ACCESS_FINE_LOCATION),
                    Triple("🖼", "Photos (media search)", if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE),
                )
                perms.forEach { (icon, label, perm) ->
                    val ok = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(icon, fontSize = 16.sp); Spacer(Modifier.width(10.dp))
                        Text(label, color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Badge(if (ok) "ALLOWED" else "OFF", if (ok) Palette.accent else Palette.muted)
                    }
                }
                LinkRow("⚙️", "Manage permissions", "Opens Android's settings for Shuddh") {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                Text("Stored on this phone", color = Palette.muted, fontSize = 12.sp)
                StorageBars(app)
            }

            Group("Profile & family", 4) {
                Field("Your mesh name", name) { name = it.take(16); app.prefs.meshName = name }
                Field("Locality / ward", area) { area = it; app.prefs.area = it.trim() }
                Field("Family member's name", famName) { famName = it; app.prefs.familyName = it }
                Field("Family member's phone", phone) { phone = it; app.prefs.familyPhone = it.trim() }
            }

            Group("Routines", 5) {
                LinkRow("⏰", "Weekly test reminder", "Sunday 9:00 am in your Clock app — \"test milk & water\"") {
                    val ok = runCatching {
                        ctx.startActivity(
                            Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, 9).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, 0)
                                .putExtra(android.provider.AlarmClock.EXTRA_DAYS, arrayListOf(java.util.Calendar.SUNDAY))
                                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Shuddh: test milk & water")
                                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        ); true
                    }.getOrDefault(false)
                    ctx.toastLong(if (ok) "Weekly reminder set for Sundays 9:00" else "No clock app found")
                }
                LinkRow("🖼", "Share my score card", "A Kitchen Health image for WhatsApp or Instagram") { card = ShareCard.render(ctx, app.store) }
                LinkRow("🏅", "Badges", "${badges.count { it.unlocked }} of ${badges.size} earned") { app.go(Screen.BADGES) }
            }

            Group("Data", 6) {
                LinkRow("⬇", "Download full report (PDF)", "Saved to Downloads/Shuddh") {
                    runCatching { Passport.saveToDownloads(ctx, Passport.build(ctx, app.store.records.toList(), app.store.verifyChain() == -1, "Full kitchen report")) }
                        .onSuccess { ctx.toastLong("Saved to $it") }.onFailure { ctx.toastLong("Nothing to export yet") }
                }
                LinkRow("⇪", "Export CSV", "For a spreadsheet or the laptop console") {
                    Complaint.shareFile(ctx, Complaint.csv(ctx, app.store.records.toList()), "text/csv", "Export scans")
                }
                LinkRow("🗑", "Delete all scan history", "${app.store.records.size} scans · cannot be undone", danger = true) { confirmClear = true }
            }

            Group("About", 7) {
                LinkRow("📖", "Adulteration guide", "Home tests for 16 common foods") { app.go(Screen.FOODGUIDE) }
                LinkRow("🧰", "Build the ₹50 kit", "Grating, polarisers, vials") { app.go(Screen.GUIDE) }
                LinkRow("✨", "On-device AI models", "${ModelRole.entries.count { app.llm.installed(it) }}/3 installed") { app.go(Screen.MODELS) }
                LinkRow("↺", "Replay intro", "See the welcome screens again") { app.prefs.onboarded = false; app.go(Screen.ONBOARDING) }
                Text("Shuddh · Qwen2.5 (Apache-2.0), MediaPipe, ML Kit, ZXing, osmdroid, © OpenStreetMap contributors. Optional web results from Bing. Screening-grade results — confirm critical findings with an accredited lab.",
                    color = Palette.muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(100.dp))
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        confirmButton = { TextButton(onClick = { app.store.clear(); confirmClear = false }) { Text("Delete", color = Palette.red) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        title = { Text("Delete all history?") },
        text = { Text("This permanently deletes ${app.store.records.size} scans and their hash chain from this phone.") },
    )
    card?.let { b ->
        AlertDialog(
            onDismissRequest = { card = null },
            confirmButton = { TextButton(onClick = { Complaint.shareFile(ctx, ShareCard.save(ctx, b), "image/png", "Share score card"); card = null }) { Text("Share") } },
            dismissButton = { TextButton(onClick = { card = null }) { Text("Close") } },
            text = { Image(b.asImageBitmap(), "Score card", Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) },
        )
    }
}

@Composable
private fun Group(title: String, i: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.enter(i), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), color = Palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 6.dp))
        Glass(padding = 14, content = content)
    }
}

@Composable
private fun ToggleRow(icon: String, title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Palette.muted, fontSize = 11.sp)
        }
        Switch(on, onChange)
    }
}

@Composable
private fun LinkRow(icon: String, title: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, danger)
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) Palette.red else Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Palette.muted, fontSize = 11.sp)
        }
        Text("›", color = Palette.muted, fontSize = 22.sp)
    }
}

@Composable
private fun IconTile(icon: String, danger: Boolean = false) {
    Box(Modifier.padding(end = 12.dp).size(38.dp).clip(RoundedCornerShape(12.dp)).background(if (danger) Palette.red.copy(alpha = 0.15f) else Palette.glass), contentAlignment = Alignment.Center) {
        Text(icon, fontSize = 17.sp)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = Palette.muted, fontSize = 11.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x22000000)).border(1.dp, Palette.line, RoundedCornerShape(12.dp)).padding(12.dp),
            textStyle = TextStyle(color = Palette.text, fontSize = 15.sp, fontFamily = Body), cursorBrush = SolidColor(Palette.cyan),
        )
    }
}

private fun dirSize(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0

@Composable
private fun StorageBars(app: AppState) {
    val ctx = app.ctx
    val items = remember {
        listOf(
            "Scan history" to File(ctx.filesDir, "scans.json").length(),
            "Photo search index" to File(ctx.filesDir, "media_index.json").length(),
            "AI models" to dirSize(app.llm.dir),
            "Reports & map cache" to dirSize(ctx.cacheDir),
        )
    }
    val maxB = (items.maxOf { it.second }).coerceAtLeast(1)
    val colors = listOf(Palette.accent, Palette.violet, Palette.cyan, Palette.amber)
    items.forEachIndexed { i, (label, bytes) ->
        val a = remember { Animatable(0f) }
        LaunchedEffect(Unit) { a.animateTo((bytes.toFloat() / maxB).coerceAtLeast(0.01f), tween(900, delayMillis = 80 * i, easing = FastOutSlowInEasing)) }
        Column {
            Row {
                Text(label, color = Palette.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(human(bytes), color = Palette.muted, fontSize = 12.sp)
            }
            Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.line)) {
                Box(Modifier.fillMaxWidth(a.value).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors[i]))
            }
        }
    }
}

private fun human(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

