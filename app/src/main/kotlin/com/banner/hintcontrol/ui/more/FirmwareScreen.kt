package com.banner.hintcontrol.ui.more

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.data.FirmwareInfo
import com.banner.hintcontrol.data.FirmwareJob
import com.banner.hintcontrol.data.FirmwarePhase
import com.banner.hintcontrol.data.FirmwareUploader
import com.banner.hintcontrol.gateway.FirmwareUploadResult
import com.banner.hintcontrol.ui.components.Format
import com.banner.hintcontrol.ui.components.InfoRow
import com.banner.hintcontrol.ui.components.SectionCard
import com.banner.hintcontrol.ui.components.SubScreen
import com.banner.hintcontrol.ui.nav.LocalAppActions
import com.banner.hintcontrol.ui.nav.containerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

data class FirmwareUi(
    val model: String? = null,
    val firmware: String? = null,
    val picked: FirmwareInfo? = null,
    val reading: Boolean = false,
    val pickError: String? = null,
    val job: FirmwareJob? = null,
)

class FirmwareViewModel(private val c: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(FirmwareUi())
    val ui: StateFlow<FirmwareUi> = combine(local, c.firmware.job) { u, j -> u.copy(job = j) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FirmwareUi())

    init {
        viewModelScope.launch {
            runCatching { c.session.status { it.mainData() } }.onSuccess { m ->
                local.update { it.copy(model = m.device?.displayName ?: m.device?.model, firmware = m.device?.softwareVersion) }
            }
        }
    }

    fun pick(uri: Uri) {
        viewModelScope.launch {
            local.update { it.copy(reading = true, picked = null, pickError = null) }
            try {
                val info = withContext(Dispatchers.IO) { describe(uri) }
                local.update { it.copy(reading = false, picked = info) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(reading = false, pickError = "Couldn't read the file: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Name, size and SHA-256 of the picked file; the size is what was actually read. */
    private fun describe(uri: Uri): FirmwareInfo {
        val resolver = c.context.contentResolver
        var name = uri.lastPathSegment ?: "firmware"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0)?.let { name = it }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val input = resolver.openInputStream(uri) ?: throw IOException("Android couldn't open it")
        input.use {
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                size += n
            }
        }
        if (size == 0L) throw IOException("the file is empty")
        return FirmwareInfo(uri.toString(), name, size, digest.digest().joinToString("") { b -> "%02x".format(b) })
    }

    fun install() {
        val file = local.value.picked ?: return
        val uri = Uri.parse(file.uri)
        val resolver = c.context.contentResolver
        c.firmware.start(file) { resolver.openInputStream(uri) ?: throw IOException("Android couldn't open ${file.name}") }
    }

    fun clearPick() = local.update { it.copy(picked = null, pickError = null) }

    fun done() {
        c.firmware.dismiss()
        clearPick()
    }
}

@Composable
fun FirmwareScreen() {
    val vm = containerViewModel { FirmwareViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val view = LocalView.current
    val busy = ui.job?.active == true
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pick(uri) }
    SubScreen("Install firmware file", actions.back) {
        FirmwareBody(ui, onChoose = { picker.launch(arrayOf("*/*")) }, onInstall = vm::install, onDone = vm::done)
    }
}

@Composable
fun FirmwareBody(ui: FirmwareUi, onChoose: () -> Unit, onInstall: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Gateway", Icons.Outlined.Router) {
            InfoRow("Model", ui.model)
            InfoRow("Firmware now", ui.job?.before ?: ui.firmware)
        }
        val job = ui.job
        if (job == null) {
            SectionCard("Before you start", Icons.Outlined.WarningAmber) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Only use a firmware file made for this exact model. A wrong or damaged file can leave the gateway unusable, and it belongs to T-Mobile.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text("Hint Plus sends the file the same way the gateway's own web page does. The gateway decides whether to install it.")
                    Text("Keep the gateway plugged in and this phone on its network until it's back. This can't be undone from the app.")
                }
            }
            PickCard(ui, onChoose, onInstall)
        } else {
            JobCard(job, onDone)
        }
    }
}

