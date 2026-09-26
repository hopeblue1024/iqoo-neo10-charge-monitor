package com.iqoo.neo10.chargemonitor

import android.app.Application
import com.iqoo.neo10.chargemonitor.data.db.AppDatabase
import com.iqoo.neo10.chargemonitor.data.repository.ChargingRepository
import com.iqoo.neo10.chargemonitor.util.PrefUtil

class App : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val repository by lazy { ChargingRepository(database.chargingDao()) }

    override fun onCreate() {
        // 在 Application 创建时应用保存的主题模式
        PrefUtil.applyTheme(this)
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
