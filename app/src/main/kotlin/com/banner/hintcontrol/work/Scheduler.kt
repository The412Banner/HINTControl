package com.banner.hintcontrol.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.banner.hintcontrol.data.AppSettings
import com.banner.hintcontrol.data.RebootSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object RebootTimes {
    /** Next time after [now] that matches the schedule, or null when it is off or has no days. */
    fun next(schedule: RebootSchedule, now: Instant, zone: ZoneId): Instant? {
        if (!schedule.enabled || schedule.days.isEmpty()) return null
        val start = ZonedDateTime.ofInstant(now, zone)
        for (offset in 0..7) {
            val day = start.toLocalDate().plusDays(offset.toLong())
            if (day.dayOfWeek.value !in schedule.days) continue
            val candidate = day.atTime(schedule.hour, schedule.minute).atZone(zone)
            if (candidate.toInstant().isAfter(now)) return candidate.toInstant()
        }
        return null
    }
}

class Scheduler(private val context: Context) {
    private val work get() = WorkManager.getInstance(context)

    fun apply(settings: AppSettings) {
        scheduleMonitor(settings.backgroundMinutes)
        scheduleUpdateCheck(settings.autoUpdateCheck)
        scheduleReboot(settings.rebootSchedule)
    }

    fun scheduleMonitor(minutes: Int) {
        if (minutes <= 0) {
            work.cancelUniqueWork(MONITOR)
            return
        }
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(MONITOR)
            .build()
        work.enqueueUniquePeriodicWork(MONITOR, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun scheduleUpdateCheck(enabled: Boolean) {
        if (!enabled) {
            work.cancelUniqueWork(UPDATES)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(UPDATES, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun nextReboot(schedule: RebootSchedule): Instant? = RebootTimes.next(schedule, Instant.now(), ZoneId.systemDefault())

    fun scheduleReboot(schedule: RebootSchedule) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = rebootIntent()
        am.cancel(pi)
        val at = nextReboot(schedule) ?: return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        }
    }

    private fun rebootIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, RebootAlarmReceiver::class.java).setAction(RebootAlarmReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val MONITOR = "monitor"
        const val UPDATES = "update-check"
    }
}