@Composable
private fun PickCard(ui: FirmwareUi, onChoose: () -> Unit, onInstall: () -> Unit) {
    SectionCard("Firmware file", Icons.Outlined.Description) {
        val picked = ui.picked
        when {
            ui.reading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Reading the file…")
            }
            picked == null -> {
                ui.pickError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                Button(onClick = onChoose) { Text("Choose file") }
            }
            else -> {
                var confirm by rememberSaveable(picked.sha256) { mutableStateOf("") }
                InfoRow("File", picked.name)
                InfoRow("Size", "${FirmwareUploader.mb(picked.size)} MB")
                InfoRow("SHA-256", picked.sha256, mono = true, copyable = true)
                Text(
                    "Compare the SHA-256 with your source before sending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    singleLine = true,
                    label = { Text("Type $CONFIRM_WORD to confirm") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onInstall, enabled = confirm.trim() == CONFIRM_WORD) { Text("Send to gateway") }
                    TextButton(onClick = onChoose) { Text("Other file") }
                }
            }
        }
    }
}

@Composable
private fun JobCard(job: FirmwareJob, onDone: () -> Unit) {
    val title = when (job.phase) {
        FirmwarePhase.Uploading -> "Sending the file"
        FirmwarePhase.Restarting -> "Waiting for the gateway"
        FirmwarePhase.Done -> "Gateway is back"
        FirmwarePhase.NoRestart -> if (job.sawOffline) "Gateway hasn't come back" else "No restart seen"
        FirmwarePhase.Failed -> "Upload failed"
    }
    val icon = if (job.phase == FirmwarePhase.Failed) Icons.Outlined.ErrorOutline else Icons.Outlined.SystemUpdate
    SectionCard(title, icon) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow("File", job.file.name)
            InfoRow("Started", Format.time(job.startedAt))
            when (job.phase) {
                FirmwarePhase.Uploading -> {
                    val fraction = if (job.file.size > 0) (job.sent.toFloat() / job.file.size).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${FirmwareUploader.mb(job.sent)} of ${FirmwareUploader.mb(job.file.size)} MB")
                    Text(
                        if (job.sent >= job.file.size) "File sent. Waiting for the gateway's answer…" else "Keep this screen open and the gateway plugged in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FirmwarePhase.Restarting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(answerText(job.answer))
                    Text(
                        if (job.sawOffline) "The gateway has gone offline to restart. Waiting for it to come back…"
                        else "Waiting for it to restart and come back. This can take several minutes. Don't unplug the gateway.",
                    )
                }
                FirmwarePhase.Done -> {
                    val changed = job.after != null && job.after != job.before
                    Text(
                        when {
                            changed -> "Firmware ${job.before ?: "?"} → ${job.after}"
                            job.after != null -> "The gateway restarted, but its firmware is still ${job.after}, so it didn't install this file."
                            else -> "The gateway is back, but it didn't report its firmware version."
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = onDone) { Text("Done") }
                }
                FirmwarePhase.NoRestart -> {
                    Text(
                        if (job.sawOffline) "The gateway went offline and didn't come back within 20 minutes. Check its lights and power, and don't unplug it while it may still be installing."
                        else "The gateway didn't restart within 20 minutes. Its firmware is ${job.after ?: job.before ?: "unknown"}.",
                    )
                    Button(onClick = onDone) { Text("Done") }
                }
                FirmwarePhase.Failed -> {
                    Text(job.message ?: "The upload failed.", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onDone) { Text("Done") }
                }
            }
        }
    }
}

private fun answerText(answer: FirmwareUploadResult?): String = when (answer) {
    FirmwareUploadResult.Accepted -> "The gateway accepted the file."
    FirmwareUploadResult.SentNoAnswer -> "The whole file was sent and the gateway closed the connection without answering. It may be installing it."
    null -> ""
}

private const val CONFIRM_WORD = "INSTALL"
