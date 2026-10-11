package com.shuddh.lab.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.GuardianService
import com.shuddh.lab.core.GuardianState
import com.shuddh.lab.core.Haptics
import com.shuddh.lab.core.MeshProto
import com.shuddh.lab.core.Sos
import kotlinx.coroutines.delay
import kotlin.math.sqrt

private val sosRed = Color(0xFFE11D48)

/** Sends an SOS everywhere it can: Bluetooth mesh (works with no network) + SMS to family. Returns a summary. */
fun sendSos(app: AppState, kind: String): String {
    val fix = app.geo.lastKnown()
    val msg = Sos.message(kind, fix?.lat, fix?.lon, app.prefs.meshName.ifBlank { "Shuddh user" })
    val sent = mutableListOf<String>()
    runCatching { if (!app.mesh.running) app.mesh.start(); app.mesh.send(MeshProto.SOS, msg); sent += "Bluetooth mesh (${app.mesh.peers.size} nearby)" }
    val phone = app.prefs.familyPhone
    if (phone.isNotBlank()) {
        val ok = app.ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED && runCatching {
            val sms = if (Build.VERSION.SDK_INT >= 31) app.ctx.getSystemService(android.telephony.SmsManager::class.java) else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            sms.sendMultipartTextMessage(phone, null, sms.divideMessage("Shuddh $msg"), null, null); true
        }.getOrDefault(false)
        if (ok) sent += "SMS to family" else runCatching { com.shuddh.lab.core.Passport.sms(app.ctx, phone, "Shuddh $msg"); sent += "SMS ready to send" }
    }
    val log = (app.prefs.json("sos_log")?.optJSONArray("l") ?: org.json.JSONArray()).put(org.json.JSONObject().put("t", System.currentTimeMillis()).put("k", kind).put("m", msg))
    app.prefs.putJson("sos_log", org.json.JSONObject().put("l", log))
    return if (sent.isEmpty()) "SOS could not be sent — add a family number in Settings and allow Bluetooth." else "SOS sent via " + sent.joinToString(" + ")
}

