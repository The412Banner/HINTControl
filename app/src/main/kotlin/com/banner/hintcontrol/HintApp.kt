package com.banner.hintcontrol

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

class HintApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLog()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.scope.launch {
            val first = container.settings.current()
            runCatching { container.scheduler.apply(first) }
            container.settings.settings
                .map { Triple(it.backgroundMinutes, it.autoUpdateCheck, it.rebootSchedule) }
                .distinctUntilChanged()
                .drop(1)
                .collect { runCatching { container.scheduler.apply(container.settings.current()) } }
        }
    }

    /** Keeps the last crash on the device (Settings → Last crash); nothing is uploaded. */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile(this).writeText(
                    "Time: ${Instant.now()}\nVersion: ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})\n" +
                        "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n" +
                        "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nThread: ${thread.name}\n\n" +
                        Log.getStackTraceString(error),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun container(context: Context): AppContainer = (context.applicationContext as HintApp).container

        fun crashFile(context: Context) = File(context.filesDir, "last-crash.txt")
    }
}
