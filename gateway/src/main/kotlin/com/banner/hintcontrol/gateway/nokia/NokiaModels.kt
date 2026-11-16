// Nokia 5G21 response shapes ported from HINT Control by Zachary Wander (MIT License).
package com.banner.hintcontrol.gateway.nokia

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable



@Serializable
data class CellStatus(
    @SerialName("cell_stat_generic")
    val cellStatGeneric: List<CellStatGeneric>? = null,
    @SerialName("cell_stat_lte")
    val cellStatLte: List<CellStat>? = null,
    @SerialName("cell_stat_5G")
    val cellStat5G: List<CellStat>? = null,
)

@Serializable
data class CellStatGeneric(
    @SerialName("RoamingStatus")
    val roamingStatus: String? = null,
    @SerialName("CurrentAccessTechnology")
    val currentAccessTechnology: String? = null,
    @SerialName("X_ALU_COM_TAC")
    val tac: String? = null,
    @SerialName("BytesSent")
    val bytesSent: Long? = null,
    @SerialName("BytesReceived")
    val bytesReceived: Long? = null,
    @SerialName("Status")
    val status: String? = null,
    @SerialName("CpeAbnormalStatus")
    val cpeAbnormalStatus: Int? = null,
)

@Serializable
data class CellStat(
    @SerialName("RSRPThreshold")
    val rsrpThreshold: String? = null,
    @SerialName("SNRCurrent")
    val snr: Int? = null,
    @SerialName("RSRPCurrent")
    val rsrp: Int? = null,
    @SerialName("RSRQCurrent")
    val rsrq: Int? = null,
    @SerialName("Band")
    val band: String? = null,
    @SerialName("Bandwidth")
    val bandwidth: String? = null,
    @SerialName("RSRPStrengthIndexCurrent")
    val rsrpStrengthIndex: Int? = null,
    @SerialName("RSRPSignalLevelCode")
    val rsrpSignalLevelCode: Int? = null,
    @SerialName("PLMNName")
    val plmnName: String? = null,
    @SerialName("CQI")
    val cqi: String? = null,
    @SerialName("ECGI")
    val ecgi: String? = null,
    @SerialName("MCC")
    val mcc: String? = null,
    @SerialName("MNC")
    val mnc: String? = null,
    @SerialName("eNBID")
    val enbid: String? = null,
    @SerialName("Cellid")
    val cellId: String? = null,
    @SerialName("RSSICurrent")
    val rssi: Int? = null,
)



@Serializable
data class ConnectionStatus(
    @SerialName("connection_status")
    val connectionStatus: List<ConnectionStatusStatus>? = null,
    @SerialName("apn_cfg")
    val apnConfigs: List<ApnConfig>? = null,
    @SerialName("cellular_stats")
    val cellularStats: List<CellularStats>? = null,
    @SerialName("ethernet_stats")
    val ethernetStats: List<EthernetStats>? = null,
    @SerialName("cell_CA_stats_cfg")
    val caStats: List<CellCAStatsConfig>? = null,
    @SerialName("cell_5G_stats_cfg")
    val fiveGStats: List<CellStatsConfig5G>? = null,
    @SerialName("cell_LTE_stats_cfg")
    val fourGStats: List<CellStatsConfigLte>? = null,
)

@Serializable
data class CellularStats(
    @SerialName("BytesReceived")
    val bytesReceived: Long? = null,
    @SerialName("BytesSent")
    val bytesSent: Long? = null,
)

@Serializable
data class Stats(
    @SerialName("BytesReceived")
    val bytesReceived: Long? = null,
    @SerialName("BytesSent")
    val bytesSent: Long? = null,
    @SerialName("PacketsReceived")
    val packetsReceived: Long? = null,
    @SerialName("PacketsSent")
    val packetsSent: Long? = null,
)

@Serializable
data class EthernetStats(
    @SerialName("Enable")
    val enable: Int? = null,
    @SerialName("Status")
    val status: String? = null,
    val stat: Stats? = null,
)

@Serializable
data class CellCAStatsConfig(
    @SerialName("X_ALU_COM_DLCarrierAggregationNumberOfEntries")
    val dlEntries: Int? = null,
    @SerialName("X_ALU_COM_UL_CarrierAggregationNumberOfEntries")
    val ulEntries: Int? = null,
    @SerialName("ca4GDL")
    val fourGDL: Map<String, CAInfo>? = null,
    @SerialName("ca4GUL")
    val fourGUL: Map<String, CAInfo>? = null,
)

