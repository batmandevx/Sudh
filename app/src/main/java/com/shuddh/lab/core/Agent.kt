package com.shuddh.lab.core

import org.json.JSONObject

/** Side effects the agent may trigger in the app. */
interface AgentHost {
    val store: Store
    val community: CommunityStore
    fun openScreen(name: String): Boolean
    fun meshSend(text: String): Boolean
    fun downloadReport(): String
    fun setTimer(seconds: Int, label: String): Boolean
    fun setAlarm(hour: Int, minute: Int, label: String): Boolean
    fun torch(on: Boolean): Boolean
    fun messageFamily(text: String): Boolean
    fun setLanguage(code: String): Boolean
    fun openWhistle(target: Int): Boolean
    fun searchPhotos(query: String): String
    /** Read-only local device information, tailored to the question that requested it. */
    fun deviceContext(question: String): String
    /** A prioritized next-step plan assembled from the scan log on this phone. */
    fun safetyPlan(): String
    /** Opens the calendar app's "new event" screen pre-filled; the user confirms. */
    fun addEvent(title: String, startMillis: Long): Boolean = false
    /** Opens the dialer with the number filled in; the user presses call. */
    fun dial(number: String): Boolean = false
    /** Launches an installed app by its visible name; returns the app's label or null. */
    fun openApp(name: String): String? = null
    /** Small private key–value store for notes and the shopping list. */
    fun memoryGet(key: String): String = ""
    fun memoryPut(key: String, value: String) {}
}

/** Deterministic parsing of spoken durations and clock times — never left to the language model. */
object When {
    private val unit = Regex("""(?i)(\d+(?:\.\d+)?)\s*(hours?|hrs?|h|ghante?|घंटे?|minutes?|mins?|m|minat|मिनट|seconds?|secs?|s|सेकंड)\b""")

    /** "1 hour 20 min", "90 seconds", "half an hour", "10" (= minutes) → seconds, or null. */
    fun durationSeconds(text: String): Int? {
        val t = text.lowercase()
        var total = 0.0
        unit.findAll(t).forEach { m ->
            val n = m.groupValues[1].toDouble()
            val u = m.groupValues[2]
            total += when {
                u.startsWith("h") || u.startsWith("ghant") || u.startsWith("घं") -> n * 3600
                u.startsWith("s") || u.startsWith("सें") -> n
                else -> n * 60
            }
        }
        if (Regex("""\bhalf an hour|aadha ghanta|आधा घंटा""").containsMatchIn(t)) total += 1800
        if (Regex("""\b(an|one|a) hour\b""").containsMatchIn(t) && total == 0.0) total += 3600
        if (Regex("""\b(a|one) minute\b""").containsMatchIn(t) && total == 0.0) total += 60
        if (total == 0.0) Regex("""(?<![:\d])(\d{1,3})(?![:\d])""").find(t)?.let { total = it.groupValues[1].toDouble() * 60 }
        return total.toInt().takeIf { it in 1..86_400 }
    }

    /** "6:30 am", "7 pm", "19:45", "subah 6 baje" → (hour, minute), or null. */
    fun clock(text: String): Pair<Int, Int>? {
        val t = text.lowercase()
        val m = Regex("""(?<!\d)(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?|baje|बजे)?""").findAll(t)
            .firstOrNull { it.groupValues[2].isNotEmpty() || it.groupValues[3].isNotEmpty() } ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val suffix = m.groupValues[3]
        val pm = suffix.startsWith("p") || Regex("""evening|night|shaam|raat|शाम|रात""").containsMatchIn(t)
        val am = suffix.startsWith("a") || Regex("""morning|subah|सुबह""").containsMatchIn(t)
        if (pm && h in 1..11) h += 12
        if (am && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    /**
     * "2:34" without am/pm or a time-of-day word is ambiguous: pick whichever of 02:34 / 14:34
     * comes next from now.
     */
    fun nextUpcoming(hm: Pair<Int, Int>, text: String, now: java.util.Calendar = java.util.Calendar.getInstance()): Pair<Int, Int> {
        val t = text.lowercase()
        if (Regex("""a\.?m|p\.?m|morning|evening|night|noon|subah|shaam|raat|सुबह|शाम|रात""").containsMatchIn(t) || hm.first == 0 || hm.first >= 12) return hm
        val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val am = hm.first * 60 + hm.second; val pm = am + 12 * 60
        fun until(m: Int) = (m - nowMin + 1440) % 1440
        return if (until(pm) < until(am)) (hm.first + 12) to hm.second else hm
    }

    fun human(seconds: Int): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "$it h" }, m.takeIf { it > 0 }?.let { "$it min" }, s.takeIf { it > 0 }?.let { "$it s" }).joinToString(" ")
    }
}

data class ToolCall(val name: String, val args: Map<String, String>, val raw: String, val byModel: Boolean)

