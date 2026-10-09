package com.shuddh.lab.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.ConsumerIrManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shuddh.lab.core.CommunityStore
import com.shuddh.lab.core.Lang
import com.shuddh.lab.core.Outcome
import com.shuddh.lab.core.Prefs
import com.shuddh.lab.core.Store
import com.shuddh.lab.core.Voice
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Screen { HOME, SPECTRUM, POLAR, NIR, ECHO, NAMI, MAGNETO, MESH, ASSISTANT, WHISTLE, LENS, MODELS, MEDIA, VIDEO, MAP, BADGES, FOODGUIDE, PANTRY, PULSE, VISION, BOIL, OIL, GRAIN, OUTBREAK, ANAEMIA, MOSQUITO, GUARDIAN, EXPOSURE, MILKMAN, STRIP, SCATTER, FLOAT, RESULT, HISTORY, INSIGHTS, COMMUNITY, SETTINGS, GUIDE, ONBOARDING }

/** Top-level destinations shown in the bottom bar. */
val tabs = listOf(Screen.HOME, Screen.HISTORY, Screen.INSIGHTS, Screen.COMMUNITY, Screen.SETTINGS)

/** One chat bubble. Assistant turns fill in live as the agent pipeline runs. */
class ChatTurn(val user: Boolean, text: String, val image: android.graphics.Bitmap? = null) {
    var text by mutableStateOf(text)
    /** routing → tool → chat → done */
    var phase by mutableStateOf(if (user) "done" else "routing")
    var tool by mutableStateOf<com.shuddh.lab.core.ToolCall?>(null)
    var facts by mutableStateOf("")
    var engine by mutableStateOf("")
    var stats by mutableStateOf("")
    /** Image produced by a tool (e.g. a generated QR code). */
    var attachment by mutableStateOf<android.graphics.Bitmap?>(null)
    /** Clear follow-through actions for a grounded assistant plan. */
    var actions by mutableStateOf<List<AssistantAction>>(emptyList())
    var webResults by mutableStateOf<com.shuddh.lab.core.WebResults?>(null)
    var webQuery by mutableStateOf<String?>(null)
    /** Multi-step plan: each sub-task and whether it has run. */
    var steps by mutableStateOf<List<Pair<String, Boolean>>>(emptyList())
}

data class AssistantAction(val label: String, val screen: Screen? = null, val run: (() -> Unit)? = null)

class AppState(val ctx: Context) : com.shuddh.lab.core.AgentHost {
    override val store = Store(ctx)
    override val community = CommunityStore(ctx)
    val llm = com.shuddh.lab.core.LocalLlm(ctx)
    val media = com.shuddh.lab.core.MediaIndex(ctx)
    val geo = com.shuddh.lab.core.Geo(ctx)
    val pantry = com.shuddh.lab.core.PantryStore(ctx)
    /** Prefill handed to Pantry from Label Lens. */
    var pendingPantryName by mutableStateOf<String?>(null)
    var pendingPantryExpiry by mutableStateOf<Long?>(null)
    /** Query handed to Media Search when the agent opens it. */
    var mediaQuery by mutableStateOf<String?>(null)
    var pendingImage by mutableStateOf<com.shuddh.lab.core.ImageFacts?>(null)
    var pendingBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    val prefs = Prefs(ctx)
    val voice = Voice(ctx)
    val chat = mutableStateListOf<ChatTurn>()

