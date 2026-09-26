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

/**
 * 充电监测前台服务。
 * 插上充电器时由 ChargingReceiver 启动，拔下时停止并结束本次记录。
 *
 * 职责：
 *  - 前台常驻通知展示实时充电信息
 *  - 每秒读取 /sys/class/power_supply 电池参数
 *  - 写入 Room 时序采样数据
 *  - 高温 / 充满告警推送
 */
class ChargingMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 独立作用域，用于在 onDestroy 时完成记录收尾，不随 scope 取消 */
    private val finalizeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val repository get() = (application as App).repository

    private var currentRecordId: Long = 0L
    private var startTime: Long = 0L

    private var highTempNotified = false
    private var fullNotified = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
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

            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!isActive) break
                val s = BatteryReader.readSnapshot()
                saveSample(s)
                updateNotification(s)
                checkAlerts(s)
            }
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

    private fun finalizeRecord() {
        finalizeScope.launch {
            val record = repository.getRecordById(currentRecordId) ?: return@launch
            val snap = BatteryReader.readSnapshot()
            record.endTime = System.currentTimeMillis()
            record.endCapacity = snap.capacity
            repository.updateRecord(record)
        }
    }

    override fun onDestroy() {
        finalizeRecord()
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L
        const val ACTION_STOP = "com.iqoo.neo10.chargemonitor.ACTION_STOP"
    }
}