data class AgentStep(val kind: String, val text: String, val millis: Long = 0)

/**
 * Two-model agent:
 *   1. Router — Qwen2.5-0.5B reads the question and emits a JSON tool call.
 *   2. Tool — Kotlin executes it against the phone's own data (or performs an app action).
 *   3. Chat — Qwen2.5-1.5B turns the tool result into a short answer in the user's language,
 *      instructed to use only those facts.
 * If a model is missing, a deterministic rule router / templated answer takes its place.
 */
object Agent {
    data class Tool(val name: String, val args: String, val desc: String)

    val tools = listOf(
        Tool("kitchen_score", "", "overall kitchen health score, weekly summary, how am I doing"),
        Tool("worst_item", "", "what failed most, weakest or most dangerous item"),
        Tool("vendor_history", "vendor", "track record of a named shop, dairy, brand or vendor"),
        Tool("last_scan", "", "the most recent test result"),
        Tool("scan_counts", "", "how many tests, how many unsafe"),
        Tool("area_alerts", "", "alerts in my area, community or nearby reports"),
        Tool("contaminant_info", "name", "what a contaminant is or why it is harmful (nitrate, arsenic, detergent, urea, starch, chlorine, fluoride, honey syrup, aflatoxin, steel)"),
        Tool("open_instrument", "name", "user wants to test or measure something: spectrum, polar, nir, nami, echo, strips, hawa, float, magneto, whistle, lens, heart rate (pulse)"),
        Tool("mesh_send", "text", "broadcast a message to nearby phones over bluetooth"),
        Tool("download_report", "", "download or export a PDF report"),
        Tool("analyze_image", "", "questions about the attached photo or food label"),
        Tool("search_photos", "query", "find photos in my gallery, e.g. show photos of milk packets"),
        Tool("set_timer", "duration,label", "start a countdown timer, e.g. boil water for 10 minutes"),
        Tool("set_alarm", "time,label", "set an alarm or a reminder at a clock time"),
        Tool("flashlight", "state", "turn the torch on or off"),
        Tool("generate_qr", "text", "make a QR code of some text"),
        Tool("message_family", "text", "send an SMS update to the family member"),
        Tool("whistle_counter", "count", "count pressure cooker whistles"),
        Tool("daily_tip", "", "teach me something new, a food safety tip"),
        Tool("set_language", "language", "switch app language to english, hindi, kannada, telugu or tamil"),
        Tool("phone_status", "", "current date or time, battery, storage, device status, local privacy and what Shuddh can access"),
        Tool("safety_brief", "", "a prioritized kitchen plan: what needs attention, what to retest, and the best next check"),
        Tool("web_search", "", "search the internet when the user explicitly requests web search, latest news or current weather"),
        Tool("recipe", "dish", "how to cook or make a dish, recipes, ingredients for a dish"),
        Tool("calculate", "expression", "arithmetic, percentages, maths"),
        Tool("convert_units", "query", "convert cups, grams, ml, kg, celsius, fahrenheit, km, miles"),
        Tool("add_event", "title,time", "add a meeting or event to the calendar"),
        Tool("save_note", "text", "remember or write down a note"),
        Tool("show_notes", "", "read my saved notes"),
        Tool("shopping_add", "items", "add items to the shopping or grocery list"),
        Tool("shopping_show", "", "show the shopping list"),
        Tool("open_app", "name", "open another app on the phone, e.g. youtube, whatsapp, camera, settings"),
        Tool("call", "number", "call or dial a phone number"),
        Tool("open_vision", "", "machine vision: detect objects, hands, gestures, face, body pose with the camera"),
        Tool("general", "", "general knowledge, explanations, writing, translation, advice, nutrition, health, science, stories"),
        Tool("none", "", "greetings and small talk"),
    )

    private val instruments = mapOf(
        "spectrum" to "SPECTRUM", "milk" to "SPECTRUM", "water" to "SPECTRUM", "chlorine" to "SPECTRUM",
        "polar" to "POLAR", "honey" to "POLAR", "nir" to "NIR", "nami" to "NAMI", "moisture" to "NAMI", "wall" to "NAMI",
        "echo" to "ECHO", "coconut" to "ECHO", "watermelon" to "ECHO", "strips" to "STRIP", "strip" to "STRIP",
        "hawa" to "SCATTER", "air" to "SCATTER", "float" to "FLOAT", "lactometer" to "FLOAT",
        "magneto" to "MAGNETO", "steel" to "MAGNETO", "whistle" to "WHISTLE", "cooker" to "WHISTLE",
        "lens" to "LENS", "boil" to "BOIL", "boiling" to "BOIL", "oil" to "OIL", "frying" to "OIL", "outbreak" to "OUTBREAK", "sick" to "OUTBREAK", "diarrhoea" to "OUTBREAK", "anaemia" to "ANAEMIA", "mosquito" to "MOSQUITO", "exposure" to "EXPOSURE", "ledger" to "EXPOSURE", "sos" to "GUARDIAN", "emergency" to "GUARDIAN", "fall" to "GUARDIAN", "guardian" to "GUARDIAN", "dengue" to "MOSQUITO", "malaria" to "MOSQUITO", "anemia" to "ANAEMIA", "pallor" to "ANAEMIA", "grain" to "GRAIN", "rice" to "GRAIN", "dal" to "GRAIN", "stones" to "GRAIN", "vision" to "VISION", "gesture" to "VISION", "gestures" to "VISION", "heart" to "PULSE", "pulse" to "PULSE", "bpm" to "PULSE", "label" to "LENS", "expiry" to "LENS",
    )

