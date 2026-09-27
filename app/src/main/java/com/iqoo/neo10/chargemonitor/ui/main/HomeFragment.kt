package com.iqoo.neo10.chargemonitor.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.iqoo.neo10.chargemonitor.battery.BatterySnapshot
import com.iqoo.neo10.chargemonitor.data.db.ChargingRecord
import com.iqoo.neo10.chargemonitor.databinding.FragmentHomeBinding
import com.iqoo.neo10.chargemonitor.util.FormatUtil

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    /** 缓存最新快照，供 updateSession 判断充电状态用 */
    private var lastSnapshot: BatterySnapshot? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun updateData(s: BatterySnapshot) {
        lastSnapshot = s
        _binding?.let { b ->
            b.tvCapacity.text = "${s.capacity}%"
            b.tvVoltage.text = "${FormatUtil.formatFloat2(s.voltage)} V"
            b.tvCurrent.text = "${FormatUtil.formatFloat2(s.current)} A"
            b.tvPower.text = "${FormatUtil.formatFloat1(s.power)} W"
            b.tvTemp.text = "${FormatUtil.formatFloat1(s.temperature)} °C"
            b.tvSource.text = s.source
            b.tvStatus.text = s.status
        }
    }

    /**
     * 更新主卡下方的充电状态提示。
     * 统一使用缓存的 BatterySnapshot.status 判断是否在充电，
     * 不再自行调用 BatteryManager.isCharging，避免两套数据源不一致。
     */
    fun updateSession(r: ChargingRecord?) {
        _binding?.let { b ->
            val snap = lastSnapshot
            val isCharging = snap != null && (snap.status == "充电中" || snap.status == "已充满")
            b.tvSessionStatus.text = when {
                r != null -> "正在记录充电中…"
                isCharging -> "已连接充电器（准备记录）"
                else -> "未在充电"
            }
        }
    }
}
