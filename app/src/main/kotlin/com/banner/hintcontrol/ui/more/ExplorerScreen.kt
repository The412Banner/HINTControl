package com.banner.hintcontrol.ui.more

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.data.userMessage
import com.banner.hintcontrol.gateway.GatewayJson
import com.banner.hintcontrol.gateway.KnownEndpoint
import com.banner.hintcontrol.gateway.RawResponse
import com.banner.hintcontrol.ui.components.Dropdown
import com.banner.hintcontrol.ui.components.SectionCard
import com.banner.hintcontrol.ui.components.SubScreen
import com.banner.hintcontrol.ui.nav.LocalAppActions
import com.banner.hintcontrol.ui.nav.containerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

class ExplorerViewModel(private val c: AppContainer) : ViewModel() {
    data class Ui(val busy: Boolean = false, val response: RawResponse? = null, val error: String? = null)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    val endpoints: List<KnownEndpoint> get() = c.session.currentClient()?.knownEndpoints.orEmpty()

    fun send(method: String, path: String, body: String?) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try {
                val r = c.session.status { client ->
                    if (!client.isLoggedIn) runCatching { c.session.authed { } }
                    client.rawRequest(method, path, body)
                }
                _ui.update { it.copy(busy = false, response = r) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }
}

private fun pretty(text: String): String = runCatching {
    GatewayJson.pretty.encodeToString(JsonElement.serializer(), GatewayJson.pretty.parseToJsonElement(text))
}.getOrDefault(text)

@Composable
fun ExplorerScreen() {
    val vm = containerViewModel { ExplorerViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val clipboard = LocalClipboardManager.current
    val endpoints = remember { vm.endpoints }
    var preset by remember { mutableStateOf(endpoints.firstOrNull()) }
    var method by rememberSaveable { mutableStateOf(preset?.method ?: "GET") }
    var path by rememberSaveable { mutableStateOf(preset?.path.orEmpty()) }
    var body by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    SubScreen("API explorer", actions.back) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (endpoints.isNotEmpty()) {
                Dropdown("Known endpoints", endpoints, preset ?: endpoints.first(), {
                    preset = it
                    method = it.method
                    path = it.path
                }, Modifier.fillMaxWidth(), text = { "${it.label}  (${it.method})" })
            }
            SingleChoiceSegmentedButtonRow {
                listOf("GET", "POST").forEachIndexed { i, m ->
                    SegmentedButton(selected = method == m, onClick = { method = m }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(m) }
                }
            }
            OutlinedTextField(path, { path = it }, label = { Text("Path (after the API root)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            if (method == "POST") {
                OutlinedTextField(body, { body = it }, label = { Text("JSON body") }, minLines = 3, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
            Button(onClick = { if (method == "GET") vm.send(method, path, null) else { confirm = true } }, enabled = !ui.busy && path.isNotBlank()) {
                if (ui.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Send")
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ui.response?.let { r ->
                val text = remember(r) { pretty(r.body) }
                SectionCard(
                    "HTTP ${r.code} · ${r.tookMs} ms",
                    null,
                    trailing = { IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Icon(Icons.Outlined.ContentCopy, "Copy response") } },
                ) {
                    r.contentType?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text.take(60_000),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                    Text("Passwords and tokens are hidden.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Send a POST request?") },
            text = { Text("POST requests can change gateway settings or reboot it. Only send what you understand.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.send(method, path, body.ifBlank { null }) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