    /** BLE mesh lives at app scope so it keeps relaying while you move between screens. */
    val mesh = com.shuddh.lab.core.Mesh(ctx, { prefs.meshName }, prefs.meshRoom, { prefs.meshRoom = it }) { p ->
        val f = p.text.split("|")
        val item = when (p.type) {
            com.shuddh.lab.core.MeshProto.ALERT -> com.shuddh.lab.core.CommunityItem(
                "alert", f.getOrElse(0) { "" }, f.getOrElse(1) { "" }, f.getOrElse(2) { "" },
                runCatching { com.shuddh.lab.core.Level.valueOf(f.getOrElse(3) { "" }) }.getOrDefault(com.shuddh.lab.core.Level.INCONCLUSIVE),
                f.getOrElse(4) { "" }, p.time, 1, if (f.getOrElse(3) { "" } == "UNSAFE") 1 else 0, "mesh:${p.name}",
            )
            else -> {
                val fails = f.getOrElse(2) { "0" }.toIntOrNull() ?: 0; val total = f.getOrElse(3) { "0" }.toIntOrNull() ?: 0
                com.shuddh.lab.core.CommunityItem(
                    "seal", f.getOrElse(0) { "" }, f.getOrElse(1) { "" }, "Vendor record",
                    if (total == 0) com.shuddh.lab.core.Level.INCONCLUSIVE else if (fails == 0) com.shuddh.lab.core.Level.SAFE else com.shuddh.lab.core.Level.CAUTION,
                    "", p.time, total, fails, "beacon:${p.name}",
                )
            }
        }
        community.add(item)
    }

    val hasIr: Boolean = (ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true

    /**
     * Read-only facts exposed to Ask Shuddh. This deliberately excludes contacts, messages,
     * calendar and notification contents: the assistant gets useful device context without
     * quietly broadening its access to personal data.
     */
    override fun deviceContext(question: String): String {
        val now = ZonedDateTime.now()
        val dateTime = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH))
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val chargeState = when (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            else -> "not charging"
        }
        val storage = StatFs(ctx.filesDir.absolutePath)
        val free = storage.availableBytes
        val total = storage.totalBytes
        val batteryText = "${percent?.let { "$it%" } ?: "unavailable"} · $chargeState"
        val storageText = "${bytes(free)} free of ${bytes(total)}"
        val q = question.lowercase()
        val wantsTime = listOf("time", "date", "today", "day", "timezone", "time zone").any { q.contains(it) }
        val wantsBattery = listOf("battery", "charge", "charging").any { q.contains(it) }
        val wantsStorage = listOf("storage", "space").any { q.contains(it) }
        val wantsPrivacy = listOf("privacy", "access", "what can you", "data").any { q.contains(it) }
        return when {
            wantsTime && !wantsBattery && !wantsStorage -> "It’s $dateTime (${now.zone.id})."
            wantsBattery && !wantsTime && !wantsStorage -> "Your battery is $batteryText."
            wantsStorage && !wantsTime && !wantsBattery -> "Your phone has $storageText."
            wantsPrivacy -> "I can use Shuddh’s saved scans and phone status. Web search is ${if (prefs.onlineAssistant) "on" else "off"}; when used, only your search text goes to Bing. I don’t read contacts, messages, calendar or notifications."
            else -> buildString {
                append("Phone status · updated just now\n")
                append("Time  $dateTime\n")
                append("Battery  $batteryText\n")
                append("Storage  $storageText\n")
                append("Device  ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("Lab  ${store.records.size} saved ${if (store.records.size == 1) "scan" else "scans"}")
            }
        }
    }

    private fun bytes(value: Long): String = when {
        value >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", value / 1_073_741_824.0)
        value >= 1_048_576L -> String.format(Locale.US, "%.0f MB", value / 1_048_576.0)
        else -> "$value B"
    }

