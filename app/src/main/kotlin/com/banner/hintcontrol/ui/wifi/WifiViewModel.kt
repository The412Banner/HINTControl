package com.banner.hintcontrol.ui.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.data.db.EventType
import com.banner.hintcontrol.data.needsLogin
import com.banner.hintcontrol.data.userMessage
import com.banner.hintcontrol.gateway.GatewayCapabilities
import com.banner.hintcontrol.gateway.GatewayKind
import com.banner.hintcontrol.gateway.model.BandConfig
import com.banner.hintcontrol.gateway.model.BandSteeringConfig
import com.banner.hintcontrol.gateway.model.SSIDConfig
import com.banner.hintcontrol.gateway.model.WifiBand
import com.banner.hintcontrol.gateway.model.WifiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class WifiUi(
    val original: WifiConfig? = null,
    val draft: WifiDraft? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val needsLogin: Boolean = false,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val savedMessage: String? = null,
    /** Changes the gateway accepted but didn't keep (read back after saving). */
    val notKept: List<String> = emptyList(),
    val capabilities: GatewayCapabilities? = null,
    val kind: GatewayKind? = null,
) {
    val changes: List<String> get() = WifiChanges.describe(original, draft)
    val warnings: List<WifiChanges.Warning> get() = WifiChanges.warnings(original, draft, kind)
    val config: WifiConfig? get() = draft?.config
}

class WifiViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(WifiUi())
    val ui: StateFlow<WifiUi> = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val client = c.session.currentClient()
                val config = c.session.authed { it.wifiConfig() }
                _ui.update {
                    it.copy(
                        original = config,
                        draft = WifiDraft.of(config),
                        loading = false,
                        needsLogin = false,
                        capabilities = client?.capabilities,
                        kind = client?.kind,
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.userMessage(), needsLogin = e.needsLogin) }
            }
        }
    }

    fun login(password: String, remember: Boolean) {
        viewModelScope.launch {
            _ui.update { it.copy(loginBusy = true, loginError = null) }
            try {
                c.session.login(password, remember)
                _ui.update { it.copy(loginBusy = false, needsLogin = false) }
                load()
            } catch (e: Exception) {
                _ui.update { it.copy(loginBusy = false, loginError = e.userMessage()) }
            }
        }
    }

    private fun edit(change: (WifiDraft) -> WifiDraft) = _ui.update { ui -> ui.draft?.let { ui.copy(draft = change(it), savedMessage = null) } ?: ui }

    fun setRadio(band: WifiBand, on: Boolean) = edit { d -> d.updateBand(band) { it.copy(isRadioEnabled = on) } }
    fun setChannel(band: WifiBand, channel: String) = edit { d -> d.updateBand(band) { it.copy(channel = channel) } }
    fun setWidth(band: WifiBand, width: String) = edit { d -> d.updateBand(band) { it.copy(channelBandwidth = width) } }
    fun setPower(band: WifiBand, power: String) = edit { d -> d.updateBand(band) { it.copy(transmissionPower = power) } }
    fun updateBand(band: WifiBand, config: BandConfig) = edit { d -> d.updateBand(band) { config } }
    fun setBandSteering(on: Boolean) = edit { d -> d.copy(config = d.config.copy(bandSteering = BandSteeringConfig(on))) }
    fun updateNetwork(index: Int, ssid: SSIDConfig) = edit { it.updateSsid(index, ssid) }
    fun addNetwork(ssid: SSIDConfig) = edit { it.addSsid(ssid) }
    fun removeNetwork(index: Int) = edit { it.removeSsid(index) }
    fun discard() = _ui.update { ui -> ui.original?.let { ui.copy(draft = WifiDraft.of(it), saveError = null) } ?: ui }
    fun dismissMessage() = _ui.update { it.copy(saveError = null, savedMessage = null, notKept = emptyList()) }

    fun save() {
        val ui = _ui.value
        val draft = ui.draft ?: return
        val summary = ui.changes
        // App scope: leaving the screen must not cancel a save halfway.
        c.scope.launch {
            _ui.update { it.copy(saving = true, saveError = null) }
            try {
                c.session.authed { it.setWifiConfig(draft.config) }
                // Read the settings back to see what the gateway actually kept: some gateways accept a
                // change and then keep their own value (e.g. the channel). The Wi-Fi may restart, so retry.
                var readBack: WifiConfig? = null
                for (attempt in 1..3) {
                    delay(3_000)
                    readBack = runCatching { c.session.authed { it.wifiConfig() } }.getOrNull()
                    if (readBack != null) break
                }
                val notKept = readBack?.let { WifiChanges.notKept(it, draft.config) }.orEmpty()
                val fresh = readBack ?: draft.config
                val detail = summary.joinToString("; ") + if (notKept.isEmpty()) "" else " (not kept: ${notKept.joinToString("; ")})"
                c.profiles.active.value?.let { p -> c.history.addEvent(p.id, EventType.WifiSaved, detail.take(500)) }
                val kept = summary.size - notKept.size
                val message = when {
                    notKept.isEmpty() -> "Saved ${summary.size} change${if (summary.size == 1) "" else "s"}"
                    kept <= 0 -> "The gateway accepted the request but didn't keep the change."
                    else -> "Saved $kept of ${summary.size} changes. The gateway didn't keep the others."
                }
                _ui.update { it.copy(saving = false, original = fresh, draft = WifiDraft.of(fresh), savedMessage = message, notKept = notKept) }
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, saveError = e.userMessage()) }
            }
        }
    }
}
