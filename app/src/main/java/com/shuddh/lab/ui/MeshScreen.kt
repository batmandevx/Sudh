package com.shuddh.lab.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

private val avatarColors = listOf(Color(0xFF8B5CF6), Color(0xFF22D3EE), Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFF43F5E), Color(0xFF60A5FA))
private fun avatarColor(name: String) = avatarColors[(name.hashCode() and 0x7fffffff) % avatarColors.size]
private fun initials(name: String) = name.split(" ", "-", "_").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "?" }

@Composable
fun MeshScreen(app: AppState) {
    val mesh = app.mesh
    var text by remember { mutableStateOf("") }
    var showInvite by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf<String?>(null) }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { mesh.start() }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (mesh.hasPermissions()) { if (!mesh.bluetoothOn) runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } else mesh.start() }
    }
    fun join() {
        when {
            mesh.running -> {}
            !mesh.hasPermissions() -> perms.launch(mesh.permissions())
            !mesh.bluetoothOn -> runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            else -> mesh.start()
        }
    }
    val roomMsgs = mesh.messages.filter { it.packet.room == mesh.roomCode || it.packet.type == MeshProto.SOS || it.packet.type == MeshProto.ALERT || it.packet.type == MeshProto.SEAL }
    val peers = mesh.peers.values.sortedByDescending { it.rssi }
    androidx.compose.runtime.LaunchedEffect(mesh.running) { if (mesh.running) app.prefs.setFlag("mesh_joined") }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Aurora(intensity = 0.45f)
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.back() }, modifier = Modifier.size(40.dp).clip(CircleShape).background(Palette.glass)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.text)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("# ${mesh.room}", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Palette.text, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (mesh.running) LiveDot(Palette.accent) else Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.muted))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (mesh.running) "${peers.size} nearby · ${peers.count { it.room == mesh.roomCode }} in room · ${mesh.relayed} relayed" else "Offline — join to chat",
                            color = Palette.muted, fontSize = 11.sp, maxLines = 1,
                        )
                    }
                }
                HeaderPill("⊞ Invite") { showInvite = true }
                Spacer(Modifier.width(6.dp))
                HeaderPill("⌁ Scan") { showScan = true }
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

            if (!mesh.running) {
                JoinHero(mesh.status) { join() }
            } else {
                // People nearby
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Avatar("You", app.prefs.meshName, true, null, inRoom = true) }
                    items(peers, key = { it.address }) { p -> Avatar(p.name, p.name, false, p, inRoom = p.room == mesh.roomCode) }
                    if (peers.isEmpty()) item { ScanningPulse() }
                }
            }
            joined?.let { Text("Joined # $it", color = Palette.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

            // Chat
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (roomMsgs.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("💬", fontSize = 36.sp)
                        Text("No messages in # ${mesh.room} yet", color = Palette.text, fontWeight = FontWeight.SemiBold)
                        Text("Tap Invite and let a friend scan the QR to join this room.\nMessages hop phone-to-phone over Bluetooth — no internet.", color = Palette.muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                items(roomMsgs, key = { it.packet.id.toString() + it.receivedAt }) { m -> ChatBubble(m) }
            }

            // Composer
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickChip("🆘 SOS", Palette.red, mesh.running) { mesh.send(MeshProto.SOS, "Unsafe drinking water reported near ${app.prefs.area.ifBlank { "this location" }}. Boil or use another source.") }
                QuickChip("📋 Share last verdict", Palette.amber, mesh.running && app.store.records.isNotEmpty()) {
                    val r = app.store.records.last()
                    mesh.send(MeshProto.ALERT, listOf(r.vendor, r.area, r.analyte, r.level.name, r.value?.let { "${fmt(it)} ${r.unit}" } ?: "").joinToString("|"))
                }
            }
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.verticalGradient(listOf(Color(0x26FFFFFF), Color(0x14FFFFFF)))).border(1.dp, Palette.line, RoundedCornerShape(28.dp)).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    text, { if (it.length <= 140) text = it }, enabled = mesh.running, maxLines = 4,
                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp),
                    textStyle = TextStyle(color = Palette.text, fontSize = 16.sp, fontFamily = Body), cursorBrush = SolidColor(Palette.cyan),
                    decorationBox = { inner -> Box { if (text.isEmpty()) Text(if (mesh.running) "Message # ${mesh.room}…" else "Join the mesh to chat", color = Palette.muted, fontSize = 16.sp); inner() } },
                )
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (mesh.running && text.isNotBlank()) Brush.linearGradient(listOf(Palette.accent, Palette.cyan)) else Brush.linearGradient(listOf(Palette.line, Palette.line)))
                        .clickable(enabled = mesh.running && text.isNotBlank()) { mesh.send(MeshProto.CHAT, text.trim()); text = "" },
                    contentAlignment = Alignment.Center,
                ) { Text("↑", color = Color(0xFF032016), fontSize = 20.sp, fontWeight = FontWeight.Black) }
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