    override fun safetyPlan(): String {
        val records = store.records.toList()
        val now = System.currentTimeMillis()
        val latest = records.lastOrNull()
        val freshConcern = latest?.takeIf {
            it.level in setOf(com.shuddh.lab.core.Level.UNSAFE, com.shuddh.lab.core.Level.CAUTION) &&
                !it.confirmed && now - it.time in 0 until 15 * 60 * 1000L
        }
        val unsafeWeek = records.filter { it.time >= now - 7 * 86_400_000L && it.level == com.shuddh.lab.core.Level.UNSAFE }
        val score = store.kitchenScore()
        val stale = latest == null || now - latest.time > 7 * 86_400_000L
        return buildString {
            append("Your kitchen plan\n")
            when {
                freshConcern != null -> {
                    val mins = ((15 * 60 * 1000L - (now - freshConcern.time) + 59_999L) / 60_000L).coerceAtLeast(1)
                    append("1. Confirm ${freshConcern.analyte} now — $mins min remain in its evidence window.\n")
                    append("2. Keep the same sample and setup for the repeat scan.\n")
                    append("3. Save both readings before deciding what to do.")
                }
                unsafeWeek.isNotEmpty() -> {
                    val top = unsafeWeek.groupBy { it.analyte }.maxByOrNull { it.value.size }!!.key
                    append("1. Review this week’s $top result before using or buying more.\n")
                    append("2. Retest a fresh sample to strengthen the evidence.\n")
                    append("3. Keep the verdict PDF if the concern repeats.")
                }
                stale -> {
                    append("1. Run one quick kitchen check today — a label, water, or milk test.\n")
                    append("2. Save the result to restart your weekly Kitchen Health score.\n")
                    append("3. Use the food guide if you’re not sure where to begin.")
                }
                else -> {
                    append("1. Your current Kitchen Health score is ${score?.first ?: "not available"}/100.\n")
                    append("2. Try a different instrument this week to broaden your snapshot.\n")
                    append("3. Review History before changing a vendor or kitchen routine.")
                }
            }
        }
    }

    fun safetyPlanActions(): List<AssistantAction> {
        val latest = store.records.lastOrNull()
        val freshConcern = latest?.takeIf {
            it.level in setOf(com.shuddh.lab.core.Level.UNSAFE, com.shuddh.lab.core.Level.CAUTION) &&
                !it.confirmed && System.currentTimeMillis() - it.time in 0 until 15 * 60 * 1000L
        }
        val repeat = freshConcern?.let { record ->
            when {
                record.instrument.contains("Spectrum", true) -> Screen.SPECTRUM
                record.instrument.contains("Polar", true) -> Screen.POLAR
                record.instrument.contains("NIR", true) -> Screen.NIR
                record.instrument.contains("Echo", true) -> Screen.ECHO
                record.instrument.contains("Nami", true) -> Screen.NAMI
                record.instrument.contains("Strip", true) -> Screen.STRIP
                record.instrument.contains("Hawa", true) -> Screen.SCATTER
                record.instrument.contains("Float", true) -> Screen.FLOAT
                record.instrument.contains("Magneto", true) -> Screen.MAGNETO
                record.instrument.contains("Lens", true) -> Screen.LENS
                else -> null
            }
        }
        return if (repeat != null) {
            listOf(AssistantAction("Repeat test", repeat), AssistantAction("View evidence", Screen.HISTORY))
        } else {
            listOf(AssistantAction("Test a label", Screen.LENS), AssistantAction("Review history", Screen.HISTORY))
        }
    }

    private val nav = mutableStateListOf(if (prefs.onboarded) Screen.HOME else Screen.ONBOARDING)
    val screen get() = nav.last()

    var outcome by mutableStateOf<Outcome?>(null)
        private set
    var lang by mutableStateOf(prefs.lang)

    init {
        UiPrefs.accent = runCatching { AccentTheme.valueOf(prefs.accent) }.getOrDefault(AccentTheme.EMERALD)
        UiPrefs.reduceMotion = prefs.reduceMotion
        UiPrefs.lang = prefs.lang
        com.shuddh.lab.core.Haptics.enabled = prefs.haptics
    }

    fun go(s: Screen) { nav.add(s) }

    fun tab(s: Screen) {
        nav.clear()
        nav.add(Screen.HOME)
        if (s != Screen.HOME) nav.add(s)
    }

    fun back(): Boolean {
        if (nav.size <= 1) return false
        nav.removeAt(nav.lastIndex)
        return true
    }

