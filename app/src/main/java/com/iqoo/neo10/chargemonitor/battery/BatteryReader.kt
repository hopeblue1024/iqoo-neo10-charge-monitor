package com.iqoo.neo10.chargemonitor.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.iqoo.neo10.chargemonitor.App
import com.iqoo.neo10.chargemonitor.util.AppLogger
import java.io.File

/**
 * 从 /sys/class/power_supply 读取实时电池参数，并以 BatteryManager API 兜底。
 *
 * iQOO Neo10 为双电芯串联设计，不同机型/内核的节点名称不一致。
 * 电流可能不在 battery 节点，而在 charger IC 节点（bq2598x / smb* / pca* 等），
 * 因此读取电流时会扫描全部 power_supply 子目录。
 *
 * 优先级：sysfs（双电芯总电压/电流精度高） → BatteryManager API（兜底）
 */
object BatteryReader {

    private const val TAG = "BatteryReader"
    private const val BASE = "/sys/class/power_supply"

    private var batteryDir: String? = null
    private val supplyDirs = listOf("usb", "ac", "dc", "wireless", "mains")
    private var sourceDirs: List<String> = emptyList()
    /** 探测到的所有 power_supply 子目录完整路径 */
    private var allDirs: List<String> = emptyList()

    private fun ctx(): Context? = try {
        App.instance
    } catch (e: Exception) {
        null
    }

    private fun readFile(path: String): String? {
        return try {
            val v = File(path).readText().trim()
            if (v.isEmpty()) null else v
        } catch (e: Exception) {
            null
        }
    }

    private fun readInt(path: String, default: Int = 0): Int =
        readFile(path)?.toIntOrNull() ?: default

    private fun readLong(path: String, default: Long = 0L): Long =
        readFile(path)?.toLongOrNull() ?: default

    private fun listSupplyNames(): List<String> {
        return try {
            File(BASE).listFiles { f -> f.isDirectory }
                ?.map { it.name }
                ?: emptyList()
        } catch (e: Exception) {
            AppLogger.w(TAG, "无法列出 $BASE: ${e.message}")
            emptyList()
        }
    }

    /** 探测电池节点 */
    fun detectBatteryDir(): String? {
        val names = listSupplyNames()
        allDirs = names.map { "$BASE/$it" }
        if (names.isEmpty()) {
            AppLogger.e(TAG, "$BASE 下没有任何子目录，无法探测电池节点")
            return null
        }
        AppLogger.i(TAG, "power_supply 节点列表: ${names.joinToString(",")}")

        val preferred = listOf("battery", "battery0", "battery1", "main", "bms")
        for (name in preferred) {
            if (name in names && isBatteryNode("$BASE/$name")) {
                batteryDir = "$BASE/$name"
                AppLogger.i(TAG, "探测到电池节点(优先): $batteryDir")
                return batteryDir
            }
        }
        for (name in names) {
            if (isBatteryNode("$BASE/$name")) {
                batteryDir = "$BASE/$name"
                AppLogger.i(TAG, "探测到电池节点(遍历): $batteryDir")
                return batteryDir
            }
        }
        AppLogger.w(TAG, "未找到包含 capacity+voltage/temp 的电池节点")
        batteryDir = null
        return null
    }

    private fun isBatteryNode(dir: String): Boolean {
        val hasCapacity = File("$dir/capacity").exists()
        val hasVoltage = File("$dir/voltage_now").exists() || File("$dir/voltage_avg").exists()
        val hasTemp = File("$dir/temp").exists()
        return hasCapacity && (hasVoltage || hasTemp)
    }

    fun detectSourceDirs(): List<String> {
        val names = listSupplyNames()
        allDirs = names.map { "$BASE/$it" }
        sourceDirs = supplyDirs.filter { it in names }.map { "$BASE/$it" }
        return sourceDirs
    }

    private fun batteryDir(): String {
        if (batteryDir == null) detectBatteryDir()
        return batteryDir ?: "$BASE/battery"
    }

    private fun batteryIntent(): Intent? {
        val c = ctx() ?: run {
            AppLogger.e(TAG, "无法获取 Context，BatteryManager 兜底不可用")
            return null
        }
        return try {
            c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            AppLogger.e(TAG, "registerReceiver 失败: ${e.message}")
            null
        }
    }

