package com.banner.hintcontrol.data

import android.content.Context
import com.banner.hintcontrol.gateway.model.MainData
import com.banner.hintcontrol.gateway.signal.SignalQuality
import kotlinx.serialization.Serializable

/** Small snapshot shown by the widget and the Quick Settings tile. */
@Serializable
data class StatusSummary(
    val gatewayName: String,
    val time: Long,
    val online: Boolean,
    /** Not reachable and the phone isn't on the gateway's network, so "offline" would be misleading. */
    val away: Boolean = false,
    val nrBand: String? = null,
    val nrRsrp: Int? = null,
    val nrSinr: Int? = null,
    val lteBand: String? = null,
    val lteRsrp: Int? = null,
    val lteSinr: Int? = null,
    val score: Int? = null,
    val quality: String? = null,
)

fun MainData.toSummary(name: String, now: Long = System.currentTimeMillis()): StatusSummary {
    val nr = signal?.fiveG
    val lte = signal?.fourG
    val primary = nr?.takeIf { it.rsrp != null } ?: lte
    return StatusSummary(
        gatewayName = name,
        time = now,
        online = true,
        nrBand = nr?.bands?.joinToString("+")?.takeIf { it.isNotEmpty() },
        nrRsrp = nr?.rsrp,
        nrSinr = nr?.sinr,
        lteBand = lte?.bands?.joinToString("+")?.takeIf { it.isNotEmpty() },
        lteRsrp = lte?.rsrp,
        lteSinr = lte?.sinr,
        score = SignalQuality.score(primary),
        quality = SignalQuality.overall(primary)?.label,
    )
}

class SummaryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("summary", Context.MODE_PRIVATE)

    fun save(summary: StatusSummary) {
        prefs.edit().putString(KEY, storeJson.encodeToString(StatusSummary.serializer(), summary)).apply()
    }

    /**
     * Records that the gateway didn't answer. Pass [host] (not for the demo gateway) so a phone that
     * is simply away from the gateway's network is shown as "not on gateway network", not "offline".
     */
    fun markOffline(name: String, host: String? = null, now: Long = System.currentTimeMillis()) {
        val away = host != null && !NetworkInfo.onGatewayNetwork(context, host)
        val last = load()
        save((last ?: StatusSummary(gatewayName = name, time = now, online = false)).copy(gatewayName = name, online = false, away = away, time = now))
    }

    fun load(): StatusSummary? = prefs.getString(KEY, null)?.let {
        runCatching { storeJson.decodeFromString(StatusSummary.serializer(), it) }.getOrNull()
    }

    private companion object {
        const val KEY = "last"
    }
}
