package com.banner.hintcontrol.ui.more

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.data.Report
import com.banner.hintcontrol.data.db.EventType
import com.banner.hintcontrol.data.needsLogin
import com.banner.hintcontrol.data.userMessage
import com.banner.hintcontrol.gateway.model.CellDataRoot
import com.banner.hintcontrol.gateway.model.MainData
import com.banner.hintcontrol.gateway.model.SimDataRoot
import com.banner.hintcontrol.ui.components.Format
import com.banner.hintcontrol.ui.components.InfoRow
import com.banner.hintcontrol.ui.components.LoginPrompt
import com.banner.hintcontrol.ui.components.PasswordField
import com.banner.hintcontrol.ui.components.SectionCard
import com.banner.hintcontrol.ui.components.SettingRow
import com.banner.hintcontrol.ui.components.SubScreen
import com.banner.hintcontrol.ui.nav.LocalAppActions
import com.banner.hintcontrol.ui.nav.containerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GatewayInfoUi(
    val name: String = "Gateway",
    val host: String = "",
    val kindLabel: String = "",
    val main: MainData? = null,
    val sim: SimDataRoot? = null,
    val cell: CellDataRoot? = null,
    val simError: String? = null,
    val needsLogin: Boolean = false,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
)

class GatewayInfoViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(GatewayInfoUi())
    val ui: StateFlow<GatewayInfoUi> = _ui.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val p = c.profiles.active.value
            _ui.update { it.copy(name = p?.name ?: "Gateway", host = p?.host.orEmpty(), kindLabel = p?.kind?.label.orEmpty(), main = c.status.state.value.main) }
            runCatching { c.session.status { it.mainData() } }.onSuccess { m -> _ui.update { it.copy(main = m) } }
            try {
                val sim = c.session.authed { it.simData() }
                val cell = runCatching { c.session.authed { it.cellData() } }.getOrNull()
                _ui.update { it.copy(sim = sim, cell = cell, simError = null, needsLogin = false) }
            } catch (e: Exception) {
                _ui.update { it.copy(simError = e.userMessage(), needsLogin = e.needsLogin) }
            }
        }
    }

    fun login(password: String, remember: Boolean) {
        viewModelScope.launch {
            _ui.update { it.copy(loginBusy = true, loginError = null) }
            try {
                c.session.login(password, remember)
                _ui.update { it.copy(loginBusy = false) }
                load()
            } catch (e: Exception) {
                _ui.update { it.copy(loginBusy = false, loginError = e.userMessage()) }
            }
        }
    }

    fun report(): String = _ui.value.let { Report.build(it.name, it.main, it.cell, it.sim) }
}

@Composable
fun GatewayInfoScreen() {
    val vm = containerViewModel { GatewayInfoViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    SubScreen(
        "Gateway details",
        actions.back,
        actions = {
            IconButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.report()), "Share gateway report"))
            }) { Icon(Icons.Outlined.Share, "Share report") }
        },
    ) {
        GatewayInfoBody(ui, vm::login)
    }
}

