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
 * 注意：Android 10+ 普通应用无法通过 listFiles() 列出 /sys/class/power_supply 目录，
 * 因此节点探测改为：先尝试目录列表，失败则回退到预置的常见节点名列表逐个探测。
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
    private var probedOnce = false

    /**
     * 常见 power_supply 子目录名。
     * 当 listFiles() 被权限拦截时，用此列表逐个尝试读取。
     * 覆盖高通/MTK/TI/SGMicro 等常见 PMIC、charger IC、gauge 节点。
     */
    private val knownSupplyNames = listOf(
        // 电池主节点
        "battery", "battery0", "battery1", "main", "bms",
        // 充电来源
        "usb", "ac", "dc", "wireless", "mains",
        // 高通 PMIC / SMB charger
        "smb139x", "smb1390", "smb1391", "smb1355", "smb1360", "smb1358",
        "smb349", "smb", "pm8150", "pm8250", "pm8350", "pm8450", "pm8550",
        "pmic", "pmic_charger", "qpnp", "pwm",
        // 高通无线充电
        "wls", "wls938x", "wls9390", "wls9340",
        // TI BQ charger / gauge
        "bq2598x", "bq25980", "bq25981", "bq25988", "bq2598",
        "bq2589x", "bq25890", "bq25892", "bq25895",
        "bq27441", "bq27541", "bq27621", "bq27z561", "bq27z562",
        // SGMicro
        "sgm41511", "sgm41514", "sgm41515", "sgm41521",
        "sgm58011", "sgm58031",
        // Gauge
        "pca953x", "pca9530", "gc2365", "gc2366", "gc2725",
        "cw2015", "cw2017", "cw201x",
        // MTK
        "mtk_battery", "mtk_ac", "mtk_usb", "mtk_charger",
        // 其他
        "chg", "charger", "fuelgauge", "power_delivery", "pd"
    )

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
        // 先尝试真实目录列表
        val real = try {
            File(BASE).listFiles { f -> f.isDirectory }
                ?.map { it.name }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (real.isNotEmpty()) return real

        // listFiles 被权限拦截，回退到预置列表，仅保留实际可读的节点
        AppLogger.w(TAG, "listFiles 无法列出 $BASE（权限限制），回退到预置节点名探测")
        val available = mutableListOf<String>()
        for (name in knownSupplyNames) {
            val dir = "$BASE/$name"
            // 只要该目录存在且能读任意一个属性文件，就认为节点存在
            if (File(dir).isDirectory &&
                (File("$dir/type").exists() ||
                 File("$dir/capacity").exists() ||
                 File("$dir/voltage_now").exists() ||
                 File("$dir/current_now").exists() ||
                 File("$dir/online").exists() ||
                 File("$dir/status").exists())) {
                available.add(name)
            }
        }
        AppLogger.i(TAG, "预置节点探测到 ${available.size} 个可读节点: ${available.joinToString(",")}")
        return available
    }

    /** 探测电池节点 */
    fun detectBatteryDir(): String? {
        val names = listSupplyNames()
        allDirs = names.map { "$BASE/$it" }
        if (names.isEmpty()) {
            AppLogger.e(TAG, "$BASE 下没有任何可读节点，无法探测电池节点")
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
        AppLogger.w(TAG, "未找到包含 capacity+voltage/temp 的电池节点，使用 $BASE/battery 作为兜底")
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
        // 多文件名备选：voltage_now / voltage_avg / voltage_now_avg / charge_voltage
        val uv = listOf("voltage_now", "voltage_avg", "voltage_now_avg", "charge_voltage")
            .firstNotNullOfOrNull { name ->
                readLong("$dir/$name", -1L).takeIf { it > 0 }
            }
        if (uv != null) return uv / 1_000_000f
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
     * 扫描所有 power_supply 子目录的 current_now/current_avg/battery_current/input_current，
     * 取绝对值最大的非零值。
     */
    fun readCurrent(): Float {
        ensureProbed()
        val dir = batteryDir()

        // 电流文件名备选（不同机型/内核命名不同）
        val currentFiles = listOf(
            "current_now", "current_avg", "current_max",
            "battery_current", "input_current", "charge_current",
            "batt_current", "ibatt", "iadc"
        )

        // 先尝试电池节点自身
        var ua: Long = currentFiles.firstNotNullOfOrNull { name ->
            readLong("$dir/$name", 0L).takeIf { it != 0L }
        } ?: 0L

        if (ua != 0L) {
            AppLogger.i(TAG, "从电池节点 $dir 读到电流 ${ua}uA")
            return ua / 1_000_000f
        }

        // 扫描所有节点找非零电流
        if (allDirs.isEmpty()) detectSourceDirs()
        var bestAbs = 0L
        var bestVal = 0L
        var bestNode = ""
        var bestFile = ""
        for (d in allDirs) {
            if (d == dir) continue
            for (name in currentFiles) {
                val v = readLong("$d/$name", 0L)
                if (v != 0L && kotlin.math.abs(v) > bestAbs) {
                    bestAbs = kotlin.math.abs(v)
                    bestVal = v
                    bestNode = d
                    bestFile = name
                }
            }
        }
        if (bestVal != 0L) {
            AppLogger.i(TAG, "从节点 $bestNode/$bestFile 读到电流 ${bestVal}uA")
            return bestVal / 1_000_000f
        }

        AppLogger.w(TAG, "所有 power_supply 节点的电流文件均为 0，无法获取电流")
        return 0f
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

    /** 确保节点探测至少执行一次（用于 readCurrent 等需要 allDirs 的场景） */
    private fun ensureProbed() {
        if (!probedOnce) {
            detectBatteryDir()
            detectSourceDirs()
            probedOnce = true
        }
    }

    fun readSnapshot(): BatterySnapshot {
        ensureProbed()

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
                // 列出所有节点的电流相关文件
                val currentFiles = listOf("current_now", "current_avg", "battery_current", "input_current")
                for (d in allDirs) {
                    for (cf in currentFiles) {
                        val v = readFile("$d/$cf")
                        if (v != null) appendLine("  ${File(d).name}/$cf=$v")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "debugDump failed", e)
            ""
        }
    }
}
