package com.banner.hintcontrol.data

import com.banner.hintcontrol.data.db.EventType
import com.banner.hintcontrol.gateway.RebootResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RebootPhase {
    data object Idle : RebootPhase
    data object Sending : RebootPhase
    data class GoingDown(val since: Long) : RebootPhase
    data class Restarting(val offlineSince: Long) : RebootPhase
    data class BackOnline(val downtimeMs: Long) : RebootPhase
    data class Failed(val message: String) : RebootPhase
    data class StillRestarting(val sentAt: Long) : RebootPhase

    val busy: Boolean get() = this is Sending || this is GoingDown || this is Restarting
}

/** Sends a reboot and follows the gateway going down and coming back. */
class RebootController(
    private val scope: CoroutineScope,
    private val session: GatewaySession,
    private val history: HistoryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _phase = MutableStateFlow<RebootPhase>(RebootPhase.Idle)
    val phase: StateFlow<RebootPhase> = _phase.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (_phase.value.busy) return
        job = scope.launch { run(maxWaitMs = 6 * 60_000L) { _phase.value = it } }
    }

    fun dismiss() {
        if (!_phase.value.busy) _phase.value = RebootPhase.Idle
    }

    /** Returns the final phase. [eventType] lets scheduled reboots log their own event. */
    suspend fun run(
        maxWaitMs: Long,
        eventType: EventType = EventType.RebootSent,
        onPhase: (RebootPhase) -> Unit,
    ): RebootPhase {
        val profile = session.active.value ?: return RebootPhase.Failed("No gateway set up").also(onPhase)
        val sentAt = clock()
        onPhase(RebootPhase.Sending)
        val result = try {
            session.authed { it.reboot() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = RebootPhase.Failed(e.userMessage())
            if (eventType == EventType.ScheduledReboot) history.addEvent(profile.id, EventType.ScheduledRebootFailed, e.userMessage())
            onPhase(failed)
            return failed
        }
        history.addEvent(profile.id, eventType, if (result == RebootResult.Unconfirmed) "Connection dropped while sending" else null)
        onPhase(RebootPhase.GoingDown(sentAt))
        val client = session.currentClient() ?: return RebootPhase.StillRestarting(sentAt).also(onPhase)

        // 1) wait for the gateway to stop answering
        var offlineAt: Long? = null
        while (clock() - sentAt < minOf(maxWaitMs, 120_000L)) {
            if (!client.exists()) {
                offlineAt = clock()
                break
            }
            delay(2_000)
        }
        if (offlineAt == null) {
            // Some gateways restart so fast the gap is missed; a low uptime confirms it.
            val uptime = runCatching { client.mainData().time?.upTime }.getOrNull()
            val final = if (uptime != null && uptime < 300) RebootPhase.BackOnline(0) else RebootPhase.Failed("The gateway accepted the request but didn't restart.")
            onPhase(final)
            return final
        }
        onPhase(RebootPhase.Restarting(offlineAt))

        // 2) wait for it to come back
        while (clock() - sentAt < maxWaitMs) {
            delay(3_000)
            if (client.exists() && runCatching { client.mainData() }.isSuccess) {
                val final = RebootPhase.BackOnline(clock() - offlineAt)
                history.addEvent(profile.id, EventType.Online, "Back after ${formatDuration(clock() - offlineAt)}")
                onPhase(final)
                return final
            }
        }
        val final = RebootPhase.StillRestarting(sentAt)
        onPhase(final)
        return final
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}
