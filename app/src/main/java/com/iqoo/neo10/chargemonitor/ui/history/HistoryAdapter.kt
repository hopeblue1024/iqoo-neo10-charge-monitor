package com.iqoo.neo10.chargemonitor.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.iqoo.neo10.chargemonitor.data.db.ChargingRecord
import com.iqoo.neo10.chargemonitor.databinding.ItemHistoryBinding
import com.iqoo.neo10.chargemonitor.util.FormatUtil

class HistoryAdapter(
    private val onClick: (ChargingRecord) -> Unit
) : ListAdapter<ChargingRecord, HistoryAdapter.VH>(DIFF) {

    inner class VH(val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = getItem(position)
        with(holder.b) {
            tvStartTime.text = FormatUtil.formatDateTime(r.startTime)
            // endTime 为 null 表示充电仍在进行中（或服务被杀未 finalize）
            val end = r.endTime
            tvDuration.text = if (end != null) {
                FormatUtil.formatDuration((end - r.startTime) / 1000)
            } else {
                "进行中"
            }
            val endCapText = r.endCapacity?.let { "${it}%" } ?: "进行中"
            tvCapacity.text = "${r.startCapacity}% → ${endCapText}"
            tvMaxPower.text = "峰值 ${FormatUtil.formatFloat1(r.maxPower)} W"
            tvMaxTemp.text = "最高 ${FormatUtil.formatFloat1(r.maxTemp)} °C"
            root.setOnClickListener { onClick(r) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChargingRecord>() {
            override fun areItemsTheSame(a: ChargingRecord, b: ChargingRecord) = a.id == b.id
            override fun areContentsTheSame(a: ChargingRecord, b: ChargingRecord) = a == b
        }
    }
}
