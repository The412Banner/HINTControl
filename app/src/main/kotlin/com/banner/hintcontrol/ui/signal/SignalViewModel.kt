package com.banner.hintcontrol.ui.signal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.data.Access
import com.banner.hintcontrol.data.LiveState
import com.banner.hintcontrol.data.db.SignalSample
import com.banner.hintcontrol.data.needsLogin
import com.banner.hintcontrol.data.userMessage
import com.banner.hintcontrol.gateway.model.BaseCellData
import com.banner.hintcontrol.gateway.model.CellDataRoot
import com.banner.hintcontrol.gateway.signal.Metric
import com.banner.hintcontrol.gateway.signal.SignalQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Tech(val label: String) { NR("5G"), LTE("LTE") }

enum class HistoryRange(val label: String, val ms: Long) {
    Hour("1 h", 3_600_000L),
    SixHours("6 h", 6 * 3_600_000L),
    Day("24 h", 86_400_000L),
    Week("7 d", 7 * 86_400_000L),
    Month("30 d", 30 * 86_400_000L),
}

data class Reading(
    val time: Long,
    val rsrp: Int?,
    val sinr: Int?,
    val rsrq: Int?,
    val rssi: Int?,
    val score: Int?,
)

data class CellState(
    val data: CellDataRoot? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val needsLogin: Boolean = false,
)

data class PlacementState(
    val on: Boolean = false,
    val best: Int? = null,
    val bestAt: Long? = null,
    val sound: Boolean = true,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
)

data class SignalUi(
    val live: LiveState = LiveState(),
    val tech: Tech = Tech.NR,
    val readingsNr: List<Reading> = emptyList(),
    val readingsLte: List<Reading> = emptyList(),
    val placement: PlacementState = PlacementState(),
    val range: HistoryRange = HistoryRange.SixHours,
    val metric: Metric = Metric.SINR,
    val history: List<SignalSample> = emptyList(),
    val recording: Boolean = true,
    val cell: CellState = CellState(),
    val access: Access = Access.Anonymous,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
) {
    fun cell(tech: Tech): BaseCellData? = when (tech) {
        Tech.NR -> live.main?.signal?.fiveG
        Tech.LTE -> live.main?.signal?.fourG
    }?.takeIf { it.rsrp != null || it.sinr != null }

    val availableTechs: List<Tech> get() = Tech.entries.filter { cell(it) != null }
}

private fun BaseCellData.reading(time: Long) = Reading(time, rsrp, sinr, rsrq, rssi, SignalQuality.score(this))

class SignalViewModel(private val c: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(SignalUi())

    private val historyFlow = combine(c.profiles.active, local.distinctUntilChangedBy { it.range }) { p, ui -> p to ui.range }
        .flatMapLatest { (p, range) ->
            if (p == null) flowOf(emptyList()) else c.history.samples(p.id, System.currentTimeMillis() - range.ms)
        }

    val ui: StateFlow<SignalUi> = combine(local, c.status.state, historyFlow, c.session.access, c.settings.settings) { l, live, history, access, s ->
        val merged = l.copy(live = live)
        val t = if (merged.cell(l.tech) == null) merged.availableTechs.firstOrNull() ?: l.tech else l.tech
        merged.copy(
            tech = t,
            history = history,
            access = access,
            recording = s.recordHistory,
            placement = l.placement.copy(sound = s.placementSound, haptics = s.placementHaptics, keepScreenOn = s.keepScreenOn),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SignalUi())

    init {
        viewModelScope.launch {
            c.status.state.distinctUntilChangedBy { it.updatedAt }.collect { live ->
                val t = live.updatedAt ?: return@collect
                val nr = live.main?.signal?.fiveG?.takeIf { it.rsrp != null }?.reading(t)
                val lte = live.main?.signal?.fourG?.takeIf { it.rsrp != null }?.reading(t)
                local.update { ui ->
                    var p = ui.placement
                    val now = if (ui.tech == Tech.NR) nr else lte
                    if (p.on && now?.score != null && (p.best == null || now.score > p.best)) p = p.copy(best = now.score, bestAt = t)
                    ui.copy(
                        readingsNr = (ui.readingsNr + listOfNotNull(nr)).takeLast(MAX_READINGS),
                        readingsLte = (ui.readingsLte + listOfNotNull(lte)).takeLast(MAX_READINGS),
                        placement = p,
                    )
                }
            }
        }
    }

    fun setTech(t: Tech) = local.update { it.copy(tech = t, placement = it.placement.copy(best = null, bestAt = null)) }

    fun setRange(r: HistoryRange) = local.update { it.copy(range = r) }

    fun setMetric(m: Metric) = local.update { it.copy(metric = m) }

    fun setPlacement(on: Boolean) {
        c.status.setFastMode(on)
        local.update { it.copy(placement = it.placement.copy(on = on, best = null, bestAt = null)) }
    }

    fun resetBest() = local.update { it.copy(placement = it.placement.copy(best = null, bestAt = null)) }

    fun setSound(on: Boolean) = viewModelScope.launch { c.settings.update { it.copy(placementSound = on) } }

    fun setHaptics(on: Boolean) = viewModelScope.launch { c.settings.update { it.copy(placementHaptics = on) } }

    fun loadCell() {
        viewModelScope.launch {
            local.update { it.copy(cell = it.cell.copy(loading = true, error = null, needsLogin = false)) }
            try {
                val data = c.session.authed { it.cellData() }
                local.update { it.copy(cell = CellState(data = data)) }
            } catch (e: Exception) {
                local.update { it.copy(cell = it.cell.copy(loading = false, error = e.userMessage(), needsLogin = e.needsLogin)) }
            }
        }
    }

    fun login(password: String, remember: Boolean) {
        viewModelScope.launch {
            local.update { it.copy(loginBusy = true, loginError = null) }
            try {
                c.session.login(password, remember)
                local.update { it.copy(loginBusy = false) }
                loadCell()
            } catch (e: Exception) {
                local.update { it.copy(loginBusy = false, loginError = e.userMessage()) }
            }
        }
    }

    fun clearHistory() = viewModelScope.launch { c.profiles.active.value?.let { c.history.clearHistory(it.id) } }

    fun csv(): String = buildString {
        appendLine("time_utc,time_local,nr_band,nr_rsrp,nr_sinr,nr_rsrq,nr_rssi,nr_cell,lte_band,lte_rsrp,lte_sinr,lte_rsrq,lte_rssi,lte_cell,uptime_s")
        ui.value.history.forEach { s ->
            appendLine(
                listOf(
                    java.time.Instant.ofEpochMilli(s.time).toString(),
                    java.time.Instant.ofEpochMilli(s.time).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString(),
                    s.nrBand, s.nrRsrp, s.nrSinr, s.nrRsrq, s.nrRssi, s.nrCell,
                    s.lteBand, s.lteRsrp, s.lteSinr, s.lteRsrq, s.lteRssi, s.lteCell, s.uptime,
                ).joinToString(",") { it?.toString().orEmpty() },
            )
        }
    }

    override fun onCleared() {
        c.status.setFastMode(false)
        super.onCleared()
    }

    companion object {
        const val MAX_READINGS = 180
    }
}
