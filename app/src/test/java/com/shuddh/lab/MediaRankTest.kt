package com.shuddh.lab

import com.shuddh.lab.core.MediaDoc
import com.shuddh.lab.core.MediaIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaRankTest {
    private fun doc(k: String, labels: List<String>, v: FloatArray, text: String = "", expiry: Long? = null, fssai: String? = null) =
        MediaDoc(k, k, 0, labels, text, fssai, expiry, v)

    @Test fun centeringRemovesSharedComponent() {
        // Every vector shares a big common component; only the small part differs.
        val common = floatArrayOf(5f, 5f, 0f, 0f)
        fun v(a: Float, b: Float) = floatArrayOf(common[0], common[1], a, b)
        val docs = listOf(doc("milk", listOf("milk"), v(1f, 0f)), doc("fruit", listOf("fruit"), v(0f, 1f)), doc("jar", listOf("jar"), v(-1f, -1f)))
        val hits = MediaIndex.rank("dairy", v(0.9f, 0.1f), docs)
        assertEquals("milk", hits.first().doc.key)
    }

    @Test fun expiryQueryBoostsExpiredLabels() {
        val z = floatArrayOf(0f, 0f, 1f)
        val docs = listOf(doc("a", listOf("cat"), z), doc("b", listOf("packet"), z, expiry = 1L), doc("c", listOf("tree"), z))
        assertEquals("b", MediaIndex.rank("expired label", z, docs).first().doc.key)
    }

    @Test fun descriptionHasNoTemplateWords() {
        val d = doc("x", listOf("milk", "carton"), FloatArray(0), text = "toned milk", fssai = "10014011000123")
        assertEquals("milk, carton, toned milk, fssai licence", d.description)
    }
}
