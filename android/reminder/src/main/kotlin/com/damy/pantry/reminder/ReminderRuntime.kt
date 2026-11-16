package com.damy.pantry.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.damy.pantry.data.PantryDatabase
import com.damy.pantry.data.SqliteItemCache
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

const val CHANNEL_ID = "expiry"
const val OPEN_REMINDERS = "open_reminders"

object ReminderRuntime {
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "到期提醒", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
    }

    fun reschedule(context: Context, notifyTime: String?) {
        val time = runCatching { LocalTime.parse(notifyTime ?: "09:00") }.getOrDefault(LocalTime.of(9, 0))
        val now = ZonedDateTime.now(systemZone())
        val next = AlarmMath.nextAlarm(now, time)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pending(context)
        val trigger = next.at.toInstant().toEpochMilli()
        val exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        if (exact) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            alarm.setWindow(AlarmManager.RTC_WAKEUP, trigger, next.windowMillis, pending)
        }
        val work = PeriodicWorkRequestBuilder<CatchUpWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("pantry-catchup", ExistingPeriodicWorkPolicy.UPDATE, work)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
        WorkManager.getInstance(context).cancelUniqueWork("pantry-catchup")
    }

    fun postSummary(context: Context, body: String) {
        if (body.isBlank()) return
        ensureChannel(context)
        val launch = Intent(context, Class.forName("com.damy.pantry.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(OPEN_REMINDERS, true)
        }
        val content = PendingIntent.getActivity(context, 2, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("今日到期提醒")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(41, notification)
        context.getSharedPreferences("pantry-reminder", Context.MODE_PRIVATE)
            .edit().putString("sent", java.time.LocalDate.now(systemZone()).toString()).apply()
    }

    fun sentToday(context: Context): Boolean {
        val sent = context.getSharedPreferences("pantry-reminder", Context.MODE_PRIVATE).getString("sent", null)
        return sent == java.time.LocalDate.now(systemZone()).toString()
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val items = SqliteItemCache(PantryDatabase.open(context)).snapshot()
        val body = summaryText(AppToday.today(), items)
        ReminderRuntime.postSummary(context, body)
        val time = context.getSharedPreferences("pantry", Context.MODE_PRIVATE).getString("notify", "09:00")
        ReminderRuntime.reschedule(context, time)
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val time = context.getSharedPreferences("pantry", Context.MODE_PRIVATE).getString("notify", "09:00")
        ReminderRuntime.reschedule(context, time)
    }
}

class CatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val now = java.time.LocalTime.now(systemZone())
        val notify = runCatching {
            java.time.LocalTime.parse(applicationContext.getSharedPreferences("pantry", Context.MODE_PRIVATE).getString("notify", "09:00"))
        }.getOrDefault(java.time.LocalTime.of(9, 0))
        if (AlarmMath.catchUpDue(now, notify, ReminderRuntime.sentToday(applicationContext))) {
            val items = SqliteItemCache(PantryDatabase.open(applicationContext)).snapshot()
            ReminderRuntime.postSummary(applicationContext, summaryText(AppToday.today(), items))
        }
        return Result.success()
    }
}
