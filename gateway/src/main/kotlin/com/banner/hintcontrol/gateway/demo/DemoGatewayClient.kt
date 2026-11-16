package com.banner.hintcontrol.gateway.demo

import com.banner.hintcontrol.gateway.GatewayCapabilities
import com.banner.hintcontrol.gateway.GatewayClient
import com.banner.hintcontrol.gateway.GatewayException
import com.banner.hintcontrol.gateway.GatewayJson
import com.banner.hintcontrol.gateway.GatewayKind
import com.banner.hintcontrol.gateway.KnownEndpoint
import com.banner.hintcontrol.gateway.RawResponse
import com.banner.hintcontrol.gateway.RebootResult
import com.banner.hintcontrol.gateway.model.AdvancedCellData
import com.banner.hintcontrol.gateway.model.AdvancedData5G
import com.banner.hintcontrol.gateway.model.AdvancedDataLTE
import com.banner.hintcontrol.gateway.model.BandConfig
import com.banner.hintcontrol.gateway.model.BandSteeringConfig
import com.banner.hintcontrol.gateway.model.CellData5G
import com.banner.hintcontrol.gateway.model.CellDataLTE
import com.banner.hintcontrol.gateway.model.CellDataRoot
import com.banner.hintcontrol.gateway.model.ClientDeviceData
import com.banner.hintcontrol.gateway.model.ClientsData
import com.banner.hintcontrol.gateway.model.DaylightSavingsData
import com.banner.hintcontrol.gateway.model.DeviceData
import com.banner.hintcontrol.gateway.model.GPSData
import com.banner.hintcontrol.gateway.model.GenericData
import com.banner.hintcontrol.gateway.model.MainData
import com.banner.hintcontrol.gateway.model.SSIDConfig
import com.banner.hintcontrol.gateway.model.SignalData
import com.banner.hintcontrol.gateway.model.SimData
import com.banner.hintcontrol.gateway.model.SimDataRoot
import com.banner.hintcontrol.gateway.model.TimeData
import com.banner.hintcontrol.gateway.model.WifiConfig
import com.banner.hintcontrol.gateway.model.WiredClientData
import com.banner.hintcontrol.gateway.model.WirelessClientData
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A simulated gateway for trying the app without hardware. Values drift slowly, the Wi-Fi
 * settings can be changed and a reboot takes the "gateway" offline for [rebootDowntimeMs].
 */
