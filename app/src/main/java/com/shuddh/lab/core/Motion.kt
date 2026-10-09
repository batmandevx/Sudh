package com.shuddh.lab.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Fuses the motion sensors every optical instrument relies on:
 *  - linear accelerometer → "steady" gate (frames are only accepted while the phone is still)
 *  - gravity → bubble level (tilt in degrees)
 *  - proximity → wave-to-capture gesture (hand over the top of the phone, no touch)
 *  - ambient light → stray-light warning
 */
class Motion(private val ctx: Context) : SensorEventListener {
    var shake by mutableFloatStateOf(0f); private set
    var tiltX by mutableFloatStateOf(0f); private set
    var tiltY by mutableFloatStateOf(0f); private set
    var lux by mutableFloatStateOf(-1f); private set
    var waves by mutableIntStateOf(0); private set
    var hasProximity by mutableStateOf(false); private set

    /** Accepted-frame gate: RMS linear acceleration over ~0.5 s below 0.12 m/s². */
    @Volatile var steady = true; private set

    private val window = FloatArray(25)
    private var wi = 0
    private var near = false
    private var lastWave = 0L
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun start() {
        listOf(Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY, Sensor.TYPE_PROXIMITY, Sensor.TYPE_LIGHT).forEach { t ->
            sm.getDefaultSensor(t)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                if (t == Sensor.TYPE_PROXIMITY) hasProximity = true
            }
        }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                window[wi++ % window.size] = m * m
                val rms = sqrt(window.average().toFloat())
                shake = rms
                steady = rms < 0.12f
            }
            Sensor.TYPE_GRAVITY -> {
                tiltX = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[2].toDouble())).toFloat()
                tiltY = Math.toDegrees(atan2(e.values[1].toDouble(), e.values[2].toDouble())).toFloat()
            }
            Sensor.TYPE_PROXIMITY -> {
                val isNear = e.values[0] < (e.sensor.maximumRange * 0.5f).coerceAtMost(3f)
                val now = System.currentTimeMillis()
                if (near && !isNear && now - lastWave > 900) { waves++; lastWave = now } // hand passed over and away
                near = isNear
            }
            Sensor.TYPE_LIGHT -> lux = e.values[0]
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
