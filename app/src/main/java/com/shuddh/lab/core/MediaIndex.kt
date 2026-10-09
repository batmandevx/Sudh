package com.shuddh.lab.core

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/** One indexed photo or video frame. */
data class MediaDoc(
    val key: String,
    val uri: String,
    val timeMs: Long,
    val labels: List<String>,
    val text: String,
    val fssai: String?,
    val expiry: Long?,
    val vec: FloatArray,
    /** Visual embedding (MobileNet-V3 via MediaPipe) for "similar photos". */
    val img: FloatArray = FloatArray(0),
) {
    /**
     * Content-only description that gets embedded. No template words ("photo of", "text:") —
     * shared boilerplate swamps a small sentence encoder and makes every photo look alike.
     */
    val description: String get() = (labels + listOfNotNull(
        text.take(120).takeIf { it.isNotBlank() },
        "fssai licence".takeIf { fssai != null },
        "expiry date".takeIf { expiry != null },
    )).joinToString(", ").ifBlank { "unknown" }

    fun toJson() = JSONObject().put("k", key).put("u", uri).put("t", timeMs).put("l", JSONArray(labels)).put("x", text)
        .put("f", fssai ?: "").put("e", expiry ?: 0L).put("v", JSONArray(vec.map { it.toDouble() }))
        .put("iv", JSONArray(img.map { it.toDouble() }))

    companion object {
        fun from(o: JSONObject): MediaDoc {
            val l = o.getJSONArray("l"); val v = o.getJSONArray("v")
            return MediaDoc(
                o.getString("k"), o.getString("u"), o.getLong("t"), List(l.length()) { l.getString(it) }, o.getString("x"),
                o.getString("f").ifBlank { null }, o.getLong("e").takeIf { it > 0 }, FloatArray(v.length()) { v.getDouble(it).toFloat() },
                o.optJSONArray("iv")?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } ?: FloatArray(0),
            )
        }
    }
}

data class MediaHit(val doc: MediaDoc, val score: Double, val why: List<String>)

/**
 * On-device semantic media search. Vision (ML Kit labels + OCR) turns pixels into words; the
 * Universal Sentence Encoder (MediaPipe, 6 MB, bundled) turns words into vectors; queries are
 * ranked by cosine similarity plus keyword and food-label boosts.
 */
class MediaIndex(private val ctx: Context) {
    private val file = File(ctx.filesDir, "media_index.json")
    val docs = mutableStateListOf<MediaDoc>()
    var progress by mutableStateOf(0f); private set
    var indexing by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set