    fun routerPrompt(question: String, hasImage: Boolean, context: String = ""): String {
        val list = tools.joinToString("\n") { "- ${it.name}(${it.args}): ${it.desc}" }
        val system = """You are the function router of the Shuddh food-safety app. Pick exactly ONE tool for the user's message.
Reply with ONLY a JSON object, no other text. Format: {"tool":"<name>","args":{...}}
Tools:
$list
Examples:
User: hi -> {"tool":"none","args":{}}
User: thanks -> {"tool":"none","args":{}}
User: who are you -> {"tool":"none","args":{}}
User: has ramesh dairy failed before? -> {"tool":"vendor_history","args":{"vendor":"ramesh dairy"}}
User: why is nitrate bad -> {"tool":"contaminant_info","args":{"name":"nitrate"}}
User: I want to check my honey -> {"tool":"open_instrument","args":{"name":"polar"}}
User: tell everyone nearby the tap water is dirty -> {"tool":"mesh_send","args":{"text":"the tap water is dirty"}}
User: how is my kitchen -> {"tool":"kitchen_score","args":{}}
User: what time is it? -> {"tool":"phone_status","args":{}}
User: how much battery do I have? -> {"tool":"phone_status","args":{}}
User: what should I check today? -> {"tool":"safety_brief","args":{}}
User: set a timer for 10 minutes to boil the water -> {"tool":"set_timer","args":{"duration":"10 minutes","label":"boil the water"}}
User: wake me up at 6:30 am -> {"tool":"set_alarm","args":{"time":"6:30 am","label":"wake up"}}
User: remind me to buy milk at 7 pm -> {"tool":"set_alarm","args":{"time":"7 pm","label":"buy milk"}}
User: turn on the flashlight -> {"tool":"flashlight","args":{"state":"on"}}
User: make a qr code for 9876543210 -> {"tool":"generate_qr","args":{"text":"9876543210"}}
User: tell my family the milk is adulterated -> {"tool":"message_family","args":{"text":"the milk is adulterated"}}
User: count 3 cooker whistles -> {"tool":"whistle_counter","args":{"count":"3"}}
User: find my photos of honey jars -> {"tool":"search_photos","args":{"query":"honey jars"}}
User: give me a recipe for paneer butter masala -> {"tool":"recipe","args":{"dish":"paneer butter masala"}}
User: how do I make masala chai -> {"tool":"recipe","args":{"dish":"masala chai"}}
User: what is 18% of 2450 -> {"tool":"calculate","args":{"expression":"18% of 2450"}}
User: convert 2 cups to ml -> {"tool":"convert_units","args":{"query":"2 cups to ml"}}
User: add a meeting with the doctor tomorrow at 5 pm -> {"tool":"add_event","args":{"title":"meeting with the doctor","time":"tomorrow 5 pm"}}
User: note that the gas cylinder was booked -> {"tool":"save_note","args":{"text":"the gas cylinder was booked"}}
User: add milk and eggs to my shopping list -> {"tool":"shopping_add","args":{"items":"milk, eggs"}}
User: open youtube -> {"tool":"open_app","args":{"name":"youtube"}}
User: call 9876543210 -> {"tool":"call","args":{"number":"9876543210"}}
User: detect my hand gestures -> {"tool":"open_vision","args":{}}
User: explain photosynthesis simply -> {"tool":"general","args":{}}
User: write a birthday wish for my mom -> {"tool":"general","args":{}}
User: hello -> {"tool":"none","args":{}}""" +
            (if (context.isNotBlank()) "\nRecent local conversation for resolving follow-ups:\n$context" else "") +
            if (hasImage) "\nA photo is attached; questions about it use analyze_image." else ""
        return LocalLlm.chatml(system, question)
    }

    /**
     * Greetings / small talk never go to the router model. Lowercasing first avoids Android ICU's
     * incompatible inline `(?iU)` flag combination (which otherwise crashes before a reply).
     */
    private val smallTalkPattern = Regex(
        "^\\s*(hi+|hey+|hello+|hii+|namaste|namaskar|नमस्ते|good (morning|afternoon|evening|night)|thanks?( you)?|thank u|ok(ay)?|cool|nice|great|bye|who are you|what can you do|help|how are you|kaise ho)[\\s!.?]*$",
    )

