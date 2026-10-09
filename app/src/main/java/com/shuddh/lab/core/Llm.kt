package com.shuddh.lab.core

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device language models (MediaPipe .task bundles). PRO, when installed, replaces CHAT for answers. */
enum class ModelRole(val file: String, val title: String, val job: String, val temperature: Float, val topK: Int, val maxTokens: Int) {
    CHAT("shuddh-chat.task", "Qwen2.5-1.5B Instruct", "Chat · explains results in your language", 0.6f, 40, 1280),
    TOOLS("shuddh-tools.task", "Qwen2.5-0.5B Instruct", "Tool router · picks which app function to call", 0.05f, 1, 2048),
    PRO("shuddh-pro.task", "Phi-4-mini Instruct 3.8B", "Pro chat · recipes, writing, reasoning — used instead of Qwen 1.5B when installed", 0.5f, 40, 4096),
}

data class GenStats(val tokens: Int, val millis: Long, val backend: String) {
    val tokensPerSec: Double get() = if (millis <= 0) 0.0 else tokens * 1000.0 / millis
}

/**
 * Runs local LLMs with MediaPipe's LLM Inference engine. Models live in the app's private
 * external folder (Android/data/com.shuddh.lab/files/models) — copy them there with adb, or
 * import them from the file picker. Inference never touches the network.
 */