    private val embedder: TextEmbedder by lazy {
        TextEmbedder.createFromOptions(
            ctx,
            TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("text_embedder.tflite").build())
                .setL2Normalize(true).build(),
        )
    }

    private val imageEmbedder by lazy {
        runCatching {
            com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder.createFromOptions(
                ctx,
                com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("image_embedder.tflite").build())
                    .setL2Normalize(true).build(),
            )
        }.getOrNull()
    }

    fun embedImage(b: Bitmap): FloatArray = runCatching {
        val argb = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
        imageEmbedder!!.embed(com.google.mediapipe.framework.image.BitmapImageBuilder(argb).build()).embeddingResult().embeddings()[0].floatEmbedding()
    }.getOrDefault(FloatArray(0))

    /** Photos that look most like [d] (visual embedding cosine). */
    fun similar(d: MediaDoc, top: Int = 8): List<Pair<MediaDoc, Double>> =
        if (d.img.isEmpty()) emptyList() else docs.filter { it.key != d.key && it.img.size == d.img.size }
            .map { it to cosine(d.img, it.img) }.sortedByDescending { it.second }.take(top)

    init {
        runCatching {
            if (file.exists()) JSONArray(file.readText()).let { a -> for (i in 0 until a.length()) docs.add(MediaDoc.from(a.getJSONObject(i))) }
        }
    }

    private fun save() = file.writeText(JSONArray().apply { docs.forEach { put(it.toJson()) } }.toString())

    fun embed(text: String): FloatArray = embedder.embed(text).embeddingResult().embeddings()[0].floatEmbedding()

    suspend fun describe(bmp: Bitmap, key: String, uri: String, timeMs: Long): MediaDoc {
        val f = Vision.analyse(bmp)
        val d = MediaDoc(key, uri, timeMs, f.labels.map { it.first.lowercase() }, f.text.replace('\n', ' ').take(400), f.lens.fssai, f.lens.expiry, FloatArray(0))
        return withContext(Dispatchers.Default) { d.copy(vec = embed(d.description), img = embedImage(bmp)) }
    }

    /** Indexes the newest [limit] gallery photos not yet indexed. */
    suspend fun indexGallery(limit: Int = 200, onDoc: (MediaDoc) -> Unit = {}) {
        if (indexing) return
        indexing = true; progress = 0f
        try {
            val uris = withContext(Dispatchers.IO) {
                val out = mutableListOf<Pair<Uri, Long>>()
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
                    null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC",
                )?.use { c ->
                    while (c.moveToNext() && out.size < limit) {
                        out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) to c.getLong(1)
                    }
                }
                out
            }
            val have = docs.map { it.key }.toHashSet()
            val todo = uris.filter { it.first.toString() !in have }
            status = if (todo.isEmpty()) "Index up to date · ${docs.size} photos" else "Indexing ${todo.size} new photos on-device…"
            todo.forEachIndexed { i, (uri, taken) ->
                val bmp = withContext(Dispatchers.IO) { runCatching { thumbnail(uri, 768) }.getOrNull() }
                if (bmp != null) {
                    val d = runCatching { describe(bmp, uri.toString(), uri.toString(), taken) }.getOrNull()
                    if (d != null) { docs.add(0, d); onDoc(d) }
                }
                progress = (i + 1f) / todo.size
                if (i % 10 == 9) withContext(Dispatchers.IO) { save() }
            }
            withContext(Dispatchers.IO) { save() }
            status = "Indexed ${docs.size} photos · fully on-device"
        } finally {
            indexing = false
        }
    }

    fun thumbnail(uri: Uri, px: Int): Bitmap =
        if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
        else @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(ctx.contentResolver, uri)

    fun clear() { docs.clear(); file.delete() }

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size || a.isEmpty()) return 0.0
            var d = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0.0 || nb == 0.0) 0.0 else d / sqrt(na * nb)
        }

        private val stop = setOf("a", "an", "the", "of", "with", "photo", "photos", "show", "me", "find", "my", "in", "on", "and", "picture", "pictures", "image", "images", "where", "when", "is", "are")

        /** Food-domain query expansion: everyday words → what the on-device labeller actually says. */
        val synonyms = mapOf(
            "dairy" to "milk, yogurt, cheese, butter, curd, paneer, dairy product",
            "milk" to "milk, carton, packet, dairy product, bottle",
            "sweet" to "honey, sugar, dessert, candy, jaggery, cake, sweets",
            "honey" to "honey, jar, bottle, syrup",
            "produce" to "fruit, vegetable, banana, apple, tomato, produce",
            "fruit" to "fruit, banana, apple, mango, orange, grape",
            "vegetable" to "vegetable, tomato, potato, onion, leaf vegetable",
            "drink" to "bottle, water, juice, beverage, drink, glass",
            "water" to "water, bottle, glass, tap, drinking water",
            "spice" to "spice, chilli, pepper, turmeric, masala, seasoning",
            "packet" to "packaging, package, box, carton, bag, label, packet",
            "label" to "label, text, packaging, sticker",
            "receipt" to "paper, text, receipt, bill, document",
            "meat" to "meat, chicken, fish, egg",
            "grain" to "rice, wheat, flour, atta, dal, lentil, grain",
            "oil" to "oil, ghee, bottle, cooking oil",
            "people" to "person, people, smile, selfie, crowd",
            "kitchen" to "kitchen, cooking, stove, utensil, pan",
        )

        fun expand(query: String): String {
            val q = query.lowercase()
            val extra = synonyms.filterKeys { k -> Regex("\\b$k").containsMatchIn(q) }.values
            return (listOf(query) + extra).joinToString(", ")
        }

        /** Semantic + keyword + food-label ranking. [qv] is the query embedding. */
        fun rank(query: String, qv: FloatArray, docs: List<MediaDoc>, top: Int = 30): List<MediaHit> {
            val q = query.lowercase()
            val words = Regex("[a-z0-9\\u0900-\\u097F]+").findAll(q).map { it.value }.filter { it !in stop && it.length > 1 }.toList()
            val wantsExpiry = Regex("expir|best before|use by|date").containsMatchIn(q)
            val wantsFssai = Regex("fssai|licen[cs]e").containsMatchIn(q)
            val now = System.currentTimeMillis()
            // Common-component removal: subtract the collection's mean vector so similarity is driven
            // by what makes each photo different (raised accuracy from 1/5 to 5/5 in on-device tests).
            val dim = qv.size
            val mean = if (docs.size >= 3) FloatArray(dim) { i -> docs.sumOf { (it.vec.getOrNull(i) ?: 0f).toDouble() }.toFloat() / docs.size } else FloatArray(dim)
            fun center(v: FloatArray) = FloatArray(dim) { (v.getOrNull(it) ?: 0f) - mean[it] }
            val q0 = center(qv)
            val df = words.associateWith { w -> docs.count { (it.labels.joinToString(" ") + " " + it.text).lowercase().contains(w) } }
            return docs.map { d ->
                val why = mutableListOf<String>()
                var s = cosine(q0, center(d.vec))
                val hay = (d.labels.joinToString(" ") + " " + d.text).lowercase()
                // Rarity-weighted keyword overlap: rare words (e.g. "fssai") count more than common ones.
                val hits = words.filter { hay.contains(it) }
                if (hits.isNotEmpty()) {
                    s += hits.sumOf { w -> 0.06 * (1 + kotlin.math.ln(docs.size.toDouble() / (1 + (df[w] ?: 0)))).coerceAtLeast(0.3) }
                    why += "matches \"${hits.joinToString()}\""
                }
                if (wantsExpiry && d.expiry != null) {
                    s += 0.3; why += if (d.expiry < now) "EXPIRED label" else "expiry date found"
                    if (q.contains("expired") && d.expiry < now) s += 0.3
                }
                if (wantsFssai && d.fssai != null) { s += 0.3; why += "FSSAI ${d.fssai}" }
                MediaHit(d, s, why)
            }.sortedByDescending { it.score }.take(top)
        }
    }
}
