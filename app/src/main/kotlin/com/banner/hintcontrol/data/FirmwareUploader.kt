package com.banner.hintcontrol.data

import com.banner.hintcontrol.data.db.EventType
import com.banner.hintcontrol.gateway.FirmwareFile
import com.banner.hintcontrol.gateway.FirmwareUploadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

/** The file the user picked, with what's shown before sending it. */
data class FirmwareInfo(
    val uri: String,
    val name: String,
    val size: Long,
    val sha256: String,
)

enum class FirmwarePhase {
    /** Sending the file. */
    Uploading,

    /** The file was sent; waiting for the gateway to restart and come back. */
    Restarting,

    /** The gateway is back (or never went away) and its firmware version was read. */
    Done,

    /** The gateway didn't come back, or didn't restart, within [FirmwareUploader.WAIT_MS]. */
    NoRestart,

    /** Sending failed; [FirmwareJob.message] says why. */
    Failed,
}

data class FirmwareJob(
    val file: FirmwareInfo,
    val phase: FirmwarePhase,
    val startedAt: Long,
    val sent: Long = 0,
    val before: String? = null,
    val after: String? = null,
    val answer: FirmwareUploadResult? = null,
    val sawOffline: Boolean = false,
    val message: String? = null,
) {
    val active: Boolean get() = phase == FirmwarePhase.Uploading || phase == FirmwarePhase.Restarting
}

/**
 * Sends a firmware file to the active gateway and follows it through the restart. Runs in the app
 * scope, so leaving the screen doesn't stop it. Only offered with Developer options on.
 */
class FirmwareUploader(
    private val scope: CoroutineScope,
    private val session: GatewaySession,
    private val history: HistoryRepository,
    private val onObservation: (gatewayName: String, Observation) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
    private val waitMs: Long = WAIT_MS,
) {
    private val _job = MutableStateFlow<FirmwareJob?>(null)
    val job: StateFlow<FirmwareJob?> = _job.asStateFlow()
    private var running: Job? = null

    fun start(file: FirmwareInfo, open: () -> InputStream) {
        if (running?.isActive == true) return
        running = scope.launch { run(file, open) }
    }

    /** Clears a finished job so a new file can be picked. */
    fun dismiss() {
        if (_job.value?.active != true) _job.value = null
    }

    private suspend fun run(file: FirmwareInfo, open: () -> InputStream) {
        val profile = session.active.value ?: return
        val before = runCatching { session.status { it.mainData() }.device?.softwareVersion }.getOrNull()
        _job.value = FirmwareJob(file, FirmwarePhase.Uploading, startedAt = clock(), before = before)
        val summary = "${file.name}, ${mb(file.size)} MB, SHA-256 ${file.sha256.take(12)}…"
        val answer = try {
            session.authed { client ->
                client.uploadFirmware(FirmwareFile(file.name, file.size, open)) { sent -> _job.update { it?.copy(sent = sent) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.userMessage()
            history.addEvent(profile.id, EventType.FirmwareSendFailed, "$summary: $message".take(500))
            _job.update { it?.copy(phase = FirmwarePhase.Failed, message = message) }
            return
        }
        val how = if (answer == FirmwareUploadResult.Accepted) "gateway accepted it" else "no answer from the gateway"
        history.addEvent(profile.id, EventType.FirmwareSent, "$summary ($how)".take(500))
        _job.update { it?.copy(phase = FirmwarePhase.Restarting, sent = file.size, answer = answer) }

        // Wait for the gateway to go away and come back, then read its firmware version.
        val deadline = clock() + waitMs
        var sawOffline = false
        while (clock() < deadline) {
            delay(pollMs)
            val main = try {
                session.status { it.mainData() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!sawOffline) {
                    sawOffline = true
                    _job.update { it?.copy(sawOffline = true) }
                }
                continue
            }
            val version = main.device?.softwareVersion
            // Feeds the firmware tracking: event log entry and notification when the version changed.
            runCatching { history.observe(profile.id, main, record = false, minIntervalMs = Long.MAX_VALUE) }
                .onSuccess { onObservation(profile.name, it) }
            if (sawOffline || (version != null && before != null && version != before)) {
                _job.update { it?.copy(phase = FirmwarePhase.Done, after = version) }
                return
            }
        }
        val last = runCatching { session.status { it.mainData() }.device?.softwareVersion }.getOrNull()
        _job.update { it?.copy(phase = FirmwarePhase.NoRestart, after = last) }
    }

    companion object {
        const val POLL_MS = 10_000L
        const val WAIT_MS = 20 * 60_000L

        fun mb(bytes: Long): String = "%.1f".format(java.util.Locale.US, bytes / 1_048_576.0)
    }
}
