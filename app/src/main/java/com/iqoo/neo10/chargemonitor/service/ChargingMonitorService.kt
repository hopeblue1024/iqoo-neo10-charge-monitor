package com.iqoo.neo10.chargemonitor.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.iqoo.neo10.chargemonitor.App
import com.iqoo.neo10.chargemonitor.battery.BatteryReader
import com.iqoo.neo10.chargemonitor.battery.BatterySnapshot
import com.iqoo.neo10.chargemonitor.data.db.BatterySample
import com.iqoo.neo10.chargemonitor.data.db.ChargingRecord
import com.iqoo.neo10.chargemonitor.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 充电监测前台服务。
 * 自己轮询检测充电状态，结束时同步写入 endTime 并自停，不依赖 Activity 的广播接收器。
 */
class ChargingMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val repository get() = (application as App).repository

    private var currentRecordId: Long = 0L
    private var startTime: Long = 0L

    private var highTempNotified = false
    private var fullNotified = false

    /** 连续检测到非充电状态的次数，达到阈值则自动停止 */
    private var nonChargingTicks = 0
    private val NON_CHARGING_THRESHOLD = 2

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finalizeAndStop()
                return START_NOT_STICKY
            }
        }
        startForeground(
            NotificationHelper.NOTIF_ID_MONITOR,
            NotificationHelper.buildMonitorNotification(this, 0, 0f, 0f, "USB")
        )
        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        if (pollJob?.isActive == true) return

        pollJob = scope.launch {
            // 复用未结束的充电记录（服务重启等场景），否则新建
            val existing = repository.getActiveRecord()
            if (existing != null) {
                currentRecordId = existing.id
                startTime = existing.startTime
            } else {
                val snap = BatteryReader.readSnapshot()
                val record = ChargingRecord(
                    startTime = System.currentTimeMillis(),
                    startCapacity = snap.capacity
                )
                currentRecordId = repository.insertRecord(record)
                startTime = record.startTime
                saveSample(snap)
            }

            highTempNotified = false
            fullNotified = false
            nonChargingTicks = 0

            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!isActive) break
                val s = BatteryReader.readSnapshot()
                saveSample(s)
                updateNotification(s)
                checkAlerts(s)

                // 检测充电是否结束：连续 2 次非充电状态则自停
                if (s.status != "充电中" && s.status != "已充满") {
                    nonChargingTicks++
                    if (nonChargingTicks >= NON_CHARGING_THRESHOLD) {
                        Log.i(TAG, "检测到充电已结束（连续 ${nonChargingTicks} 次非充电状态），停止服务")
                        break
                    }
                } else {
                    nonChargingTicks = 0
                }
            }

            // 同步写入 endTime + endCapacity，确保进程被杀前持久化
            finalizeRecordSync()
            stopSelf()
        }
    }

    private suspend fun saveSample(s: BatterySnapshot) {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000L
        val sample = BatterySample(
            recordId = currentRecordId,
            timestamp = System.currentTimeMillis(),
            elapsedSec = elapsed,
            capacity = s.capacity,
            voltage = s.voltage,
            current = s.current,
            power = s.power,
            temperature = s.temperature,
            source = s.source
        )
        repository.insertSample(sample)

        // 更新记录的最高功率与温度
        val record = repository.getRecordById(currentRecordId) ?: return
        var changed = false
        if (s.power > record.maxPower) { record.maxPower = s.power; changed = true }
        if (s.temperature > record.maxTemp) { record.maxTemp = s.temperature; changed = true }
        if (changed) repository.updateRecord(record)
    }

    private fun updateNotification(s: BatterySnapshot) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(
            NotificationHelper.NOTIF_ID_MONITOR,
            NotificationHelper.buildMonitorNotification(this, s.capacity, s.power, s.temperature, s.source)
        )
    }

    private fun checkAlerts(s: BatterySnapshot) {
        if (!highTempNotified && NotificationHelper.isHighTemp(s.temperature)) {
            NotificationHelper.notifyHighTemp(this, s.temperature)
            highTempNotified = true
        }
        if (!fullNotified && s.capacity >= 100) {
            NotificationHelper.notifyFullCharge(this)
            fullNotified = true
        }
    }

    /** 同步写入 endTime + endCapacity，调用方确保在 IO 线程或 suspend 环境中 */
    private suspend fun finalizeRecordSync() {
        val record = repository.getRecordById(currentRecordId) ?: return
        val snap = BatteryReader.readSnapshot()
        record.endTime = System.currentTimeMillis()
        record.endCapacity = snap.capacity
        repository.updateRecord(record)
        Log.i(TAG, "finalizeRecord: endTime=${record.endTime}, endCapacity=${record.endCapacity}")
    }

    /** 外部请求停止时，走同步 finalize 路径 */
    private fun finalizeAndStop() {
        scope.launch {
            finalizeRecordSync()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 兜底：如果因异常被杀，用 runBlocking 抢时间写入 endTime
        runBlocking {
            try { finalizeRecordSync() } catch (_: Exception) {}
        }
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ChargingMonitorService"
        private const val POLL_INTERVAL_MS = 60_000L
        const val ACTION_STOP = "com.iqoo.neo10.chargemonitor.ACTION_STOP"
    }
}