    fun finishOnboarding() {
        prefs.onboarded = true
        nav.clear(); nav.add(Screen.HOME)
    }

    /** Hand an instrument reading to the verdict screen. */
    fun show(o: Outcome) {
        outcome = o
        go(Screen.RESULT)
    }

    fun setLanguage(l: Lang) {
        lang = l
        UiPrefs.lang = l
        prefs.lang = l
        if (l != Lang.EN) prefs.setFlag("polyglot")
    }

    override fun openScreen(name: String): Boolean =
        runCatching { Screen.valueOf(name) }.getOrNull()?.let { go(it); true } ?: false

    override fun meshSend(text: String): Boolean {
        if (!mesh.running) return false
        mesh.send(com.shuddh.lab.core.MeshProto.CHAT, text.take(140)); return true
    }

    /** Preset for the whistle counter when the agent opens it. */
    var whistleTarget by mutableStateOf<Int?>(null)

    private fun launch(i: android.content.Intent): Boolean = runCatching {
        ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    override fun setTimer(seconds: Int, label: String) = launch(
        android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
            .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true),
    )

    override fun setAlarm(hour: Int, minute: Int, label: String) = launch(
        android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true),
    )

    override fun torch(on: Boolean): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        cm.setTorchMode(id, on); true
    }.getOrDefault(false)

    override fun messageFamily(text: String): Boolean {
        val phone = prefs.familyPhone.ifBlank { return false }
        return runCatching { com.shuddh.lab.core.Passport.sms(ctx, phone, "Shuddh: $text"); true }.getOrDefault(false)
    }

    override fun setLanguage(code: String): Boolean =
        runCatching { setLanguage(Lang.valueOf(code)); true }.getOrDefault(false)

    override fun searchPhotos(query: String): String {
        if (media.docs.isEmpty()) { mediaQuery = query; go(Screen.MEDIA); return "The photo index is empty — opened Media Search; tap Index gallery first." }
        val hits = com.shuddh.lab.core.MediaIndex.rank(query, media.embed(query), media.docs.toList(), top = 3)
        mediaQuery = query; go(Screen.MEDIA)
        return "Top matches for \"$query\": " + hits.joinToString("; ") { h ->
            "photo from ${com.shuddh.lab.core.stamp(h.doc.timeMs)} showing ${h.doc.labels.take(3).joinToString().ifBlank { "text" }}" +
                (h.doc.expiry?.let { if (it < System.currentTimeMillis()) " (EXPIRED label)" else "" } ?: "")
        } + ". Opened Media Search."
    }

    override fun addEvent(title: String, startMillis: Long) = launch(
        android.content.Intent(android.content.Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.Events.TITLE, title)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 3_600_000L),
    )

    override fun dial(number: String) = launch(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number")))

    override fun openApp(name: String): String? {
        val pm = ctx.packageManager
        val main = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val n = name.lowercase().trim()
        val hit = apps.firstOrNull { it.first.lowercase() == n } ?: apps.firstOrNull { it.first.lowercase().startsWith(n) } ?: apps.firstOrNull { it.first.lowercase().contains(n) }
            ?: return null
        val i = pm.getLaunchIntentForPackage(hit.second) ?: return null
        return if (launch(i)) hit.first else null
    }

    override fun memoryGet(key: String): String = ctx.getSharedPreferences("agent_memory", Context.MODE_PRIVATE).getString(key, "") ?: ""
    override fun memoryPut(key: String, value: String) { ctx.getSharedPreferences("agent_memory", Context.MODE_PRIVATE).edit().putString(key, value).apply() }

    override fun openWhistle(target: Int): Boolean { whistleTarget = target; go(Screen.WHISTLE); return true }

    override fun downloadReport(): String = com.shuddh.lab.core.Passport.saveToDownloads(
        ctx, com.shuddh.lab.core.Passport.build(ctx, store.records.toList(), store.verifyChain() == -1, "Full kitchen report"),
    )
}
