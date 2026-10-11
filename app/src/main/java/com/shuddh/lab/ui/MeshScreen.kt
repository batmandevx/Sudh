package com.shuddh.lab.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.MeshMessage
import com.shuddh.lab.core.MeshProto
import com.shuddh.lab.core.Peer
import com.shuddh.lab.core.Qr
import com.shuddh.lab.core.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
private val avatarColors = listOf(Color(0xFF8B5CF6), Color(0xFF06B6D4), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFF43F5E), Color(0xFF3B82F6))
private fun avatarColor(name: String) = avatarColors[(name.hashCode() and 0x7fffffff) % avatarColors.size]
private fun initials(name: String) = name.split(" ", "-", "_").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }
private val MeshGrad = listOf(Color(0xFF6366F1), Color(0xFF06B6D4))

@Composable
fun MeshScreen(app: AppState) {
    val mesh = app.mesh
    val ctx = app.ctx
    var text by remember { mutableStateOf("") }
    var showInvite by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf<String?>(null) }
    var sendSpin by remember { mutableStateOf(0f) }
    val spin by animateFloatAsState(sendSpin, spring(dampingRatio = 0.5f), label = "spin")
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { mesh.start() }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (mesh.hasPermissions()) { if (!mesh.bluetoothOn) runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } else mesh.start() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { u ->
            runCatching { ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()?.let { b ->
                if (!mesh.sendImage(b)) ctx.toastLong("Photos need Bluetooth 5 (extended advertising) on this phone")
            }
        }
    }
    fun join() {
        when {
            mesh.running -> {}
            !mesh.hasPermissions() -> perms.launch(mesh.permissions())
            !mesh.bluetoothOn -> runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            else -> mesh.start()
        }
    }
    fun send() { if (mesh.running && text.isNotBlank()) { mesh.send(MeshProto.CHAT, text.trim()); text = ""; sendSpin += 360f } }
    val roomMsgs = mesh.messages.filter { it.packet.room == mesh.roomCode || it.packet.type == MeshProto.SOS || it.packet.type == MeshProto.ALERT || it.packet.type == MeshProto.SEAL }
    val peers = mesh.peers.values.sortedByDescending { it.rssi }
    androidx.compose.runtime.LaunchedEffect(mesh.running) { if (mesh.running) app.prefs.setFlag("mesh_joined") }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Aurora(intensity = 0.55f)
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Brush.horizontalGradient(MeshGrad.map { it.copy(alpha = 0.16f) })).border(1.dp, MeshGrad[0].copy(alpha = 0.25f), RoundedCornerShape(22.dp)).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { app.back() }, modifier = Modifier.size(40.dp).clip(CircleShape).background(Palette.glass)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.text)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("# ${mesh.room}", fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Palette.text, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (mesh.running) LiveDot(Palette.accent) else Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.muted))
                        Spacer(Modifier.width(6.dp))
                        Text(if (mesh.running) "${peers.size} nearby · ${mesh.relayed} relayed · offline" else "Bluetooth mesh — no internet needed", color = Palette.muted, fontSize = 11.sp, maxLines = 1)
                    }
                }
                HeaderPill("⊞") { showInvite = true }
                Spacer(Modifier.width(4.dp))
                HeaderPill("⌁") { showScan = true }
                Box {
                    IconButton(onClick = { menu = true }) { Text("⋮", color = Palette.text, fontSize = 22.sp) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Go to # Public") }, onClick = { mesh.joinRoom("Public"); menu = false })
                        DropdownMenuItem(text = { Text("New private room") }, onClick = { mesh.joinRoom("Room " + (1000..9999).random()); menu = false; showInvite = true })
                        DropdownMenuItem(text = { Text(if (mesh.beacon == null) "Start Purity Beacon" else "Stop Purity Beacon") }, onClick = {
                            mesh.beacon = if (mesh.beacon == null) app.store.vendors().firstOrNull()?.let { v -> val m = app.store.vendorMemory(v); "$v|${app.prefs.area}|${m.failures}|${m.total}" } else null
                            menu = false
                        })
                        if (mesh.running) DropdownMenuItem(text = { Text("Leave mesh") }, onClick = { mesh.stop(); menu = false })
                    }
                }
            }

            if (!mesh.running) JoinHero(mesh.status) { join() }
            else Radar(peers, mesh.roomCode, app.prefs.meshName)
            joined?.let { Text("Joined # $it", color = Palette.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

            // A verdict waiting to be broadcast.
            app.pendingMeshAlert?.let { msg ->
                val f = msg.split("|")
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(Brush.horizontalGradient(listOf(Palette.amber.copy(alpha = 0.22f), Palette.amber.copy(alpha = 0.06f)))).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📡", fontSize = 22.sp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Warn people nearby", color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${f.getOrNull(2) ?: ""} — ${f.getOrNull(3) ?: ""}" + (f.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), color = Palette.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Text(if (mesh.running) "Broadcast" else "Join first", color = if (mesh.running) Palette.onAccent else Palette.muted, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (mesh.running) Palette.accent else Palette.veil(0x14))
                            .clickable(enabled = mesh.running) { mesh.send(MeshProto.ALERT, msg); app.pendingMeshAlert = null }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }

            // Chat
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (roomMsgs.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ScanningPulse(big = true)
                        Text("No messages in # ${mesh.room} yet", color = Palette.text, fontWeight = FontWeight.SemiBold)
                        Text("Tap ⊞ to invite a friend by QR.\nText, photos and alerts hop phone-to-phone over Bluetooth.", color = Palette.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
                items(roomMsgs, key = { it.packet.id.toString() + it.receivedAt }) { m -> ChatBubble(m, mesh, Modifier.enter(0)) }
            }

            // Quick actions
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickChip("🆘 SOS", Palette.red, mesh.running) { mesh.send(MeshProto.SOS, "Unsafe drinking water reported near ${app.prefs.area.ifBlank { "this location" }}. Boil or use another source.") }
                QuickChip("📋 Last verdict", Palette.amber, mesh.running && app.store.records.isNotEmpty()) {
                    val r = app.store.records.last()
                    mesh.send(MeshProto.ALERT, listOf(r.vendor, r.area, r.analyte, r.level.name, r.value?.let { "${fmt(it)} ${r.unit}" } ?: "").joinToString("|"))
                }
            }
            // Composer
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Palette.surface).border(1.dp, Brush.horizontalGradient(MeshGrad.map { it.copy(alpha = 0.5f) }), RoundedCornerShape(28.dp)).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Palette.veil(0x10)).clickable(enabled = mesh.running) {
                        if (mesh.canSendImages) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        else ctx.toastLong("Photos need Bluetooth 5 (extended advertising) on this phone")
                    },
                    contentAlignment = Alignment.Center,
                ) { Text("📷", fontSize = 18.sp) }
                BasicTextField(
                    text, { if (it.length <= 140) text = it }, enabled = mesh.running, maxLines = 4,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = TextStyle(color = Palette.text, fontSize = 16.sp, fontFamily = Body), cursorBrush = SolidColor(Palette.cyan),
                    decorationBox = { inner -> Box { if (text.isEmpty()) Text(if (mesh.running) "Message # ${mesh.room}…" else "Join the mesh to chat", color = Palette.muted, fontSize = 16.sp); inner() } },
                )
                if (text.isNotEmpty()) Text("${140 - text.length}", color = Palette.muted, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
                Box(
                    Modifier.size(44.dp).graphicsLayer { rotationZ = spin }.clip(CircleShape)
                        .background(if (mesh.running && text.isNotBlank()) Brush.linearGradient(MeshGrad) else Brush.linearGradient(listOf(Palette.line, Palette.line)))
                        .clickable(enabled = mesh.running && text.isNotBlank()) { send() },
                    contentAlignment = Alignment.Center,
                ) { Text("➤", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black) }
            }
        }
    }

    if (showInvite) InviteDialog(app.prefs.meshName, mesh.room, onRoom = { mesh.joinRoom(it) }) { showInvite = false }
    if (showScan) {
        AlertDialog(
            onDismissRequest = { showScan = false },
            confirmButton = { TextButton(onClick = { showScan = false }) { Text("Close") } },
            title = { Text("Scan a room invite") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QrScanner { code ->
                        MeshProto.parseInvite(code)?.let { (room, from) ->
                            mesh.joinRoom(room); joined = "$room (invited by $from)"; showScan = false; join()
                        }
                    }
                    Text("Point at the QR on your friend's Mesh → Invite screen.", fontSize = 12.sp, color = Palette.muted)
                }
            },
        )
    }
}

