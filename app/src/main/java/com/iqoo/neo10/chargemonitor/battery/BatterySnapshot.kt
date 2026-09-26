package com.iqoo.neo10.chargemonitor.battery

/**
 * 单次电池采样快照。
 * 电压/电流均为双电芯总电压与总电流，功率 = 电压 * 电流。
 */
data class BatterySnapshot(
    val capacity: Int,
    /** 双电芯总电压 V */
    val voltage: Float,
    /** 总电流 A（充电为正，放电为负） */
    val current: Float,
    /** 功率 W = 电压 * 电流 */
    val power: Float,
    /** 温度 °C */
    val temperature: Float,
    /** 充电来源 AC / USB / Unknown */
    val source: String,
    /** 状态 Charging / Discharging / Full / Not charging */
    val status: String
)
