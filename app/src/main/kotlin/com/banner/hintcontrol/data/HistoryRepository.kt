package com.banner.hintcontrol.data

import com.banner.hintcontrol.data.db.EventType
import com.banner.hintcontrol.data.db.GatewayEvent
import com.banner.hintcontrol.data.db.HistoryDatabase
import com.banner.hintcontrol.data.db.KnownDevice
import com.banner.hintcontrol.data.db.SignalSample
import com.banner.hintcontrol.gateway.model.Client
import com.banner.hintcontrol.gateway.model.MainData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What changed since the previous reading, for alerts. */
data class Observation(
    val sample: SignalSample,
    val rebooted: Boolean,
    val nrBandChanged: Pair<String?, String?>?,
    val lteBandChanged: Pair<String?, String?>?,
)

class HistoryRepository(private val db: HistoryDatabase) {
    private val mutex = Mutex()
    private val lastRecorded = mutableMapOf<String, Long>()
    private val lastSeen = mutableMapOf<String, SignalSample>()

    fun samples(profileId: String, since: Long): Flow<List<SignalSample>> = db.samples().since(profileId, since)

    suspend fun samplesOnce(profileId: String, since: Long): List<SignalSample> = db.samples().sinceOnce(profileId, since)

    fun sampleCount(profileId: String): Flow<Int> = db.samples().count(profileId)

    fun events(profileId: String): Flow<List<GatewayEvent>> = db.events().recent(profileId)

    fun devices(profileId: String): Flow<List<KnownDevice>> = db.devices().all(profileId)

    suspend fun addEvent(profileId: String, type: EventType, detail: String? = null, time: Long = System.currentTimeMillis()) {
        db.events().insert(GatewayEvent(profileId = profileId, time = time, type = type, detail = detail))
    }

    suspend fun lastEvent(profileId: String, type: EventType): GatewayEvent? = db.events().last(profileId, type)

    suspend fun clearHistory(profileId: String) {
        db.samples().clear(profileId)
        lastSeen.remove(profileId)
    }

    suspend fun clearEvents(profileId: String) = db.events().clear(profileId)

    suspend fun prune(keepDays: Int, now: Long = System.currentTimeMillis()) {
        val before = now - keepDays.coerceAtLeast(1) * 86_400_000L
        db.samples().deleteBefore(before)
        db.events().deleteBefore(now - 365L * 86_400_000L)
    }

    /**
     * Compares [main] with the previous reading (reboot = uptime went down, band changes) and
     * stores a sample when [record] is set and at least [minIntervalMs] passed since the last one.
     */
    suspend fun observe(
        profileId: String,
        main: MainData,
        record: Boolean,
        minIntervalMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Observation = mutex.withLock {
        val sample = main.toSample(profileId, now)
        val previous = lastSeen[profileId] ?: db.samples().latest(profileId)
        // Uptime well below "previous uptime + time since then" means the gateway restarted in between.
        val rebooted = previous?.uptime != null && sample.uptime != null &&
            now - previous.time < 7 * 86_400_000L &&
            (previous.uptime + (now - previous.time) / 1000) - sample.uptime > 120
        val nrChanged = previous?.let { p -> if (p.nrBand != sample.nrBand && (p.nrBand != null || sample.nrBand != null)) p.nrBand to sample.nrBand else null }
        val lteChanged = previous?.let { p -> if (p.lteBand != sample.lteBand && (p.lteBand != null || sample.lteBand != null)) p.lteBand to sample.lteBand else null }
        lastSeen[profileId] = sample
        if (record && now - (lastRecorded[profileId] ?: 0L) >= minIntervalMs) {
            db.samples().insert(sample)
            lastRecorded[profileId] = now
        }
        if (rebooted) addEvent(profileId, EventType.RebootDetected, "Uptime ${formatUptimeShort(sample.uptime ?: 0)}", now)
        if (nrChanged != null && previous != null) addEvent(profileId, EventType.BandChange, "5G ${nrChanged.first ?: "none"} → ${nrChanged.second ?: "none"}", now)
        if (lteChanged != null && previous != null) addEvent(profileId, EventType.BandChange, "LTE ${lteChanged.first ?: "none"} → ${lteChanged.second ?: "none"}", now)
        Observation(sample, rebooted, nrChanged, lteChanged)
    }

    /** Updates the device list; returns devices never seen before (not on the very first run). */
    suspend fun updateDevices(profileId: String, clients: List<Client>, now: Long = System.currentTimeMillis()): List<Client> {
        val known = db.devices().allOnce(profileId).associateBy { it.deviceKey }
        val firstRun = known.isEmpty()
        val fresh = mutableListOf<Client>()
        val rows = clients.map { c ->
            val k = known[c.key]
            if (k == null && !firstRun) fresh += c
            KnownDevice(
                profileId = profileId,
                deviceKey = c.key,
                alias = k?.alias,
                lastName = c.name ?: k?.lastName,
                lastIp = c.ipv4 ?: k?.lastIp,
                link = c.link.name,
                firstSeen = k?.firstSeen ?: now,
                lastSeen = if (c.connected) now else (k?.lastSeen ?: now),
            )
        }
        db.devices().upsert(rows)
        return fresh
    }

    suspend fun setAlias(profileId: String, key: String, alias: String?) =
        db.devices().setAlias(profileId, key, alias?.trim()?.takeIf { it.isNotEmpty() })
}

fun MainData.toSample(profileId: String, now: Long) = SignalSample(
    profileId = profileId,
    time = now,
    nrRsrp = signal?.fiveG?.rsrp,
    nrSinr = signal?.fiveG?.sinr,
    nrRsrq = signal?.fiveG?.rsrq,
    nrRssi = signal?.fiveG?.rssi,
    nrBand = signal?.fiveG?.bands?.joinToString("+")?.takeIf { it.isNotEmpty() },
    nrCell = signal?.fiveG?.nbid,
    lteRsrp = signal?.fourG?.rsrp,
    lteSinr = signal?.fourG?.sinr,
    lteRsrq = signal?.fourG?.rsrq,
    lteRssi = signal?.fourG?.rssi,
    lteBand = signal?.fourG?.bands?.joinToString("+")?.takeIf { it.isNotEmpty() },
    lteCell = signal?.fourG?.nbid,
    uptime = time?.upTime,
)

fun formatUptimeShort(seconds: Long): String {
    val d = seconds / 86_400
    val h = (seconds % 86_400) / 3_600
    val m = (seconds % 3_600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}