/** Live radar: sweep line, distance rings, and a blip per nearby phone (closer = nearer the centre). */
@Composable
private fun Radar(peers: List<Peer>, room: Int, me: String) {
    val inf = rememberInfiniteTransition(label = "radar")
    val sweep by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "s")
    val ping by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "p")
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(22.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(118.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(118.dp)) {
                val r = size.minDimension / 2
                for (k in 1..3) drawCircle(MeshGrad[1].copy(alpha = 0.18f), r * k / 3, style = Stroke(1.5f))
                drawCircle(MeshGrad[1].copy(alpha = 0.25f * (1 - ping)), r * ping)
                rotate(sweep) { drawArc(Brush.sweepGradient(listOf(Color.Transparent, MeshGrad[1].copy(alpha = 0.45f)), center), -60f, 60f, true) }
                peers.forEach { p ->
                    val a = Math.toRadians(((p.address.hashCode() and 0x7fffffff) % 360).toDouble())
                    val d = (p.metres / 30.0).coerceIn(0.15, 0.95) * r
                    val c = Offset(center.x + (d * kotlin.math.cos(a)).toFloat(), center.y + (d * kotlin.math.sin(a)).toFloat())
                    drawCircle(avatarColor(p.name).copy(alpha = 0.3f), 9f, c); drawCircle(avatarColor(p.name), 5f, c)
                    if (p.room == room) drawCircle(Color(0xFF22C55E), 9f, c, style = Stroke(2f))
                }
                drawCircle(Brush.linearGradient(MeshGrad), 7f, center)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (peers.isEmpty()) "Looking for phones…" else "${peers.size} phone${if (peers.size > 1) "s" else ""} in range", color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Avatar("You", me, true, null, inRoom = true) }
                items(peers, key = { it.address }) { p -> Avatar(p.name, p.name, false, p, inRoom = p.room == room) }
            }
        }
    }
}

