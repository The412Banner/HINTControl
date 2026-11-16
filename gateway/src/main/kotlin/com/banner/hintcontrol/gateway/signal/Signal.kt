package com.banner.hintcontrol.gateway.signal

import com.banner.hintcontrol.gateway.model.BaseCellData
import kotlin.math.roundToInt

enum class Metric(val label: String, val unit: String, val gaugeMin: Int, val gaugeMax: Int) {
    RSRP("RSRP", "dBm", -130, -60),
    SINR("SINR", "dB", -10, 30),
    RSRQ("RSRQ", "dB", -25, -3),
    RSSI("RSSI", "dBm", -110, -40),
}

enum class Quality(val label: String) {
    Excellent("Excellent"),
    Good("Good"),
    Fair("Fair"),
    Poor("Poor"),
}

/** Common rule-of-thumb ranges for LTE/NR reference-signal readings. */
object SignalQuality {
    fun rate(metric: Metric, value: Int?): Quality? {
        value ?: return null
        val (excellent, good, fair) = when (metric) {
            Metric.RSRP -> Triple(-80, -90, -100)
            Metric.SINR -> Triple(20, 13, 0)
            Metric.RSRQ -> Triple(-10, -15, -20)
            Metric.RSSI -> Triple(-65, -75, -85)
        }
        return when {
            value >= excellent -> Quality.Excellent
            value >= good -> Quality.Good
            value >= fair -> Quality.Fair
            else -> Quality.Poor
        }
    }

    /** 0..1 position of [value] on the gauge for [metric]. */
    fun fraction(metric: Metric, value: Int?): Float {
        value ?: return 0f
        return ((value - metric.gaugeMin).toFloat() / (metric.gaugeMax - metric.gaugeMin)).coerceIn(0f, 1f)
    }

    /** Worse of RSRP and SINR: strength alone isn't enough if the signal is noisy. */
    fun overall(cell: BaseCellData?): Quality? {
        cell ?: return null
        val r = rate(Metric.RSRP, cell.rsrp)
        val s = rate(Metric.SINR, cell.sinr)
        return listOfNotNull(r, s).maxByOrNull { it.ordinal }
    }

    /**
     * 0..100 combined score used by the placement helper: RSRP 50 %, SINR 40 %, RSRQ 10 %.
     * Missing values are left out and the remaining weights rescaled.
     */
    fun score(rsrp: Int?, sinr: Int?, rsrq: Int?): Int? {
        val parts = listOfNotNull(
            rsrp?.let { norm(it, -120, -75) to 0.5 },
            sinr?.let { norm(it, -5, 25) to 0.4 },
            rsrq?.let { norm(it, -20, -5) to 0.1 },
        )
        if (parts.isEmpty()) return null
        val weight = parts.sumOf { it.second }
        return (parts.sumOf { it.first * it.second } / weight * 100).roundToInt().coerceIn(0, 100)
    }

    fun score(cell: BaseCellData?): Int? = cell?.let { score(it.rsrp, it.sinr, it.rsrq) }

    private fun norm(v: Int, lo: Int, hi: Int): Double = ((v - lo).toDouble() / (hi - lo)).coerceIn(0.0, 1.0)
}

enum class BandClass(val label: String) {
    Low("Low band"),
    Mid("Mid band"),
    High("mmWave"),
}

data class BandInfo(
    val id: String,
    val isNr: Boolean,
    val number: Int?,
    val frequency: String?,
    val duplex: String?,
    val bandClass: BandClass?,
) {
    val summary: String
        get() = listOfNotNull(frequency, bandClass?.label).joinToString(" · ")
}

object Bands {
    private data class Spec(val frequency: String, val duplex: String, val cls: BandClass)

    private val lte = mapOf(
        2 to Spec("1900 MHz", "FDD", BandClass.Mid),
        4 to Spec("1700/2100 MHz", "FDD", BandClass.Mid),
        5 to Spec("850 MHz", "FDD", BandClass.Low),
        12 to Spec("700 MHz", "FDD", BandClass.Low),
        13 to Spec("700 MHz", "FDD", BandClass.Low),
        14 to Spec("700 MHz", "FDD", BandClass.Low),
        17 to Spec("700 MHz", "FDD", BandClass.Low),
        25 to Spec("1900 MHz", "FDD", BandClass.Mid),
        26 to Spec("850 MHz", "FDD", BandClass.Low),
        29 to Spec("700 MHz", "SDL", BandClass.Low),
        30 to Spec("2300 MHz", "FDD", BandClass.Mid),
        41 to Spec("2.5 GHz", "TDD", BandClass.Mid),
        46 to Spec("5.2 GHz", "TDD", BandClass.Mid),
        48 to Spec("3.5 GHz", "TDD", BandClass.Mid),
        66 to Spec("1700/2100 MHz", "FDD", BandClass.Mid),
        71 to Spec("600 MHz", "FDD", BandClass.Low),
    )

    private val nr = mapOf(
        2 to Spec("1900 MHz", "FDD", BandClass.Mid),
        5 to Spec("850 MHz", "FDD", BandClass.Low),
        12 to Spec("700 MHz", "FDD", BandClass.Low),
        14 to Spec("700 MHz", "FDD", BandClass.Low),
        25 to Spec("1900 MHz", "FDD", BandClass.Mid),
        41 to Spec("2.5 GHz", "TDD", BandClass.Mid),
        48 to Spec("3.5 GHz", "TDD", BandClass.Mid),
        66 to Spec("1700/2100 MHz", "FDD", BandClass.Mid),
        70 to Spec("1700/2000 MHz", "FDD", BandClass.Mid),
        71 to Spec("600 MHz", "FDD", BandClass.Low),
        77 to Spec("3.7 GHz", "TDD", BandClass.Mid),
        78 to Spec("3.5 GHz", "TDD", BandClass.Mid),
        258 to Spec("26 GHz", "TDD", BandClass.High),
        260 to Spec("39 GHz", "TDD", BandClass.High),
        261 to Spec("28 GHz", "TDD", BandClass.High),
    )

    /**
     * [raw] is what the gateway reports ("n41", "b2", "B66", "LTE BAND 2", "71").
     * [nrHint] decides the technology when the string has no prefix.
     */
    fun describe(raw: String, nrHint: Boolean): BandInfo {
        val clean = raw.trim().lowercase()
        val digits = Regex("(\\d+)").findAll(clean).lastOrNull()?.value?.toIntOrNull()
        val isNr = when {
            clean.startsWith("n") -> true
            clean.startsWith("b") || clean.startsWith("lte") -> false
            else -> nrHint
        }
        val spec = digits?.let { (if (isNr) nr else lte)[it] }
        val id = if (digits != null) (if (isNr) "n$digits" else "b$digits") else raw.trim()
        return BandInfo(id, isNr, digits, spec?.frequency, spec?.duplex, spec?.cls)
    }
}

object CellMath {
    /**
     * LTE eNB and sector from the ECGI string the gateway reports (PLMN digits followed by the
     * 28-bit cell identity in decimal). Returns null if it can't be split.
     */
    fun lteFromEcgi(ecgi: String?, mcc: String?, mnc: String?): Pair<Long, Int>? {
        ecgi ?: return null
        val plmnLength = (mcc?.length ?: 3) + (mnc?.length ?: 3)
        if (ecgi.length <= plmnLength || !ecgi.all(Char::isDigit)) return null
        val eci = ecgi.substring(plmnLength).toLongOrNull() ?: return null
        if (eci < 0 || eci > 0x0FFF_FFFFL) return null
        return (eci shr 8) to (eci and 0xFF).toInt()
    }
}
