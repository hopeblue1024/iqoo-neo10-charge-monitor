package com.iqoo.neo10.chargemonitor.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.iqoo.neo10.chargemonitor.data.db.AppDatabase
import com.iqoo.neo10.chargemonitor.notification.NotificationHelper
import com.iqoo.neo10.chargemonitor.service.ChargingMonitorService

/**
 * 充电状态检查 Worker。
 *
 * 两个用途：
 * 1. 启动桥梁：ChargingReceiver 在 Android 12+ 后台无法直接 startForegroundService，
 *    通过 expedited WorkRequest 走这里再启动服务。
 * 2. 后备检查：PeriodicWorkRequest（15分钟，充电约束）定期检查充电状态，
 *    如果在充电但服务未运行，重新拉起服务（服务被系统杀掉的场景）。
 */
class ChargingCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val TAG = "ChargingCheckWorker"

    override suspend fun doWork(): Result {
        return try {
            // 创建通道，确保通知可用
            NotificationHelper.createChannels(applicationContext)

            // 检查是否在充电
            val isCharging = checkIsCharging()
            Log.i(TAG, "doWork: isCharging=$isCharging")

            if (!isCharging) {
                // 未充电，无需启动服务
                return Result.success()
            }

            // 检查是否有未结束的充电记录（服务应该正在运行）
            val db = AppDatabase.getInstance(applicationContext)
            val activeRecord = db.chargingDao().getActiveRecord()

            if (activeRecord != null) {
                // 有未结束的记录，检查服务是否在运行
                // 直接尝试启动服务，onStartCommand 会处理重复启动
                Log.i(TAG, "检测到未结束的充电记录，尝试启动服务")
                startMonitorService()
            } else {
                // 没有未结束的记录，可能是新充电，启动服务
                Log.i(TAG, "无未结束记录，充电中，启动服务开始新记录")
                startMonitorService()
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "doWork failed", e)
            Result.retry()
        }
    }

    private fun checkIsCharging(): Boolean {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE)
                as android.os.BatteryManager
        return bm.isCharging
    }

    private fun startMonitorService() {
        val svc = Intent(applicationContext, ChargingMonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(svc)
            } else {
                applicationContext.startService(svc)
            }
            Log.i(TAG, "启动 ChargingMonitorService 成功")
        } catch (e: Exception) {
            Log.e(TAG, "启动 ChargingMonitorService 失败", e)
        }
    }
}