    fun isSmallTalk(q: String): Boolean = smallTalkPattern.matches(q.trim().lowercase())

    /**
     * Words that must appear in the user's own message before an action tool may run. A small
     * model's pick alone can't set alarms, open screens or send messages.
     */
    private val actionTriggers = mapOf(
        "set_timer" to "timer|countdown|minutes?|mins?|seconds?|hours?|remind me in|after",
        "set_alarm" to "alarm|wake|remind|reminder|am|pm|baje|o'?clock|\\d{1,2}[:.]\\d{2}|yaad",
        "flashlight" to "torch|flash ?light|light",
        "generate_qr" to "qr",
        "message_family" to "family|maa|mummy|papa|beta|mom|dad|sms|message",
        "whistle_counter" to "whistles?|seeti|सीटी|cooker",
        "set_language" to "hindi|kannada|telugu|tamil|english|language|bhasha|हिन्दी|ಕನ್ನಡ|తెలుగు|தமிழ்",
        "open_instrument" to "open|test|check|measure|start|scan|jaanch|जाँच",
        "mesh_send" to "mesh|broadcast|nearby|everyone|send|bhejo",
        "download_report" to "download|export|pdf|report",
        "search_photos" to "photos?|pictures?|gallery|images?",
        "analyze_image" to "photo|image|picture|label|this|packet|expir|fssai",
        "add_event" to "calendar|event|meeting|schedule|appointment",
        "save_note" to "note|remember|write down|jot",
        "shopping_add" to "shopping|grocery|groceries|list|buy",
        "open_app" to "open|launch|start",
        "call" to "call|dial|phone",
        "open_vision" to "vision|detect|gestures?|hands?|face|camera|objects?|pose|see",
    )

    fun guard(call: ToolCall, question: String, hasImage: Boolean): ToolCall {
        if (call.name == "web_search" && !WebSearch.requested(question)) return ruleRoute(question, hasImage, call.raw)
        val pattern = actionTriggers[call.name] ?: return call
        val ok = Regex("\\b($pattern)\\b").containsMatchIn(question.lowercase()) || (call.name == "analyze_image" && hasImage)
        return if (ok) call else ruleRoute(question, hasImage, call.raw).let { if (it.name == call.name) ToolCall("none", emptyMap(), call.raw, false) else it }
    }

    fun parse(raw: String, question: String, hasImage: Boolean): ToolCall = guard(parseRaw(raw, question, hasImage), question, hasImage)

    private fun parseRaw(raw: String, question: String, hasImage: Boolean): ToolCall {
        val json = Regex("""\{[\s\S]*\}""").find(raw)?.value
        val parsed = json?.let { j ->
            runCatching {
                val o = JSONObject(j)
                val name = o.optString("tool")
                val args = o.optJSONObject("args")?.let { a -> a.keys().asSequence().associateWith { a.optString(it) } } ?: emptyMap()
                val spec = tools.firstOrNull { it.name == name }
                // Keep only the arguments the tool declares — small models sometimes invent extras.
                spec?.let { t -> ToolCall(name, args.filterKeys { k -> t.args.split(",").map { it.trim() }.contains(k) }, raw, true) }
            }.getOrNull()
        }
        return parsed ?: ruleRoute(question, hasImage, raw)
    }

