package com.shuddh.lab.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Insights and the scan history in one tab, switched by an animated segmented control. */
@Composable
fun InsightsHub(app: AppState, history: Boolean) {
    var showHistory by rememberSaveable(history) { mutableStateOf(history) }
    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).clip(RoundedCornerShape(50)).background(Palette.veil(0x10)).padding(4.dp)) {
            listOf(false to "📊  Insights", true to "🧾  History").forEach { (h, label) ->
                val on = showHistory == h
                val bg by androidx.compose.animation.animateColorAsState(if (on) Palette.accent else androidx.compose.ui.graphics.Color.Transparent, tween(250), label = "seg")
                Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(bg).clickable { showHistory = h }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (on) Palette.onAccent else Palette.text, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
                }
            }
        }
        AnimatedContent(showHistory, Modifier.weight(1f), transitionSpec = { (fadeIn(tween(250)) + slideInHorizontally(tween(300)) { if (targetState) it / 5 else -it / 5 }) togetherWith fadeOut(tween(150)) }, label = "ih") { h ->
            if (h) HistoryScreen(app) else InsightsScreen(app)
        }
    }
}