    fun readCapacity(): Int {
        val dir = batteryDir()
        val sys = readInt("$dir/capacity", -1)
        if (sys in 0..100) return sys
        AppLogger.w(TAG, "sysfs capacity 无效($sys)，使用 BatteryManager 兜底")
        val intent = batteryIntent() ?: return 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            AppLogger.w(TAG, "BatteryManager level=$level scale=$scale 无效")
            return 0
        }
        return (level * 100 / scale).coerceIn(0, 100)
    }

    fun readVoltage(): Float {
        val dir = batteryDir()
        val uv = readLong("$dir/voltage_now", -1L)
            .takeIf { it > 0 }
            ?: readLong("$dir/voltage_avg", -1L)
                .takeIf { it > 0 }
            ?: readLong("$dir/voltage_now_avg", -1L)
        if (uv > 0) return uv / 1_000_000f
        AppLogger.w(TAG, "sysfs 电压读取失败(节点=$dir)，使用 BatteryManager 兜底")
        val intent = batteryIntent() ?: return 0f
        val mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        if (mv <= 0) {
            AppLogger.w(TAG, "BatteryManager 电压无效: $mv mV")
            return 0f
        }
        return mv / 1000f
    }

    /**
     * 读取总电流，单位 A。
     * iQOO 双电芯机型的电流可能分布在多个 charger IC 节点，
     * 扫描所有 power_supply 子目录的 current_now，取绝对值最大的非零值。
     */
    fun readCurrent(): Float {
        val dir = batteryDir()
        // 先尝试电池节点自身
        var ua = readLong("$dir/current_now", 0L)
            .takeIf { it != 0L }
            ?: readLong("$dir/current_avg", 0L)

        if (ua == 0L) {
            // 扫描所有节点找非零电流
            if (allDirs.isEmpty()) detectSourceDirs()
            var bestAbs = 0L
            var bestVal = 0L
            var bestNode = ""
            for (d in allDirs) {
                if (d == dir) continue
                val v = readLong("$d/current_now", 0L)
                    .takeIf { it != 0L }
                    ?: readLong("$d/current_avg", 0L)
                if (v != 0L && kotlin.math.abs(v) > bestAbs) {
                    bestAbs = kotlin.math.abs(v)
                    bestVal = v
                    bestNode = d
                }
            }
            if (bestVal != 0L) {
                ua = bestVal
                AppLogger.i(TAG, "从节点 $bestNode 读到电流 ${ua}uA")
            } else {
                AppLogger.w(TAG, "所有 power_supply 节点的 current_now 均为 0，无法获取电流")
            }
        }
        return ua / 1_000_000f
    }

    fun readTemperature(): Float {
        val dir = batteryDir()
        val raw = readInt("$dir/temp", Int.MIN_VALUE)
        if (raw != Int.MIN_VALUE) return raw / 10f
        AppLogger.w(TAG, "sysfs 温度读取失败(节点=$dir)，使用 BatteryManager 兜底")
        val intent = batteryIntent() ?: return 0f
        val t = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (t == Int.MIN_VALUE) {
            AppLogger.w(TAG, "BatteryManager 温度无效")
            return 0f
        }
        return t / 10f
    }

    fun readStatus(): String {
        val dir = batteryDir()
        val sys = readFile("$dir/status")
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

    fun readSource(): String {
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
        val intent = batteryIntent() ?: return "Unknown"
        return when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Unknown"
        }
    }

    fun readSnapshot(): BatterySnapshot {
        if (batteryDir == null) detectBatteryDir()
        if (sourceDirs.isEmpty()) detectSourceDirs()

        val voltage = readVoltage()
        var current = readCurrent()
        val status = readStatus()
        if (status == "Charging" && current < 0) current = -current
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
                // 列出所有节点的 current_now
                for (d in allDirs) {
                    val cn = readFile("$d/current_now")
                    if (cn != null) appendLine("  ${File(d).name}/current_now=$cn")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "debugDump failed", e)
            ""
        }
    }
}
