package com.shuddh.lab.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shuddh.lab.core.Agent
import androidx.compose.foundation.horizontalScroll
import com.shuddh.lab.core.Assistant
import com.shuddh.lab.core.Lang
import com.shuddh.lab.core.ModelRole
import com.shuddh.lab.core.Vision
import com.shuddh.lab.core.WebSearch
import com.shuddh.lab.core.fmt
import kotlinx.coroutines.launch

/**
 * Ask Shuddh — the on-device AI agent.
 * Voice (on-device speech model) → Qwen 0.5B router (JSON tool call) → Kotlin tool on phone data
 * → Qwen 1.5B answer, streamed and spoken. Photos are read by ML Kit and passed in as facts.
 */
@Composable
fun AssistantScreen(app: AppState) {
    val ctx = app.ctx
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var partial by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var searchNext by remember { mutableStateOf(false) }
    val turns = app.chat
    val llm = app.llm

    val onDevice = remember { Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }
    val available = remember { onDevice || SpeechRecognizer.isRecognitionAvailable(ctx) }

    fun ask(q: String) {
        if (q.isBlank() || running) return
        app.prefs.bump("ai_questions")
        val image = app.pendingImage
        val webRequested = searchNext || WebSearch.requested(q)
        searchNext = false
        val context = turns.takeLast(4).joinToString("\n") { turn ->
            (if (turn.user) "User: " else "Shuddh: ") + turn.text.replace("\n", " ").take(260)
        }
        turns.add(ChatTurn(true, q, app.pendingBitmap))
        val a = ChatTurn(false, "")
        turns.add(a)
        input = ""
        running = true
        scope.launch {
            try {
                // Network requests are a separate read-only branch: no history, image facts or
                // model-generated arguments can enter the outgoing query or execute actions.
                if (webRequested) {
                    a.tool = com.shuddh.lab.core.ToolCall("web_search", emptyMap(), "", false)
                    a.webQuery = WebSearch.query(q)
                    if (!app.prefs.onlineAssistant) {
                        a.text = "Web search is off. Turn on Web access below, then send your search again."
                    } else {
                        a.phase = "web"
                        try {
                            val results = WebSearch.search(q, app.prefs.onlineAssistant)
                            a.webResults = results
                            a.text = if (results.sources.isEmpty()) "No results came back for that search. Try a more specific query or open Bing below."
                                else "Here’s what I found online. Open a source to read the full page."
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            a.text = "I couldn’t reach web search. Check your connection and try again, or open Bing below."
                        }
                    }
                    a.phase = "done"
                    return@launch
                }
                val hasImg = image != null
                // Agentic planning: a compound request becomes ordered sub-tasks run one by one.
                val parts = if (hasImg) listOf(q) else com.shuddh.lab.core.Planner.split(q)
                if (parts.size > 1) a.steps = parts.map { it to false }
                val outputs = mutableListOf<String>()
                var answer = ""
                var routeMs = 0L
                val t1 = System.currentTimeMillis()
                for ((idx, sub) in parts.withIndex()) {
                    // 1. Route
                    a.phase = "routing"
                    val t0 = System.currentTimeMillis()
                    val call = if (Agent.isSmallTalk(sub)) {
                        a.engine = "small-talk shortcut"; com.shuddh.lab.core.ToolCall("none", emptyMap(), "", false)
                    } else if (llm.installed(ModelRole.TOOLS)) {
                        a.engine = "Qwen2.5-0.5B router"
                        val raw = runCatching { llm.generate(ModelRole.TOOLS, Agent.routerPrompt(sub, hasImg, context)) }.getOrDefault("")
                        // Deterministic tools (maths, units, recipes…) win over a shaky small-model pick.
                        val rule = Agent.ruleRoute(sub, hasImg)
                        if (rule.name in setOf("calculate", "convert_units", "recipe", "shopping_add", "shopping_show", "save_note", "show_notes", "call", "add_event", "open_vision", "open_instrument", "pantry_brief", "vendor_history", "set_timer", "set_alarm", "flashlight", "whistle_counter", "set_language", "phone_status", "open_app")) rule
                        else Agent.parse(raw, sub, hasImg)
                    } else {
                        a.engine = "rule router"; Agent.ruleRoute(sub, hasImg)
                    }
                    routeMs += System.currentTimeMillis() - t0
                    a.tool = call
                    // 2. Tool
                    a.phase = "tool"
                    a.facts = Agent.execute(call, app, app.lang, image, sub)
                    if (call.name == "safety_brief") a.actions = app.safetyPlanActions()
                    if (call.name == "generate_qr") {
                        a.attachment = runCatching { com.shuddh.lab.core.Qr.encode(call.args["text"].orEmpty().ifBlank { sub }.take(300), 600) }.getOrNull()
                    }
                    // 3. Answer
                    a.phase = "chat"
                    val prefix = if (parts.size > 1) outputs.joinToString("\n\n") + (if (outputs.isEmpty()) "" else "\n\n") + "${idx + 1}. " else ""
                    val one = if (call.name in Agent.exactAnswer) {
                        // Exact local facts (time, battery, maths, lists): never let a model paraphrase them.
                        a.facts
                    } else if (llm.hasChat()) {
                        val role = llm.chatRole(longForm = call.name in setOf("recipe", "general"))
                        a.engine += " → ${role.title}"
                        runCatching { llm.generate(role, Agent.chatPrompt(sub, call, a.facts, app.lang, context)) { a.text = prefix + it } }
                            .getOrElse { "Model error: ${it.message}. Facts: ${a.facts}" }
                    } else if (call.name in Agent.openKnowledge && call.name != "none") {
                        "I need the on-device chat model for that. Open AI Models and download the chat model — then I can write recipes and answer anything, fully offline."
                    } else {
                        a.engine += " → template"
                        if (call.name == "none") Assistant.ask(sub, app.store, app.community, app.lang).text else a.facts
                    }
                    if (call.name == "recipe") a.actions = recipeTimers(app, one)
                    outputs += if (parts.size > 1) "${idx + 1}. $one" else one
                    if (parts.size > 1) a.steps = a.steps.mapIndexed { i, st -> if (i == idx) st.first to true else st }
                    answer = outputs.joinToString("\n\n")
                    a.text = answer
                }
                // Hindi: the small LLM answers in English; add the deterministic Hindi line and speak that.
                val hindi = if (app.lang == Lang.HI && parts.size == 1) Assistant.ask(q, app.store, app.community, Lang.HI).takeIf { it.grounded && it.text.any { c -> c in '\u0900'..'\u097F' } }?.text else null
                a.text = if (hindi != null && llm.hasChat()) "$answer\n\n🇮🇳 $hindi" else answer
                val chatStats = llm.lastStats.maxByOrNull { (r, _) -> if (r == ModelRole.TOOLS) 0 else 1 }?.value
                a.stats = "route ${routeMs} ms · answer ${System.currentTimeMillis() - t1} ms" +
                    (chatStats?.let { " · ${fmt(it.tokensPerSec)} tok/s on ${it.backend}" } ?: "")
                a.phase = "done"
                app.voice.speak(cleanModelText(answer).replace(Regex("[*#_`]"), "").take(600), if (answer.any { it in 'ऀ'..'ॿ' }) Lang.HI else if (answer.any { it in 'ಀ'..'೿' }) Lang.KN else if (answer.any { it in 'ఀ'..'౿' }) Lang.TE else if (answer.any { it in '஀'..'௿' }) Lang.TA else Lang.EN)
            } catch (e: kotlinx.coroutines.CancellationException) {
                a.text = "Request stopped. Send your question again to continue."
                a.phase = "done"
                throw e
            } catch (e: Exception) {
                // A local model or parser failure must stay inside the chat, never take down the app.
                a.text = "I couldn't complete that on this phone. Please try again."
                a.stats = "Assistant error: ${e.javaClass.simpleName}"
                a.phase = "done"
            } finally {
                running = false
                app.pendingImage = null; app.pendingBitmap = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val b = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            else @Suppress("DEPRECATION") android.provider.MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)
            val bmp = b.copy(Bitmap.Config.ARGB_8888, false)
            app.pendingBitmap = bmp
            app.pendingImage = Vision.analyse(bmp)
        }
    }

    // Speech engine stage: 0 = on-device recogniser, 1 = standard recogniser, 2 = system voice popup.
    // Some phones (e.g. iQOO / OriginOS) advertise on-device recognition but fail with ERROR_CLIENT (5);
    // on such errors we move to the next stage, retry immediately, and remember what works.
    val firstStage = if (onDevice && Build.VERSION.SDK_INT >= 33) 0 else if (available) 1 else 2
    var stage by remember { androidx.compose.runtime.mutableIntStateOf(app.prefs.json("speech_stage")?.optInt("s", firstStage)?.coerceAtLeast(firstStage) ?: firstStage) }
    var pendingRetry by remember { mutableStateOf(false) }
    val recognizer = remember(stage) {
        runCatching {
            when (stage) {
                0 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                1 -> SpeechRecognizer.createSpeechRecognizer(ctx)
                else -> null
            }
        }.getOrNull()
    }
    fun langTag() = when (app.lang) { Lang.HI -> "hi-IN"; Lang.KN -> "kn-IN"; Lang.TE -> "te-IN"; Lang.TA -> "ta-IN"; Lang.EN -> "en-IN" }
    fun speechIntent(offline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag())
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Shuddh…")
    val voicePopup = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { ask(it) }
    }
    fun advance() {
        if (stage < 2) {
            stage += 1; pendingRetry = true
            app.prefs.putJson("speech_stage", org.json.JSONObject().put("s", stage))
        }
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; partial = "" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false; level = 0f }
            override fun onError(error: Int) {
                listening = false; level = 0f
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ctx.toastLong("Didn't catch that — tap the mic and speak again")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ctx.toastLong("Microphone permission is needed")
                    // Client / service / language errors: fall back to the next speech engine and retry.
                    else -> advance()
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false; level = 0f
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { ask(it) }
            }
            override fun onPartialResults(p: Bundle?) { p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it } }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { recognizer?.destroy() }
    }

    fun mic() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ctx.toastLong("Microphone permission is needed"); return }
        if (stage >= 2 || recognizer == null) {
            runCatching { voicePopup.launch(speechIntent(offline = false)) }.onFailure { ctx.toastLong("No speech service on this phone — install Google voice typing, or type instead") }
            return
        }
        if (listening) { recognizer.stopListening(); return }
        runCatching { recognizer.startListening(speechIntent(offline = stage == 0)) }.onFailure { advance() }
    }
    androidx.compose.runtime.LaunchedEffect(stage) { if (pendingRetry) { pendingRetry = false; kotlinx.coroutines.delay(200); mic() } }

    // Pre-load both models in the background so the first answer isn't slowed by loading.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (llm.installed(ModelRole.TOOLS)) llm.warmUp(ModelRole.TOOLS)
        if (llm.hasChat()) llm.warmUp(llm.chatRole(longForm = false))
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val ordered = turns.toList()
    androidx.compose.runtime.LaunchedEffect(ordered.size, ordered.lastOrNull()?.text?.length) {
        if (ordered.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Aurora(intensity = 0.5f)
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = { app.back() }, modifier = Modifier.clip(CircleShape).background(Palette.glass)) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.text)
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ask Shuddh", fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Palette.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.clickable { app.go(Screen.MODELS) }) {
                        MiniChip(when (app.llm.chatRole()) { ModelRole.MINICPM -> "MINICPM"; ModelRole.PRO -> "PRO"; else -> "CHAT" }, app.llm.hasChat())
                        MiniChip("TOOLS", app.llm.installed(ModelRole.TOOLS))
                        MiniChip(if (onDevice) "SPEECH" else "TYPE", onDevice)
                        MiniChip(if (app.prefs.onlineAssistant) "WEB ON" else "LOCAL", true)
                    }
                }
            }

            // Messages (newest at the bottom)
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ordered.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MicOrb(listening, level) { mic() }
                            Text(if (listening) partial.ifBlank { "Listening…" } else "Ask about your food, a vendor, or a photo", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("AI runs on your phone. Enable Web access to search online with source links.", color = Palette.muted, fontSize = 12.sp)
                            Pipeline("idle")
                        }
                    }
                } else {
                    items(ordered.reversed()) { t -> TurnBubble(app, t, Modifier) }
                }
            }

            // Attached photo
            app.pendingBitmap?.let { b ->
                Row(Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.glass).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(b.asImageBitmap(), "attached", Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.size(10.dp))
                    Text(app.pendingImage?.let { "Photo · ${it.labels.take(2).joinToString { l -> l.first }} · ${if (it.lens.fssai != null) "FSSAI found" else "no FSSAI"}" } ?: "Reading photo…",
                        color = Palette.text, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("✕", color = Palette.muted, modifier = Modifier.clickable { app.pendingBitmap = null; app.pendingImage = null }.padding(8.dp))
                }
            }

            // Suggestions
            if (input.isBlank() && !running) {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val sugg = listOf("✨ What should I check today?", "🕒 What time is it?", "📱 Phone status", "⏱ Timer 10 min to boil water", "⏰ Remind me to buy milk at 7 pm", "How is my kitchen?", "🔦 Turn on the torch",
                        "What failed the most?", "Count 3 cooker whistles", "▦ QR for my vendor list", "Teach me something new", "Why is nitrate dangerous?", "Download my report")
                    items(sugg) { sg ->
                        Text(sg, color = Palette.text, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.glass)
                            .border(1.dp, Palette.line, RoundedCornerShape(50)).clickable { ask(sg) }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Web access", color = Palette.text, fontSize = 12.sp)
                    Text("Only search text is sent to Bing", color = Palette.muted, fontSize = 10.sp)
                }
                androidx.compose.material3.Switch(
                    checked = app.prefs.onlineAssistant,
                    onCheckedChange = { app.prefs.updateWebAccess(it); if (!it) searchNext = false },
                    enabled = !running,
                )
                if (app.prefs.onlineAssistant) androidx.compose.material3.FilterChip(
                    selected = searchNext,
                    onClick = { searchNext = !searchNext },
                    enabled = !running,
                    label = { Text(if (searchNext) "Web selected" else "Search web", fontSize = 11.sp) },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // Composer
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.verticalGradient(listOf(Palette.veil(0x26), Palette.veil(0x14)))).border(1.dp, Palette.line, RoundedCornerShape(28.dp))
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon("📷") { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                androidx.compose.foundation.text.BasicTextField(
                    input, { input = it }, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 16.sp, fontFamily = Body),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.cyan), maxLines = 4,
                    decorationBox = { inner -> Box { if (input.isEmpty()) Text(if (listening) partial.ifBlank { "Listening…" } else if (searchNext) "Search the web…" else "Message Shuddh…", color = Palette.muted, fontSize = 16.sp); inner() } },
                )
                RoundIcon(if (listening) "■" else "🎙", active = listening, level = level) { mic() }
                Spacer(Modifier.size(6.dp))
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(if (input.isNotBlank() && !running) Brush.linearGradient(listOf(Palette.accent, Palette.cyan)) else Brush.linearGradient(listOf(Palette.line, Palette.line)))
                        .clickable(enabled = input.isNotBlank() && !running) { ask(input) },
                    contentAlignment = Alignment.Center,
                ) { Text(if (running) "…" else "↑", color = Palette.onAccent, fontSize = 20.sp, fontWeight = FontWeight.Black) }
            }
        }
    }
}

