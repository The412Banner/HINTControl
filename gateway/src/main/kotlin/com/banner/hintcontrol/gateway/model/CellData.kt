// Data shapes ported from HINT Control by Zachary Wander (MIT License).
package com.banner.hintcontrol.gateway.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class CellDataRoot(
    val cell: AdvancedCellData? = null,
)

@Serializable
data class AdvancedCellData(
    @SerialName("4g")
    val fourG: AdvancedDataLTE? = null,
    @SerialName("5g")
    val fiveG: AdvancedData5G? = null,
    val generic: GenericData? = null,
    val gps: GPSData? = null,
)

interface BaseAdvancedData {
    val bandwidth: String?
    val mcc: String?
    val mnc: String?
    val plmn: String?
    val status: Boolean?
    val supportedBands: List<String>?
    val sector: BaseCellData?
    val earfcn: String?
    val cqi: Int?
    val ecgi: String?
    val pci: String?
    val tac: String?
}

@Serializable
data class AdvancedDataLTE(
    override val cqi: Int? = null,
    override val earfcn: String? = null,
    override val ecgi: String? = null,
    override val pci: String? = null,
    override val tac: String? = null,
    override val bandwidth: String? = null,
    override val mcc: String? = null,
    override val mnc: String? = null,
    override val plmn: String? = null,
    override val status: Boolean? = null,
    override val supportedBands: List<String>? = null,
    override val sector: CellDataLTE? = null,
) : BaseAdvancedData

@Serializable
data class AdvancedData5G(
    override val cqi: Int? = null,
    override val earfcn: String? = null,
    override val ecgi: String? = null,
    override val pci: String? = null,
    override val tac: String? = null,
    override val bandwidth: String? = null,
    override val mcc: String? = null,
    override val mnc: String? = null,
    override val plmn: String? = null,
    override val status: Boolean? = null,
    override val supportedBands: List<String>? = null,
    override val sector: CellData5G? = null,
) : BaseAdvancedData

// Gateways send "latitude"/"longitude"; the old app read "lat"/"lon", so both are accepted.
@Serializable
data class GPSData(
    @JsonNames("lat")
    val latitude: Double? = null,
    @JsonNames("lon")
    val longitude: Double? = null,
)

@Serializable
data class SimDataRoot(
    val sim: SimData? = null,
)

@Serializable
data class SimData(
    val iccId: String? = null,
    val imei: String? = null,
    val imsi: String? = null,
    val msisdn: String? = null,
    val status: Boolean? = null,
)
