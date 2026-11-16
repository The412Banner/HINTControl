package com.banner.hintcontrol.update

/**
 * Release version like "1.2.0", "v1.2.0-pre3" or "1.2.0-beta.2". A plain release sorts after
 * its pre-releases; pre-releases compare by label, then number.
 */
data class AppVersion(
    val numbers: List<Int>,
    val preLabel: String?,
    val preNumber: Int,
) : Comparable<AppVersion> {
    val isPrerelease: Boolean get() = preLabel != null

    override fun compareTo(other: AppVersion): Int {
        val n = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until n) {
            val c = (numbers.getOrElse(i) { 0 }).compareTo(other.numbers.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (preLabel == null && other.preLabel == null) return 0
        if (preLabel == null) return 1
        if (other.preLabel == null) return -1
        val l = preLabel.compareTo(other.preLabel)
        if (l != 0) return l
        return preNumber.compareTo(other.preNumber)
    }

    override fun toString(): String =
        numbers.joinToString(".") + (preLabel?.let { "-$it${if (preNumber > 0) preNumber else ""}" } ?: "")

    companion object {
        private val pattern = Regex("^v?(\\d+(?:\\.\\d+)*)(?:-([A-Za-z]+)\\.?(\\d*))?.*$")

        fun parse(text: String): AppVersion? {
            val m = pattern.matchEntire(text.trim()) ?: return null
            val numbers = m.groupValues[1].split('.').mapNotNull(String::toIntOrNull)
            val label = m.groupValues[2].takeIf { it.isNotEmpty() }?.lowercase()
            val num = m.groupValues[3].toIntOrNull() ?: 0
            return AppVersion(numbers, label, num)
        }
    }
}
