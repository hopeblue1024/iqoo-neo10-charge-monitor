package com.iqoo.neo10.chargemonitor.util

import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图表 X 轴时间格式化器。
 * X 值为「距充电开始的分钟数」，结合 recordStartTime 换算成实际时间并按 HH:mm 显示。
 */
class TimeAxisFormatter(private val recordStartTime: Long) : ValueFormatter() {

    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun getAxisLabel(value: Float, axis: AxisBase?): String {
        // value 单位为分钟
        val ms = recordStartTime + (value * 60_000L).toLong()
        return fmt.format(Date(ms))
    }
}
