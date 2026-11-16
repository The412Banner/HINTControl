package com.banner.hintcontrol.ui.wifi

import com.banner.hintcontrol.gateway.GatewayKind
import com.banner.hintcontrol.gateway.model.BandConfig
import com.banner.hintcontrol.gateway.model.SSIDConfig
import com.banner.hintcontrol.gateway.model.WifiBand
import com.banner.hintcontrol.gateway.model.WifiConfig

/**
 * Edited copy of the gateway's Wi-Fi settings. [origins] holds, for each network in
 * [config], its index in the original list (null = added), so changes can be described.
 */
data class WifiDraft(
    val config: WifiConfig,
    val origins: List<Int?>,
) {
    companion object {
        fun of(config: WifiConfig) = WifiDraft(config, config.ssids.orEmpty().indices.map { it })
    }

    fun updateBand(band: WifiBand, change: (BandConfig) -> BandConfig): WifiDraft {
        val b = config.band(band) ?: return this
        return copy(config = config.withBand(band, change(b)))
    }

    fun updateSsid(index: Int, ssid: SSIDConfig): WifiDraft {
        val list = config.ssids.orEmpty().toMutableList()
        list[index] = ssid
        return copy(config = config.copy(ssids = list))
    }

    fun addSsid(ssid: SSIDConfig): WifiDraft =
        copy(config = config.copy(ssids = config.ssids.orEmpty() + ssid), origins = origins + null)

    fun removeSsid(index: Int): WifiDraft = copy(
        config = config.copy(ssids = config.ssids.orEmpty().filterIndexed { i, _ -> i != index }),
        origins = origins.filterIndexed { i, _ -> i != index },
    )
}

object WifiChanges {
    /**
     * What the gateway didn't keep: compares the settings read back after saving with what was sent,
     * network by network in order (the gateway returns its list in the same order).
     */
    fun notKept(readBack: WifiConfig, sent: WifiConfig): List<String> =
        describe(readBack, WifiDraft(sent, sent.ssids.orEmpty().indices.map { i -> i.takeIf { it < readBack.ssids.orEmpty().size } }))

    fun describe(original: WifiConfig?, draft: WifiDraft?): List<String> {
        if (original == null || draft == null || original == draft.config) return emptyList()
        val edited = draft.config
        val out = mutableListOf<String>()
        WifiBand.entries.forEach { band ->
            val a = original.band(band)
            val b = edited.band(band)
            if (a != null && b != null && a != b) out += bandChanges(band, a, b)
        }
        val steering = edited.bandSteering
        if (steering != null && original.bandSteering?.isEnabled != steering.isEnabled) {
            out += "Band steering ${onOff(steering.isEnabled)}"
        }
        val before = original.ssids.orEmpty()
        val after = edited.ssids.orEmpty()
        after.forEachIndexed { i, s ->
            val from = draft.origins.getOrNull(i)?.let(before::getOrNull)
            if (from == null) out += "New network \"${s.ssidName}\"" else out += ssidChanges(from, s)
        }
        before.indices.filter { it !in draft.origins }.forEach { out += "Remove network \"${before[it].ssidName}\"" }
        return out
    }

    private fun bandChanges(band: WifiBand, a: BandConfig, b: BandConfig): List<String> = buildList {
        if (a.isRadioEnabled != b.isRadioEnabled) add("${band.label} radio ${onOff(b.isRadioEnabled == true)}")
        if (a.channel != b.channel) add("${band.label} channel ${a.channel ?: "?"} → ${b.channel}")
        if (a.channelBandwidth != b.channelBandwidth) add("${band.label} width ${a.channelBandwidth ?: "?"} → ${b.channelBandwidth}")
        if (a.transmissionPower != b.transmissionPower) add("${band.label} power ${a.transmissionPower ?: "?"} → ${b.transmissionPower}")
    }

    private fun ssidChanges(a: SSIDConfig, b: SSIDConfig): List<String> = buildList {
        val name = "\"${a.ssidName}\""
        if (a.ssidName != b.ssidName) add("Rename $name to \"${b.ssidName}\"")
        if (a.wpaKey != b.wpaKey) add("New password for $name")
        if (a.encryptionVersion != b.encryptionVersion) add("$name security ${a.encryptionVersion} → ${b.encryptionVersion}")
        if (a.encryptionMode != b.encryptionMode) add("$name encryption ${a.encryptionMode} → ${b.encryptionMode}")
        if (a.isBroadcastEnabled != b.isBroadcastEnabled) add("$name ${if (b.isBroadcastEnabled == true) "visible" else "hidden"}")
        if (a.guest != b.guest) add("$name guest network ${onOff(b.guest == true)}")
        if (a.enabled != b.enabled) add("$name turned ${onOff(b.enabled == true)}")
        WifiBand.entries.forEach { band -> if (a.onBand(band) != b.onBand(band)) add("$name ${onOff(b.onBand(band))} ${band.label}") }
    }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    enum class Warning(val text: String) {
        AllRadiosOff("Every Wi-Fi radio would be turned off. Only do this with a wired connection to the gateway; otherwise you can't reach it again without a factory reset."),
        FiveGhzOff("Turning off 5 GHz on this gateway may make it hang while booting, and many settings can't be changed again until it is factory reset."),
        Disconnect("Devices on a renamed network or with a changed password or security will disconnect — possibly this phone too."),
    }

    fun warnings(original: WifiConfig?, draft: WifiDraft?, kind: GatewayKind?): List<Warning> {
        if (original == null || draft == null) return emptyList()
        val edited = draft.config
        val out = mutableListOf<Warning>()
        val bands = WifiBand.entries.filter { edited.band(it) != null }
        if (bands.isNotEmpty() && bands.all { edited.band(it)?.isRadioEnabled == false } && bands.any { original.band(it)?.isRadioEnabled == true }) {
            out += Warning.AllRadiosOff
        }
        if (kind == GatewayKind.Tmi && original.fiveGig?.isRadioEnabled == true && edited.fiveGig?.isRadioEnabled == false) {
            out += Warning.FiveGhzOff
        }
        val before = original.ssids.orEmpty()
        val credentials = edited.ssids.orEmpty().withIndex().any { (i, s) ->
            val from = draft.origins.getOrNull(i)?.let(before::getOrNull) ?: return@any false
            from.ssidName != s.ssidName || from.wpaKey != s.wpaKey || from.encryptionVersion != s.encryptionVersion
        }
        if (credentials) out += Warning.Disconnect
        return out
    }
}