    /** Deterministic fallback router (used when the router model is absent or its JSON is invalid). */
    fun ruleRoute(q0: String, hasImage: Boolean, raw: String = ""): ToolCall {
        val q = q0.lowercase()
        fun c(name: String, vararg a: Pair<String, String>) = ToolCall(name, a.toMap(), raw, false)
        /** Whole-word match (so "ramesh" doesn't trigger "mesh", nor "dairy" trigger "air"). */
        fun w(pattern: String) = Regex("\\b($pattern)\\b").containsMatchIn(q)
        return when {
            WebSearch.requested(q0) -> c("web_search")
            w("recipe|recipes|how (do|to|can) (i |you )?(make|cook|prepare|bake)|banane ki vidhi|kaise banaye|kaise banate|बनाने की विधि|ingredients for") -> c("recipe", "dish" to dishOf(q0))
            Units.convert(q0) != null -> c("convert_units", "query" to q0)
            Calc.looksLikeMath(q0) -> c("calculate", "expression" to q0)
            w("calendar|meeting|appointment") || (w("event") && w("add|create|schedule")) -> c("add_event", "title" to q0, "time" to q0)
            w("shopping|grocery|groceries") && w("show|what|read|my list|list") && !w("add|put") -> c("shopping_show")
            w("shopping|grocery|groceries") || (w("add") && w("list")) -> c("shopping_add", "items" to itemsOf(q0))
            w("notes") && w("show|read|my|list|what") -> c("show_notes")
            w("note|write down|jot") -> c("save_note", "text" to q0)
            w("call|dial") && Regex("""\+?\d[\d\s-]{5,}""").containsMatchIn(q) -> c("call", "number" to Regex("""\+?\d[\d\s-]{5,}""").find(q)!!.value)
            w("vision|gestures?|hand tracking|face mesh|object detection|detect objects|what do you see|pose|body tracking") -> c("open_vision")
            w("open|launch") && instruments.keys.none { w(it) } && Regex("""\b(?:open|launch)\s+(?:the\s+)?([a-z][a-z ]{1,24})""").containsMatchIn(q) ->
                c("open_app", "name" to Regex("""\b(?:open|launch)\s+(?:the\s+)?([a-z][a-z ]{1,24})""").find(q)!!.groupValues[1].removeSuffix(" app").trim())
            w("date|time|battery|charging|charge|storage|space|phone status|device status|device|timezone|time zone|privacy|what can you access") || q.contains("what day") -> c("phone_status")
            w("plan|brief|what should i do|what should i check|what needs attention|next step|next test|today.?s checks|today.?s plan|priority|priorities") -> c("safety_brief")
            hasImage && w("photo|image|picture|label|this|packet|expiry|expired|fssai|tasveer|फोटो") -> c("analyze_image")
            w("photo|photos|picture|pictures|gallery") && w("find|show|search|where") && !hasImage -> c("search_photos", "query" to q0)
            w("timer|countdown") || (w("remind me in|after") && When.durationSeconds(q) != null) -> c("set_timer", "duration" to q0, "label" to q0)
            w("alarm|wake me|wake up|remind me|reminder|yaad dilao|याद") -> c("set_alarm", "time" to q0, "label" to q0)
            w("torch|flashlight|flash light|light on|light off") -> c("flashlight", "state" to if (w("off|band|बंद")) "off" else "on")
            w("qr|qr code") -> c("generate_qr", "text" to q0.substringAfter(" for ", q0.substringAfter(" of ", q0)))
            w("family|maa|mummy|papa|beta|ghar") && w("tell|message|sms|send|bata|batao") -> c("message_family", "text" to q0)
            w("whistle|whistles|seeti|सीटी") -> c("whistle_counter", "count" to (Regex("""\d+""").find(q)?.value ?: "3"))
            w("tip|learn|teach|something new|fact") -> c("daily_tip")
            w("hindi|हिन्दी|kannada|telugu|tamil|english|language|bhasha") && w("switch|change|set|use|speak|badlo") -> c("set_language", "language" to q0)
            w("send|broadcast|tell (everyone|nearby)|mesh|bhejo|भेजो") -> c("mesh_send", "text" to q0.substringAfter(" ", q0))
            w("download|export|pdf|report") -> c("download_report")
            w("open|check my|test my|start|measure|jaanch|जाँच") ->
                c("open_instrument", "name" to (instruments.keys.firstOrNull { w(it) } ?: "spectrum"))
            Assistant.facts.any { (k, _) -> k.any { q.contains(it) } } && w("what|why|kya|क्या|danger|dangerous|harm|harmful|about|is") ->
                c("contaminant_info", "name" to (Assistant.facts.first { (k, _) -> k.any { q.contains(it) } }.first.first()))
            w("vendor|dairy|shop|store|brand|wala|वाला") -> c("vendor_history", "vendor" to q0)
            w("worst|weakest|fail|failed|fails|kharab|खराब") -> c("worst_item")
            w("last|latest|recent|pichla|पिछला") -> c("last_scan")
            w("how many|kitne|कितने|count") -> c("scan_counts")
            w("area|alert|alerts|community|nearby|ilaka|इलाका") -> c("area_alerts")
            w("score|health|kitchen|summary|week|स्कोर|रसोई") -> c("kitchen_score")
            hasImage -> c("analyze_image")
            else -> c("general")
        }
    }

    private fun dishOf(q: String): String = q.lowercase()
        .replace(Regex("""^.*?\b(recipe (for|of)|recipes? for|how (do|to|can) (i |you )?(make|cook|prepare|bake)|ingredients for|give me (a|the)?|tell me (a|the)?)\b"""), "")
        .replace(Regex("""\b(recipe|recipes|please|at home|banane ki vidhi|kaise banaye|kaise banate)\b|[?.!]"""), "").trim()
        .replace(Regex("""^((for|of|a|an|the|me|some)\s+)+"""), "").replace(Regex("""\s+"""), " ").trim().ifBlank { q }

    private fun itemsOf(q: String): String = q.lowercase()
        .replace(Regex("""\b(add|put|to|on|in|my|the|shopping|grocery|groceries|list|please)\b"""), " ")
        .replace(Regex("""\s+and\s+"""), ", ").replace(Regex("""\s+"""), " ").trim(' ', ',')

