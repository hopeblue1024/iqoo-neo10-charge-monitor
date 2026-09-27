package com.iqoo.neo10.chargemonitor.ui.historydetail

import android.graphics.Color
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.iqoo.neo10.chargemonitor.App
import com.iqoo.neo10.chargemonitor.data.db.BatterySample
import com.iqoo.neo10.chargemonitor.data.db.ChargingRecord
import com.iqoo.neo10.chargemonitor.databinding.ActivityHistoryDetailBinding
import com.iqoo.neo10.chargemonitor.util.CsvExporter
import com.iqoo.neo10.chargemonitor.util.FormatUtil
import com.iqoo.neo10.chargemonitor.util.TimeAxisFormatter
import kotlinx.coroutines.launch

class HistoryDetailViewModel : ViewModel() {
    private val repository = App.instance.repository

    private val _record = MutableLiveData<ChargingRecord?>()
    val record: LiveData<ChargingRecord?> = _record

    private val _samples = MutableLiveData<List<BatterySample>>()
    val samples: LiveData<List<BatterySample>> = _samples

    fun load(id: Long) {
        viewModelScope.launch {
            _record.postValue(repository.getRecordById(id))
            _samples.postValue(repository.getSamples(id))
        }
    }
}

class HistoryDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryDetailBinding
    private val vm: HistoryDetailViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        if (recordId <= 0) { finish(); return }

        setupChart(binding.chartCurrent, "电流 (A)", Color.parseColor("#0A84FF"))
        setupChart(binding.chartPower, "功率 (W)", Color.parseColor("#FF9F0A"))
        setupChart(binding.chartTemp, "温度 (°C)", Color.parseColor("#FF453A"))
        val tempLimit = LimitLine(42f, "高温阈值 42°C").apply {
            lineColor = Color.RED; lineWidth = 1.5f; textColor = Color.RED
        }
        binding.chartTemp.axisLeft.addLimitLine(tempLimit)

        vm.record.observe(this) { renderInfo() }
        vm.samples.observe(this) { samples ->
            updateCharts(samples)
            renderInfo()
        }

        binding.btnExport.setOnClickListener {
            val r = vm.record.value ?: return@setOnClickListener
            val s = vm.samples.value ?: emptyList()
            if (s.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                CsvExporter.exportAndShare(this@HistoryDetailActivity, r, s)
            }
        }

        vm.load(recordId)
    }

    /** 渲染标题与统计信息，需 record 和 samples 都加载完成 */
    private fun renderInfo() {
        val r = vm.record.value ?: return
        binding.tvTitle.text = FormatUtil.formatDateTime(r.startTime)

        val samples = vm.samples.value
        val endTime = r.endTime
        // 时长：优先用 endTime；endTime 为 null 时从最后一条样本推算（进行中或服务被杀的兜底）
        val durSec = when {
            endTime != null -> (endTime - r.startTime) / 1000
            !samples.isNullOrEmpty() -> {
                val last = samples.last()
                maxOf((last.timestamp - r.startTime) / 1000, last.elapsedSec)
            }
            else -> 0L
        }
        // 结束电量：优先 endCapacity；为 null 时取最后一条样本的容量
        val endCap = r.endCapacity ?: samples?.lastOrNull()?.capacity ?: r.startCapacity

        binding.tvInfo.text = buildString {
            append("时长：${FormatUtil.formatDuration(durSec)}\n")
            append("电量：${r.startCapacity}% → ${endCap}%\n")
            append("峰值功率：${FormatUtil.formatFloat1(r.maxPower)} W\n")
            append("最高温度：${FormatUtil.formatFloat1(r.maxTemp)} °C")
        }
        // X 轴时间格式化
        val formatter = TimeAxisFormatter(r.startTime)
        binding.chartCurrent.xAxis.valueFormatter = formatter
        binding.chartPower.xAxis.valueFormatter = formatter
        binding.chartTemp.xAxis.valueFormatter = formatter
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
            xAxis.granularity = 1f
            axisLeft.textColor = Color.GRAY
            axisLeft.setDrawGridLines(true)
            axisLeft.gridColor = Color.parseColor("#333333")
            setBackgroundColor(Color.TRANSPARENT)
        }
        val ds = LineDataSet(emptyList(), label).apply {
            this.color = color; setCircleColor(color); circleRadius = 2.5f
            lineWidth = 1.5f; setDrawValues(false); mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        chart.data = LineData(ds)
    }

    private fun updateCharts(samples: List<BatterySample>) {
        updateChart(binding.chartCurrent, samples.map { Entry(it.elapsedSec / 60f, it.current) })
        updateChart(binding.chartPower, samples.map { Entry(it.elapsedSec / 60f, it.power) })
        updateChart(binding.chartTemp, samples.map { Entry(it.elapsedSec / 60f, it.temperature) })

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

    companion object {
        const val EXTRA_RECORD_ID = "record_id"
    }
}
