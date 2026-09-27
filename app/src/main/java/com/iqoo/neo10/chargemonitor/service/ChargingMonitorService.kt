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

    /** 连续检测到非充电状态的次数，达到阈值则自动停止。5s × 6 = 30s 确认，避免瞬时读数抖动误判 */
    private var nonChargingTicks = 0
    private val NON_CHARGING_THRESHOLD = 6

    /** 复用未结束记录的最大间隔（毫秒）。超过此间隔说明中间断开过，应新建记录而非复用 */
    private val REUSE_MAX_GAP_MS = 90_000L

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
            // 处理未结束的残留记录：如果最后一条样本距今超过 REUSE_MAX_GAP_MS，
            // 说明中间断开过（拔了充电器），应 finalize 旧记录后新建，保证每次插拔一条记录
            val existing = repository.getActiveRecord()
            if (existing != null) {
                val lastSample = repository.getLastSample(existing.id)
                val gap = System.currentTimeMillis() - (lastSample?.timestamp ?: existing.startTime)
                if (gap > REUSE_MAX_GAP_MS) {
                    Log.i(TAG, "残留记录最后样本距今 ${gap}ms，已断开，finalize 后新建记录")
                    finalizeExistingRecord(existing, lastSample)
                    createNewRecord()
                } else {
                    // 间隔较短，服务刚重启，复用旧记录
                    Log.i(TAG, "复用未结束记录（最后样本距今 ${gap}ms）")
                    currentRecordId = existing.id
                    startTime = existing.startTime
                }
            } else {
                createNewRecord()
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

    /** 新建一条充电记录并保存首个样本 */
    private suspend fun createNewRecord() {
        val snap = BatteryReader.readSnapshot()
        val record = ChargingRecord(
            startTime = System.currentTimeMillis(),
            startCapacity = snap.capacity
        )
        currentRecordId = repository.insertRecord(record)
        startTime = record.startTime
        saveSample(snap)
        Log.i(TAG, "新建充电记录 id=$currentRecordId, capacity=${snap.capacity}%")
    }

    /** finalize 残留的未结束记录，endTime 取最后一条样本的时间戳（更准确） */
    private suspend fun finalizeExistingRecord(record: ChargingRecord, lastSample: BatterySample?) {
        record.endTime = lastSample?.timestamp ?: System.currentTimeMillis()
        record.endCapacity = lastSample?.capacity ?: record.startCapacity
        repository.updateRecord(record)
        Log.i(TAG, "finalize 残留记录 id=${record.id}, endTime=${record.endTime}, endCap=${record.endCapacity}")
    }

    /** 同步写入 endTime + endCapacity，调用方确保在 IO 线程或 suspend 环境中 */
    private suspend fun finalizeRecordSync() {
        val record = repository.getRecordById(currentRecordId) ?: return
        // endTime 优先取最后一条样本的时间戳，比 System.currentTimeMillis() 更准确
        val lastSample = repository.getLastSample(currentRecordId)
        val snap = BatteryReader.readSnapshot()
        record.endTime = lastSample?.timestamp ?: System.currentTimeMillis()
        record.endCapacity = lastSample?.capacity ?: snap.capacity
        repository.updateRecord(record)
        Log.i(TAG, "finalizeRecord: endTime=${record.endTime}, endCapacity=${record.endCapacity}")
    }

    /** 外部请求停止时，同步 finalize 后再 stopSelf，确保 endTime 一定写入 */
    private fun finalizeAndStop() {
        runBlocking {
            try { finalizeRecordSync() } catch (e: Exception) {
                Log.e(TAG, "finalizeAndStop 异常", e)
            }
        }
        stopSelf()
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
        private const val POLL_INTERVAL_MS = 5_000L
        const val ACTION_STOP = "com.iqoo.neo10.chargemonitor.ACTION_STOP"
    }
}
