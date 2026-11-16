package com.banner.hintcontrol.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.banner.hintcontrol.HintApp
import com.banner.hintcontrol.data.NetworkInfo
import com.banner.hintcontrol.data.db.EventType
import com.banner.hintcontrol.data.isOffline
import com.banner.hintcontrol.data.toSummary
import com.banner.hintcontrol.gateway.model.flatten
import kotlinx.coroutines.CancellationException

/**
 * Background check: records a signal sample, updates the widget and tile, and raises the
 * alerts the user turned on (offline, restart, weak signal, new device).
 */
class MonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = HintApp.container(applicationContext)
        val profile = c.profiles.active.value ?: return Result.success()
        val settings = c.settings.current()
        val prefs = applicationContext.getSharedPreferences("monitor", Context.MODE_PRIVATE)
        val onHomeNetwork = profile.isDemo || NetworkInfo.onGatewayNetwork(applicationContext, profile.host)

        try {
            val main = c.session.status { it.mainData() }
            val now = System.currentTimeMillis()
            val obs = c.history.observe(profile.id, main, record = settings.recordHistory, minIntervalMs = 60_000, now = now)
            c.summaries.save(main.toSummary(profile.name, now))

            if (prefs.getBoolean(KEY_OFFLINE, false)) {
                prefs.edit().putBoolean(KEY_OFFLINE, false).apply()
                c.history.addEvent(profile.id, EventType.Online)
                if (settings.alertOffline) {
                    c.notifier.show(Notifier.ID_OFFLINE, Notifier.Channel.Alerts, "${profile.name} is back online", "The gateway answers again.")
                }
            }
            if (obs.rebooted && settings.alertReboot) {
                c.notifier.show(Notifier.ID_REBOOT, Notifier.Channel.Alerts, "${profile.name} restarted", "The gateway's uptime went back to zero since the last check.", "events")
            }
            if (settings.alertWeakSignal) checkWeak(c, profile.name, profile.id, obs.sample.nrSinr ?: obs.sample.lteSinr, settings.weakSinrThreshold, prefs)
            if (settings.alertNewDevice && (profile.hasSavedPassword || profile.isDemo)) {
                runCatching {
                    val clients = c.session.authed { it.clients() }.flatten()
                    val fresh = c.history.updateDevices(profile.id, clients, now)
                    fresh.forEach { d ->
                        c.history.addEvent(profile.id, EventType.NewDevice, listOfNotNull(d.name, d.ipv4, d.mac).joinToString(" · "))
                    }
                    if (fresh.isNotEmpty()) {
                        val names = fresh.joinToString { it.name ?: it.ipv4 ?: it.mac ?: "Unknown" }
                        c.notifier.show(Notifier.ID_DEVICE, Notifier.Channel.Devices, if (fresh.size == 1) "New device on your network" else "${fresh.size} new devices on your network", names, "devices")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isOffline && onHomeNetwork) {
                c.summaries.markOffline(profile.name)
                if (!prefs.getBoolean(KEY_OFFLINE, false)) {
                    prefs.edit().putBoolean(KEY_OFFLINE, true).apply()
                    c.history.addEvent(profile.id, EventType.Offline)
                    if (settings.alertOffline) {
                        c.notifier.show(Notifier.ID_OFFLINE, Notifier.Channel.Alerts, "${profile.name} is offline", "The gateway didn't answer, although this phone is on its network.")
                    }
                }
            }
        } finally {
            c.history.prune(settings.historyDays)
            c.refreshSurfaces()
        }
        return Result.success()
    }

    private suspend fun checkWeak(c: com.banner.hintcontrol.AppContainer, name: String, profileId: String, sinr: Int?, threshold: Int, prefs: android.content.SharedPreferences) {
        sinr ?: return
        val weakCount = if (sinr < threshold) prefs.getInt(KEY_WEAK, 0) + 1 else 0
        prefs.edit().putInt(KEY_WEAK, weakCount).apply()
        val lastAlert = prefs.getLong(KEY_WEAK_ALERT, 0)
        val now = System.currentTimeMillis()
        if (weakCount == 2 && now - lastAlert > 6 * 3_600_000L) {
            prefs.edit().putLong(KEY_WEAK_ALERT, now).apply()
            c.history.addEvent(profileId, EventType.WeakSignal, "SINR $sinr dB")
            c.notifier.show(Notifier.ID_WEAK, Notifier.Channel.Alerts, "Weak signal on $name", "SINR is $sinr dB (alert below $threshold dB) on two checks in a row.", "signal")
        }
    }

    private companion object {
        const val KEY_OFFLINE = "offline"
        const val KEY_WEAK = "weak_count"
        const val KEY_WEAK_ALERT = "weak_alert"
    }
}