/** Guardian — fall detection + offline SOS over the Bluetooth mesh. */
@Composable
fun GuardianScreen(app: AppState) {
    val ctx = app.ctx
    var disaster by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val gTrace = remember { mutableStateListOf<Float>() }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { GuardianService.start(ctx) }

    // Live g-force (only while this screen is open) so people can see what the detector watches.
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81f
                gTrace += g; if (gTrace.size > 200) gTrace.removeAt(0)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(l) }
    }
    // Disaster beacon: broadcast location over the mesh every 60 s while on.
    LaunchedEffect(disaster) {
        while (disaster) {
            val fix = app.geo.lastKnown()
            runCatching { if (!app.mesh.running) app.mesh.start(); app.mesh.send(MeshProto.SOS, Sos.message("beacon", fix?.lat, fix?.lon, app.prefs.meshName.ifBlank { "Shuddh user" })) }
            delay(60_000)
        }
    }

    ScreenFrame("Guardian", "Fall detection + SOS that works with no network", onBack = { app.back() }) {
        Glass(Modifier.enter(0), glow = if (GuardianState.running) Palette.accent else sosRed, padding = 14) {
            Shield(GuardianState.running)
            Text(if (GuardianState.running) "Guardian is watching" else "Guardian is off", color = if (GuardianState.running) Palette.accent else Palette.text,
                fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("If you fall and don't respond in 30 s, Shuddh sends an SOS with your location — over Bluetooth phone-to-phone when there's no signal, and by SMS to family.",
                color = Palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛡 Fall detection (background)", color = Palette.text, modifier = Modifier.weight(1f))
                Switch(GuardianState.running, { on ->
                    if (on) {
                        val need = buildList {
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            add(Manifest.permission.SEND_SMS); add(Manifest.permission.ACCESS_FINE_LOCATION)
                        }.toTypedArray()
                        perms.launch(need)
                    } else GuardianService.stop(ctx)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("📡 Disaster beacon", color = Palette.text)
                    Text("Broadcast your location over the mesh every minute (floods, quakes)", color = Palette.muted, fontSize = 11.sp)
                }
                Switch(disaster, { disaster = it; Haptics.click(ctx) })
            }
        }

        Section("Live motion (what Guardian watches)") {
            GTrace(gTrace)
            Note("A fall = free fall (below 0.45 g) → impact (above 2.4 g) → lying still. Walking, sitting down hard, or picking up a dropped phone don't match.")
        }

        Glass(Modifier.enter(2), glow = sosRed) {
            Text("Emergency", color = sosRed, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 18.sp)
            SlideToConfirm("Slide to send SOS", Modifier.fillMaxWidth(), confirmedLabel = "SOS sent") {
                msg = sendSos(app, "manual"); Haptics.alarm(ctx)
                app.voice.speak("SOS sent.", app.lang)
            }
            Btn("🧪 Test: simulate a fall", Modifier.fillMaxWidth(), primary = false) { GuardianState.trigger("test") }
            msg?.let { Note(it, Palette.accent) }
            if (app.prefs.familyPhone.isBlank()) Note("Add a family member's number in Settings so SOS also goes by SMS.", Palette.amber)
        }

        val log = app.prefs.json("sos_log")?.optJSONArray("l")
        if (log != null && log.length() > 0) Section("SOS history") {
            for (i in (log.length() - 1) downTo maxOf(0, log.length() - 5)) {
                val o = log.getJSONObject(i)
                Text("${com.shuddh.lab.core.stamp(o.getLong("t"))} · ${o.getString("k")}", color = Palette.text, fontSize = 13.sp)
            }
        }
        HowItWorks(listOf(
            "The accelerometer is sampled ~50 times a second in a low-power background service.",
            "A fall has a signature: a fraction of a second of free fall, a hard impact, then stillness. All three must happen in order.",
            "Shuddh then asks loudly \"Are you OK?\" for 30 seconds. Tap I'm OK to cancel — nothing is sent.",
            "Otherwise it broadcasts an SOS with your GPS location over Shuddh's Bluetooth mesh, where every Shuddh phone relays it onward — no mobile network needed — and texts your family. The torch flashes SOS in Morse code and a siren sounds to guide rescuers.",
        ))
    }
}

@Composable
private fun Shield(on: Boolean) {
    val inf = rememberInfiniteTransition(label = "shield")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p")
    val col = if (on) Palette.accent else Palette.muted
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        if (on) for (k in 0 until 3) { val q = (p + k / 3f) % 1f; drawCircle(col.copy(alpha = (1 - q) * 0.35f), 40f + q * 110f, c, style = Stroke(4f)) }
        val w = 110f; val h = 130f
        val shield = Path().apply {
            moveTo(c.x, c.y - h / 2); lineTo(c.x + w / 2, c.y - h / 2 + 22f)
            quadraticTo(c.x + w / 2, c.y + h / 4, c.x, c.y + h / 2)
            quadraticTo(c.x - w / 2, c.y + h / 4, c.x - w / 2, c.y - h / 2 + 22f); close()
        }
        drawPath(shield, Brush.verticalGradient(listOf(col.copy(alpha = 0.9f), col.copy(alpha = 0.4f)), c.y - h / 2, c.y + h / 2))
        drawPath(shield, Color.White.copy(alpha = 0.6f), style = Stroke(3f))
        // Heart-line inside the shield.
        val l = Path().apply { moveTo(c.x - 34f, c.y); lineTo(c.x - 12f, c.y); lineTo(c.x - 4f, c.y - 22f); lineTo(c.x + 6f, c.y + 18f); lineTo(c.x + 14f, c.y); lineTo(c.x + 34f, c.y) }
        drawPath(l, Color.White, style = Stroke(5f, cap = StrokeCap.Round))
    }
}

@Composable
private fun GTrace(v: List<Float>) {
    val cyan = Palette.cyan
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        fun y(g: Float) = size.height - (g / 3f).coerceIn(0f, 1f) * size.height
        drawLine(Color(0xFFF43F5E).copy(alpha = 0.5f), Offset(0f, y(2.4f)), Offset(size.width, y(2.4f)), 2f)
        drawLine(Palette.tint(Color(0xFFFBBF24)).copy(alpha = 0.5f), Offset(0f, y(0.45f)), Offset(size.width, y(0.45f)), 2f)
        drawLine(Palette.ink.copy(alpha = 0.15f), Offset(0f, y(1f)), Offset(size.width, y(1f)), 1f)
        if (v.size > 1) {
            val dx = size.width / (v.size - 1)
            val p = Path(); v.forEachIndexed { i, g -> if (i == 0) p.moveTo(0f, y(g)) else p.lineTo(i * dx, y(g)) }
            drawPath(p, cyan, style = Stroke(3f, cap = StrokeCap.Round))
        }
    }
    Row { Text("— impact 2.4 g", color = Color(0xFFF43F5E), fontSize = 10.sp, modifier = Modifier.weight(1f)); Text("— free fall 0.45 g", color = Palette.tint(Color(0xFFFBBF24)), fontSize = 10.sp) }
}

