package com.shuddh.lab.core

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Daily 9 am pantry nudge: what runs out soon, what expires, and what is cheapest this month. */
class PantryReminder : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { if (enabled(ctx)) schedule(ctx); return }
        if (!enabled(ctx)) return
        val p = PantryStore(ctx)
        val restock = p.restock(1.5)
        val expiring = p.items.filter { it.daysLeft in 0..2 }
        if (restock.isEmpty() && expiring.isEmpty()) return
        val text = listOfNotNull(
            restock.takeIf { it.isNotEmpty() }?.let { r -> "Buy soon: " + r.take(4).joinToString { it.label } },
            expiring.takeIf { it.isNotEmpty() }?.let { e -> "Use first: " + e.take(3).joinToString { it.name } },
        ).joinToString(" · ")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Pantry reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, com.shuddh.lab.MainActivity::class.java).putExtra("screen", "PANTRY"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Pantry check").setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(4201, n) }
    }

    companion object {
        private const val CH = "pantry"
        fun enabled(ctx: Context) = ctx.getSharedPreferences("shuddh_pantry", Context.MODE_PRIVATE).getBoolean("remind", false)

        fun set(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences("shuddh_pantry", Context.MODE_PRIVATE).edit().putBoolean("remind", on).apply()
            if (on) schedule(ctx) else ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx))
        }

        private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, 42, Intent(ctx, PantryReminder::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun schedule(ctx: Context) {
            val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1) }
            ctx.getSystemService(AlarmManager::class.java).setInexactRepeating(AlarmManager.RTC_WAKEUP, c.timeInMillis, AlarmManager.INTERVAL_DAY, pi(ctx))
        }
    }
}
