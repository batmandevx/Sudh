package com.shuddh.lab.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Fusion

/**
 * Multi-sensor verdict panel: one bar per sensor (its estimate on a 0..[full] scale, and its weight
 * in the fused answer), then the fused value and how well the sensors agree.
 */
@Composable
fun FusionBars(f: Fusion.Fused, full: Double, show: (Double, Double) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${f.parts.size} sensor${if (f.parts.size == 1) "" else "s"} · agree ${(f.agreement * 100).toInt()}%",
            color = if (f.agreement >= 0.6) Palette.accent else Palette.amber, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
        f.parts.forEachIndexed { i, p ->
            val grow by animateFloatAsState((p.value / full).toFloat().coerceIn(0f, 1f), tween(800), label = "b$i")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.sensor, color = Palette.muted, fontSize = 11.sp, modifier = Modifier.weight(1.1f))
                Box(Modifier.weight(1.4f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Palette.veil(0x22))) {
                    Box(Modifier.fillMaxWidth(grow).fillMaxHeight().background(if (p.sensor == f.outlier) Palette.amber else Palette.cyan))
                }
                Text("${show(p.value, p.sigma)} · ${(f.weights[i] * 100).toInt()}%w", color = Palette.text, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
        }
        if (f.parts.size > 1) {
            val grow by animateFloatAsState((f.value / full).toFloat().coerceIn(0f, 1f), tween(900), label = "fused")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("FUSED", color = Palette.text, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1.1f))
                Box(Modifier.weight(1.4f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Palette.veil(0x22))) {
                    Box(Modifier.fillMaxWidth(grow).fillMaxHeight().background(Palette.accent))
                }
                Text(show(f.value, f.sigma), color = Palette.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        }
        f.outlier?.let { Note("Sensors disagree — $it looks off. Re-check it, then test again.", Palette.amber) }
    }
}

/** Collapsible card for secondary content (details, settings, charts). */
@Composable
fun Fold(title: String, tint: Color, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    val rot by animateFloatAsState(if (open) 90f else 0f, tween(250), label = "rot")
    Glass(Modifier.clickable { open = !open }, padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("›", color = tint, fontSize = 22.sp, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) { content() }
        }
    }
}