/** Full-screen "Are you OK?" countdown shown over any screen when a fall is detected. */
@Composable
fun GuardianAlert(app: AppState) {
    val at = GuardianState.alertAt ?: return
    val ctx = app.ctx
    var left by remember(at) { mutableIntStateOf(30) }
    var sent by remember(at) { mutableStateOf<String?>(null) }
    val ring = remember(at) { Animatable(1f) }
    LaunchedEffect(at) {
        app.voice.speak("Did you fall? Are you OK? Tap I'm OK. Otherwise I will send an SOS.", app.lang)
        ring.animateTo(0f, tween(30_000, easing = LinearEasing))
    }
    LaunchedEffect(at) {
        while (left > 0 && sent == null) { delay(1000); left--; if (left % 5 == 0) Haptics.thud(ctx) }
        if (sent == null) {
            sent = sendSos(app, GuardianState.alertKind)
            app.voice.speak("SOS sent. Help is being called.", app.lang)
            // Siren + SOS in Morse code on the torch for a minute, to guide people to you.
            val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
            val end = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < end && GuardianState.alertAt == at) {
                tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
                for ((on, ms) in Sos.morse) { app.torch(on); delay(ms) }
            }
            app.torch(false); tone?.release()
        }
    }
    val inf = rememberInfiniteTransition(label = "alert")
    val pulse by inf.animateFloat(0.6f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.fillMaxSize().background(sosRed.copy(alpha = 0.92f * pulse)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(24.dp)) {
            Text(if (sent == null) (if (GuardianState.alertKind == "test") "Test fall" else "Did you fall?") else "SOS SENT", color = Color.White, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 38.sp)
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(200.dp)) {
                    drawArc(Palette.ink.copy(alpha = 0.25f), 0f, 360f, false, Offset(14f, 14f), Size(size.width - 28f, size.height - 28f), style = Stroke(16f))
                    drawArc(Color.White, -90f, 360f * ring.value, false, Offset(14f, 14f), Size(size.width - 28f, size.height - 28f), style = Stroke(16f, cap = StrokeCap.Round))
                }
                Text(if (sent == null) "$left" else "🆘", color = Color.White, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 64.sp)
            }
            Text(sent ?: "SOS with your location goes out automatically when the timer ends.", color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
            Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(36.dp)).background(Color.White).clickable {
                GuardianState.alertAt = null; app.torch(false); app.voice.speak("Okay. Glad you are safe.", app.lang); Haptics.click(ctx)
            }, contentAlignment = Alignment.Center) {
                Text(if (sent == null) "I'm OK" else "I'm safe now — stop", color = sosRed, fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 24.sp)
            }
            if (sent == null) Text("Send SOS now", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { left = 0 }.padding(8.dp))
        }
    }
}

/** Banner for an SOS received from a nearby phone over the mesh. */
@Composable
fun IncomingSosBanner(app: AppState) {
    val sos = app.mesh.messages.lastOrNull { !it.mine && it.packet.type == MeshProto.SOS && System.currentTimeMillis() - it.receivedAt < 15 * 60_000 } ?: return
    var dismissed by remember(sos.packet.id) { mutableStateOf(false) }
    if (dismissed) return
    LaunchedEffect(sos.packet.id) { Haptics.alarm(app.ctx); app.voice.speak("Emergency. Someone nearby needs help.", app.lang) }
    val link = Regex("https://maps\\S+").find(sos.packet.text)?.value
    Row(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(18.dp)).background(sosRed).clickable {
        link?.let { runCatching { app.ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🆘", fontSize = 28.sp); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("SOS nearby · ${sos.packet.name} · ${sos.hops} hop${if (sos.hops == 1) "" else "s"}", color = Color.White, fontWeight = FontWeight.Black)
            Text(if (link != null) "Tap to open their location" else sos.packet.text, color = Color.White, fontSize = 12.sp, maxLines = 2)
        }
        Text("✕", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable { dismissed = true }.padding(8.dp))
    }
}
