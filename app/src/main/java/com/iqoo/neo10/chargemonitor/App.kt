package com.iqoo.neo10.chargemonitor

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.iqoo.neo10.chargemonitor.data.db.AppDatabase
import com.iqoo.neo10.chargemonitor.data.repository.ChargingRepository
import com.iqoo.neo10.chargemonitor.util.PrefUtil
import com.iqoo.neo10.chargemonitor.worker.ChargingCheckWorker
import java.util.concurrent.TimeUnit

class App : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val repository by lazy { ChargingRepository(database.chargingDao()) }

    override fun onCreate() {
        // 在 Application 创建时应用保存的主题模式
        PrefUtil.applyTheme(this)
        super.onCreate()
        instance = this
        scheduleChargingCheckWork()
    }

    /**
     * 调度周期性充电检查任务（15分钟一次，仅在充电时执行）。
     * 作为后备：当 ChargingReceiver 在 Android 12+ 后台无法启动前台服务、
     * 或服务被系统杀掉时，这个任务会在下一个周期内重新拉起服务。
     */
    private fun scheduleChargingCheckWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresCharging(true)
            .build()

        val request = PeriodicWorkRequestBuilder<ChargingCheckWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CHARGING_CHECK_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        lateinit var instance: App
            private set
        const val CHARGING_CHECK_WORK_NAME = "charging_check_work"
    }
}