@Composable
private fun HeaderPill(label: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.glass).border(1.dp, Palette.line, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuickChip(label: String, c: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(label, color = if (enabled) c else Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = if (enabled) 0.14f else 0.05f)).border(1.dp, c.copy(alpha = if (enabled) 0.35f else 0.1f), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun JoinHero(status: String, onJoin: () -> Unit) {
    Column(
        Modifier.padding(14.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(MeshGrad[0].copy(alpha = 0.18f), MeshGrad[1].copy(alpha = 0.10f))))
            .border(1.dp, MeshGrad[1].copy(alpha = 0.35f), RoundedCornerShape(26.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScanningPulse(big = true)
        Text("Offline chat over Bluetooth", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Text("No SIM, no Wi-Fi, no internet. Text, photos and food-safety alerts hop phone to phone up to 4 times.", color = Palette.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("💬 Chat", "📷 Photos", "🆘 SOS", "📡 Alerts").forEach { Text(it, color = Palette.text, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.veil(0x10)).padding(horizontal = 8.dp, vertical = 4.dp)) }
        }
        if (status.isNotBlank() && status != "Mesh is off") Text(status, color = Palette.amber, fontSize = 12.sp)
        SlideToConfirm("Slide to join mesh", Modifier.fillMaxWidth(), confirmedLabel = "Joining…") { onJoin() }
    }
}

@Composable
private fun ScanningPulse(big: Boolean = false) {
    val t = rememberInfiniteTransition(label = "scan")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800)), label = "p")
    val s = if (big) 90.dp else 56.dp
    Box(Modifier.size(s), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(s)) {
            for (k in 0..2) {
                val q = (p + k / 3f) % 1f
                drawCircle(MeshGrad[1].copy(alpha = (1 - q) * 0.5f), size.minDimension / 2 * q, style = Stroke(3f))
            }
            drawCircle(Brush.linearGradient(MeshGrad), size.minDimension * 0.1f)
        }
    }
}

@Composable
private fun Avatar(label: String, name: String, me: Boolean, p: Peer?, inRoom: Boolean) {
    val c = if (me) Palette.accent else avatarColor(name)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(54.dp)) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.55f))))
                    .border(2.dp, if (inRoom) Color(0xFF22C55E) else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(initials(name), color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp) }
            p?.let { SignalBars(it.rssi) }
        }
        Text(label, color = Palette.text, fontSize = 10.sp, maxLines = 1)
        p?.let { Text("${fmt(it.metres)} m", color = Palette.muted, fontSize = 9.sp) }
    }
}

@Composable
private fun SignalBars(rssi: Int) {
    val bars = when { rssi > -60 -> 4; rssi > -70 -> 3; rssi > -82 -> 2; else -> 1 }
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.bg).padding(2.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (i in 1..4) Box(Modifier.width(3.dp).height((3 * i).dp).background(if (i <= bars) Palette.accent else Palette.line))
    }
}