@Composable
fun GatewayInfoBody(ui: GatewayInfoUi, onLogin: (String, Boolean) -> Unit) {
    val d = ui.main?.device
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Device", Icons.Outlined.Router) {
            InfoRow("Name", ui.name)
            InfoRow("Model", d?.displayName)
            InfoRow("Type", ui.kindLabel)
            InfoRow("Address", ui.host, copyable = true)
            InfoRow("Firmware", d?.softwareVersion, copyable = true)
            InfoRow("Hardware", d?.hardwareVersion)
            InfoRow("Update state", d?.updateState?.replaceFirstChar(Char::uppercase))
            InfoRow("Serial", d?.serial, mono = true, copyable = true)
            InfoRow("MAC", d?.macId, mono = true, copyable = true)
            InfoRow("Mesh support", d?.isMeshSupported?.let { if (it) "Yes" else "No" })
            InfoRow("Uptime", Format.uptime(ui.main?.time?.upTime))
            ui.main?.time?.localTimeZone?.let { InfoRow("Time zone", it) }
        }
        SectionCard("SIM", Icons.Outlined.SimCard) {
            val sim = ui.sim?.sim
            when {
                sim != null -> {
                    InfoRow("Status", sim.status?.let { if (it) "Active" else "Not active" })
                    SecretRow("Phone number", sim.msisdn)
                    SecretRow("ICCID", sim.iccId)
                    SecretRow("IMEI", sim.imei)
                    SecretRow("IMSI", sim.imsi)
                }
                ui.needsLogin -> LoginPrompt(ui.loginBusy, ui.loginError, onLogin, Modifier.height(380.dp))
                ui.simError != null -> Text(ui.simError, color = MaterialTheme.colorScheme.error)
                else -> CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun SecretRow(label: String, value: String?) {
    var shown by rememberSaveable(label) { mutableStateOf(false) }
    InfoRow(label, value?.let { if (shown) it else Report.mask(it) }, mono = true, copyable = shown, trailing = {
        if (value != null) IconButton(onClick = { shown = !shown }, modifier = Modifier.size(36.dp)) {
            Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (shown) "Hide $label" else "Show $label", modifier = Modifier.size(20.dp))
        }
    })
}

@Composable
fun ScheduleScreen() {
    val vm = containerViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle(initialValue = emptyList())
    val actions = LocalAppActions.current
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val schedule = s.rebootSchedule
    val last = events.firstOrNull { it.type == EventType.ScheduledReboot || it.type == EventType.ScheduledRebootFailed }
    SubScreen("Scheduled reboot", actions.back) {
        SettingRow("Reboot on a schedule", icon = Icons.Outlined.Schedule, trailing = { Switch(schedule.enabled, { vm.setSchedule(schedule.copy(enabled = it)) }) })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("When", null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%02d:%02d".format(schedule.hour, schedule.minute), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { picking = true }) { Text("Change time") }
                }
                Spacer(Modifier.height(12.dp))
                DayChips(schedule.days) { vm.setSchedule(schedule.copy(days = it)) }
                Spacer(Modifier.height(12.dp))
                val next = vm.nextReboot(schedule)
                Text(
                    when {
                        !schedule.enabled -> "Off"
                        next == null -> "Pick at least one day"
                        else -> "Next: ${Format.dateTime(next.toEpochMilli())}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "This phone sends the reboot, so it must be on the gateway's network at that time and the gateway's admin password must be saved.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (schedule.enabled && !vm.canScheduleExact()) {
                SectionCard("Exact timing is off", null) {
                    Text("Android may delay the reboot by several minutes. Allow alarms for exact timing.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(android.net.Uri.parse("package:${context.packageName}"))) }
                    }) { Text("Allow") }
                }
            }
            last?.let { e ->
                SectionCard("Last scheduled reboot", null) {
                    InfoRow(if (e.type == EventType.ScheduledReboot) "Sent" else "Failed", Format.dateTime(e.time))
                    e.detail?.let { InfoRow("Detail", it) }
                }
            }
        }
    }
    if (picking) {
        val state = rememberTimePickerState(initialHour = schedule.hour, initialMinute = schedule.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Reboot time") },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { vm.setSchedule(schedule.copy(hour = state.hour, minute = state.minute)); picking = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
}

class AdminLoginViewModel(private val c: AppContainer) : ViewModel() {
    data class Ui(val busy: Boolean = false, val error: String? = null, val done: Boolean = false, val username: String = "admin")

    private val _ui = MutableStateFlow(Ui(username = c.profiles.active.value?.username ?: "admin"))
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    fun change(username: String, password: String, remember: Boolean) {
        c.scope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                c.session.authed { it.setLogin(username, password) }
                c.session.passwordChanged(username, password, remember)
                c.profiles.active.value?.let { c.history.addEvent(it.id, EventType.LoginChanged, "User $username") }
                _ui.update { it.copy(busy = false, done = true) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }
}

@Composable
fun AdminLoginScreen() {
    val vm = containerViewModel { AdminLoginViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    var username by rememberSaveable { mutableStateOf(ui.username) }
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    val valid = username.isNotBlank() && password.isNotBlank() && password == repeat
    LaunchedEffect(ui.done) { if (ui.done) actions.back() }
    SubScreen("Admin login", actions.back) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Change the gateway's admin login", Icons.Outlined.Key) {
                Text("You'll need the new password for the T-Mobile app and this app.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PasswordField(password, { password = it }, "New password")
                PasswordField(repeat, { repeat = it }, "Repeat new password", isError = repeat.isNotEmpty() && repeat != password, supporting = if (repeat.isNotEmpty() && repeat != password) "Doesn't match" else null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(remember, { remember = it })
                    Text("Save the new password on this phone")
                }
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { confirm = true }, enabled = valid && !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    if (ui.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Change login")
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Change the admin login?") },
            text = { Text("If you forget the new password, only a factory reset of the gateway gets you back in.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.change(username.trim(), password, remember) }) { Text("Change") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