@Composable
private fun HeaderPill(label: String, onClick: () -> Unit) {
    Text(label, color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.glass).border(1.dp, Palette.line, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp))
}

@Composable
private fun QuickChip(label: String, c: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(label, color = if (enabled) c else Palette.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = if (enabled) 0.14f else 0.05f)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun JoinHero(status: String, onJoin: () -> Unit) {
    Column(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF13304A), Color(0xFF0F1A2A))))
            .border(1.dp, Palette.cyan.copy(alpha = 0.35f), RoundedCornerShape(24.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScanningPulse(big = true)
        Text("Offline chat over Bluetooth", color = Palette.text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("No SIM, no Wi-Fi, no internet. Messages hop from phone to phone up to 4 times.", color = Palette.muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                drawCircle(Palette.cyan.copy(alpha = (1 - q) * 0.5f), size.minDimension / 2 * q, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            }
            drawCircle(Palette.cyan, size.minDimension * 0.08f)
        }
    }
}

@Composable
private fun Avatar(label: String, name: String, me: Boolean, p: Peer?, inRoom: Boolean) {
    val c = if (me) Palette.accent else avatarColor(name)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.5f))))
                    .border(2.dp, if (inRoom) Palette.accent else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(initials(name), color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp) }
            p?.let { SignalBars(it.rssi) }
        }
        Text(label, color = Palette.text, fontSize = 11.sp, maxLines = 1)
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
private fun ChatBubble(m: MeshMessage) {
    val p = m.packet
    val (tag, color) = when (p.type) {
        MeshProto.ALERT -> "ALERT" to Palette.amber
        MeshProto.SEAL -> "SEAL" to Palette.accent
        MeshProto.SOS -> "SOS" to Palette.red
        else -> null to Palette.blue
    }
    val body = when (p.type) {
        MeshProto.ALERT -> p.text.split("|").let { f ->
            "${f.getOrNull(2) ?: ""} ${f.getOrNull(4) ?: ""} — ${f.getOrNull(3) ?: ""}" +
                (f.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + (f.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        }
        MeshProto.SEAL -> p.text.split("|").let { f -> "${f.getOrNull(0)}: ${f.getOrNull(2)} of ${f.getOrNull(3)} scans failed" }
        else -> p.text
    }
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(p.time))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!m.mine) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(avatarColor(p.name)), contentAlignment = Alignment.Center) {
                Text(initials(p.name), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
        }
        Column(
            Modifier.widthIn(max = 290.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (m.mine) 20.dp else 6.dp, bottomEnd = if (m.mine) 6.dp else 20.dp))
                .background(
                    when {
                        tag != null -> Brush.horizontalGradient(listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0.1f)))
                        m.mine -> Brush.horizontalGradient(listOf(Color(0xFF0E5E4A), Color(0xFF0E4E5E)))
                        else -> Brush.horizontalGradient(listOf(Palette.surface2, Palette.surface2))
                    },
                ).padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            if (!m.mine) Text(p.name, color = avatarColor(p.name), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (tag != null) Badge(tag, color)
            Text(body, color = Palette.text, fontSize = 15.sp)
            Text(time + if (!m.mine) " · ${m.hops} hop${if (m.hops > 1) "s" else ""}" else " ✓", color = Palette.muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
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

