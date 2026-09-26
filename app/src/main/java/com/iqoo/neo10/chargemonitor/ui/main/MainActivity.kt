package com.iqoo.neo10.chargemonitor.ui.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.BatteryManager
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.iqoo.neo10.chargemonitor.databinding.ActivityMainBinding
import com.iqoo.neo10.chargemonitor.service.ChargingMonitorService
import com.iqoo.neo10.chargemonitor.ui.chart.ChartActivity
import com.iqoo.neo10.chargemonitor.ui.history.HistoryActivity
import com.iqoo.neo10.chargemonitor.util.FormatUtil

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    /** 动态接收充电插拔事件（Activity 位于前台时可启动前台服务） */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    Log.i(TAG, "动态接收: 电源已连接")
                    startMonitorService()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    Log.i(TAG, "动态接收: 电源已断开")
                    stopMonitorService()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()

        vm.snapshot.observe(this) { s ->
            binding.tvCapacity.text = "${s.capacity}%"
            binding.tvVoltage.text = "${FormatUtil.formatFloat2(s.voltage)} V"
            binding.tvCurrent.text = "${FormatUtil.formatFloat2(s.current)} A"
            binding.tvPower.text = "${FormatUtil.formatFloat1(s.power)} W"
            binding.tvTemp.text = "${FormatUtil.formatFloat1(s.temperature)} °C"
            binding.tvSource.text = s.source
            binding.tvStatus.text = s.status
            // 每秒轮询兜底：若系统判定正在充电但尚未记录，则启动服务
            ensureMonitorIfCharging()
        }

        vm.activeRecord.observe(this) { r ->
            val charging = isCharging()
            binding.tvSessionStatus.text = when {
                r != null -> "正在记录充电中…"
                charging -> "已连接充电器（准备记录）"
                else -> "未在充电"
            }
        }

        binding.btnChart.setOnClickListener {
            startActivity(Intent(this, ChartActivity::class.java))
        }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, filter)
        vm.startTicking()
        ensureMonitorIfCharging()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(powerReceiver)
        } catch (_: Exception) {
        }
        vm.stopTicking()
    }

    private fun isCharging(): Boolean {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    /** 若正在充电且无活跃记录，则启动监测服务（前台调用，不受后台启动限制） */
    private fun ensureMonitorIfCharging() {
        if (isCharging() && vm.activeRecord.value == null) {
            startMonitorService()
        }
    }

    private fun startMonitorService() {
        try {
            val svc = Intent(this, ChargingMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
            Log.i(TAG, "已请求启动充电监测服务")
        } catch (e: Exception) {
            Log.e(TAG, "启动服务失败", e)
        }
    }

    private fun stopMonitorService() {
        try {
            val svc = Intent(this, ChargingMonitorService::class.java).apply {
                action = ChargingMonitorService.ACTION_STOP
            }
            startService(svc)
        } catch (e: Exception) {
            Log.e(TAG, "停止服务失败", e)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