@Serializable
data class CAInfo(
    @SerialName("PhysicalCellID")
    val pci: Int? = null,
    @SerialName("ScellBand")
    val band: String? = null,
    @SerialName("ScellChannel")
    val channel: Int? = null,
)

interface CellStatsConfig {
    val stat: CellStats?
}

@Serializable
data class CellStatsConfigLte(
    override val stat: CellStatsLte? = null,
) : CellStatsConfig

@Serializable
data class CellStatsLte(
    @SerialName("RSSICurrent")
    override val rssi: Int? = null,
    @SerialName("SNRCurrent")
    override val snr: Int? = null,
    @SerialName("RSRPCurrent")
    override val rsrp: Int? = null,
    @SerialName("RSRPStrengthIndexCurrent")
    override val rsrpStrengthIndex: Int? = null,
    @SerialName("PhysicalCellID")
    override val pci: String? = null,
    @SerialName("RSRQCurrent")
    override val rsrq: Int? = null,
    @SerialName("SignalStrengthLevel")
    override val signalStrengthLevel: Int? = null,
    @SerialName("Band")
    override val band: String? = null,
    @SerialName("DownlinkEarfcn")
    val earfcn: Long? = null,
) : CellStats

@Serializable
data class CellStatsConfig5G(
    override val stat: CellStats5G? = null,
) : CellStatsConfig

@Serializable
data class CellStats5G(
    @SerialName("RSSICurrent")
    override val rssi: Int? = null,
    @SerialName("SNRCurrent")
    override val snr: Int? = null,
    @SerialName("RSRPCurrent")
    override val rsrp: Int? = null,
    @SerialName("RSRPStrengthIndexCurrent")
    override val rsrpStrengthIndex: Int? = null,
    @SerialName("PhysicalCellID")
    override val pci: String? = null,
    @SerialName("RSRQCurrent")
    override val rsrq: Int? = null,
    @SerialName("SignalStrengthLevel")
    override val signalStrengthLevel: Int? = null,
    @SerialName("Band")
    override val band: String? = null,
    @SerialName("Downlink_NR_ARFCN")
    val nrarfcn: Long? = null,
) : CellStats

interface CellStats {
    @SerialName("RSSICurrent")
    val rssi: Int?
    @SerialName("SNRCurrent")
    val snr: Int?
    @SerialName("RSRPCurrent")
    val rsrp: Int?
    @SerialName("RSRPStrengthIndexCurrent")
    val rsrpStrengthIndex: Int?
    @SerialName("PhysicalCellID")
    val pci: String?
    @SerialName("RSRQCurrent")
    val rsrq: Int?
    @SerialName("SignalStrengthLevel")
    val signalStrengthLevel: Int?
    @SerialName("Band")
    val band: String?
}

@Serializable
data class ConnectionStatusStatus(
    @SerialName("ConnectionStatus")
    val connectionStatus: Int? = null,
)

@Serializable
data class ApnConfig(
    val oid: Int? = null,
    @SerialName("Enable")
    val enable: Int? = null,
    @SerialName("APN")
    val apn: String? = null,
    @SerialName("X_ALU_COM_ServiceType")
    val serviceType: String? = null,
    @SerialName("X_ALU_COM_ConnectionState")
    val connectionState: Int? = null,
    @SerialName("X_ALU_COM_IPAddressV4")
    val ipv4: String? = null,
    @SerialName("X_ALU_COM_IPAddressV6")
    val ipv6: String? = null,
)



@Serializable
data class DeviceInfoStatus(
    @SerialName("device_app_status")
    val deviceAppStatus: List<DeviceAppStatus>? = null,
    @SerialName("device_cfg")
    val deviceConfig: List<DeviceConfigItem>? = null,
    @SerialName("bluetooth_status")
    val bluetoothStatus: List<BluetoothStatus>? = null,
    @SerialName("unread_sms")
    val unreadSms: List<UnreadSms>? = null,
)

@Serializable
data class DeviceAppStatus(
    @SerialName("ManufacturerOUI")
    val manufacturer: String? = null,
    @SerialName("ProductClass")
    val productClass: String? = null,
    @SerialName("SerialNumber")
    val serialNumber: String? = null,
    @SerialName("HardwareVersion")
    val hardwareVersion: String? = null,
    @SerialName("SoftwareVersion")
    val softwareVersion: String? = null,
    @SerialName("Description")
    val description: String? = null,
    @SerialName("X_ALU_COM_AllowedForwarding")
    val forwardingAllowed: Int? = null,
    @SerialName("UpTime")
    val upTime: Long? = null,
    @SerialName("lot_number")
    val lotNumber: String? = null,
)

