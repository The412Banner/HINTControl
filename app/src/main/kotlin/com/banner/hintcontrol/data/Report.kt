package com.banner.hintcontrol.data

import com.banner.hintcontrol.BuildConfig
import com.banner.hintcontrol.gateway.model.BaseAdvancedData
import com.banner.hintcontrol.gateway.model.BaseCellData
import com.banner.hintcontrol.gateway.model.CellDataRoot
import com.banner.hintcontrol.gateway.model.MainData
import com.banner.hintcontrol.gateway.model.SimDataRoot
import com.banner.hintcontrol.gateway.signal.Bands
import com.banner.hintcontrol.gateway.signal.SignalQuality
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Plain-text status report for sharing (e.g. with T-Mobile support). SIM numbers are masked. */
object Report {
    fun build(profileName: String, main: MainData?, cell: CellDataRoot?, sim: SimDataRoot?, now: Long = System.currentTimeMillis()): String = buildString {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(now))
        appendLine("Gateway report — $profileName")
        appendLine(time)
        appendLine()
        main?.device?.let { d ->
            appendLine("Gateway: ${d.displayName ?: "unknown"}")
            d.softwareVersion?.let { appendLine("Firmware: $it") }
            d.hardwareVersion?.let { appendLine("Hardware: $it") }
        }
        main?.time?.upTime?.let { appendLine("Uptime: ${formatUptimeShort(it)}") }
        main?.signal?.generic?.let { g ->
            g.registration?.let { appendLine("Registration: $it") }
            g.apn?.let { appendLine("APN: $it") }
            g.roaming?.let { appendLine("Roaming: ${if (it) "yes" else "no"}") }
        }
        section("5G", main?.signal?.fiveG, cell?.cell?.fiveG, nr = true)
        section("LTE", main?.signal?.fourG, cell?.cell?.fourG, nr = false)
        sim?.sim?.let { s ->
            appendLine()
            appendLine("SIM: ${if (s.status == true) "active" else "not active"}")
            s.iccId?.let { appendLine("ICCID: ${mask(it)}") }
            s.imei?.let { appendLine("IMEI: ${mask(it)}") }
        }
        appendLine()
        append("Hint Plus ${BuildConfig.VERSION_NAME}")
    }

    private fun StringBuilder.section(title: String, sector: BaseCellData?, adv: BaseAdvancedData?, nr: Boolean) {
        val s = sector ?: adv?.sector ?: return
        appendLine()
        val bands = s.bands.orEmpty().joinToString { b -> Bands.describe(b, nr).let { i -> listOfNotNull(i.id, i.frequency).joinToString(" ") } }
        appendLine("$title: ${bands.ifBlank { "connected" }}")
        appendLine("  RSRP ${s.rsrp ?: "—"} dBm · SINR ${s.sinr ?: "—"} dB · RSRQ ${s.rsrq ?: "—"} dB · RSSI ${s.rssi ?: "—"} dBm")
        SignalQuality.overall(s)?.let { appendLine("  Quality: ${it.label} (score ${SignalQuality.score(s)})") }
        s.nbid?.let { appendLine("  ${if (nr) "gNB" else "eNB"}: $it${s.cid?.let { c -> " · cell $c" } ?: ""}") }
        adv?.let { a ->
            listOfNotNull(
                a.pci?.let { "PCI $it" },
                a.earfcn?.let { "${if (nr) "NR-ARFCN" else "EARFCN"} $it" },
                a.bandwidth?.let { "BW $it" },
                a.cqi?.let { "CQI $it" },
            ).takeIf { it.isNotEmpty() }?.let { appendLine("  " + it.joinToString(" · ")) }
        }
    }

    fun mask(value: String): String =
        if (value.length <= 4) "••••" else "•".repeat((value.length - 4).coerceAtMost(12)) + value.takeLast(4)
}
