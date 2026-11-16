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
    fun dismissMessage() = _ui.update { it.copy(saveError = null, savedMessage = null) }

    fun save() {
        val ui = _ui.value
        val draft = ui.draft ?: return
        val summary = ui.changes
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, saveError = null) }
            try {
                c.session.authed { it.setWifiConfig(draft.config) }
                c.profiles.active.value?.let { p -> c.history.addEvent(p.id, EventType.WifiSaved, summary.joinToString("; ").take(500)) }
                val fresh = runCatching { c.session.authed { it.wifiConfig() } }.getOrNull() ?: draft.config
                _ui.update { it.copy(saving = false, original = fresh, draft = WifiDraft.of(fresh), savedMessage = "Saved ${summary.size} change${if (summary.size == 1) "" else "s"}") }
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, saveError = e.userMessage()) }
            }
        }
    }
}