@Serializable
data class DeviceConfigItem(
    @SerialName("_oid")
    val oid: Int? = null,
    @SerialName("HostName")
    val hostName: String? = null,
    @SerialName("IPAddress")
    val ipAddress: String? = null,
    @SerialName("MACAddress")
    val macAddress: String? = null,
    @SerialName("AddressSource")
    val addressSource: String? = null,
    @SerialName("Active")
    val active: Int? = null,
    @SerialName("InterfaceType")
    val interfaceType: String? = null,
    @SerialName("LeaseTimeRemaining")
    val leaseTimeRemaining: Long? = null,
    @SerialName("X_ALU_COM_LastActiveTime")
    val lastActiveTime: String? = null,
)

@Serializable
data class BluetoothStatus(
    @SerialName("Enable")
    val enable: Int? = null,
    @SerialName("Status")
    val status: String? = null,
)

@Serializable
data class UnreadSms(
    @SerialName("UnreadSMS")
    val unreadSms: Int? = null,
)



@Serializable
data class DeviceStatus(
    @SerialName("num_extenders")
    val numExtenders: Int? = null,
    @SerialName("extender_info")
    val extenderInfo: List<ExtenderInfo>? = null,
)

@Serializable
data class ExtenderInfo(
    @SerialName("beacon_mac")
    val beaconMac: String? = null,
    @SerialName("beacon_sno")
    val beaconSno: String? = null,
    @SerialName("beacon_state")
    val beaconState: String? = null,
    @SerialName("beacon_isOnline")
    val beaconIsOnline: Int? = null,
    @SerialName("beacon_swVersion")
    val beaconSwVersion: String? = null,
)



@Serializable
data class RebootAction(
    val action: String = "Reboot",
)



@Serializable
data class SetWifiConfig(
    val action: String = "WLANConfig",
    val paralist: List<SetSSIDConfig>?,
)

@Serializable
data class SetSSIDConfig(
    @SerialName("BeaconType")
    val beaconType: String?,
    @SerialName("Enable")
    val enable: Boolean?,
    @SerialName("id")
    val id: List<String>?,
    @SerialName("PreSharedKey")
    val preSharedKey: String?,
    @SerialName("SSID")
    val ssid: String?,
    @SerialName("SSIDAdvertisementEnabled")
    val ssidAdvertisementEnabled: Boolean?,
    @SerialName("WPAEncryptionModes")
    val wpaEncryptionModes: String?,
)



@Serializable
data class StatisticsInfo(
    @SerialName("network_cfg")
    val networkConfig: List<NetworkConfig>? = null,
    @SerialName("sim_cfg")
    val simConfig: List<SIMConfig>? = null,
)

@Serializable
data class NetworkConfig(
    @SerialName("IMEI")
    val imei: String? = null,
)

@Serializable
data class SIMConfig(
    @SerialName("Type")
    val type: String? = null,
    @SerialName("Status")
    val status: String? = null,
    @SerialName("IMSI")
    val imsi: String? = null,
    @SerialName("ICCID")
    val iccid: String? = null,
    @SerialName("MSISDN")
    val msisdn: String? = null,
)



@Serializable
data class WifiListing(
    @SerialName("wlan_list")
    val wlanList: List<NokiaWlan>? = null,
    @SerialName("guest_list")
    val guestList: List<NokiaWlan>? = null,
)

@Serializable
data class NokiaWlan(
    val oid: Int? = null,
    @SerialName("Enable")
    val enable: Int? = null,
    @SerialName("Type")
    val type: String? = null,
    @SerialName("SSID")
    val ssid: String? = null,
    @SerialName("IsGuestSSID")
    val isGuestSsid: Int? = null,
    @SerialName("BeaconType")
    val beaconType: String? = null,
    @SerialName("BasicAuthenticationMode")
    val basicAuthenticationMode: String? = null,
    @SerialName("BasicEncryptionModes")
    val basicEncryptionModes: String? = null,
    @SerialName("WEPEncryptionLevel")
    val wepEncryptionLevel: String? = null,
    @SerialName("WPAAuthenticationMode")
    val wpaAuthenticationMode: String? = null,
    @SerialName("WPAEncryptionModes")
    val wpaEncryptionModes: String? = null,
    @SerialName("PreSharedKey")
    val preSharedKey: String? = null,
    @SerialName("WEPKey")
    val wepKey: String? = null,
    @SerialName("PossibleChannels")
    val possibleChannels: String? = null,
    @SerialName("SSIDAdvertisementEnabled")
    val ssidAdvertisementEnabled: Int? = null,
)
