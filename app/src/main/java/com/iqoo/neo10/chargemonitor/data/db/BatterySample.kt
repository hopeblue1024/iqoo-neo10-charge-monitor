package com.iqoo.neo10.chargemonitor.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 充电过程中的时序采样点。
 * 电压、电流均为读取到的双电芯总值。
 */
@Entity(
    tableName = "battery_samples",
    foreignKeys = [ForeignKey(
        entity = ChargingRecord::class,
        parentColumns = ["id"],
        childColumns = ["recordId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("recordId")]
)
data class BatterySample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val timestamp: Long,
    /** 距充电开始的秒数 */
    val elapsedSec: Long,
    val capacity: Int,
    /** 双电芯总电压 (V) */
    val voltage: Float,
    /** 总电流 (A)，正值表示充电 */
    val current: Float,
    /** 功率 W = 电压 * 电流 */
    val power: Float,
    /** 温度 °C */
    val temperature: Float,
    /** 充电来源 AC / USB */
    val source: String
)
