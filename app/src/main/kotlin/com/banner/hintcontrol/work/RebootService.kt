package com.banner.hintcontrol.work

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.banner.hintcontrol.HintApp
import com.banner.hintcontrol.data.RebootPhase
import com.banner.hintcontrol.data.db.EventType
import com.banner.hintcontrol.data.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Runs one reboot (scheduled or from an automation app) in the foreground so it isn't cut off. */
class RebootService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = HintApp.container(this)
        val scheduled = intent?.getBooleanExtra(EXTRA_SCHEDULED, false) == true
        val notification = c.notifier.build(Notifier.Channel.Work, "Rebooting the gateway", "Sending the reboot request…")
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, Notifier.ID_SERVICE, notification, type)
        } catch (e: Exception) {
            Log.w("RebootService", "startForeground failed, using WorkManager: ${e.javaClass.simpleName}")
            RebootWorker.enqueue(this, scheduled)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                runReboot(this@RebootService, scheduled)
            } finally {
                ServiceCompat.stopForeground(this@RebootService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    @Deprecated("Android 14 variant")
    override fun onTimeout(startId: Int) {
        stopSelf(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SCHEDULED = "scheduled"

        fun start(context: Context, scheduled: Boolean) {
            val intent = Intent(context, RebootService::class.java).putExtra(EXTRA_SCHEDULED, scheduled)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                RebootWorker.enqueue(context, scheduled)
            }
        }

        suspend fun runReboot(context: Context, scheduled: Boolean) {
            val c = HintApp.container(context)
            val name = c.profiles.active.value?.name ?: "Gateway"
            // A short service may run about 3 minutes; stop waiting a little before that.
            val result = c.reboots.run(maxWaitMs = 150_000, eventType = if (scheduled) EventType.ScheduledReboot else EventType.RebootSent) {}
            val (title, text) = when (result) {
                is RebootPhase.BackOnline -> "$name rebooted" to if (result.downtimeMs > 0) "Back online after ${formatDuration(result.downtimeMs)}." else "Back online."
                is RebootPhase.StillRestarting -> "$name is restarting" to "The reboot was sent; the gateway wasn't back yet when the app stopped waiting."
                is RebootPhase.Failed -> "Reboot failed" to result.message
                else -> "$name reboot" to "Done."
            }
            c.notifier.show(Notifier.ID_SCHEDULED, Notifier.Channel.Reboots, title, text, "events")
            if (scheduled) c.scheduler.scheduleReboot(c.settings.current().rebootSchedule)
        }
    }
}

/** Fallback when a foreground service can't be started. */
class RebootWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        val n = HintApp.container(applicationContext).notifier
            .build(Notifier.Channel.Work, "Rebooting the gateway", "Sending the reboot request…")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            androidx.work.ForegroundInfo(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            androidx.work.ForegroundInfo(Notifier.ID_SERVICE, n)
        }
    }

    override suspend fun doWork(): Result {
        RebootService.runReboot(applicationContext, inputData.getBoolean(RebootService.EXTRA_SCHEDULED, false))
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context, scheduled: Boolean) {
            val request = OneTimeWorkRequestBuilder<RebootWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(androidx.work.workDataOf(RebootService.EXTRA_SCHEDULED to scheduled))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

class RebootAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        RebootService.start(context, scheduled = true)
    }

    companion object {
        const val ACTION = "com.banner.hintcontrol.SCHEDULED_REBOOT"
    }
}

/** Re-arms the reboot alarm and background work after a phone restart or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val c = HintApp.container(context)
                c.scheduler.apply(c.settings.current())
            } finally {
                pending.finish()
            }
        }
    }
}
