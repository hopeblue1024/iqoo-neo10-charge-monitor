package com.iqoo.neo10.chargemonitor.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次充电会话记录。
 * endTime 为 null 表示充电仍在进行中。
 */
@Entity(tableName = "charging_records")
data class ChargingRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    var endTime: Long? = null,
    val startCapacity: Int,
    var endCapacity: Int? = null,
    /** 本次充电最高功率 (W) */
    var maxPower: Float = 0f,
    /** 本次充电最高温度 (°C) */
    var maxTemp: Float = 0f
)
