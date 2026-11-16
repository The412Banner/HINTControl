package com.banner.hintcontrol.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.HintApp
import com.banner.hintcontrol.data.AppSettings
import com.banner.hintcontrol.data.GatewayProfile
import com.banner.hintcontrol.data.ProfilesState
import com.banner.hintcontrol.data.RebootSchedule
import com.banner.hintcontrol.data.db.GatewayEvent
import com.banner.hintcontrol.gateway.GatewayAddress
import com.banner.hintcontrol.gateway.GatewayCapabilities
import com.banner.hintcontrol.update.UpdateState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.time.Instant

/** Shared by the More, Settings, Alerts, Gateways, Automation, Schedule and Event log screens. */
class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settings.settings
    val profiles: StateFlow<ProfilesState> = c.profiles.state
    val update: StateFlow<UpdateState> = c.updates.state

    val events: Flow<List<GatewayEvent>> = c.profiles.active.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else c.history.events(p.id)
    }

    val sampleCount: Flow<Int> = c.profiles.active.flatMapLatest { p ->
        if (p == null) flowOf(0) else c.history.sampleCount(p.id)
    }

    fun capabilities(): GatewayCapabilities? = c.session.currentClient()?.capabilities

    fun set(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { c.settings.update(transform) }
    }

    fun setSchedule(schedule: RebootSchedule) = set { it.copy(rebootSchedule = schedule) }

    fun nextReboot(schedule: RebootSchedule): Instant? = c.scheduler.nextReboot(schedule)

    fun canScheduleExact(): Boolean {
        val am = c.context.getSystemService(android.app.AlarmManager::class.java) ?: return true
        return android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun clearEvents() = viewModelScope.launch { c.profiles.active.value?.let { c.history.clearEvents(it.id) } }

    fun clearHistory() = viewModelScope.launch { c.profiles.active.value?.let { c.history.clearHistory(it.id) } }

    fun setActive(id: String) = viewModelScope.launch { c.profiles.setActive(id) }

    fun deleteProfile(id: String) = viewModelScope.launch { c.profiles.delete(id) }

    fun forgetPassword(p: GatewayProfile) = viewModelScope.launch {
        c.profiles.setPassword(p.id, null)
        if (c.profiles.active.value?.id == p.id) c.session.logout(forgetPassword = true)
    }

    fun rename(p: GatewayProfile, name: String, host: String) = viewModelScope.launch {
        c.profiles.save(p.copy(name = name.trim().ifBlank { p.name }, host = if (p.isDemo) p.host else GatewayAddress.normalize(host)), makeActive = false)
    }

    fun newAutomationToken() = set { it.copy(automationToken = randomToken(), automationEnabled = true) }

    fun applySchedules() = viewModelScope.launch { c.scheduler.apply(c.settings.current()) }

    fun crashLog(): String? = HintApp.crashFile(c.context).takeIf { it.exists() }?.readText()

    fun deleteCrashLog() {
        HintApp.crashFile(c.context).delete()
    }

    fun checkUpdates() = viewModelScope.launch { c.updates.check() }

    private fun randomToken(): String {
        val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }
}
