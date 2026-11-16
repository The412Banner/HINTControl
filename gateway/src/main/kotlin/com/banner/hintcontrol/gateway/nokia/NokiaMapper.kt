// Mapping ported from HINT Control by Zachary Wander (MIT License), with fixes noted inline.
package com.banner.hintcontrol.gateway.nokia

import com.banner.hintcontrol.gateway.model.AdvancedCellData
import com.banner.hintcontrol.gateway.model.AdvancedData5G
import com.banner.hintcontrol.gateway.model.AdvancedDataLTE
import com.banner.hintcontrol.gateway.model.CellData5G
import com.banner.hintcontrol.gateway.model.CellDataLTE
import com.banner.hintcontrol.gateway.model.CellDataRoot
import com.banner.hintcontrol.gateway.model.ClientDeviceData
import com.banner.hintcontrol.gateway.model.ClientsData
import com.banner.hintcontrol.gateway.model.DeviceData
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

object NokiaMapper {
    /** Nokia reports 0xFFFFFFFF as the channel number of a radio that isn't connected. */
    private const val NOT_CONNECTED = 0xFFFF_FFFFL

    private fun ConnectionStatus?.lteConnected() =
        this?.fourGStats?.firstOrNull()?.stat?.earfcn != NOT_CONNECTED

    private fun ConnectionStatus?.nrConnected() =
        this?.fiveGStats?.firstOrNull()?.stat?.nrarfcn != NOT_CONNECTED

    private fun CellStat.toLte() = CellDataLTE(
        nbid = enbid?.toLongOrNull(),
        cid = cellId?.toLongOrNull(),
        rssi = rssi,
        bands = band?.let { listOf(it) },
        bars = rsrpStrengthIndex?.toDouble(),
        rsrp = rsrp,
        rsrq = rsrq,
        sinr = snr,
    )

    private fun CellStat.toNr() = CellData5G(
        nbid = enbid?.toLongOrNull(),
        cid = cellId?.toLongOrNull(),
        rssi = rssi,
        bands = band?.let { listOf(it) },
        bars = rsrpStrengthIndex?.toDouble(),
        rsrp = rsrp,
        rsrq = rsrq,
        sinr = snr,
    )

    private fun roaming(cell: CellStatus?): Boolean? =
        cell?.cellStatGeneric?.firstOrNull()?.roamingStatus?.lowercase()?.let { it != "home" }

    fun main(device: DeviceInfoStatus?, cell: CellStatus?, connection: ConnectionStatus?): MainData {
        val app = device?.deviceAppStatus?.firstOrNull()
        val cfg = device?.deviceConfig?.firstOrNull()
        return MainData(
            device = DeviceData(
                name = app?.description,
                // Nokia reports its OUI (hex) here, not a name.
                manufacturer = app?.manufacturer?.takeUnless { Regex("^[0-9A-Fa-f]{6}$").matches(it) } ?: "Nokia",
                model = app?.productClass,
                type = app?.productClass,
                hardwareVersion = app?.hardwareVersion,
                softwareVersion = app?.softwareVersion,
                isEnabled = cfg?.active?.let { it == 1 },
                isMeshSupported = true,
                macId = cfg?.macAddress,
                serial = app?.serialNumber,
            ),
            signal = SignalData(
                fourG = if (connection.lteConnected()) cell?.cellStatLte?.firstOrNull()?.toLte() else null,
                fiveG = if (connection.nrConnected()) cell?.cellStat5G?.firstOrNull()?.toNr() else null,
                generic = GenericData(
                    apn = connection?.apnConfigs?.firstOrNull()?.apn,
                    hasIPv6 = !connection?.apnConfigs?.firstOrNull()?.ipv6.isNullOrBlank(),
                    roaming = roaming(cell),
                ),
            ),
            time = TimeData(upTime = app?.upTime),
        )
    }

