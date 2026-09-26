package com.iqoo.neo10.chargemonitor.ui.chart

import android.graphics.Color
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.iqoo.neo10.chargemonitor.data.db.BatterySample
import com.iqoo.neo10.chargemonitor.databinding.ActivityChartBinding
import com.iqoo.neo10.chargemonitor.util.TimeAxisFormatter

class ChartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChartBinding
    private val vm: ChartViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChart(binding.chartCurrent, "电流 (A)", Color.parseColor("#0A84FF"))
        setupChart(binding.chartPower, "功率 (W)", Color.parseColor("#FF9F0A"))
        setupChart(binding.chartTemp, "温度 (°C)", Color.parseColor("#FF453A"))

        val tempLimit = LimitLine(42f, "高温阈值 42°C").apply {
            lineColor = Color.RED
            lineWidth = 1.5f
            textColor = Color.RED
        }
        binding.chartTemp.axisLeft.addLimitLine(tempLimit)

        vm.activeRecord.observe(this) { record ->
            val start = record?.startTime ?: System.currentTimeMillis()
            val formatter = TimeAxisFormatter(start)
            binding.chartCurrent.xAxis.valueFormatter = formatter
            binding.chartPower.xAxis.valueFormatter = formatter
            binding.chartTemp.xAxis.valueFormatter = formatter
        }

        vm.samples.observe(this) { samples -> updateCharts(samples) }
    }

    private fun setupChart(chart: LineChart, label: String, color: Int) {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(false)
            setPinchZoom(false)
            setDrawGridBackground(false)
            legend.isEnabled = false
            axisRight.isEnabled = false
            xAxis.setDrawGridLines(false)
            xAxis.textColor = Color.GRAY
            xAxis.granularity = 1f // 每 1 分钟一个数据点
            axisLeft.textColor = Color.GRAY
            axisLeft.setDrawGridLines(true)
            axisLeft.gridColor = Color.parseColor("#333333")
            setBackgroundColor(Color.TRANSPARENT)
            setNoDataText("暂无数据，充电时将自动绘制")
            setNoDataTextColor(Color.GRAY)
        }
        val ds = LineDataSet(emptyList(), label).apply {
            this.color = color
            setCircleColor(color)
            circleRadius = 2.5f
            lineWidth = 1.5f
            setDrawValues(false)
            setDrawFilled(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        chart.data = LineData(ds)
    }

    private fun updateCharts(samples: List<BatterySample>) {
        // X 轴单位：分钟（距充电开始）
        val currentEntries = samples.map { Entry(it.elapsedSec / 60f, it.current) }
        val powerEntries = samples.map { Entry(it.elapsedSec / 60f, it.power) }
        val tempEntries = samples.map { Entry(it.elapsedSec / 60f, it.temperature) }

        updateChart(binding.chartCurrent, currentEntries)
        updateChart(binding.chartPower, powerEntries)
        updateChart(binding.chartTemp, tempEntries)

        // 按 15 分钟间隔设置坐标轴标签数
        val minutes = if (samples.isNotEmpty()) samples.last().elapsedSec / 60f else 60f
        val labelCount = (minutes / 15f).toInt().coerceIn(2, 12) + 1
        binding.chartCurrent.xAxis.setLabelCount(labelCount, true)
        binding.chartPower.xAxis.setLabelCount(labelCount, true)
        binding.chartTemp.xAxis.setLabelCount(labelCount, true)
    }

    private fun updateChart(chart: LineChart, entries: List<Entry>) {
        val ds = chart.data.getDataSetByIndex(0) as LineDataSet
        ds.values = entries
        chart.data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.invalidate()
    }
}