class DemoGatewayClient(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random(7),
    private val latencyMs: Long = 120L,
    private val rebootDowntimeMs: Long = 25_000L,
) : GatewayClient {
    override val kind = GatewayKind.Demo
    override val host = "demo"
    override val capabilities = GatewayCapabilities(
        changeLogin = true,
        addRemoveNetworks = true,
        radioSettings = true,
        statusWithoutLogin = true,
    )
    override val knownEndpoints = listOf(
        KnownEndpoint("Gateway status", "GET", "gateway/?get=all", needsLogin = false),
        KnownEndpoint("Cell details", "GET", "network/telemetry/?get=cell"),
        KnownEndpoint("Connected devices", "GET", "network/telemetry/?get=clients"),
        KnownEndpoint("Wi-Fi settings", "GET", "network/configuration/v2?get=ap"),
    )

    private var loggedIn = false
    private var username = "admin"
    private var password = DEMO_PASSWORD
    private var bootedAt = clock() - 3L * 24 * 3600 * 1000 - 5 * 3600 * 1000
    private var offlineUntil = 0L

    // Random-walk state for the radio values.
    private var nrRsrp = -92.0
    private var nrSinr = 14.0
    private var lteRsrp = -97.0
    private var lteSinr = 9.0
    private var lastStep = clock()

    private var wifi = WifiConfig(
        twoGig = BandConfig(airtimeFairness = true, channel = "Auto", channelBandwidth = "Auto", isMUMIMOEnabled = true, isRadioEnabled = true, isWMMEnabled = true, maxClients = 128, mode = "auto", transmissionPower = "100%"),
        fiveGig = BandConfig(airtimeFairness = true, channel = "Auto", channelBandwidth = "80MHz", isMUMIMOEnabled = true, isRadioEnabled = true, isWMMEnabled = true, maxClients = 128, mode = "auto", transmissionPower = "100%"),
        sixGig = BandConfig(airtimeFairness = true, channel = "Auto", channelBandwidth = "160MHz", isMUMIMOEnabled = true, isRadioEnabled = true, isWMMEnabled = true, maxClients = 128, mode = "auto", transmissionPower = "100%"),
        bandSteering = BandSteeringConfig(isEnabled = true),
        ssids = listOf(
            SSIDConfig(twoGigSsid = true, fiveGigSsid = true, sixGigSsid = true, encryptionMode = "AES", encryptionVersion = "WPA2/WPA3", guest = false, isBroadcastEnabled = true, ssidName = "Home-5G", wpaKey = "correct-horse-battery"),
            SSIDConfig(twoGigSsid = true, fiveGigSsid = false, sixGigSsid = false, encryptionMode = "AES", encryptionVersion = "WPA2", guest = true, isBroadcastEnabled = true, ssidName = "Home-Guest", wpaKey = "welcome-guests"),
        ),
    )

    private val devices = listOf(
        Triple("Living-Room-TV", "5", "A4:83:E7:1C:22:10"),
        Triple("Pixel-9", "5", "DA:A1:19:3E:47:5B"),
        Triple("Work-Laptop", "6", "F0:2F:74:9A:0C:31"),
        Triple("Thermostat", "2", "64:16:66:22:91:AF"),
        Triple("Doorbell", "2", "2C:AA:8E:51:07:E2"),
        Triple("Desktop-PC", "E", "B4:2E:99:F1:6D:03"),
        Triple("Game-Console", "E", "98:B6:E9:44:12:7C"),
        Triple("", "5", "5E:0B:2D:88:91:C4"),
    )

    override val isLoggedIn: Boolean get() = loggedIn

    private suspend fun online() {
        delay(latencyMs)
        if (clock() < offlineUntil) throw GatewayException.Unreachable(host)
    }

    private suspend fun authed() {
        online()
        if (!loggedIn) throw GatewayException.Unauthorized("Log in to the gateway first")
    }

    override suspend fun exists(): Boolean = clock() >= offlineUntil

    override suspend fun login(username: String, password: String) {
        online()
        if (username != this.username || password != this.password) throw GatewayException.Unauthorized()
        loggedIn = true
    }

    override suspend fun logout() {
        loggedIn = false
    }

    private fun step() {
        val now = clock()
        val dt = ((now - lastStep) / 1000.0).coerceIn(0.0, 600.0)
        lastStep = now
        val wave = sin(now / 60_000.0 * 2 * PI / 7)
        fun walk(v: Double, center: Double, spread: Double, lo: Double, hi: Double): Double {
            val pull = (center - v) * 0.08 * (dt / 5).coerceAtMost(4.0)
            return (v + pull + random.nextDouble(-spread, spread) * (dt / 5).coerceIn(0.2, 3.0)).coerceIn(lo, hi)
        }
        nrRsrp = walk(nrRsrp, -90 + wave * 6, 1.6, -125.0, -70.0)
        nrSinr = walk(nrSinr, 15 + wave * 5, 1.2, -5.0, 30.0)
        lteRsrp = walk(lteRsrp, -96 + wave * 4, 1.4, -125.0, -72.0)
        lteSinr = walk(lteSinr, 10 + wave * 3, 1.0, -5.0, 28.0)
    }

    private fun bars(rsrp: Int) = when {
        rsrp >= -85 -> 5.0
        rsrp >= -95 -> 4.0
        rsrp >= -105 -> 3.0
        rsrp >= -115 -> 2.0
        else -> 1.0
    }

    private fun nr(): CellData5G {
        val rsrp = nrRsrp.roundToInt()
        return CellData5G(bands = listOf("n41"), bars = bars(rsrp), cid = 281, nbid = 1_402_118, rsrp = rsrp, rsrq = (-11 + (nrSinr - 15) / 4).roundToInt(), rssi = rsrp + 26, sinr = nrSinr.roundToInt())
    }

    private fun lte(): CellDataLTE {
        val rsrp = lteRsrp.roundToInt()
        return CellDataLTE(bands = listOf("b66"), bars = bars(rsrp), cid = 12, nbid = 310_463, rsrp = rsrp, rsrq = (-12 + (lteSinr - 10) / 4).roundToInt(), rssi = rsrp + 24, sinr = lteSinr.roundToInt())
    }

    override suspend fun mainData(): MainData {
        online()
        step()
        val now = clock()
        return MainData(
            device = DeviceData(friendlyName = "5G Gateway", hardwareVersion = "R01", isEnabled = true, isMeshSupported = true, macId = "10:AC:67:81:18:86", manufacturer = "Demo", model = "G5AR", name = "5G Gateway", role = "gateway", serial = "DEMO00042", softwareVersion = "1.2403.0.12", type = "HSID", updateState = "latest"),
            signal = SignalData(fourG = lte(), fiveG = nr(), generic = GenericData(apn = "FBB.HOME", hasIPv6 = true, registration = "registered", roaming = false)),
            time = TimeData(daylightSavings = DaylightSavingsData(isUsed = true), localTime = now / 1000, localTimeZone = "EST5EDT", upTime = (now - bootedAt) / 1000),
        )
    }

    override suspend fun cellData(): CellDataRoot {
        authed()
        step()
        return CellDataRoot(
            cell = AdvancedCellData(
                fourG = AdvancedDataLTE(bandwidth = "20M", cqi = 9, earfcn = "66786", ecgi = "31026079478540", mcc = "310", mnc = "260", pci = "63", plmn = "310260", status = true, supportedBands = listOf("b2", "b4", "b5", "b12", "b25", "b41", "b46", "b48", "b66", "b71"), tac = "22233", sector = lte()),
                fiveG = AdvancedData5G(bandwidth = "100M", cqi = 12, earfcn = "520110", ecgi = "3102605743075609", mcc = "310", mnc = "260", pci = "781", plmn = "310260", status = true, supportedBands = listOf("n25", "n41", "n66", "n71", "n77"), tac = "22233", sector = nr()),
                generic = GenericData(apn = "FBB.HOME", hasIPv6 = true, registration = "registered", roaming = false),
                gps = GPSData(latitude = 39.9526, longitude = -75.1652),
            ),
        )
    }

    override suspend fun simData(): SimDataRoot {
        authed()
        return SimDataRoot(SimData(iccId = "8901260000000000042", imei = "350000000000042", imsi = "310260000000042", msisdn = "12155550142", status = true))
    }

    override suspend fun clients(): ClientDeviceData {
        authed()
        fun wireless(tag: String) = devices.filter { it.second == tag }.mapIndexed { i, (name, _, mac) ->
            WirelessClientData(connected = true, ipv4 = "192.168.12.${100 + devices.indexOfFirst { d -> d.third == mac }}", ipv6 = listOf("fe80::${(0x1a2b + i).toString(16)}"), mac = mac, name = name)
        }
        return ClientDeviceData(
            clients = ClientsData(
                twoGig = wireless("2"),
                fiveGig = wireless("5"),
                sixGig = wireless("6"),
                ethernet = devices.filter { it.second == "E" }.map { (name, _, mac) ->
                    WiredClientData(connected = true, ipv4 = "192.168.12.${100 + devices.indexOfFirst { d -> d.third == mac }}", mac = mac, name = name)
                },
            ),
        )
    }

    override suspend fun wifiConfig(): WifiConfig {
        authed()
        return wifi
    }

    override suspend fun setWifiConfig(config: WifiConfig) {
        authed()
        delay(900)
        wifi = config
    }

    override suspend fun setLogin(username: String, password: String) {
        authed()
        this.username = username
        this.password = password
    }

    override suspend fun reboot(): RebootResult {
        authed()
        val now = clock()
        offlineUntil = now + rebootDowntimeMs
        bootedAt = offlineUntil
        return RebootResult.Accepted
    }

    override suspend fun rawRequest(method: String, path: String, body: String?): RawResponse {
        online()
        val text = when {
            path.startsWith("gateway") -> GatewayJson.pretty.encodeToString(MainData.serializer(), mainData())
            path.contains("get=cell") -> GatewayJson.pretty.encodeToString(CellDataRoot.serializer(), cellData())
            path.contains("get=clients") -> GatewayJson.pretty.encodeToString(ClientDeviceData.serializer(), clients())
            path.contains("get=ap") -> GatewayJson.pretty.encodeToString(WifiConfig.serializer(), wifiConfig())
            else -> return RawResponse(404, "{\"error\":\"not found\"}", latencyMs, "application/json")
        }
        return RawResponse(200, text, latencyMs, "application/json")
    }

    companion object {
        const val DEMO_PASSWORD = "demo"
    }
}
