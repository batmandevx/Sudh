package com.shuddh.lab.core

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Fix(val lat: Double, val lon: Double, val accuracy: Float, val time: Long)

data class Place(val name: String, val kind: String, val lat: Double, val lon: Double, val metres: Double)

/**
 * Location + orientation for the map. GPS via the platform LocationManager (no Google services),
 * heading from the fused rotation-vector sensor (accelerometer + gyro + magnetometer), and a
 * walking/still state from the linear accelerometer.
 */
class Geo(private val ctx: Context) : SensorEventListener {
    var fix by mutableStateOf<Fix?>(null); private set
    var heading by mutableFloatStateOf(0f); private set
    var moving by mutableStateOf(false); private set
    var tracking by mutableStateOf(false); private set
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = FloatArray(30)
    private var ai = 0
    private var users = 0

    private val listener = LocationListener { l -> accept(l) }

    private fun accept(l: Location) {
        val cur = fix
        // Prefer fresher or more accurate fixes.
        if (cur == null || l.accuracy <= cur.accuracy * 1.5f || l.time - cur.time > 20_000) {
            fix = Fix(l.latitude, l.longitude, l.accuracy, l.time)
        }
    }

    fun hasPermission() = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        users++
        if (tracking || !hasPermission()) return
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER).forEach { p ->
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    lm.getLastKnownLocation(p)?.let { accept(it) }
                    lm.requestLocationUpdates(p, 2000L, 1f, listener, Looper.getMainLooper())
                }
            }
        }
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        tracking = true
    }

    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0 || !tracking) return
        runCatching { lm.removeUpdates(listener) }
        sm.unregisterListener(this)
        tracking = false
    }

    /** Cheap single read for tagging scans. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Fix? = fix ?: if (!hasPermission()) null else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }?.let { Fix(it.latitude, it.longitude, it.accuracy, it.time) }

    private val rot = FloatArray(9)
    private val ori = FloatArray(3)
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                val h = ((Math.toDegrees(ori[0].toDouble()) + 360) % 360).toFloat()
                // Low-pass across the 0/360 seam.
                var d = h - heading
                if (d > 180) d -= 360; if (d < -180) d += 360
                heading = (heading + d * 0.2f + 360) % 360
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                accel[ai++ % accel.size] = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                moving = accel.average() > 1.0 // m/s² — holding a phone still reads ~0.1–0.4
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    companion object {
        private const val R = 6_371_000.0

        fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * R * atan2(sqrt(h), sqrt(1 - h))
        }

        /** Bearing in degrees, 0 = north, clockwise. */
        fun bearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val φ1 = Math.toRadians(aLat); val φ2 = Math.toRadians(bLat); val dλ = Math.toRadians(bLon - aLon)
            val y = sin(dλ) * cos(φ2); val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dλ)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        /**
         * Nearby food & water places from OpenStreetMap (Overpass API). Network is used only here and
         * only when the user has switched the online map on; no scan data is sent — just a lat/lon box.
         */
        suspend fun nearbyPlaces(lat: Double, lon: Double, radius: Int = 2000): List<Place> = withContext(Dispatchers.IO) {
            val q = """[out:json][timeout:12];(
                nwr(around:$radius,$lat,$lon)[shop~"dairy|supermarket|convenience|greengrocer|butcher|bakery|general"];
                nwr(around:$radius,$lat,$lon)[amenity~"drinking_water|marketplace|restaurant|fast_food|cafe"];
                );out center 80;"""
            // 1) Overpass mirrors (fast, complete when available).
            for (m in listOf("https://overpass.private.coffee/api/interpreter", "https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
                val c = (URL(m).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 8_000; readTimeout = 14_000
                    setRequestProperty("User-Agent", UA); setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                try {
                    c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                    if (c.responseCode == 200) parse(c.inputStream.bufferedReader().readText(), lat, lon).takeIf { it.isNotEmpty() }?.let { return@withContext it }
                } catch (_: Exception) {
                } finally { c.disconnect() }
            }
            // 2) Nominatim category phrases (reliable, 1 request/second per its usage policy).
            val d = 0.03 // ≈ 3 km box
            val box = "${lon - d},${lat + d},${lon + d},${lat - d}"
            val out = LinkedHashMap<String, Place>()
            for (phrase in listOf("supermarkets", "dairies", "greengrocers", "convenience stores", "bakeries", "cafes", "restaurants", "fast food", "drinking water", "marketplaces")) {
                runCatching {
                    val url = URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=15&bounded=1&viewbox=$box&q=" + URLEncoder.encode(phrase, "UTF-8"))
                    val c = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 10_000; setRequestProperty("User-Agent", UA) }
                    try {
                        val arr = org.json.JSONArray(c.inputStream.bufferedReader().readText())
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val la = o.getString("lat").toDouble(); val lo = o.getString("lon").toDouble()
                            val kind = o.optString("type").replace('_', ' ')
                            val name = o.optString("name").ifBlank { kind.replaceFirstChar { it.uppercase() } }
                            out.putIfAbsent("$name@${"%.4f".format(la)}", Place(name, kind, la, lo, metres(lat, lon, la, lo)))
                        }
                    } finally { c.disconnect() }
                }
                Thread.sleep(1100)
            }
            if (out.isEmpty()) throw IllegalStateException("No places found nearby on OpenStreetMap")
            out.values.sortedBy { it.metres }
        }

        private const val UA = "Shuddh/4 (food safety lab; Android)"

        private fun parse(body: String, lat: Double, lon: Double): List<Place> {
            val els = JSONObject(body).getJSONArray("elements")
            return List(els.length()) { els.getJSONObject(it) }.mapNotNull { e ->
                val tags = e.optJSONObject("tags") ?: return@mapNotNull null
                val pos = e.optJSONObject("center") ?: e
                if (!pos.has("lat")) return@mapNotNull null
                val kind = tags.optString("shop").ifBlank { tags.optString("amenity") }.replace('_', ' ')
                val name = tags.optString("name").ifBlank { kind.replaceFirstChar { it.uppercase() } }
                val la = pos.getDouble("lat"); val lo = pos.getDouble("lon")
                Place(name, kind, la, lo, metres(lat, lon, la, lo))
            }.sortedBy { it.metres }
        }
    }
}
