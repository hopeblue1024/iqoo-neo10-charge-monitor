package com.iqoo.neo10.chargemonitor.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.iqoo.neo10.chargemonitor.App
import java.io.File

/**
 * 从 /sys/class/power_supply 读取实时电池参数，并以 BatteryManager API 兜底。
 *
 * iQOO Neo10 为双电芯串联设计，不同机型/内核的电池节点名称不一致
 * （可能是 battery / battery0 / main / bq* 等），因此启动时动态探测。
 *
 * 优先级：sysfs（双电芯总电压/电流精度高） → BatteryManager API（兜底）
 */
object BatteryReader {

    private const val TAG = "BatteryReader"
    private const val BASE = "/sys/class/power_supply"

    /** 探测到的电池节点目录，例如 /sys/class/power_supply/battery0 */
    private var batteryDir: String? = null

    /** 已知的充电来源节点，按优先级探测 */
    private val supplyDirs = listOf("usb", "ac", "dc", "wireless", "mains")

    /** 已探测到的充电来源节点，例如 /sys/class/power_supply/usb */
    private var sourceDirs: List<String> = emptyList()

    private fun ctx(): Context? = try {
        App.instance
    } catch (e: Exception) {
        null
    }

    private fun readFile(path: String): String? {
        return try {
            File(path).readText().trim()
        } catch (e: Exception) {
            null
        }
    }

    private fun readInt(path: String, default: Int = 0): Int =
        readFile(path)?.toIntOrNull() ?: default

    private fun readLong(path: String, default: Long = 0L): Long =
        readFile(path)?.toLongOrNull() ?: default

    /** 列出 /sys/class/power_supply 下的所有子目录名 */
    private fun listSupplyNames(): List<String> {
        return try {
            File(BASE).listFiles { f -> f.isDirectory }
                ?.map { it.name }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 探测电池节点：优先尝试常见名称，否则遍历所有子目录，
     * 找到第一个同时包含 capacity 与 voltage_now（或 temp）的目录。
     */
    fun detectBatteryDir(): String? {
        val names = listSupplyNames()
        if (names.isEmpty()) return null

        // 常见名称优先
        val preferred = listOf("battery", "battery0", "battery1", "main", "bms")
        for (name in preferred) {
            if (name in names && isBatteryNode("$BASE/$name")) {
                batteryDir = "$BASE/$name"
                return batteryDir
            }
        }
        // 兜底：遍历全部
        for (name in names) {
            if (isBatteryNode("$BASE/$name")) {
                batteryDir = "$BASE/$name"
                return batteryDir
            }
        }
        batteryDir = null
        return null
    }

    private fun isBatteryNode(dir: String): Boolean {
        val hasCapacity = File("$dir/capacity").exists()
        val hasVoltage = File("$dir/voltage_now").exists() || File("$dir/voltage_avg").exists()
        val hasTemp = File("$dir/temp").exists()
        return hasCapacity && (hasVoltage || hasTemp)
    }

    /** 探测充电来源节点 */
    fun detectSourceDirs(): List<String> {
        val names = listSupplyNames()
        sourceDirs = supplyDirs.filter { it in names }.map { "$BASE/$it" }
        return sourceDirs
    }

    private fun batteryDir(): String {
        if (batteryDir == null) detectBatteryDir()
        return batteryDir ?: "$BASE/battery"
    }

    /** 通过 BatteryManager 获取电池 Intent（粘性广播，无需注册接收器） */
    private fun batteryIntent(): Intent? {
        val c = ctx() ?: return null
        return c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    /** 读取电量百分比 0-100 */
    fun readCapacity(): Int {
        val sys = readInt("${batteryDir()}/capacity", -1)
        if (sys in 0..100) return sys
        // BatteryManager 兜底
        val intent = batteryIntent() ?: return 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return 0
        return (level * 100 / scale).coerceIn(0, 100)
    }

    /**
     * 读取双电芯总电压，单位 V。
     * sysfs 的 voltage_now 通常是双电芯串联总电压（约 7.4~8.8V），
     * BatteryManager 的 EXTRA_VOLTAGE 多为单电芯电压（约 3.7~4.4V）。
     * 优先 sysfs，兜底用 BatteryManager。
     */
    fun readVoltage(): Float {
        val dir = batteryDir()
        val uv = readLong("$dir/voltage_now", -1L)
            .takeIf { it > 0 }
            ?: readLong("$dir/voltage_avg", -1L)
                .takeIf { it > 0 }
            ?: readLong("$dir/voltage_now_avg", -1L)
        if (uv > 0) return uv / 1_000_000f
        // BatteryManager 兜底（单位 mV）
        val intent = batteryIntent() ?: return 0f
        val mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        return if (mv > 0) mv / 1000f else 0f
    }

    /** 读取总电流，单位 A。sysfs 单位 uA。 */
    fun readCurrent(): Float {
        val dir = batteryDir()
        val ua = readLong("$dir/current_now", 0L)
            .takeIf { it != 0L }
            ?: readLong("$dir/current_avg", 0L)
        return ua / 1_000_000f
    }

    /** 读取温度，单位 °C。sysfs 通常为 0.1°C。 */
    fun readTemperature(): Float {
        val dir = batteryDir()
        val raw = readInt("$dir/temp", Int.MIN_VALUE)
        if (raw != Int.MIN_VALUE) return raw / 10f
        // BatteryManager 兜底（单位 0.1°C）
        val intent = batteryIntent() ?: return 0f
        val t = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t != Int.MIN_VALUE) t / 10f else 0f
    }

    /** 读取状态：Charging / Discharging / Full / Not charging / Unknown */
    fun readStatus(): String {
        val sys = readFile("${batteryDir()}/status")
        if (!sys.isNullOrBlank()) return sys
        val intent = batteryIntent() ?: return "Unknown"
        return when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }
    }

    /** 读取充电来源：AC / USB / Wireless / DC / Unknown */
    fun readSource(): String {
        // sysfs 探测各来源节点的 online
        if (sourceDirs.isEmpty()) detectSourceDirs()
        for (dir in sourceDirs) {
            val online = readInt("$dir/online", 0)
            if (online == 1) {
                val name = File(dir).name.uppercase()
                return when (name) {
                    "AC", "MAINS" -> "AC"
                    "USB" -> "USB"
                    "WIRELESS" -> "Wireless"
                    "DC" -> "DC"
                    else -> name
                }
            }
        }
        // BatteryManager 兜底
        val intent = batteryIntent() ?: return "Unknown"
        return when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Unknown"
        }
    }

