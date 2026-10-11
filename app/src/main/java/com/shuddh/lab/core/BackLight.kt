package com.shuddh.lab.core

import android.content.Context
import android.util.Log

/**
 * iQOO / vivo back RGB light ("halo" around the rear camera), driven through the system's
 * VivoLightManager (vivo-framework). A constant-colour segment is played as a camera fill light,
 * so the rear camera can measure a sample under red, green, blue or white light from the phone itself.
 * Everything is reflective and guarded: on other phones [available] is false and nothing happens.
 */
object BackLight {
    private const val TAG = "BackLight"
    /** vivo light types: 500 = camera fill light (LIGHT_TYPE_PHOTO_FLASH). */
    const val TYPE_PHOTO_FLASH = 500

    private val mgr: Any? by lazy {
        // Exempt only vivo's light classes from the non-SDK API block, for this app's process.
        if (android.os.Build.VERSION.SDK_INT >= 28) runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Lcom/vivo/framework/vivolight/", "Lvivo/app/vivolight/") }
        runCatching { Class.forName("com.vivo.framework.vivolight.VivoLightManager").getMethod("getInstance").invoke(null) }
            .onFailure { Log.w(TAG, "no VivoLightManager: $it") }.getOrNull()
    }
    @Volatile private var lastId = -1
    /**
     * Which byte slot (0 = bits 16–23, 1 = 8–15, 2 = 0–7) actually drives red, green and blue on this
     * phone's ring, as measured by the rear camera ([measuredMap]). Identity until checked.
     */
    @Volatile var map: IntArray = intArrayOf(0, 1, 2)
    /** True once a camera check showed the ring can make distinct colours; false if it is single-colour. */
    @Volatile var multicolour: Boolean? = null
    /** Light type + preview flag that the camera check found actually lights the ring on this phone. */
    /** 502 = "mood" light: one of the types with a colour palette on iQOO (500, the photo flash, has none). */
    @Volatile var type: Int = 502
    @Volatile var preview: Boolean = false
    /** Colour byte format the ring hardware accepts: 0 = ARGB, 1 = RGB with alpha 0, 2 = R/B swapped. */
    @Volatile var encoding: Int = 0
    /** How to drive the ring: -1 = constant-colour segment; ≥0 = vivo prebaked effect id with our colour. */
    @Volatile var effect: Int = -1
    /** Segment duration. 0 would be a zero-length segment that plays nothing — ask for a long one, stop with [off]. */
    const val HOLD_MS = 60_000

    fun encode(argb: Int, enc: Int): Int = when (enc) {
        1 -> argb and 0x00FFFFFF
        2 -> (argb and 0xFF00FF00.toInt()) or ((argb shr 16) and 0xFF) or ((argb and 0xFF) shl 16)
        else -> argb or 0xFF000000.toInt()
    }
    /** Candidates tried by the camera check: camera fill light, incoming call, notification, mood, plus preview variants. */
    val candidates = listOf(502 to false, 400 to false, 710 to false, 502 to true, 300 to false, TYPE_PHOTO_FLASH to false)

    fun loadMap(p: Prefs) {
        p.json("ring_map")?.let { o -> runCatching { map = IntArray(3) { o.getJSONArray("m").getInt(it) }; multicolour = o.optBoolean("multi", true); type = o.optInt("type", 502); preview = o.optBoolean("preview", false); encoding = o.optInt("enc", 0)
            // Old setups: photo-flash type has no colour palette; alpha-0 colours (format 1) light nothing.
            if (type == TYPE_PHOTO_FLASH || encoding == 1) { type = 502; encoding = 0; preview = false; multicolour = null }
            effect = o.optInt("effect", -1)
        } }
    }
    fun saveMap(p: Prefs) = p.putJson("ring_map", org.json.JSONObject().put("m", org.json.JSONArray(map.toList())).put("multi", multicolour ?: true).put("type", type).put("preview", preview).put("enc", encoding).put("effect", effect))

    /** Raw ARGB with each requested colour channel moved into the slot that really drives it. */
    fun mapped(argb: Int): Int {
        val c = intArrayOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        var out = argb and 0xFF000000.toInt()
        for (i in 0..2) out = out or (c[i] shl (16 - 8 * map[i]))
        return out
    }

    /** From a 3×3 response (slot → camera R,G,B rise on white paper), derive [map]. Returns a human summary. */
    fun learn(resp: Array<DoubleArray>): String {
        val names = listOf("red", "green", "blue")
        val dom = resp.map { r -> r.indices.maxByOrNull { r[it] }!! }
        val strength = resp.map { it.maxOrNull() ?: 0.0 }
        if (strength.all { it < 3 }) { multicolour = null; return "Ring light not seen by the camera — hold the phone 8–15 cm over white paper, in a dimmer spot." }
        if (dom.toSet().size < 3) {
            multicolour = false; map = intArrayOf(0, 1, 2)
            return "This ring shows mostly ${names[dom.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key]} — it is used as one extra light, not three colours."
        }
        multicolour = true
        // requested colour c → the slot whose light the camera saw as c
        map = IntArray(3) { c -> dom.indexOf(c) }
        return "Ring colours checked: " + (0..2).joinToString(" · ") { s -> "slot ${s + 1} → ${names[dom[s]]}" } + if (map.contentEquals(intArrayOf(0, 1, 2))) " (standard order)" else " (order corrected)"
    }
    var lastError: String? = null; private set

    fun available(): Boolean = mgr?.let { m -> runCatching { m.javaClass.getMethod("hasLight").invoke(m) as Boolean }.getOrDefault(false) } ?: false

