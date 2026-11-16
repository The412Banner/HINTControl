package com.banner.hintcontrol.ui.components

import com.banner.hintcontrol.data.userMessage

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Format {
    fun uptime(seconds: Long?): String {
        seconds ?: return "—"
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3_600
        val m = (seconds % 3_600) / 60
        return buildString {
            if (d > 0) append("${d}d ")
            if (d > 0 || h > 0) append("${h}h ")
            append("${m}m")
        }
    }

    fun ago(time: Long?, now: Long = System.currentTimeMillis()): String {
        time ?: return "never"
        val s = ((now - time) / 1000).coerceAtLeast(0)
        return when {
            s < 5 -> "just now"
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dateTimeFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

    fun time(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    fun dateTime(ms: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    fun day(ms: Long): String = dayFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    fun dbm(v: Int?) = v?.let { "$it dBm" } ?: "—"

    fun db(v: Int?) = v?.let { "$it dB" } ?: "—"
}

fun Throwable?.userMessageOrNull(): String? = this?.userMessage()
