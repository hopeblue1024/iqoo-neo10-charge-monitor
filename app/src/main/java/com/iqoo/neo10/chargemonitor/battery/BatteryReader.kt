package com.iqoo.neo10.chargemonitor.battery

import android.util.Log
import java.io.File

/**
 * 从 /sys/class/power_supply 读取实时电池参数。
 * 针对 iQOO Neo10 双电芯串联设计：
 *  - voltage_now 读取的是双电芯总电压 (约 7.4V ~ 8.8V)
 *  - current_now 读取的是总电流
 *  - 功率 = 总电压 * 总电流
 *
 * 无需 ROOT 权限，标准 battery 节点通常对应用可读。
 */
object BatteryReader {

    private const val TAG = "BatteryReader"

    private const val BASE = "/sys/class/power_supply"
    private const val BATTERY = "$BASE/battery"
    private const val USB = "$BASE/usb"
    private const val AC = "$BASE/ac"

    private fun readFile(path: String): String? {
        return try {
            File(path).readText().trim()
        } catch (e: Exception) {
            null
        }
    }

    private fun readInt(path: String, default: Int = 0): Int {
        return readFile(path)?.toIntOrNull() ?: default
    }

    private fun readLong(path: String, default: Long = 0L): Long {
        return readFile(path)?.toLongOrNull() ?: default
    }

    /** 读取电量百分比 0-100 */
    fun readCapacity(): Int = readInt("$BATTERY/capacity", 0).coerceIn(0, 100)

    /** 读取双电芯总电压，单位 V */
    fun readVoltage(): Float {
        // 优先取 voltage_now (uV)，部分机型使用 voltage_avg / voltage_now_avg
        val uv = readLong("$BATTERY/voltage_now", -1L)
            .takeIf { it > 0 }
            ?: readLong("$BATTERY/voltage_avg", -1L)
            .takeIf { it > 0 }
            ?: readLong("$BATTERY/voltage_now_avg", -1L)
        return if (uv > 0) uv / 1_000_000f else 0f
    }

    /** 读取总电流，单位 A。读取值若为负（放电），保持符号；充电时通常为正。 */
    fun readCurrent(): Float {
        // current_now 单位 uA，部分机型放电为负、充电为正
        val ua = readLong("$BATTERY/current_now", 0L)
            .takeIf { it != 0L }
            ?: readLong("$BATTERY/current_avg", 0L)
        return ua / 1_000_000f
    }

    /** 读取温度，单位 °C（sysfs 通常为 0.1°C） */
    fun readTemperature(): Float {
        val raw = readInt("$BATTERY/temp", 0)
        return raw / 10f
    }

    /** 读取状态 */
    fun readStatus(): String = readFile("$BATTERY/status") ?: "Unknown"

    /** 读取充电来源 AC / USB */
    fun readSource(): String {
        // ac/online
        val acOnline = readInt("$AC/online", 0)
        if (acOnline == 1) return "AC"
        val usbType = readFile("$USB/type")
        val usbOnline = readInt("$USB/online", 0)
        if (usbOnline == 1) {
            return when (usbType?.uppercase()) {
                "USB" -> "USB"
                "USB_DCP", "USB_HVDCP", "USB_HVDCP_3", "USB_TYPEC" -> "USB"
                else -> "USB"
            }
        }
        // fallback: 根据电池状态判断
        val status = readStatus()
        return if (status == "Charging" || status == "Full") "USB" else "Unknown"
    }

    /** 读取一次完整快照 */
    fun readSnapshot(): BatterySnapshot {
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
            buildString {
                appendLine("capacity=${readFile("$BATTERY/capacity")}")
                appendLine("voltage_now=${readFile("$BATTERY/voltage_now")}")
                appendLine("current_now=${readFile("$BATTERY/current_now")}")
                appendLine("temp=${readFile("$BATTERY/temp")}")
                appendLine("status=${readFile("$BATTERY/status")}")
                appendLine("usb/type=${readFile("$USB/type")}")
                appendLine("usb/online=${readFile("$USB/online")}")
                appendLine("ac/online=${readFile("$AC/online")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "debugDump failed", e)
            ""
        }
    }
}
