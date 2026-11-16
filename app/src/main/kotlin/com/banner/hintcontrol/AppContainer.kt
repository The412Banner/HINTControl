package com.banner.hintcontrol

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import com.banner.hintcontrol.data.GatewaySession
import com.banner.hintcontrol.data.FirmwareMemory
import com.banner.hintcontrol.data.HistoryRepository
import com.banner.hintcontrol.data.Observation
import com.banner.hintcontrol.data.ProfileRepository
import com.banner.hintcontrol.data.RebootController
import com.banner.hintcontrol.data.SecretCipher
import com.banner.hintcontrol.data.SettingsRepository
import com.banner.hintcontrol.data.StatusRepository
import com.banner.hintcontrol.data.SpeedTestStore
import com.banner.hintcontrol.data.SummaryStore
import com.banner.hintcontrol.data.appDataStore
import com.banner.hintcontrol.data.db.HistoryDatabase
import com.banner.hintcontrol.gateway.demo.DemoBootTimeStore
import com.banner.hintcontrol.tile.SignalTileService
import com.banner.hintcontrol.update.UpdateRepository
import com.banner.hintcontrol.widget.StatusWidget
import com.banner.hintcontrol.work.Notifier
import com.banner.hintcontrol.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App-wide objects, created once per process. */
class AppContainer(
    val context: Context,
    database: HistoryDatabase? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(context.appDataStore, scope)
    val profiles = ProfileRepository(context.appDataStore, SecretCipher(), scope)
    val database: HistoryDatabase = database ?: HistoryDatabase.create(context)
    val history = HistoryRepository(this.database, object : FirmwareMemory {
        private val prefs = context.getSharedPreferences("firmware", Context.MODE_PRIVATE)
        override fun load(profileId: String): String? = prefs.getString(profileId, null)
        override fun save(profileId: String, version: String) = prefs.edit().putString(profileId, version).apply()
    })
    val session = GatewaySession(scope, profiles, demoBootTime = object : DemoBootTimeStore {
        private val prefs = context.getSharedPreferences("demo", Context.MODE_PRIVATE)
        override fun load(): Long? = prefs.getLong("booted_at", 0L).takeIf { it > 0 }
        override fun save(bootedAt: Long) = prefs.edit().putLong("booted_at", bootedAt).apply()
    })
    val summaries = SummaryStore(context)
    val notifier = Notifier(context)
    val scheduler = Scheduler(context)
    val status = StatusRepository(scope, session, settings, history, summaries, onSummary = ::refreshSurfaces, onObservation = ::notifyFirmware)
    val reboots = RebootController(scope, session, history)
    val updates = UpdateRepository(context, settings)
    val speedTests = SpeedTestStore(context)

    /** Notifies when a gateway's firmware version changed (logged in the event log either way). */
    fun notifyFirmware(gatewayName: String, obs: Observation) {
        val (from, to) = obs.firmwareChanged ?: return
        if (!settings.settings.value.alertFirmware) return
        notifier.show(Notifier.ID_FIRMWARE, Notifier.Channel.Alerts, "$gatewayName firmware updated", "$from → $to", "events")
    }

    /** Asks the home-screen widget and the Quick Settings tile to redraw from the saved summary. */
    fun refreshSurfaces() {
        scope.launch {
            runCatching { StatusWidget.refreshAll(context) }
            runCatching { TileService.requestListeningState(context, ComponentName(context, SignalTileService::class.java)) }
        }
    }
}
