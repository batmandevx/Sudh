package com.shuddh.lab.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Motion
import com.shuddh.lab.core.fmt

@Composable
fun rememberMotion(): Motion {
    val ctx = LocalContext.current
    val m = remember { Motion(ctx) }
    DisposableEffect(Unit) { m.start(); onDispose { m.stop() } }
    return m
}

/** Fires [onWave] each time a hand is waved over the phone (proximity sensor), skipping the first composition. */
@Composable
fun OnWave(m: Motion, onWave: () -> Unit) {
    val cb by rememberUpdatedState(onWave)
    val start = remember { m.waves }
    LaunchedEffect(m.waves) { if (m.waves != start) cb() }
}

/** Live bubble level + steadiness + ambient light — the accuracy guard on every optical instrument. */
@Composable
fun SteadyBar(m: Motion, waveHint: String? = null) {
    val steady = m.shake < 0.12f
    val col by animateColorAsState(if (steady) Palette.accent else Palette.amber, label = "steady")
    val bx by animateFloatAsState((m.tiltX / 20f).coerceIn(-1f, 1f), spring(stiffness = 300f), label = "bx")
    val by by animateFloatAsState((m.tiltY / 20f).coerceIn(-1f, 1f), spring(stiffness = 300f), label = "by")
    Glass(padding = 12, glow = col) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(54.dp)) {
                val r = size.minDimension / 2
                drawCircle(Color.White.copy(alpha = 0.06f), r)
                drawCircle(Color.White.copy(alpha = 0.25f), r * 0.32f, style = Stroke(2f))
                val p = Offset(center.x - bx * r * 0.7f, center.y + by * r * 0.7f)
                drawCircle(Brush.radialGradient(listOf(col, col.copy(alpha = 0.3f)), p, r * 0.25f), r * 0.24f, p)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (steady) "STEADY — frames accepted" else "Hold still — frames paused", color = col, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "tilt ${fmt(m.tiltX.toDouble())}° / ${fmt(m.tiltY.toDouble())}° · motion ${fmt(m.shake.toDouble())} m/s²" +
                        if (m.lux >= 0) " · ${fmt(m.lux.toDouble())} lux" else "",
                    color = Palette.muted, fontSize = 11.sp,
                )
                if (m.lux > 400) Text("Bright room — stray light may leak into the box", color = Palette.amber, fontSize = 11.sp)
                if (waveHint != null && m.hasProximity) Text("👋 Wave over the top of the phone to $waveHint — no touch needed", color = Palette.cyan, fontSize = 11.sp)
            }
        }
    }
}

/** Fill bar showing which share of frames passed the steadiness gate. */
@Composable
fun GateMeter(accepted: Int, rejected: Int) {
    val total = (accepted + rejected).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Frames kept ${accepted}/${accepted + rejected}", color = Palette.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Canvas(Modifier.width(120.dp).size(120.dp, 8.dp)) {
            drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f))
            drawRoundRect(Palette.accent, size = androidx.compose.ui.geometry.Size(size.width * accepted / total, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f))
        }
    }
}