    /** Light the back ring in [argb] for [durationMs] (0 = until [off]). Returns true if the system accepted it. */
    fun set(ctx: Context, argb: Int, durationMs: Int = 0): Boolean = setRaw(ctx, mapped(argb), durationMs)

    /** Sends [argb] to the ring exactly as given (no channel mapping), with the checked light type unless overridden. */
    fun setRaw(ctx: Context, argb0: Int, durationMs0: Int = 0, type: Int = this.type, preview: Boolean = this.preview, strength: Int = 100, enc: Int = this.encoding, effect: Int = this.effect): Boolean {
        val argb = encode(argb0, enc)
        val durationMs = if (durationMs0 <= 0) HOLD_MS else durationMs0
        val m = mgr ?: run { lastError = "VivoLightManager not found"; return false }
        return runCatching {
            off(ctx)
            val rec = Class.forName("vivo.app.vivolight.VivoLightRecord")
                .getConstructor(Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(0, ctx.packageName, type, preview, false, durationMs)
            val seg = if (effect < 0) Class.forName("vivo.app.vivolight.ConstantDurSegment")
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(argb, durationMs, strength)
            else {
                // Prebaked hardware effect (the path vivo's own settings use), coloured with our colour.
                val colors = android.util.SparseArray<Int>().apply { put(0, argb) }
                Class.forName("vivo.app.vivolight.PrebakeSegment")
                    .getConstructor(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, android.util.SparseArray::class.java)
                    .newInstance(effect, false, durationMs, strength, colors)
            }
            rec.javaClass.getMethod("addSegment", Class.forName("vivo.app.vivolight.SegmentBase")).invoke(rec, seg)
            val id = m.javaClass.getMethod("startLight", rec.javaClass).invoke(m, rec) as Int
            lastId = id
            Log.w(TAG, "startLight type=$type preview=$preview effect=$effect dur=$durationMs argb=${Integer.toHexString(argb)} → id=$id")
            lastError = if (id < 0) "startLight returned $id" else null
            id >= 0
        }.getOrElse { lastError = "${it.javaClass.simpleName}: ${it.cause?.message ?: it.message}"; Log.w(TAG, "set failed", it); false }
    }

    /** Stops our light. Works across restarts too: stops this app's camera-fill light by type + package. */
    fun off(ctx: Context? = null) {
        val m = mgr ?: return
        val id = lastId
        if (id >= 0) runCatching { m.javaClass.getMethod("stopLightById", Int::class.javaPrimitiveType).invoke(m, id) }
        ctx?.let { c -> (candidates.map { it.first }.distinct() + (1000..1010)).forEach { t -> runCatching { m.javaClass.getMethod("stopLightByLightType", Int::class.javaPrimitiveType, String::class.java).invoke(m, t, c.packageName) } } }
        lastId = -1
    }

    /** Diagnostic: what the vivo light service reports about scenes, effects and supported colours. */
    fun probe(): String {
        val m = mgr ?: return "no VivoLightManager"
        fun call(name: String, vararg a: Int): Any? = runCatching {
            m.javaClass.methods.first { it.name == name && it.parameterTypes.size == a.size }.invoke(m, *a.toTypedArray())
        }.getOrElse { "ERR ${it.javaClass.simpleName}" }
        val sb = StringBuilder()
        sb.append("hasLight=${call("hasLight")} lightType=${call("getLightType")} lightCase=${call("getLightCase")} breathMode=${call("getBreathingLightOpenMode")}\n")
        for (t in listOf(300, 400, 500, 502, 600, 601, 602, 603, 700, 710, 800, 810)) {
            val eff = call("getSceneEffectType", t)
            val e = (eff as? Int) ?: 0
            val sub = call("getSceneEffectSubType", t, e)
            val s2 = (sub as? Int) ?: 0
            sb.append("type $t eff=$eff sub=$sub colors=${call("getSceneSupportColors", t, e, s2)} select=${call("getSceneSelectColor", t, e, s2)}\n")
        }
        for (i in 0..3) sb.append("anim[$i]=${call("getAnimationInfo", i)}\n")
        return sb.toString()
    }

    /**
     * Game-light route: colours are chosen by INDEX into the halo palette (0 white, 1 orange, 2 yellow, 3 red,
     * 4 green, 5 teal, 6 blue, 7 purple, 8 pink) through vivo's game JSON config.
     */
    fun setGame(ctx: Context, colorIdx: Int, id: Int = 1001, type: Int = 1, subtype: Int = 0, times: Int = 0): Boolean {
        val m = mgr ?: run { lastError = "VivoLightManager not found"; return false }
        val json = org.json.JSONObject().put("game", org.json.JSONObject().put("id", id).put("subId", 0).put("type", type)
            .put("defaultSubtype", subtype).put("defaultColorIdx", colorIdx).put("customSubtype", subtype).put("customColorIdx", colorIdx)).toString()
        return runCatching {
            off(ctx)
            val r = if (times > 0) m.javaClass.getMethod("startLightForGameByTimes", String::class.java, String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(m, ctx.packageName, json, times, false)
                    else m.javaClass.getMethod("startLightForGame", String::class.java, String::class.java).invoke(m, ctx.packageName, json)
            val id2 = (r as? Int) ?: -1
            lastId = id2
            Log.w(TAG, "startLightForGame $json → $id2")
            id2 >= 0
        }.getOrElse { lastError = "${it.javaClass.simpleName}: ${it.cause?.message ?: it.message}"; Log.w(TAG, "game failed", it); false }
    }
}
