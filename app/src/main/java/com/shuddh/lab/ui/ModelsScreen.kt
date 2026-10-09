package com.shuddh.lab.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.LocalLlm
import com.shuddh.lab.core.ModelRole
import com.shuddh.lab.core.fmt
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(app: AppState) {
    val llm = app.llm
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf<ModelRole?>(null) }
    var bench by remember { mutableStateOf<Map<ModelRole, String>>(emptyMap()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val role = importing ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch { runCatching { llm.import(role, uri) }.onFailure { app.ctx.toastLong("Import failed: ${it.message}") } }
        importing = null
    }

    ScreenFrame("On-device AI models", "Run locally with MediaPipe LLM Inference", onBack = { app.back() }) {
        ModelRole.entries.forEachIndexed { i, role ->
            Glass(Modifier.enter(i), glow = if (llm.installed(role)) Palette.accent else Palette.amber) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InstrumentGlyph(if (role == ModelRole.TOOLS) Glyph.POLAR else Glyph.ECHO, when (role) { ModelRole.CHAT -> Palette.violet; ModelRole.PRO -> Palette.amber; else -> Palette.cyan }, Modifier.size(44.dp))
                    Spacer(Modifier.size(12.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(role.title, color = Palette.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(role.job, color = Palette.muted, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    Badge(llm.state[role] ?: "", if (llm.loaded(role)) Palette.accent else if (llm.installed(role)) Palette.cyan else Palette.amber)
                    llm.lastStats[role]?.let { Badge("${fmt(it.tokensPerSec)} tok/s · ${it.backend}", Palette.violet) }
                }
                if (role == ModelRole.PRO && llm.installed(role)) {
                    var pro by remember { mutableStateOf(llm.proEnabled) }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Use for recipes & long answers", color = Palette.text, fontSize = 13.sp)
                            Text("Better quality, slower (~4 tok/s). Short replies stay on fast Qwen.", color = Palette.muted, fontSize = 11.sp)
                        }
                        androidx.compose.material3.Switch(pro, { pro = it; llm.proEnabled = it })
                    }
                }
                var be by remember(role) { mutableStateOf(llm.backendPref(role)) }
                Chips(listOf("GPU", "CPU"), be, { "Backend: $it" }) { be = it; llm.setBackendPref(role, it) }
                bench[role]?.let { Note(it, Palette.text) }
                BtnRow {
                    if (llm.installed(role)) {
                        if (!llm.loaded(role)) Btn("Load") { scope.launch { llm.warmUp(role); llm.refresh() } }
                        else Btn("Unload", primary = false) { llm.unload(role) }
                        Btn("Benchmark", primary = false, enabled = !llm.busy) {
                            scope.launch {
                                bench = bench + (role to "Running…")
                                val out = runCatching {
                                    llm.generate(role, LocalLlm.chatml("You are concise.", "In one sentence, why should milk be tested for adulteration?"))
                                }.getOrElse { "Error: ${it.message}" }
                                val st = llm.lastStats[role]
                                bench = bench + (role to "\"${out.take(160)}\"" + (st?.let { "\n${it.tokens} tokens in ${it.millis} ms = ${fmt(it.tokensPerSec)} tok/s (${it.backend})" } ?: ""))
                            }
                        }
                    }
                    Btn("Import file…", primary = false) { importing = role; picker.launch(arrayOf("*/*")) }
                }
            }
        }
        Section("Install by USB (fastest)") {
            Note("Copy the .task files into the app's private model folder:", Palette.text)
            Mono("adb push qwen15.task /sdcard/Android/data/com.shuddh.lab/files/models/${ModelRole.CHAT.file}\nadb push qwen05.task /sdcard/Android/data/com.shuddh.lab/files/models/${ModelRole.TOOLS.file}\nadb push phi4.task /sdcard/Android/data/com.shuddh.lab/files/models/${ModelRole.PRO.file}", Palette.cyan)
            Note("Models: litert-community/Qwen2.5-1.5B-Instruct and Qwen2.5-0.5B-Instruct (q8, ekv1280) from Hugging Face, Apache-2.0. Shuddh has no internet permission, so models are never downloaded by the app itself.")
            Btn("Refresh", primary = false) { llm.refresh() }
        }
        HowItWorks(listOf(
            "Two small language models run entirely on this phone's GPU (CPU fallback) through Google's MediaPipe LLM Inference engine.",
            "The 0.5B 'router' reads your question and writes a JSON function call — e.g. vendor_history(vendor=\"Ramesh Dairy\").",
            "Shuddh runs that function on your own scan log, then the 1.5B 'chat' model turns the result into a short answer in your language.",
            "The chat model is told to use only those facts, so answers stay grounded in your data.",
            "Photos are read by Google ML Kit's on-device text recogniser and image labeller, and those facts are passed to the chat model.",
        ))
    }
}