@Composable
private fun MiniChip(text: String, ok: Boolean) {
    Text(
        (if (ok) "● " else "○ ") + text, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
        color = if (ok) Palette.accent else Palette.amber,
        modifier = Modifier.clip(RoundedCornerShape(50)).background((if (ok) Palette.accent else Palette.amber).copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun RoundIcon(glyph: String, active: Boolean = false, level: Float = 0f, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (active) 1f + level * 0.35f else 1f, tween(120), label = "ri")
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (active) Palette.violet.copy(alpha = 0.5f) else Palette.glass).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (active) Canvas(Modifier.size(44.dp)) { drawCircle(Palette.violet, size.minDimension / 2 * ring * 0.9f, style = Stroke(3f)) }
        Text(glyph, fontSize = 18.sp, color = Color.White)
    }
}

@Composable
private fun ModelPill(tag: String, role: ModelRole, app: AppState, modifier: Modifier) {
    val ok = app.llm.installed(role)
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(if (ok) Palette.accent.copy(alpha = 0.12f) else Palette.amber.copy(alpha = 0.12f)).padding(10.dp)) {
        Text(tag, color = if (ok) Palette.accent else Palette.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(role.title, color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(app.llm.state[role] ?: "", color = Palette.muted, fontSize = 10.sp, maxLines = 1)
    }
}

/** You → Router → Tool → Chat, lighting up as the agent works. */
@Composable
private fun Pipeline(phase: String) {
    val stages = listOf("You", "Router", "Tool", "Answer")
    val idx = when (phase) { "routing" -> 1; "tool" -> 2; "chat" -> 3; "done" -> 4; else -> 0 }
    val t = rememberInfiniteTransition(label = "pipe")
    val flow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "flow")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        stages.forEachIndexed { i, s ->
            val active = i == idx; val done = i < idx || phase == "done"
            val c = when { active -> Palette.amber; done -> Palette.accent; else -> Palette.line }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(c.copy(alpha = if (active) 0.9f else 0.35f)).border(2.dp, c, CircleShape).pulse(active), contentAlignment = Alignment.Center) {
                    Text(if (done && !active) "✓" else "${i + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(s, color = if (active || done) Palette.text else Palette.muted, fontSize = 10.sp)
            }
            if (i < stages.lastIndex) {
                Canvas(Modifier.weight(0.6f).height(4.dp)) {
                    drawLine(Palette.ink.copy(alpha = 0.1f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4f)
                    if (i + 1 == idx) drawCircle(Palette.amber, 5f, Offset(size.width * flow, size.height / 2))
                    if (i + 1 < idx) drawLine(Palette.accent, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4f)
                }
            }
        }
    }
}

