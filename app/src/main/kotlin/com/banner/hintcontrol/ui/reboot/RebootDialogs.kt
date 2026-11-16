package com.banner.hintcontrol.ui.reboot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.banner.hintcontrol.data.RebootPhase
import com.banner.hintcontrol.data.formatDuration
import kotlinx.coroutines.delay

@Composable
fun RebootConfirmDialog(gatewayName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.RestartAlt, null) },
        title = { Text("Reboot $gatewayName?") },
        text = { Text("Internet and Wi-Fi drop for a few minutes while the gateway restarts.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Reboot", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RebootProgressDialog(phase: RebootPhase, onDismiss: () -> Unit) {
    if (phase == RebootPhase.Idle) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase) {
        while (phase.busy) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!phase.busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !phase.busy, dismissOnBackPress = !phase.busy),
        icon = {
            when (phase) {
                is RebootPhase.BackOnline -> Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                is RebootPhase.Failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Outlined.RestartAlt, null)
            }
        },
        title = {
            Text(
                when (phase) {
                    RebootPhase.Sending -> "Sending reboot…"
                    is RebootPhase.GoingDown -> "Waiting for the gateway to restart…"
                    is RebootPhase.Restarting -> "Gateway is restarting"
                    is RebootPhase.BackOnline -> "Back online"
                    is RebootPhase.Failed -> "Reboot failed"
                    is RebootPhase.StillRestarting -> "Still restarting"
                    RebootPhase.Idle -> ""
                },
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (phase) {
                    is RebootPhase.Restarting -> Text("Offline for ${formatDuration(now - phase.offlineSince)}. This usually takes 1–3 minutes.")
                    is RebootPhase.GoingDown -> Text("Sent ${formatDuration(now - phase.since)} ago.")
                    is RebootPhase.BackOnline -> Text(if (phase.downtimeMs > 0) "The gateway was offline for ${formatDuration(phase.downtimeMs)}." else "The gateway restarted.")
                    is RebootPhase.Failed -> Text(phase.message)
                    is RebootPhase.StillRestarting -> Text("The reboot was sent but the gateway hasn't answered yet. Check again in a few minutes.")
                    else -> {}
                }
                if (phase.busy) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (!phase.busy) TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}
