package com.banner.hintcontrol

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.banner.hintcontrol.data.Access
import com.banner.hintcontrol.data.AppSettings
import com.banner.hintcontrol.data.GatewayProfile
import com.banner.hintcontrol.data.LiveState
import com.banner.hintcontrol.data.ThemeMode
import com.banner.hintcontrol.data.db.SignalSample
import com.banner.hintcontrol.gateway.DetectedGateway
import com.banner.hintcontrol.gateway.GatewayKind
import com.banner.hintcontrol.gateway.demo.DemoGatewayClient
import com.banner.hintcontrol.gateway.model.flatten
import com.banner.hintcontrol.gateway.signal.Metric
import com.banner.hintcontrol.ui.devices.DeviceItem
import com.banner.hintcontrol.ui.devices.DevicesContent
import com.banner.hintcontrol.ui.devices.DevicesUi
import com.banner.hintcontrol.ui.more.GatewayInfoBody
import com.banner.hintcontrol.ui.more.GatewayInfoUi
import com.banner.hintcontrol.ui.more.MoreContent
import com.banner.hintcontrol.ui.more.MoreUi
import com.banner.hintcontrol.ui.more.SettingsBody
import com.banner.hintcontrol.ui.more.UpdatesBody
import com.banner.hintcontrol.ui.nav.AppActions
import com.banner.hintcontrol.ui.nav.AppNavigationSuite
import com.banner.hintcontrol.ui.nav.TopDestination
import com.banner.hintcontrol.ui.nav.LocalAppActions
import com.banner.hintcontrol.ui.overview.OverviewContent
import com.banner.hintcontrol.ui.overview.OverviewUi
import com.banner.hintcontrol.ui.setup.SetupContent
import com.banner.hintcontrol.ui.setup.SetupStep
import com.banner.hintcontrol.ui.setup.SetupUi
import com.banner.hintcontrol.ui.signal.CellState
import com.banner.hintcontrol.ui.signal.HistoryRange
import com.banner.hintcontrol.ui.signal.PlacementState
import com.banner.hintcontrol.ui.signal.Reading
import com.banner.hintcontrol.ui.signal.SignalContent
import com.banner.hintcontrol.ui.signal.SignalUi
import com.banner.hintcontrol.ui.theme.HintTheme
import com.banner.hintcontrol.ui.wifi.WifiActions
import com.banner.hintcontrol.ui.wifi.WifiContent
import com.banner.hintcontrol.ui.wifi.WifiDraft
import com.banner.hintcontrol.ui.wifi.WifiUi
import com.banner.hintcontrol.gateway.model.WifiBand
import com.banner.hintcontrol.update.AppRelease
import com.banner.hintcontrol.update.AppVersion
import com.banner.hintcontrol.update.GhAsset
import com.banner.hintcontrol.update.UpdateState
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