class LocalLlm(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("shuddh_llm", Context.MODE_PRIVATE)

    /** Preferred backend per model ("GPU" or "CPU"). */
    fun backendPref(role: ModelRole): String = sp.getString("backend_${role.name}", if (role == ModelRole.TOOLS) "CPU" else "GPU")!!
    fun setBackendPref(role: ModelRole, b: String) { sp.edit().putString("backend_${role.name}", b).apply(); unload(role) }

    val dir: File = File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }
    private val engines = mutableMapOf<ModelRole, LlmInference>()
    private val backends = mutableMapOf<ModelRole, String>()
    val state = mutableStateMapOf<ModelRole, String>()
    var lastStats by mutableStateOf<Map<ModelRole, GenStats>>(emptyMap()); private set
    var busy by mutableStateOf(false); private set

    fun file(role: ModelRole) = File(dir, role.file)
    fun installed(role: ModelRole) = file(role).let { it.exists() && it.length() > 50_000_000 }
    fun sizeMb(role: ModelRole) = file(role).length() / 1_048_576
    fun loaded(role: ModelRole) = engines.containsKey(role)

    /** Use Phi-4-mini for long-form answers (recipes, explanations) when installed. */
    var proEnabled: Boolean
        get() = sp.getBoolean("pro_enabled", true)
        set(v) { sp.edit().putBoolean("pro_enabled", v).apply() }

    /**
     * The model that writes an answer. Phi-4-mini (better, ~4 tok/s) handles long-form knowledge
     * tasks; Qwen 1.5B (fast) handles short replies. Falls back to whichever is installed.
     */
    fun chatRole(longForm: Boolean = true): ModelRole = when {
        installed(ModelRole.PRO) && proEnabled && (longForm || !installed(ModelRole.CHAT)) -> ModelRole.PRO
        installed(ModelRole.CHAT) -> ModelRole.CHAT
        else -> ModelRole.PRO
    }
    fun hasChat() = installed(ModelRole.PRO) || installed(ModelRole.CHAT)

    init { refresh() }

    fun refresh() = ModelRole.entries.forEach { r ->
        state[r] = when {
            loaded(r) -> "Loaded · ${backends[r]}"
            installed(r) -> "Installed · ${sizeMb(r)} MB"
            else -> "Not installed"
        }
    }

    /** Copies a model chosen in the system file picker into the models folder. */
    suspend fun import(role: ModelRole, uri: Uri) = withContext(Dispatchers.IO) {
        state[role] = "Importing…"
        unload(role)
        ctx.contentResolver.openInputStream(uri)!!.use { input -> file(role).outputStream().use { input.copyTo(it, 1 shl 20) } }
        refresh()
    }

    private suspend fun engine(role: ModelRole): LlmInference = engines[role] ?: withContext(Dispatchers.IO) {
        check(installed(role)) { "${role.title} is not installed" }
        state[role] = "Loading…"
        fun build(b: LlmInference.Backend) = LlmInference.createFromOptions(
            ctx,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file(role).absolutePath)
                .setMaxTokens(role.maxTokens)
                .setMaxTopK(40)
                .setPreferredBackend(b)
                .build(),
        )
        val first = if (backendPref(role) == "CPU") LlmInference.Backend.CPU else LlmInference.Backend.GPU
        val (e, name) = runCatching { build(first) to first.name }
            .recoverCatching { build(LlmInference.Backend.CPU) to "CPU" }
            .getOrElse { state[role] = "Failed to load: ${it.message}"; throw it }
        engines[role] = e; backends[role] = name
        state[role] = "Loaded · $name"
        e
    }

    suspend fun warmUp(role: ModelRole) { runCatching { engine(role) } }

    fun unload(role: ModelRole) {
        engines.remove(role)?.close()
        backends.remove(role)
        refresh()
    }

    /** Streams a completion. [onText] receives the full text so far on every new chunk. */
    suspend fun generate(role: ModelRole, prompt: String, onText: (String) -> Unit = {}): String {
        val e = engine(role)
        busy = true
        val started = System.currentTimeMillis()
        try {
            val session = LlmInferenceSession.createFromOptions(
                e,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(role.temperature).setTopK(role.topK).setTopP(0.9f).build(),
            )
            val out = StringBuilder()
            val text = try {
                session.addQueryChunk(if (role == ModelRole.PRO) toPhi(prompt) else prompt)
                suspendCancellableCoroutine { cont ->
                    var stopped = false
                    var kept = ""
                    val future = session.generateResponseAsync(ProgressListener<String> { partial, done ->
                        if (!stopped) {
                            if (partial != null) out.append(partial)
                            val cur = fixBytes(out.toString())
                            // Small models can fall into a repetition loop; stop and keep the text before it.
                            val cut = loopCut(cur)
                            if (cut != null) {
                                stopped = true
                                kept = cur.substring(0, cut).trimEnd()
                                onText(kept)
                                runCatching { session.cancelGenerateResponseAsync() }
                            } else if (partial != null) onText(cur)
                        }
                        // Resume only once the engine reports done, so the next call never collides.
                        if (done && cont.isActive) cont.resume(if (stopped) kept else fixBytes(out.toString()))
                    })
                    future.addListener({
                        val err = runCatching { future.get() }.exceptionOrNull()
                        if (err != null && cont.isActive) { if (stopped) cont.resume(kept) else cont.resumeWithException(err) }
                    }, Runnable::run)
                    cont.invokeOnCancellation { runCatching { session.cancelGenerateResponseAsync() } }
                }
            } finally {
                session.close()
            }
            val tokens = runCatching { e.sizeInTokens(text) }.getOrDefault(text.length / 4)
            lastStats = lastStats + (role to GenStats(tokens, System.currentTimeMillis() - started, backends[role] ?: "?"))
            return text.trim()
        } finally {
            busy = false
        }
    }

    companion object {
        /** GPT-2 byte-level BPE: the 68 control/space bytes that were remapped to U+0100..U+0143. */
        private val byteOfMapped: Map<Int, Int> by lazy {
            val printable = (33..126) + (161..172) + (174..255)
            var n = 0
            (0..255).filter { it !in printable }.associateBy { 256 + n++ }
        }

        /**
         * Repairs text where the tokenizer left raw byte-level BPE symbols undecoded (non-Latin
         * scripts like Hindi come out as "à¤" + "Ġ"). Symbols are mapped back to bytes and the
         * whole string is re-decoded as UTF-8; text that was already fine passes through unchanged.
         */
        fun fixBytes(s: String): String {
            if (s.none { it.code in 0x80..0x143 }) return s
            val out = java.io.ByteArrayOutputStream()
            for (ch in s) {
                val cp = ch.code
                when {
                    cp in 0x80..0xFF -> out.write(cp)
                    cp in 0x100..0x143 -> out.write(byteOfMapped[cp] ?: 0x3F)
                    else -> out.write(ch.toString().toByteArray(Charsets.UTF_8))
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8).replace("\uFFFD", "")
        }

        /**
         * Detects a degenerate repetition loop. Returns where to cut (start of the first repeat),
         * or null. Two signals: a substantial line occurring a third time, or the text's tail
         * repeating the same 15–200 character block three times in a row.
         */
        fun loopCut(s: String): Int? {
            // Line-level: the same non-trivial line seen 3 times → cut at its 2nd occurrence.
            val seen = HashMap<String, MutableList<Int>>()
            var pos = 0
            for (line in s.split('\n')) {
                val key = line.trim().lowercase().trimStart('-', '*', '•', ' ').replace(Regex("""^\d+[.)]\s*"""), "")
                if (key.length >= 12) {
                    val l = seen.getOrPut(key) { mutableListOf() }
                    l += pos
                    if (l.size >= 3) return l[1]
                }
                pos += line.length + 1
            }
            // Character-level: tail = X X X for some block X.
            val n = s.length
            for (p in 15..minOf(200, n / 3)) {
                val a = s.substring(n - p); val b = s.substring(n - 2 * p, n - p); val c = s.substring(n - 3 * p, n - 2 * p)
                if (a == b && b == c && a.isNotBlank()) return n - 2 * p
            }
            return null
        }

        /** Converts a ChatML prompt to Phi-4's template (<|system|>…<|end|><|user|>…<|end|><|assistant|>). */
        fun toPhi(chatml: String): String = chatml
            .replace("<|im_start|>system\n", "<|system|>").replace("<|im_start|>user\n", "<|user|>")
            .replace("<|im_start|>assistant\n", "<|assistant|>").replace("<|im_end|>\n", "<|end|>").replace("<|im_end|>", "<|end|>")

        /** Qwen2.5 chat template. */
        fun chatml(system: String, user: String) =
            "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }
}
