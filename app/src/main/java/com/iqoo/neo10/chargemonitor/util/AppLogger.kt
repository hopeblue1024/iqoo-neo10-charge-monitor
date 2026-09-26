package com.iqoo.neo10.chargemonitor.util

import android.util.Log
import com.iqoo.neo10.chargemonitor.App
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行时日志管理器。
 *
 * - 内存环形缓冲（最多 500 条），供日志页面实时查看
 * - 同时写入 app 私有目录文件，便于持久化排查
 * - 级别：D / I / W / E
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val MAX_LINES = 500

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private fun logFile(): File? {
        return try {
            File(App.instance.filesDir, "app_log.txt")
        } catch (e: Exception) {
            null
        }
    }

    fun d(tag: String, msg: String) = append("D", tag, msg)
    fun i(tag: String, msg: String) = append("I", tag, msg)
    fun w(tag: String, msg: String) = append("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg
        append("E", tag, full)
    }

    private fun append(level: String, tag: String, msg: String) {
        val time = timeFormat.format(Date())
        val line = "[$time] $level/$tag: $msg"
        // 同步到 logcat
        when (level) {
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            "E" -> Log.e(tag, msg)
        }
        synchronized(lock) {
            if (buffer.size >= MAX_LINES) buffer.removeFirst()
            buffer.addLast(line)
        }
        // 异步写入文件
        try {
            logFile()?.appendText("$line\n")
        } catch (_: Exception) {
        }
    }

    /** 获取当前全部日志（最新在前） */
    fun allLines(): List<String> {
        synchronized(lock) {
            return buffer.toList().reversed()
        }
    }

    /** 清空内存与文件日志 */
    fun clear() {
        synchronized(lock) { buffer.clear() }
        try {
            logFile()?.writeText("")
        } catch (_: Exception) {
        }
    }
}
