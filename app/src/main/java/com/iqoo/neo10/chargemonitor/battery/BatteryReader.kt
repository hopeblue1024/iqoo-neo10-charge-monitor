package com.iqoo.neo10.chargemonitor.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.iqoo.neo10.chargemonitor.App
import com.iqoo.neo10.chargemonitor.util.AppLogger
import java.io.File

/**
 * 电池数据读取。
 *
 * iQOO Neo10（OriginOS）对普通应用完全屏蔽 /sys/class/power_supply（File.exists 均返回 false），
 * 因此数据来源以 Android 公开 API BatteryManager 为主，sysfs 仅作辅助（在可读的机型上生效）。
 *
 * 电流：BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW) — API 21+ 公开接口，单位 μA。
 * 电压：BatteryManager EXTRA_VOLTAGE（mV），双电芯机型若返回单电芯电压则需 ×2。
 */
object BatteryReader {

    private const val TAG = "BatteryReader"
    private const val BASE = "/sys/class/power_supply"

    private var batteryDir: String? = null
    private val supplyDirs = listOf("usb", "ac", "dc", "wireless", "mains")
    private var sourceDirs: List<String> = emptyList()
    private var allDirs: List<String> = emptyList()

    /** 探测是否已执行（无论成功失败），用于避免重复探测刷屏 */
    private var probedOnce = false
    /** 缓存的节点列表，探测后不再变更 */
    private var cachedSupplyNames: List<String>? = null

    private val knownSupplyNames = listOf(
        "battery", "battery0", "battery1", "main", "bms",
        "usb", "ac", "dc", "wireless", "mains",
        "smb139x", "smb1390", "smb1391", "smb1355", "smb1360", "smb1358",
        "smb349", "smb", "pm8150", "pm8250", "pm8350", "pm8450", "pm8550",
        "pmic", "pmic_charger", "qpnp",
        "wls", "wls938x", "wls9390", "wls9340",
        "bq2598x", "bq25980", "bq25981", "bq25988", "bq2598",
        "bq2589x", "bq25890", "bq25892", "bq25895",
        "bq27441", "bq27541", "bq27621", "bq27z561", "bq27z562",
        "sgm41511", "sgm41514", "sgm41515", "sgm41521",
        "sgm58011", "sgm58031",
        "pca953x", "pca9530", "gc2365", "gc2366", "gc2725",
        "cw2015", "cw2017", "cw201x",
        "mtk_battery", "mtk_ac", "mtk_usb", "mtk_charger",
        "chg", "charger", "fuelgauge", "power_delivery", "pd"
    )

    private fun ctx(): Context? = try {
        App.instance
    } catch (e: Exception) {
        null
    }

