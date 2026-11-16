// Data shapes ported from HINT Control by Zachary Wander (MIT License).
// Field names and defaults must stay as they are: the gateway receives this object
// back in full when settings are saved (see GatewayJson.write).
package com.banner.hintcontrol.gateway.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WifiConfig(
    @SerialName("2.4ghz")
    val twoGig: BandConfig? = null,
    @SerialName("5.0ghz")
    val fiveGig: BandConfig? = null,
    @SerialName("6.0ghz")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sixGig: BandConfig? = null,
    val bandSteering: BandSteeringConfig? = null,
    val ssids: List<SSIDConfig>? = null,
    val canAddAndRemove: Boolean = true,
) {
    fun band(band: WifiBand): BandConfig? = when (band) {
        WifiBand.TwoGig -> twoGig
        WifiBand.FiveGig -> fiveGig
        WifiBand.SixGig -> sixGig
    }

    fun withBand(band: WifiBand, config: BandConfig?): WifiConfig = when (band) {
        WifiBand.TwoGig -> copy(twoGig = config)
        WifiBand.FiveGig -> copy(fiveGig = config)
        WifiBand.SixGig -> copy(sixGig = config)
    }

    val availableBands: List<WifiBand>
        get() = WifiBand.entries.filter { band(it) != null }
}

@Serializable
data class BandConfig(
    val airtimeFairness: Boolean? = null,
    val channel: String? = null,
    val channelBandwidth: String? = null,
    val isMUMIMOEnabled: Boolean? = null,
    val isRadioEnabled: Boolean? = null,
    val isWMMEnabled: Boolean? = null,
    val maxClients: Int? = null,
    val mode: String? = null,
    val transmissionPower: String? = null,
)

@Serializable
data class BandSteeringConfig(
    val isEnabled: Boolean,
)

@Serializable
data class SSIDConfig(
    @SerialName("2.4ghzSsid")
    val twoGigSsid: Boolean? = null,
    @SerialName("5.0ghzSsid")
    val fiveGigSsid: Boolean? = null,
    @SerialName("6.0ghzSsid")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sixGigSsid: Boolean? = null,
    val encryptionMode: String? = null,
    val encryptionVersion: String? = null,
    val guest: Boolean? = null,
    val isBroadcastEnabled: Boolean? = null,
    val ssidName: String? = null,
    val wpaKey: String? = null,
    val canEditFrequencyAndGuest: Boolean = true,
    val ssidId: Int? = null,
    val enabled: Boolean? = null,
) {
    fun onBand(band: WifiBand): Boolean = when (band) {
        WifiBand.TwoGig -> twoGigSsid == true
        WifiBand.FiveGig -> fiveGigSsid == true
        WifiBand.SixGig -> sixGigSsid == true
    }

    fun withBand(band: WifiBand, on: Boolean): SSIDConfig = when (band) {
        WifiBand.TwoGig -> copy(twoGigSsid = on)
        WifiBand.FiveGig -> copy(fiveGigSsid = on)
        WifiBand.SixGig -> copy(sixGigSsid = on)
    }
}

object EncryptionVersions {
    const val WPA_WPA2 = "WPA/WPA2"
    const val WPA2 = "WPA2"
    const val WPA2_WPA3 = "WPA2/WPA3"
    val all = listOf(WPA2_WPA3, WPA2, WPA_WPA2)
}

object EncryptionModes {
    const val AES = "AES"
    const val TKIP = "TKIP"
    val all = listOf(AES, TKIP)
}

object TransmissionPowers {
    const val FULL = "100%"
    const val HALF = "50%"
    val all = listOf(FULL, HALF)
}

enum class WifiBand(
    val label: String,
    val channels: List<String>,
    val bandwidths: List<String>,
) {
    TwoGig(
        label = "2.4 GHz",
        channels = (1..11).map(Int::toString),
        bandwidths = listOf("20MHz", "40MHz"),
    ),
    FiveGig(
        label = "5 GHz",
        channels = listOf(
            36, 40, 44, 48, 52, 56, 60, 64,
            100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
            149, 153, 157, 161, 165,
        ).map(Int::toString),
        bandwidths = listOf("20MHz", "40MHz", "80MHz"),
    ),
    SixGig(
        label = "6 GHz",
        channels = (1..233 step 4).map(Int::toString),
        bandwidths = listOf("40MHz", "80MHz", "160MHz", "320MHz"),
    );

    companion object {
        const val AUTO = "Auto"
    }
}

object WifiRules {
    private val wpaKeyPattern = Regex("^[\\u0020-\\u007e]{8,63}$")

    fun isValidWpaKey(key: String?): Boolean = key != null && key.isNotBlank() && wpaKeyPattern.matches(key)

    /** SSIDs are 1..32 bytes (IEEE 802.11). */
    fun isValidSsid(name: String?): Boolean =
        name != null && name.isNotBlank() && name.encodeToByteArray().size in 1..32
}