/** Renders screens with demo data into app/build/screenshots/ for visual review (CI artifact). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val demo = DemoGatewayClient(clock = { now }, latencyMs = 0)
    private val data = runBlocking {
        demo.login("admin", DemoGatewayClient.DEMO_PASSWORD)
        Sample(demo.mainData(), demo.cellData(), demo.simData(), demo.clients(), demo.wifiConfig())
    }

    data class Sample(
        val main: com.banner.hintcontrol.gateway.model.MainData,
        val cell: com.banner.hintcontrol.gateway.model.CellDataRoot,
        val sim: com.banner.hintcontrol.gateway.model.SimDataRoot,
        val clients: com.banner.hintcontrol.gateway.model.ClientDeviceData,
        val wifi: com.banner.hintcontrol.gateway.model.WifiConfig,
    )

    private val profile = GatewayProfile(id = "p1", name = "Living room gateway", host = "192.168.12.1", kind = GatewayKind.Tmi, model = "Demo G5AR")
    private val live = LiveState(profileId = "p1", main = data.main, updatedAt = now - 3_000)
    private val actions = AppActions(navigate = {}, back = {}, askReboot = {})

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            HintTheme(mode = if (dark) ThemeMode.Dark else ThemeMode.Light, dynamicColor = false) {
                CompositionLocalProvider(LocalAppActions provides actions) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun history(): List<SignalSample> = (0 until 360).map { i ->
        val t = now - (360 - i) * 60_000L
        val w = sin(i / 30.0)
        SignalSample(
            profileId = "p1",
            time = t,
            nrRsrp = (-92 + w * 6).toInt(),
            nrSinr = (14 + w * 5 + (i % 7) - 3).toInt(),
            nrRsrq = -11,
            nrRssi = -66,
            nrBand = if (i in 200..230) "n71" else "n41",
            lteRsrp = (-98 + w * 4).toInt(),
            lteSinr = (9 + w * 3).toInt(),
            lteRsrq = -12,
            lteRssi = -72,
            lteBand = "b66",
            uptime = 3_600L + i * 60,
        )
    }

    private fun readings(): List<Reading> = (0 until 60).map { i ->
        Reading(now - (60 - i) * 1_000L, -90 + (i % 9) - 4, 15 + (i % 6) - 2, -11, -64, 60 + (i % 10))
    }

    private val overviewUi get() = OverviewUi(
        profile = profile,
        profiles = listOf(profile),
        live = live,
        access = Access.LoggedIn,
        clientCounts = data.clients.flatten().groupingBy { it.link }.eachCount(),
        trendNr = history().takeLast(30).mapNotNull { it.nrSinr?.toFloat() },
        trendLte = history().takeLast(30).mapNotNull { it.lteSinr?.toFloat() },
    )

    @Test
    fun overview() = shot("01-overview") { OverviewContent(overviewUi, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test
    fun overviewDark() = shot("02-overview-dark", dark = true) { OverviewContent(overviewUi, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xhdpi")
    fun overviewLandscape() = shot("03-overview-landscape") { OverviewContent(overviewUi, {}, {}, {}, {}, {}, {}, {}, {}) }

    private val signalUi get() = SignalUi(
        live = live,
        readingsNr = readings(),
        placement = PlacementState(on = true, best = 72, bestAt = now - 40_000),
        history = history(),
        range = HistoryRange.SixHours,
        metric = Metric.SINR,
        cell = CellState(data = data.cell),
        access = Access.LoggedIn,
    )

    private fun signal(tab: Int): @Composable () -> Unit = {
        SignalContent(signalUi, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, initialTab = tab)
    }

    @Test
    fun signalLive() = shot("04-signal-live", content = signal(0))

    @Test
    fun signalHistory() = shot("05-signal-history", content = signal(1))

    @Test
    fun signalCell() = shot("06-signal-cell", content = signal(2))

    @Test
    fun devices() = shot("07-devices") {
        val items = data.clients.flatten().mapIndexed { i, c ->
            DeviceItem(c, alias = if (i == 0) "Living room TV" else null, firstSeen = now - i * 86_400_000L, isNew = i == 7)
        }
        DevicesContent(DevicesUi(loading = false, items = items, updatedAt = now - 5_000), {}, { _, _ -> }, {}, {}, {}, { _, _ -> })
    }

    @Test
    fun devicesLogin() = shot("08-devices-login") {
        DevicesContent(DevicesUi(loading = false, needsLogin = true), {}, { _, _ -> }, {}, {}, {}, { _, _ -> })
    }

    @Test
    fun wifi() = shot("09-wifi") {
        val draft = WifiDraft.of(data.wifi).updateBand(WifiBand.FiveGig) { it.copy(channel = "149") }
        WifiContent(WifiUi(original = data.wifi, draft = draft, loading = false, kind = GatewayKind.Tmi), WifiActions.None)
    }

    @Test
    fun more() = shot("10-more") {
        MoreContent(MoreUi(gatewayName = profile.name, scheduleSummary = "Every day at 04:00", canChangeLogin = true, developerMode = true, updateAvailable = "v1.0.0", profileCount = 2))
    }

    @Test
    fun gatewayInfo() = shot("11-gateway-info") {
        androidx.compose.foundation.layout.Column(Modifier.padding(top = 24.dp)) {
            GatewayInfoBody(GatewayInfoUi(name = profile.name, host = profile.host, kindLabel = GatewayKind.Tmi.label, main = data.main, sim = data.sim, cell = data.cell)) { _, _ -> }
        }
    }

    @Test
    fun updates() = shot("12-updates") {
        val release = AppRelease(
            tag = "v1.0.0-pre2",
            version = AppVersion.parse("1.0.0-pre2")!!,
            title = "Banners HINT Control v1.0.0-pre2",
            notes = "## What's new\n- **Placement mode** beeps faster near a better spot\n- Fixed the 6 GHz settings overwriting 5 GHz\n\nSee `CHANGELOG` for details.",
            publishedAt = "2026-11-16T12:00:00Z",
            pageUrl = "https://github.com/The412Banner/HINTControl/releases",
            apk = GhAsset("HINTControl-v1.0.0-pre2.apk", "https://x", 9_400_000),
            checksum = null,
            prerelease = true,
        )
        androidx.compose.foundation.layout.Column(Modifier.padding(top = 24.dp)) {
            UpdatesBody(UpdateState.Available(release), AppSettings(includePrereleases = true, lastUpdateCheck = now), true, {}, {}, { _, _ -> }, {}, {}, {}, {})
        }
    }

    @Test
    fun settings() = shot("13-settings") {
        androidx.compose.foundation.layout.Column(Modifier.padding(top = 24.dp)) {
            SettingsBody(AppSettings(), 1234, {}, {}, {})
        }
    }

    @Test
    fun setup() = shot("14-setup") {
        SetupContent(
            SetupUi(step = SetupStep.Found(DetectedGateway(GatewayKind.Tmi, "192.168.12.1", "Arcadyan KVD21"))),
            onBack = null, onHostChange = {}, onDetect = {}, onConnect = { _, _, _, _ -> }, onDemo = {},
        )
    }

    @Test
    fun setupNotFound() = shot("15-setup-not-found", dark = true) {
        SetupContent(SetupUi(step = SetupStep.NotFound("No T-Mobile gateway answered at 192.168.12.1, 192.168.1.1.")), null, {}, {}, { _, _, _, _ -> }, {})
    }

    @Test
    fun shellPortrait() = shot("17-shell-portrait") {
        AppNavigationSuite(isSelected = { it == TopDestination.Overview }, updateBadge = true, onSelect = {}) {
            OverviewContent(overviewUi, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land-xhdpi")
    fun shellLandscape() = shot("18-shell-landscape") {
        AppNavigationSuite(isSelected = { it == TopDestination.Signal }, updateBadge = false, onSelect = {}) {
            SignalContent(signalUi, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> })
        }
    }

    @Test
    fun icon() = shot("16-icon") {
        Row(Modifier.padding(24.dp)) {
            listOf(160.dp, 96.dp, 48.dp).forEach { s ->
                Box(Modifier.padding(12.dp).size(s).clip(RoundedCornerShape(s / 4))) {
                    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size(s))
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(s))
                }
            }
        }
    }
}