    /** 读取一次完整快照 */
    fun readSnapshot(): BatterySnapshot {
        // 首次调用时确保节点已探测
        if (batteryDir == null) detectBatteryDir()
        if (sourceDirs.isEmpty()) detectSourceDirs()

        val voltage = readVoltage()
        var current = readCurrent()
        val status = readStatus()
        // 若状态为 Charging 但电流为负，按设备约定取绝对值
        if (status == "Charging" && current < 0) current = -current
        // 若状态为 Discharging 但电流为正，取负
        if (status == "Discharging" && current > 0) current = -current

        val power = voltage * current
        val temperature = readTemperature()
        val capacity = readCapacity()
        val source = readSource()

        return BatterySnapshot(
            capacity = capacity,
            voltage = voltage,
            current = current,
            power = power,
            temperature = temperature,
            source = source,
            status = status
        )
    }

    fun debugDump(): String {
        return try {
            val dir = batteryDir()
            buildString {
                appendLine("detected_battery_dir=$dir")
                appendLine("supply_names=${listSupplyNames().joinToString(",")}")
                appendLine("source_dirs=${sourceDirs.joinToString(",")}")
                appendLine("capacity=${readFile("$dir/capacity")}")
                appendLine("voltage_now=${readFile("$dir/voltage_now")}")
                appendLine("current_now=${readFile("$dir/current_now")}")
                appendLine("temp=${readFile("$dir/temp")}")
                appendLine("status=${readFile("$dir/status")}")
                appendLine("bm_status=${readStatus()}")
                appendLine("bm_source=${readSource()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "debugDump failed", e)
            ""
        }
    }
}
