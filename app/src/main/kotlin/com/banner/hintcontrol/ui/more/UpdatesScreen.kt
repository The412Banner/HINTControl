package com.banner.hintcontrol.ui.more

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.banner.hintcontrol.AppContainer
import com.banner.hintcontrol.BuildConfig
import com.banner.hintcontrol.data.AppSettings
import com.banner.hintcontrol.ui.components.Format
import com.banner.hintcontrol.ui.components.InfoRow
import com.banner.hintcontrol.ui.components.MarkdownLite
import com.banner.hintcontrol.ui.components.Pill
import com.banner.hintcontrol.ui.components.SectionCard
import com.banner.hintcontrol.ui.components.SubScreen
import com.banner.hintcontrol.ui.nav.LocalAppActions
import com.banner.hintcontrol.ui.nav.containerViewModel
import com.banner.hintcontrol.update.AppRelease
import com.banner.hintcontrol.update.UpdateInstaller
import com.banner.hintcontrol.update.UpdateState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class UpdatesViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<UpdateState> = c.updates.state
    val settings: StateFlow<AppSettings> = c.settings.settings

    fun check() = viewModelScope.launch { c.updates.check() }

    fun download(release: AppRelease) = viewModelScope.launch { c.updates.download(release) }

    fun install(release: AppRelease, file: java.io.File) = viewModelScope.launch {
        c.updates.markInstalling(release)
        runCatching { UpdateInstaller.install(c.context, file) }
            .onFailure { c.updates.installFinished(false, it.message) }
    }

    fun skip(release: AppRelease) = viewModelScope.launch {
        c.settings.update { it.copy(skippedVersion = release.version.toString()) }
        c.updates.reset()
    }

    fun setPrereleases(on: Boolean) = viewModelScope.launch {
        c.settings.update { it.copy(includePrereleases = on) }
        c.updates.check()
    }
}

@Composable
fun UpdatesScreen() {
    val vm = containerViewModel { UpdatesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (state is UpdateState.Idle) vm.check() }
    // Re-checked on every resume: the "install unknown apps" switch is flipped in Android's settings.
    var canInstall by remember { mutableStateOf(UpdateInstaller.canInstall(context)) }
    LifecycleResumeEffect(Unit) {
        canInstall = UpdateInstaller.canInstall(context)
        onPauseOrDispose { }
    }
    SubScreen("Updates", actions.back) {
        UpdatesBody(
            state = state,
            settings = settings,
            canInstall = canInstall,
            onCheck = { vm.check() },
            onDownload = { vm.download(it) },
            onInstall = { r, f -> vm.install(r, f) },
            onSkip = { vm.skip(it) },
            onAllowInstall = { context.startActivity(UpdateInstaller.permissionIntent(context)) },
            onOpenPage = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            onPrereleases = { vm.setPrereleases(it) },
        )
    }
}

@Composable
fun UpdatesBody(
    state: UpdateState,
    settings: AppSettings,
    canInstall: Boolean,
    onCheck: () -> Unit,
    onDownload: (AppRelease) -> Unit,
    onInstall: (AppRelease, java.io.File) -> Unit,
    onSkip: (AppRelease) -> Unit,
    onAllowInstall: () -> Unit,
    onOpenPage: (String) -> Unit,
    onPrereleases: (Boolean) -> Unit,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("This version", Icons.Outlined.SystemUpdate) {
            InfoRow("Installed", BuildConfig.VERSION_NAME)
            InfoRow("Build", BuildConfig.GIT_SHA)
            InfoRow("Last check", if (settings.lastUpdateCheck > 0) Format.dateTime(settings.lastUpdateCheck) else "Never")
            InfoRow("Channel", if (settings.includePrereleases) "Releases and test builds" else "Releases only")
            TextButton(onClick = { onPrereleases(!settings.includePrereleases) }) {
                Text(if (settings.includePrereleases) "Only offer full releases" else "Also offer test builds")
            }
        }
        val release = when (state) {
            is UpdateState.Available -> state.release
            is UpdateState.Downloading -> state.release
            is UpdateState.Verifying -> state.release
            is UpdateState.ReadyToInstall -> state.release
            is UpdateState.Installing -> state.release
            is UpdateState.Failed -> state.release
            else -> null
        }
        when (state) {
            UpdateState.Idle, UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("  Checking GitHub for updates…")
            }
            is UpdateState.UpToDate -> SectionCard("You're up to date", Icons.Outlined.CheckCircle) {
                Text(
                    state.latest?.let { "Newest ${if (it.prerelease) "test build" else "release"}: ${it.tag}" } ?: "No releases found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCheck) { Text("Check again") }
            }
            is UpdateState.Failed -> SectionCard("Update problem", Icons.Outlined.ErrorOutline) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onCheck) { Text("Try again") }
            }
            else -> {}
        }
        if (release != null) {
            SectionCard(release.title, Icons.Outlined.NewReleases, trailing = { if (release.prerelease) Pill("Test build") }) {
                Text(
                    listOfNotNull(release.tag, release.publishedAt?.let { runCatching { Format.dateTime(Instant.parse(it).toEpochMilli()) }.getOrNull() }, "${release.apk.size / 1_048_576} MB").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (release.notes.isNotBlank()) MarkdownLite(release.notes.take(6_000))
                Spacer(Modifier.height(12.dp))
                when (state) {
                    is UpdateState.Downloading -> {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading ${(state.progress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                    is UpdateState.Verifying -> Text("Checking the download…", style = MaterialTheme.typography.bodySmall)
                    is UpdateState.Installing -> Text("Waiting for Android's installer…", style = MaterialTheme.typography.bodySmall)
                    is UpdateState.ReadyToInstall -> {
                        if (!canInstall) {
                            Text("Allow this app to install updates first.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onAllowInstall) { Text("Allow") }
                        }
                        Button(onClick = { onInstall(state.release, state.file) }, enabled = canInstall, modifier = Modifier.fillMaxWidth()) { Text("Install") }
                    }
                    else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onDownload(release) }) { Text("Download & install") }
                        OutlinedButton(onClick = { onSkip(release) }) { Text("Skip") }
                    }
                }
                release.pageUrl?.let { url -> TextButton(onClick = { onOpenPage(url) }) { Text("View on GitHub") } }
            }
        }
    }
}