    /**
     * Tools whose result is already the exact answer (an action performed, a computed value, a
     * list). A language model must never paraphrase these — it can turn "alarm set" into a refusal.
     */
    val exactAnswer = setOf(
        "phone_status", "safety_brief", "calculate", "convert_units", "show_notes", "shopping_show", "shopping_add", "save_note",
        "set_timer", "set_alarm", "flashlight", "generate_qr", "message_family", "whistle_counter", "set_language",
        "open_instrument", "open_vision", "open_app", "call", "add_event", "mesh_send", "download_report", "search_photos",
    )

    /** Short questions that lean on the previous turn ("make it spicier", "what about tea?"). */
    fun isFollowUp(q: String): Boolean {
        val t = q.lowercase().trim()
        return t.split(Regex("\\s+")).size <= 7 && Regex("\\b(it|that|this|those|them|more|again|also|another|same|instead|what about|and the|spicier|shorter|longer)\\b").containsMatchIn(t)
    }

    /** Tools whose answers come from the chat model's own knowledge rather than phone data. */
    val openKnowledge = setOf("none", "general", "recipe")

    /** Executes a tool against on-phone data. Returns the facts the chat model may use. */
    fun execute(call: ToolCall, host: AgentHost, lang: Lang, image: ImageFacts?, question: String): String {
        val s = host.store
        return when (call.name) {
            "kitchen_score" -> s.kitchenScore()?.let { "Kitchen score ${it.first}/100 over the last 7 days. ${it.second}. ${Insights.digest(s.records.toList()) ?: ""}" }
                ?: "No scans in the last 7 days."
            "worst_item" -> Insights.byTest(s.records.toList()).firstOrNull { it.second.unsafe > 0 }
                ?.let { "${it.first}: ${it.second.unsafe} unsafe of ${it.second.total} scans." } ?: "No item has failed so far."
            "vendor_history" -> {
                val v = call.args["vendor"].orEmpty()
                val match = s.vendors().firstOrNull { it.equals(v, true) || it.contains(v, true) || (v.isNotBlank() && v.contains(it, true)) }
                    ?: s.vendors().firstOrNull { question.contains(it, true) }
                match?.let { s.vendorMemory(it).sentence() } ?: "No vendor named \"$v\" in the scan log. Known vendors: ${s.vendors().joinToString().ifBlank { "none yet" }}."
            }
            "last_scan" -> s.records.lastOrNull()?.let { r ->
                "Last scan: ${r.analyte} = ${r.value?.let { "${fmt(it)} ${r.unit}" } ?: "—"}, ${r.level.name}${if (r.confirmed) " (confirmed)" else ""}, ${stamp(r.time)}${if (r.vendor.isNotBlank()) ", vendor ${r.vendor}" else ""}."
            } ?: "No scans yet."
            "scan_counts" -> "${s.records.size} scans; ${s.records.count { it.level == Level.UNSAFE }} unsafe; ${s.records.count { it.confirmed }} confirmed by a second scan."
            "phone_status" -> host.deviceContext(question)
            "safety_brief" -> host.safetyPlan()
            "area_alerts" -> Assistant.ask("area alerts", s, host.community, Lang.EN).text
            "contaminant_info" -> {
                val n = call.args["name"].orEmpty().lowercase()
                (Assistant.facts.firstOrNull { (k, _) -> k.any { n.contains(it) || it.contains(n) && n.length > 2 } }
                    ?: Assistant.facts.firstOrNull { (k, _) -> k.any { question.lowercase().contains(it) } })
                    ?.second?.en ?: "No fact sheet entry for \"$n\"."
            }
            "open_instrument" -> {
                val n = call.args["name"].orEmpty().lowercase()
                fun has(t: String, k: String) = Regex("\\b$k\\b").containsMatchIn(t)
                val target = instruments.entries.firstOrNull { has(n, it.key) }?.value
                    ?: instruments.entries.firstOrNull { has(question.lowercase(), it.key) }?.value
                if (target != null && host.openScreen(target)) "Opened the ${target.lowercase()} instrument." else "No instrument matches \"$n\"."
            }
            "mesh_send" -> {
                val text = call.args["text"].orEmpty().ifBlank { question }
                if (host.meshSend(text)) "Broadcast to the Bluetooth mesh: \"$text\"." else "The Bluetooth mesh is off — open Hive → Shuddh Mesh and join first."
            }
            "download_report" -> runCatching { "Saved the PDF report to ${host.downloadReport()}." }.getOrElse { "Could not save the report: ${it.message}" }
            "analyze_image" -> image?.asFacts() ?: "No photo is attached."
            "set_timer" -> {
                val secs = When.durationSeconds(call.args["duration"].orEmpty()) ?: When.durationSeconds(question)
                val label = call.args["label"].orEmpty().ifBlank { "Shuddh timer" }.take(40)
                when {
                    secs == null -> "Couldn't understand the duration. Say e.g. 'timer for 10 minutes'."
                    host.setTimer(secs, label) -> "Timer started for ${When.human(secs)} (\"$label\") in the Clock app."
                    else -> "No clock app accepted the timer."
                }
            }
            "set_alarm" -> {
                val hm = (When.clock(call.args["time"].orEmpty()) ?: When.clock(question))?.let { When.nextUpcoming(it, question + " " + call.args["time"].orEmpty()) }
                val label = call.args["label"].orEmpty().ifBlank { "Shuddh reminder" }.take(40).let { if (Regex("(?i)^(set|can you|please|alarm)").containsMatchIn(it)) "Shuddh alarm" else it }
                when {
                    hm == null -> "Couldn't understand the time. Say e.g. 'alarm at 6:30 am'."
                    host.setAlarm(hm.first, hm.second, label) -> "Alarm set for %02d:%02d (\"$label\") in the Clock app.".format(hm.first, hm.second)
                    else -> "No clock app accepted the alarm."
                }
            }
            "flashlight" -> {
                val on = !(call.args["state"].orEmpty().lowercase().contains("off") || Regex("\\boff\\b|बंद").containsMatchIn(question.lowercase()))
                if (host.torch(on)) "Flashlight turned ${if (on) "on" else "off"}." else "This phone's torch is unavailable right now."
            }
            "generate_qr" -> "Generated a QR code for: \"${call.args["text"].orEmpty().ifBlank { question }.take(200)}\". It is shown in the chat."
            "message_family" -> if (host.messageFamily(call.args["text"].orEmpty().ifBlank { question })) "Opened an SMS to the family member, ready to send." else "No family number is saved — add one in Settings → Family Care."
            "whistle_counter" -> {
                val n = (call.args["count"]?.toIntOrNull() ?: Regex("""\d+""").find(question)?.value?.toIntOrNull() ?: 3).coerceIn(1, 8)
                if (host.openWhistle(n)) "Opened the whistle counter set to $n whistles. Tap Start." else "Couldn't open the whistle counter."
            }
            "daily_tip" -> Assistant.facts.random().second.en
            "search_photos" -> host.searchPhotos(call.args["query"].orEmpty().ifBlank { question })
            "set_language" -> {
                val l = call.args["language"].orEmpty().lowercase() + " " + question.lowercase()
                val code = when { Regex("hindi|हिन्दी").containsMatchIn(l) -> "HI"; Regex("kannada|ಕನ್ನಡ").containsMatchIn(l) -> "KN"; Regex("telugu|తెలుగు").containsMatchIn(l) -> "TE"; Regex("tamil|தமிழ்").containsMatchIn(l) -> "TA"; else -> "EN" }
                if (host.setLanguage(code)) "Language switched to ${mapOf("HI" to "Hindi", "KN" to "Kannada", "TE" to "Telugu", "TA" to "Tamil", "EN" to "English")[code]}." else "Couldn't switch language."
            }
            "recipe" -> "Dish requested: ${call.args["dish"].orEmpty().ifBlank { question }}. Write the recipe from your own cooking knowledge."
            "calculate" -> (Calc.eval(call.args["expression"].orEmpty()) ?: Calc.eval(question))?.let { "Exact result computed on the phone: ${Calc.normalise(call.args["expression"].orEmpty().ifBlank { question })} = ${Calc.pretty(it)}" }
                ?: "Couldn't parse that as arithmetic."
            "convert_units" -> Units.convert(call.args["query"].orEmpty()) ?: Units.convert(question) ?: "Couldn't parse the conversion. Say e.g. 'convert 2 cups to ml'."
            "add_event" -> {
                val hm = When.clock(call.args["time"].orEmpty()) ?: When.clock(question)
                val cal = java.util.Calendar.getInstance()
                if (Regex("\\btomorrow|kal\\b").containsMatchIn(question.lowercase())) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                if (hm != null) { cal.set(java.util.Calendar.HOUR_OF_DAY, hm.first); cal.set(java.util.Calendar.MINUTE, hm.second) } else cal.add(java.util.Calendar.HOUR_OF_DAY, 1)
                val title = call.args["title"].orEmpty().ifBlank { question }.replace(Regex("(?i)^(add|create|schedule)\\s+(a\\s+)?"), "").take(60)
                if (host.addEvent(title, cal.timeInMillis)) "Opened the calendar with \"$title\" on ${stamp(cal.timeInMillis)} — tap Save to confirm." else "No calendar app accepted the event."
            }
            "save_note" -> {
                val text = call.args["text"].orEmpty().ifBlank { question }.replace(Regex("(?i)^(please\\s+)?(note|write down|jot down|remember)( that| down)?\\s*:?\\s*"), "")
                host.memoryPut("notes", (host.memoryGet("notes").lines().filter { it.isNotBlank() } + "${stamp(System.currentTimeMillis())} — $text").takeLast(50).joinToString("\n"))
                "Saved note: \"$text\". You have ${host.memoryGet("notes").lines().count { it.isNotBlank() }} notes."
            }
            "show_notes" -> host.memoryGet("notes").ifBlank { "No notes saved yet." }.let { "Saved notes:\n$it" }
            "shopping_add" -> {
                val items = call.args["items"].orEmpty().ifBlank { itemsOf(question) }.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val list = (host.memoryGet("shopping").lines().filter { it.isNotBlank() } + items).distinctBy { it.lowercase() }
                host.memoryPut("shopping", list.joinToString("\n"))
                "Added ${items.joinToString()} to the shopping list. It now has: ${list.joinToString()}."
            }
            "shopping_show" -> host.memoryGet("shopping").lines().filter { it.isNotBlank() }.let { if (it.isEmpty()) "The shopping list is empty." else "Shopping list: ${it.joinToString()}." }
            "open_app" -> call.args["name"].orEmpty().ifBlank { question }.let { n -> host.openApp(n)?.let { "Opened $it." } ?: "No installed app matches \"$n\"." }
            "call" -> call.args["number"].orEmpty().filter { it.isDigit() || it == '+' }.ifBlank { Regex("""\+?\d{6,}""").find(question.replace(" ", ""))?.value.orEmpty() }
                .let { n -> if (n.length >= 6 && host.dial(n)) "Opened the dialer with $n — press call to connect." else "Couldn't find a phone number to dial." }
            "open_vision" -> if (host.openScreen("VISION")) "Opened Vision Lab: live object, hand-gesture, face and body tracking — all on-device." else "Couldn't open Vision Lab."
            "general", "none" -> "Open question. Answer from your own knowledge. (Phone context, only if relevant: ${s.records.size} scans, kitchen score ${s.kitchenScore()?.first ?: "none yet"}.)"
            else -> "General conversation. Scans on phone: ${s.records.size}. Kitchen score: ${s.kitchenScore()?.first ?: "none yet"}."
        }
    }