    fun cell(connection: ConnectionStatus?, cell: CellStatus?): CellDataRoot {
        val lte = cell?.cellStatLte?.firstOrNull()
        val nr = cell?.cellStat5G?.firstOrNull()
        val lteStat = connection?.fourGStats?.firstOrNull()?.stat
        val nrStat = connection?.fiveGStats?.firstOrNull()?.stat
        return CellDataRoot(
            cell = AdvancedCellData(
                fourG = if (connection.lteConnected()) AdvancedDataLTE(
                    cqi = lte?.cqi?.toIntOrNull(),
                    earfcn = lteStat?.earfcn?.toString(),
                    ecgi = lte?.ecgi,
                    pci = lteStat?.pci,
                    tac = cell?.cellStatGeneric?.firstOrNull()?.tac,
                    bandwidth = lte?.bandwidth,
                    mcc = lte?.mcc,
                    mnc = lte?.mnc,
                    plmn = lte?.plmnName,
                    sector = lte?.toLte(),
                ) else null,
                fiveG = if (connection.nrConnected()) AdvancedData5G(
                    cqi = nr?.cqi?.toIntOrNull(),
                    earfcn = nrStat?.nrarfcn?.toString(),
                    ecgi = nr?.ecgi,
                    pci = nrStat?.pci,
                    bandwidth = nr?.bandwidth,
                    mcc = nr?.mcc,
                    mnc = nr?.mnc,
                    plmn = nr?.plmnName,
                    sector = nr?.toNr(),
                ) else null,
                generic = GenericData(
                    apn = connection?.apnConfigs?.firstOrNull()?.apn,
                    hasIPv6 = !connection?.apnConfigs?.firstOrNull()?.ipv6.isNullOrBlank(),
                    roaming = roaming(cell),
                ),
            ),
        )
    }

    fun sim(statistics: StatisticsInfo?): SimDataRoot {
        val sim = statistics?.simConfig?.firstOrNull()
        return SimDataRoot(
            sim = SimData(
                iccId = sim?.iccid,
                imei = statistics?.networkConfig?.firstOrNull()?.imei,
                imsi = sim?.imsi,
                msisdn = sim?.msisdn,
                // The old app lower-cased the status and compared it with "Valid", which never matched.
                status = sim?.status?.equals("valid", ignoreCase = true),
            ),
        )
    }

    fun clients(device: DeviceInfoStatus?): ClientDeviceData {
        val all = device?.deviceConfig.orEmpty()
        fun wireless(types: Set<String>) = all.filter { it.interfaceType in types }.map {
            WirelessClientData(connected = it.active == 1, ipv4 = it.ipAddress, mac = it.macAddress, name = it.hostName)
        }
        return ClientDeviceData(
            clients = ClientsData(
                ethernet = all.filter { it.interfaceType == "Ethernet" }.map {
                    WiredClientData(connected = it.active == 1, ipv4 = it.ipAddress, mac = it.macAddress, name = it.hostName)
                },
                twoGig = wireless(setOf("802.11")),
                fiveGig = wireless(setOf("802.11ac", "802.11ax")),
            ),
        )
    }

    fun wifi(listing: WifiListing?): WifiConfig = WifiConfig(
        ssids = listing?.wlanList?.map { wlan ->
            SSIDConfig(
                canEditFrequencyAndGuest = false,
                twoGigSsid = wlan.type == "2.4G",
                fiveGigSsid = wlan.type == "5G",
                encryptionMode = if (wlan.wpaEncryptionModes?.startsWith("AES") == true) "AES" else "TKIP",
                encryptionVersion = encryptionToTmi(wlan.beaconType),
                guest = wlan.isGuestSsid == 1,
                isBroadcastEnabled = wlan.ssidAdvertisementEnabled == 1,
                ssidName = wlan.ssid,
                ssidId = wlan.oid,
                wpaKey = wlan.preSharedKey,
                enabled = wlan.enable == 1,
            )
        },
        canAddAndRemove = false,
    )

    fun setWifi(config: WifiConfig): SetWifiConfig = SetWifiConfig(
        paralist = config.ssids?.map { ssid ->
            SetSSIDConfig(
                beaconType = encryptionToNokia(ssid.encryptionVersion),
                enable = ssid.enabled,
                id = ssid.ssidId?.let { listOf(it.toString()) },
                preSharedKey = ssid.wpaKey,
                ssid = ssid.ssidName,
                ssidAdvertisementEnabled = ssid.isBroadcastEnabled,
                wpaEncryptionModes = "${ssid.encryptionMode}Encryption",
            )
        },
    )

    /** "WPAand11i" -> "WPA/WPA2" */
    fun encryptionToTmi(nokia: String?): String? =
        nokia?.split("and")?.joinToString("/") { if (it == "11i") "WPA2" else it }

    /** "WPA/WPA2" -> "WPAand11i" */
    fun encryptionToNokia(tmi: String?): String? =
        tmi?.split("/")?.joinToString("and") { if (it == "WPA2") "11i" else it }
}