@Composable
private fun ChatBubble(m: MeshMessage, mesh: com.shuddh.lab.core.Mesh, modifier: Modifier) {
    val p = m.packet
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(p.time))
    val meta = time + if (!m.mine) " · ${m.hops} hop${if (m.hops > 1) "s" else ""}" else " ✓"
    // Alerts, seals and SOS get a full-width card.
    if (p.type == MeshProto.ALERT || p.type == MeshProto.SEAL || p.type == MeshProto.SOS) {
        val (icon, tag, c) = when (p.type) { MeshProto.ALERT -> Triple("⚠️", "FOOD ALERT", Palette.amber); MeshProto.SEAL -> Triple("🛡️", "PURITY SEAL", Palette.accent); else -> Triple("🆘", "SOS", Palette.red) }
        val body = when (p.type) {
            MeshProto.ALERT -> p.text.split("|").let { f -> "${f.getOrNull(2) ?: ""} ${f.getOrNull(4) ?: ""} — ${f.getOrNull(3) ?: ""}" + (f.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + (f.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") }
            MeshProto.SEAL -> p.text.split("|").let { f -> "${f.getOrNull(0)}: ${f.getOrNull(2)} of ${f.getOrNull(3)} scans failed" }
            else -> p.text
        }
        Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.20f), c.copy(alpha = 0.05f)))).border(1.dp, c.copy(alpha = 0.4f), RoundedCornerShape(18.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 26.sp); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tag, color = c, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text(body, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("from ${if (m.mine) "you" else p.name} · $meta", color = Palette.muted, fontSize = 10.sp)
            }
        }
        return
    }
    val imgId = if (p.type == MeshProto.IMAGE) p.text.removePrefix("img:").toIntOrNull() else null
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!m.mine) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(avatarColor(p.name)), contentAlignment = Alignment.Center) { Text(initials(p.name), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(6.dp))
        }
        Column(
            Modifier.widthIn(max = 280.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (m.mine) 20.dp else 6.dp, bottomEnd = if (m.mine) 6.dp else 20.dp))
                .background(if (m.mine) Brush.linearGradient(MeshGrad) else Brush.linearGradient(listOf(Palette.surface, Palette.surface2)))
                .then(if (m.mine) Modifier else Modifier.border(1.dp, Palette.line, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)))
                .padding(horizontal = if (imgId != null) 6.dp else 14.dp, vertical = if (imgId != null) 6.dp else 9.dp),
        ) {
            if (!m.mine) Text(p.name, color = avatarColor(p.name), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = if (imgId != null) 6.dp else 0.dp))
            if (imgId != null) {
                val bmp = mesh.images[imgId]
                val prog = mesh.imageProgress[imgId] ?: 0f
                Box(Modifier.size(180.dp, 140.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0B1220)), contentAlignment = Alignment.Center) {
                    if (bmp != null) Image(bmp.asImageBitmap(), "photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val a by animateFloatAsState(prog, tween(400), label = "ip")
                        Canvas(Modifier.size(52.dp)) {
                            drawArc(Color.White.copy(alpha = 0.15f), -90f, 360f, false, style = Stroke(6f))
                            drawArc(Brush.sweepGradient(MeshGrad), -90f, 360f * a, false, style = Stroke(6f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                        Text("photo arriving ${(prog * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                    }
                }
            } else Text(p.text, color = if (m.mine) Color.White else Palette.text, fontSize = 15.sp)
            Text(meta, color = if (m.mine) Color.White.copy(alpha = 0.75f) else Palette.muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.End).padding(end = if (imgId != null) 6.dp else 0.dp))
        }
    }
}

@Composable
private fun InviteDialog(me: String, room: String, onRoom: (String) -> Unit, onDismiss: () -> Unit) {
    val bmp = remember(room, me) { Qr.encode(MeshProto.invite(room, me), 640) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { onRoom("Room " + (1000..9999).random()) }) { Text("New private room") } },
        title = { Text("Invite to # $room") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(bmp.asImageBitmap(), "Room invite QR", Modifier.size(230.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp))
                Text("On the other phone: Shuddh → Hive → Shuddh Mesh → ⌁ Scan", fontSize = 12.sp, color = Palette.muted)
                Text("Both phones then chat in # $room. Messages stay on Bluetooth — nothing goes online.", fontSize = 12.sp)
            }
        },
    )
}

