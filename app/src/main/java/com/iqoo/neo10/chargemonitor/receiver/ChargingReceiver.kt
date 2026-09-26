package com.iqoo.neo10.chargemonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.iqoo.neo10.chargemonitor.service.ChargingMonitorService

/**
 * 监听充电插拔事件：
 *  - 插上充电器 -> 启动 ChargingMonitorService
 *  - 拔下充电器 -> 停止服务并结束本次充电记录
 */
class ChargingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> startMonitor(context)
            Intent.ACTION_POWER_DISCONNECTED -> stopMonitor(context)
            Intent.ACTION_BOOT_COMPLETED -> {
                // 开机后若仍在充电，则启动监测
                if (isCharging(context)) startMonitor(context)
            }
        }
    }

    private fun startMonitor(context: Context) {
        Log.i(TAG, "Power connected -> start monitor")
        try {
            val svc = Intent(context, ChargingMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务受限；用户打开 APP 时 MainActivity 会兜底启动
            Log.e(TAG, "start foreground service failed", e)
        }
    }

    private fun stopMonitor(context: Context) {
        Log.i(TAG, "Power disconnected -> stop monitor")
        val svc = Intent(context, ChargingMonitorService::class.java).apply {
            action = ChargingMonitorService.ACTION_STOP
        }
        context.startService(svc)
    }

    private fun isCharging(context: Context): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return bm.isCharging
    }

    companion object { private const val TAG = "ChargingReceiver" }
}
