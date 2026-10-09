package com.shuddh.lab.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shuddh.lab.MainActivity

/** App-wide Guardian state: whether monitoring is on, and a pending "Are you OK?" alert. */
object GuardianState {
    var running by mutableStateOf(false)
    /** Time a fall (or manual trigger) was detected; non-null shows the full-screen check. */
    var alertAt by mutableStateOf<Long?>(null)
    var alertKind by mutableStateOf("fall")
    var lastImpactG by mutableStateOf(0.0)
    fun trigger(kind: String, impact: Double = 0.0) { alertKind = kind; lastImpactG = impact; alertAt = System.currentTimeMillis() }
}

/**
 * Foreground service that keeps the fall detector running while the app is in the background.
 * On a fall it raises a full-screen, high-priority notification that opens Shuddh's "Are you OK?"
 * countdown; the SOS itself is only sent from that screen, after the person fails to respond.
 */
class GuardianService : Service(), SensorEventListener {
    private val detector = FallDetector()
    private lateinit var sm: SensorManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ON, "Guardian monitoring", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Fall alerts", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, CH_ON)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Shuddh Guardian is on")
            .setContentText("Watching for falls · offline SOS ready")
            .setContentIntent(open(0, false))
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ON, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTE_ON, n)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        GuardianState.running = true
        return START_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        detector.push(e.values[0], e.values[1], e.values[2], System.currentTimeMillis())?.let { ev ->
            GuardianState.trigger("fall", ev.impactG)
            Haptics.alarm(this)
            val alert = Notification.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Did you fall? Are you OK?")
                .setContentText("Tap to respond — SOS will be sent automatically in 30 seconds.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(open(1, true), true)
                .setContentIntent(open(1, true))
                .setAutoCancel(true).build()
            getSystemService(NotificationManager::class.java).notify(NOTE_ALERT, alert)
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() {
        runCatching { sm.unregisterListener(this) }
        GuardianState.running = false
        super.onDestroy()
    }

    private fun open(code: Int, alert: Boolean): PendingIntent = PendingIntent.getActivity(
        this, code, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("guardian_alert", alert),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_STOP = "com.shuddh.lab.GUARDIAN_STOP"
        private const val CH_ON = "guardian_on"
        private const val CH_ALERT = "guardian_alert"
        private const val NOTE_ON = 41
        private const val NOTE_ALERT = 42
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, GuardianService::class.java))
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, GuardianService::class.java).setAction(ACTION_STOP)) }
    }
}