    fun chatPrompt(question: String, call: ToolCall, facts: String, lang: Lang, context: String = ""): String {
        // Small on-device models write Hindi poorly in Devanagari; Hinglish (Latin letters) stays accurate.
        val language = when (lang) {
            Lang.HI -> "simple Hinglish — Hindi written in English letters, e.g. \"Aapka kitchen score 62 hai, doodh mein detergent mila hai\""
            Lang.KN, Lang.TE, Lang.TA -> "simple English"
            Lang.EN -> "English"
        }
        if (call.name in openKnowledge) {
            val style = if (call.name == "recipe")
                """Write a complete, practical home recipe for an Indian family kitchen:
a one-line intro, "Serves", an "Ingredients" list (at most 12 items, each listed once, with quantities), numbered "Steps" (at most 8, with cooking times in minutes), and one "Food-safety tip" at the end.
Format with markdown: "## Ingredients" and "## Steps" headings, "- " bullets, "1. " numbered steps, **bold** for key words. Never repeat a line. Stop after the tip."""
            else "Answer helpfully and accurately from your own knowledge. Keep it clear and well organised; use short paragraphs, or markdown dash bullets and **bold** when that helps. Never repeat yourself. For simple chat, reply in 1 to 2 friendly sentences."
            val general = """You are Shuddh AI, a capable, friendly on-device assistant running fully offline on the user's phone.
You can help with anything a good general assistant does: recipes and cooking, nutrition, health and wellness basics, science, maths explanations, writing messages and stories, translation, study help, travel, and everyday advice.
Never refuse an ordinary, harmless request and never say you are only a food-safety assistant. If something truly needs a doctor or the internet, say so briefly after helping as much as you can.
Answer in $language. $style"""
            // Earlier turns are included only for genuine follow-ups, so an old topic can't leak into a new answer.
            return LocalLlm.chatml(general, if (context.isNotBlank() && isFollowUp(question)) "Earlier conversation:\n$context\n\nNow answer: $question" else question)
        }
        val system = """You are Shuddh, a warm, concise food-safety assistant inside an offline phone laboratory used by Indian families.
Answer in $language, in 2 to 4 short sentences. Use ONLY the facts provided. Do not invent numbers, names or results.
Use recent conversation only to resolve a clear follow-up; never let it override the current question or run an action without the user's request.
If the facts don't answer the question, say so honestly and suggest which Shuddh instrument to use.
If something is unsafe, give one clear action (boil, discard, complain to FSSAI, use another source).
Detergent, urea, starch or sugar syrup found in food means it was adulterated: say do not consume it and report the vendor — never suggest cleaning or washing it."""
        return LocalLlm.chatml(system, "Question: $question\nTool used: ${call.name}\nFacts from this phone: $facts" + if (context.isNotBlank()) "\nRecent local conversation:\n$context" else "")
    }
}