    private fun batteryManager(): BatteryManager? {
        val c = ctx() ?: return null
        return try {
            c.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取 BatteryManager 失败: ${e.message}")
            null
        }
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
        cachedSupplyNames?.let { return it }

        val real = try {
            File(BASE).listFiles { f -> f.isDirectory }
                ?.map { it.name }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (real.isNotEmpty()) {
            cachedSupplyNames = real
            return real
        }

        AppLogger.w(TAG, "listFiles 无法列出 $BASE（权限限制），回退到预置节点名探测")
        val available = mutableListOf<String>()
        for (name in knownSupplyNames) {
            val dir = "$BASE/$name"
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
        cachedSupplyNames = available
        return available
    }

    fun detectBatteryDir(): String? {
        val names = listSupplyNames()
        allDirs = names.map { "$BASE/$it" }
        if (names.isEmpty()) {
            AppLogger.e(TAG, "$BASE 下没有任何可读节点，sysfs 不可用，将使用 BatteryManager")
            batteryDir = "$BASE/battery" // 设为非 null 防止重复探测
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
        batteryDir = "$BASE/battery"
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
        val c = ctx() ?: return null
        return try {
            c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            AppLogger.e(TAG, "registerReceiver 失败: ${e.message}")
            null
        }
    }

    private fun ensureProbed() {
        if (!probedOnce) {
            detectBatteryDir()
            detectSourceDirs()
            probedOnce = true
        }
    }

    fun readCapacity(): Int {
        // sysfs 优先
        val dir = batteryDir()
        val sys = readInt("$dir/capacity", -1)
        if (sys in 0..100) return sys

        val intent = batteryIntent() ?: return 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return 0
        return (level * 100 / scale).coerceIn(0, 100)
    }

    fun readVoltage(): Float {
        // sysfs 优先（双电芯总电压精度高）
        val dir = batteryDir()
        val uv = listOf("voltage_now", "voltage_avg", "voltage_now_avg", "charge_voltage")
            .firstNotNullOfOrNull { name -> readLong("$dir/$name", -1L).takeIf { it > 0 } }
        if (uv != null) return uv / 1_000_000f

        // BatteryManager 兜底
        val intent = batteryIntent() ?: return 0f
        val mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        if (mv <= 0) {
            AppLogger.w(TAG, "BatteryManager 电压无效: $mv mV")
            return 0f
        }
        val v = mv / 1000f
        AppLogger.i(TAG, "BatteryManager 电压=${mv}mV (${v}V)，若为单电芯则总电压应×2")
        return v
    }

    /**
     * 读取总电流，单位 A。
     * 优先 sysfs；失败则使用 BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)。
     * 双电芯串联时，电流等于单电芯电流，直接乘以总电压即为总功率。
     */
    fun readCurrent(): Float {
        ensureProbed()
        val dir = batteryDir()

        val currentFiles = listOf(
            "current_now", "current_avg", "current_max",
            "battery_current", "input_current", "charge_current",
            "batt_current", "ibatt", "iadc"
        )

        // 1. 电池节点自身
        var ua: Long = currentFiles.firstNotNullOfOrNull { name ->
            readLong("$dir/$name", 0L).takeIf { it != 0L }
        } ?: 0L
        if (ua != 0L) {
            AppLogger.i(TAG, "sysfs 电池节点电流: ${ua}uA")
            return ua / 1_000_000f
        }

        // 2. 扫描所有 sysfs 节点
        var bestAbs = 0L
        var bestVal = 0L
        for (d in allDirs) {
            if (d == dir) continue
            for (name in currentFiles) {
                val v = readLong("$d/$name", 0L)
                if (v != 0L && kotlin.math.abs(v) > bestAbs) {
                    bestAbs = kotlin.math.abs(v)
                    bestVal = v
                }
            }
        }
        if (bestVal != 0L) {
            AppLogger.i(TAG, "sysfs 扫描到电流: ${bestVal}uA")
            return bestVal / 1_000_000f
        }

        // 3. BatteryManager API（公开接口，API 21+）
        val bm = batteryManager()
        if (bm != null) {
            val now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val avg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            AppLogger.i(TAG, "BatteryManager current_now=${now}uA current_avg=${avg}uA")
            val v = when {
                now != Int.MIN_VALUE && now != 0 -> now.toLong()
                avg != Int.MIN_VALUE && avg != 0 -> avg.toLong()
                else -> 0L
            }
            if (v != 0L) {
                AppLogger.i(TAG, "使用 BatteryManager 电流: ${v}uA")
                return v / 1_000_000f
            }
        }

        AppLogger.w(TAG, "sysfs 与 BatteryManager 均无法获取电流")
        return 0f
    }

    fun readTemperature(): Float {
        val dir = batteryDir()
        val raw = readInt("$dir/temp", Int.MIN_VALUE)
        if (raw != Int.MIN_VALUE) return raw / 10f

        val intent = batteryIntent() ?: return 0f
        val t = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (t == Int.MIN_VALUE) return 0f
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
            val bm = batteryManager()
            val now = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val avg = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            buildString {
                appendLine("detected_battery_dir=$dir")
                appendLine("supply_names=${listSupplyNames().joinToString(",")}")
                appendLine("source_dirs=${sourceDirs.joinToString(",")}")
                appendLine("bm_current_now=$now")
                appendLine("bm_current_avg=$avg")
                appendLine("capacity=${readFile("$dir/capacity")}")
                appendLine("voltage_now=${readFile("$dir/voltage_now")}")
                appendLine("current_now=${readFile("$dir/current_now")}")
                appendLine("temp=${readFile("$dir/temp")}")
                appendLine("status=${readFile("$dir/status")}")
                for (d in allDirs) {
                    for (cf in listOf("current_now", "current_avg", "battery_current", "input_current")) {
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
