package com.iqoo.neo10.chargemonitor

import android.app.Application
import com.iqoo.neo10.chargemonitor.data.db.AppDatabase
import com.iqoo.neo10.chargemonitor.data.repository.ChargingRepository

class App : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val repository by lazy { ChargingRepository(database.chargingDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
