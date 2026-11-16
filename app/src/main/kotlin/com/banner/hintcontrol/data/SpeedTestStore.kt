package com.banner.hintcontrol.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One speed test, with the gateway's signal and the phone's connection at the time. */
@Serializable
data class SpeedRecord(
    val time: Long,
    val profileId: String? = null,
    val gatewayName: String? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val server: String? = null,
    val bytesDown: Long = 0,
    val bytesUp: Long = 0,
    /** "Wi-Fi", "Ethernet" or "Mobile data". */
    val network: String? = null,
    val wifiLinkMbps: Int? = null,
    val nrBand: String? = null,
    val nrRsrp: Int? = null,
    val nrSinr: Int? = null,
    val lteBand: String? = null,
    val lteRsrp: Int? = null,
    val lteSinr: Int? = null,
)

/** Last [MAX] results, kept on the phone only. */
class SpeedTestStore(context: Context) {
    private val prefs = context.getSharedPreferences("speedtests", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(SpeedRecord.serializer())
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<SpeedRecord>> = _records.asStateFlow()

    fun add(record: SpeedRecord) = write((listOf(record) + _records.value).take(MAX))

    fun clear() = write(emptyList())

    private fun write(list: List<SpeedRecord>) {
        _records.value = list
        prefs.edit().putString(KEY, storeJson.encodeToString(serializer, list)).apply()
    }

    private fun load(): List<SpeedRecord> =
        prefs.getString(KEY, null)?.let { runCatching { storeJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private companion object {
        const val KEY = "records"
        const val MAX = 50
    }
}