@Composable
private fun TurnBubble(app: AppState, t: ChatTurn, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (t.user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(22.dp))
                .background(
                    if (t.user) Brush.horizontalGradient(listOf(Color(0xFF3B2A7A), Color(0xFF1F4E7A)))
                    else Brush.verticalGradient(listOf(Palette.veil(0x24), Palette.veil(0x10))),
                ).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            t.image?.let { Image(it.asImageBitmap(), "photo", Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop) }
            t.attachment?.let { Image(it.asImageBitmap(), "QR code", Modifier.size(200.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(6.dp)) }
            if (!t.user && t.text.isBlank() && t.phase != "done") {
                ThinkingDots(when (t.phase) { "web" -> "Searching the web…"; "routing" -> "Thinking…"; "tool" -> "Checking…"; else -> "Writing the answer…" })
            } else {
                if (t.user) Text(t.text, color = Palette.text, fontSize = 15.sp, lineHeight = 21.sp) else MarkdownText(t.text)
            }
            if (!t.user) {
                t.webResults?.let { results ->
                    Text("Bing results · fetched ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(results.fetchedAt))}", color = Palette.cyan, fontSize = 11.sp)
                    results.sources.forEach { source ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.glass)
                                .clickable { openWebSource(app, source.url) }.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(source.title, color = Palette.cyan, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(java.net.URI(source.url).host, color = Palette.muted, fontSize = 11.sp)
                            if (source.snippet.isNotBlank()) Text(source.snippet, color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                    if (results.sources.isNotEmpty()) Text("Search excerpts; page dates and details may differ.", color = Palette.muted, fontSize = 10.sp)
                }
                if (t.phase == "done" && app.prefs.onlineAssistant) t.webQuery?.let { query ->
                    androidx.compose.material3.TextButton(onClick = { openWebSource(app, WebSearch.browserUrl(query)) }) {
                        Text("Open full search ↗", color = Palette.cyan)
                    }
                }
                // Keep normal chat human-readable. Routing/model telemetry is intentionally not
                // exposed here; it is useful for debugging, not for someone asking a question.
                if (t.tool?.name == "phone_status") {
                    Text("Updated just now · on this phone", color = Palette.cyan, fontSize = 11.sp)
                }
                if (t.steps.isNotEmpty()) {
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.cyan.copy(alpha = 0.08f)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("🧭 Agent plan · ${t.steps.count { it.second }}/${t.steps.size} done", color = Palette.cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        t.steps.forEachIndexed { i, (st, done) ->
                            Text("${if (done) "✅" else "⏳"} ${i + 1}. $st", color = if (done) Palette.text else Palette.muted, fontSize = 12.sp)
                        }
                    }
                }
                if (t.actions.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        t.actions.take(5).forEach { action ->
                            Text(
                                action.label, color = Palette.onAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(Palette.accent, Palette.cyan)))
                                    .clickable { action.run?.invoke() ?: action.screen?.let { sc -> if (sc in tabs) app.tab(sc) else app.go(sc) } }
                                    .padding(horizontal = 11.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openWebSource(app: AppState, url: String) {
    if (!WebSearch.safeUrl(url)) return
    runCatching {
        app.ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { app.ctx.toastLong("No browser available to open this source") }
}

@Composable
private fun ThinkingDots(label: String) {
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "p")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a = (1f - kotlin.math.abs(p - i - 0.5f)).coerceIn(0.25f, 1f)
            Box(Modifier.padding(end = 4.dp).size(8.dp).clip(CircleShape).background(Palette.violet.copy(alpha = a)))
        }
        Spacer(Modifier.size(8.dp))
        Text(label, color = Palette.muted, fontSize = 12.sp)
    }
}

/** Glowing microphone orb whose halo follows the live voice level. */
@Composable
private fun MicOrb(active: Boolean, level: Float, onTap: () -> Unit) {
    val l by animateFloatAsState(if (active) 0.35f + level * 0.65f else 0.15f, tween(120), label = "lvl")
    val t = rememberInfiniteTransition(label = "orb")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "rot")
    val breathe by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "b")
    Box(Modifier.size(170.dp).clip(CircleShape).clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(170.dp)) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Color(0xFF8B5CF6).copy(alpha = 0.55f * l + 0.1f), Color.Transparent)), r)
            rotate(rot) {
                drawCircle(Brush.sweepGradient(SpectrumColors + SpectrumColors.first()), r * (0.55f + 0.25f * l), style = Stroke(6f))
            }
            drawCircle(Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF22D3EE))), r * 0.42f * breathe)
        }
        Text(if (active) "●" else "🎙", fontSize = 34.sp, color = Color.White)
    }
}


/** One-tap cooking timers for every "N minutes" step in a recipe. */
private fun recipeTimers(app: AppState, recipe: String): List<AssistantAction> =
    Regex("""(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?\s*(?:minutes|minute|mins|min)\b""", RegexOption.IGNORE_CASE).findAll(recipe)
        .map { (it.groupValues[2].ifBlank { it.groupValues[1] }).toInt() }.filter { it in 1..180 }.distinct().take(4)
        .map { m -> AssistantAction("⏱ $m min timer", run = { app.setTimer(m * 60, "Shuddh recipe step") }) }.toList()
