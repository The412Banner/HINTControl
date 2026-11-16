package com.banner.hintcontrol.data

import android.util.Log
import com.banner.hintcontrol.gateway.model.MainData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class LiveState(
    val profileId: String? = null,
    val main: MainData? = null,
    val updatedAt: Long? = null,
    val error: Throwable? = null,
    val refreshing: Boolean = false,
) {
    val offline: Boolean get() = error?.isOffline == true
}

/**
 * Polls the gateway's status while anything on screen collects [state]. The interval comes from
 * settings; [setFastMode] switches to one reading per second (placement helper).
 */
class StatusRepository(
    scope: CoroutineScope,
    private val session: GatewaySession,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val summaries: SummaryStore,
    private val onSummary: () -> Unit,
) {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val fast = MutableStateFlow(false)
    private var lastSummaryPush = 0L

    init {
        scope.launch {
            combine(
                _state.subscriptionCount.map { it > 0 }.distinctUntilChanged(),
                session.active.map { it?.id }.distinctUntilChanged(),
                settings.settings.map { it.refreshSeconds }.distinctUntilChanged(),
                fast,
            ) { watched, profileId, seconds, isFast -> Triple(watched, profileId, if (isFast) 1 else seconds) }
                .collectLatest { (watched, profileId, seconds) ->
                    if (_state.value.profileId != profileId) _state.value = LiveState(profileId = profileId)
                    if (!watched || profileId == null) return@collectLatest
                    while (true) {
                        refreshOnce()
                        if (seconds <= 0) {
                            refreshRequests.first()
                        } else {
                            withTimeoutOrNull(seconds * 1000L) { refreshRequests.first() }
                        }
                    }
                }
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        refreshRequests.tryEmit(Unit)
    }

    fun setFastMode(on: Boolean) {
        fast.value = on
    }

    private suspend fun refreshOnce() {
        val profile = session.active.value ?: return
        _state.update { it.copy(refreshing = true) }
        try {
            val main = session.status { it.mainData() }
            val now = System.currentTimeMillis()
            _state.value = LiveState(profile.id, main, now, null, false)
            val s = settings.settings.value
            // History and widget data are extras; a problem there must not look like a gateway error.
            runCatching { history.observe(profile.id, main, record = s.recordHistory, minIntervalMs = FOREGROUND_RECORD_MS, now = now) }
                .onFailure { Log.w(TAG, "history: ${it.javaClass.simpleName}: ${it.message}") }
            runCatching { summaries.save(main.toSummary(profile.name, now)) }
            if (now - lastSummaryPush > 60_000) {
                lastSummaryPush = now
                onSummary()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e, refreshing = false) }
            if (e.isOffline) summaries.markOffline(profile.name)
        }
    }

    companion object {
        private const val TAG = "StatusRepository"

        /** While the app is open, store at most one history sample per 30 s. */
        const val FOREGROUND_RECORD_MS = 30_000L
    }
}
