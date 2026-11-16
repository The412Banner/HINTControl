package com.banner.hintcontrol.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.R
import com.banner.hintcontrol.data.GatewayProfile
import com.banner.hintcontrol.data.NetworkInfo
import com.banner.hintcontrol.data.userMessage
import com.banner.hintcontrol.gateway.DetectedGateway
import com.banner.hintcontrol.gateway.GatewayAddress
import com.banner.hintcontrol.gateway.GatewayDiscovery
import com.banner.hintcontrol.gateway.GatewayException
import com.banner.hintcontrol.gateway.GatewayHttp
import com.banner.hintcontrol.gateway.GatewayKind
import com.banner.hintcontrol.ui.components.PasswordField
import com.banner.hintcontrol.ui.nav.containerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SetupStep {
    /** [slow] = second pass with a longer wait, for gateways that answer slowly. */
    data class Detecting(val slow: Boolean = false) : SetupStep
    data class NotFound(val message: String) : SetupStep
    data class Found(val gateway: DetectedGateway) : SetupStep
}

data class SetupUi(
    val step: SetupStep = SetupStep.Detecting(),
    val host: String = GatewayAddress.DEFAULT_HOST,
    val connecting: Boolean = false,
    val loginError: String? = null,
)

class SetupViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = _ui.asStateFlow()
    private var job: Job? = null

    init {
        detect(null)
    }

    fun setHost(host: String) = _ui.update { it.copy(host = host) }

    fun detect(manualHost: String?) {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(step = SetupStep.Detecting(), loginError = null) }
            val hosts = buildList {
                manualHost?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (manualHost == null) {
                    add(GatewayAddress.DEFAULT_HOST)
                    addAll(NetworkInfo.localGateways(c.context))
                }
            }
            try {
                val found = try {
                    GatewayDiscovery.detect(hosts)
                } catch (_: GatewayException.NoGatewayFound) {
                    _ui.update { it.copy(step = SetupStep.Detecting(slow = true)) }
                    GatewayDiscovery.detect(hosts, timeoutMs = GatewayHttp.SLOW_PROBE_TIMEOUT_MS)
                }
                _ui.update { it.copy(step = SetupStep.Found(found), host = found.host) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(step = SetupStep.NotFound(e.userMessage())) }
            }
        }
    }

    fun connect(found: DetectedGateway, username: String, password: String, remember: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(connecting = true, loginError = null) }
            try {
                if (password.isNotEmpty()) GatewayDiscovery.clientFor(found.kind, found.host).login(username, password)
                val profile = GatewayProfile(
                    name = found.model ?: "My gateway",
                    host = found.host,
                    kind = found.kind,
                    username = username.ifBlank { "admin" },
                    passwordCipher = if (remember && password.isNotEmpty()) c.profiles.encrypt(password) else null,
                    model = found.model,
                )
                c.profiles.save(profile)
                if (password.isNotEmpty() && (!remember || profile.passwordCipher == null)) {
                    withTimeoutOrNull(3_000) { c.profiles.active.first { it?.id == profile.id } }
                    runCatching { c.session.login(password, remember = false) }
                }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loginError = e.userMessage()) }
            } finally {
                _ui.update { it.copy(connecting = false) }
            }
        }
    }

    fun useDemo(onDone: () -> Unit) {
        viewModelScope.launch {
            c.profiles.save(GatewayProfile(name = "Demo gateway", host = "demo", kind = GatewayKind.Demo, model = "Demo G5AR"))
            onDone()
        }
    }
}

@Composable
fun SetupScreen(onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    val vm = containerViewModel { SetupViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    SetupContent(
        ui = ui,
        onBack = onBack,
        onHostChange = vm::setHost,
        onDetect = { vm.detect(ui.host) },
        onConnect = { found, user, pw, remember -> vm.connect(found, user, pw, remember, onDone) },
        onDemo = { vm.useDemo(onDone) },
    )
}

@Composable
fun SetupContent(
    ui: SetupUi,
    onBack: (() -> Unit)?,
    onHostChange: (String) -> Unit,
    onDetect: () -> Unit,
    onConnect: (DetectedGateway, String, String, Boolean) -> Unit,
    onDemo: () -> Unit,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp)) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)))
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(88.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("Connect to your gateway", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    "For T-Mobile Home Internet gateways: Arcadyan, Sagemcom, Sercomm and Nokia.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                AnimatedContent(ui.step, label = "setup", contentKey = { it::class }) { step ->
                    when (step) {
                        is SetupStep.Detecting -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(
                                if (step.slow) "Still looking. Some gateways take a while to answer…" else "Looking for your gateway…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        is SetupStep.NotFound -> NotFoundCard(step.message, ui.host, onHostChange, onDetect)
                        is SetupStep.Found -> LoginCard(step.gateway, ui.connecting, ui.loginError, onConnect, onDetect)
                    }
                }
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = onDemo) { Text("Try the demo gateway") }
            }
        }
    }
}

@Composable
private fun NotFoundCard(message: String, host: String, onHostChange: (String) -> Unit, onDetect: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SearchOff, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(12.dp))
                Text("No gateway found", style = MaterialTheme.typography.titleMedium)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Connect this phone to the gateway's Wi-Fi, or enter its address.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Gateway address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = !GatewayAddress.isPlausible(host),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = onDetect, enabled = GatewayAddress.isPlausible(host), modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        }
    }
}

@Composable
private fun LoginCard(
    found: DetectedGateway,
    connecting: Boolean,
    error: String?,
    onConnect: (DetectedGateway, String, String, Boolean) -> Unit,
    onDetect: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("admin") }
    var password by rememberSaveable { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(true) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(found.model ?: "Gateway found", style = MaterialTheme.typography.titleMedium)
                    Text("${found.kind.label} · ${found.host}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.Router, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PasswordField(password, { password = it }, "Admin password", isError = error != null, supporting = error, onDone = { onConnect(found, username, password, remember) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(remember, { remember = it })
                Text("Remember password (encrypted on this phone)", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { onConnect(found, username, password, remember) }, enabled = !connecting && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                if (connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect")
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onConnect(found, username, "", false) }, enabled = !connecting) { Text("Status only") }
                TextButton(onClick = onDetect, enabled = !connecting) { Text("Search again") }
            }
        }
    }
}
