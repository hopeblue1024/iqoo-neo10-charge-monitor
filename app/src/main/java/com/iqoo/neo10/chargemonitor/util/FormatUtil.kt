package com.iqoo.neo10.chargemonitor.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtil {

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun formatTime(ts: Long): String = timeFmt.format(Date(ts))
    fun formatDateTime(ts: Long): String = dateTimeFmt.format(Date(ts))

    /** 秒数 -> "12:34" 或 "1h 02m 03s" */
    fun formatDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%dh %02dm %02ds", h, m, sec)
        else String.format("%02d:%02d", m, sec)
    }

    fun formatFloat1(v: Float): String = String.format(Locale.getDefault(), "%.1f", v)
    fun formatFloat2(v: Float): String = String.format(Locale.getDefault(), "%.2f", v)
}
