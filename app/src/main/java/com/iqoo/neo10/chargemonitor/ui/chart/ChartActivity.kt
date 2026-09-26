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

class ChartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChartBinding
    private val vm: ChartViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupChart(binding.chartCurrent, "电流 (A)", Color.parseColor("#4FC3F7"))
        setupChart(binding.chartPower, "功率 (W)", Color.parseColor("#FFB74D"))
        setupChart(binding.chartTemp, "温度 (°C)", Color.parseColor("#EF5350"))

        // 温度阈值线
        val tempLimit = LimitLine(42f, "高温阈值 42°C").apply {
            lineColor = Color.RED
            lineWidth = 1.5f
            textColor = Color.RED
        }
        binding.chartTemp.axisLeft.addLimitLine(tempLimit)

        vm.samples.observe(this) { samples ->
            updateCharts(samples)
        }
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
            xAxis.textColor = Color.LTGRAY
            axisLeft.textColor = Color.LTGRAY
            axisLeft.setDrawGridLines(true)
            axisLeft.gridColor = Color.parseColor("#333333")
            setBackgroundColor(Color.parseColor("#121212"))
            setNoDataText("暂无数据，充电时将自动绘制")
            setNoDataTextColor(Color.GRAY)
        }
        val ds = LineDataSet(emptyList(), label).apply {
            this.color = color
            setCircleColor(color)
            circleRadius = 1.5f
            lineWidth = 1.5f
            setDrawValues(false)
            setDrawFilled(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        chart.data = LineData(ds)
    }

    private fun updateCharts(samples: List<BatterySample>) {
        updateChart(binding.chartCurrent, samples.map { Entry(it.elapsedSec.toFloat(), it.current) })
        updateChart(binding.chartPower, samples.map { Entry(it.elapsedSec.toFloat(), it.power) })
        updateChart(binding.chartTemp, samples.map { Entry(it.elapsedSec.toFloat(), it.temperature) })
    }

    private fun updateChart(chart: LineChart, entries: List<Entry>) {
        val ds = chart.data.getDataSetByIndex(0) as LineDataSet
        ds.values = entries
        chart.data.notifyDataChanged()
        chart.notifyDataSetChanged()
        chart.invalidate()
    }
}
