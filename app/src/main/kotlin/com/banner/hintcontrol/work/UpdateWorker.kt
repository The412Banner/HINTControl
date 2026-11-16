package com.banner.hintcontrol.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.banner.hintcontrol.HintApp

/** Periodic check of this app's GitHub releases; notifies once per new version. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = HintApp.container(applicationContext)
        val settings = c.settings.current()
        if (!settings.autoUpdateCheck) return Result.success()
        val release = c.updates.check() ?: return Result.success()
        val tag = release.version.toString()
        val prefs = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (settings.skippedVersion == tag || prefs.getString("notified", null) == tag) return Result.success()
        prefs.edit().putString("notified", tag).apply()
        c.notifier.show(
            Notifier.ID_UPDATE,
            Notifier.Channel.Updates,
            "Update available: ${release.tag}",
            "Tap to see what's new and install it.",
            "updates",
        )
        return Result.success()
    }
}
