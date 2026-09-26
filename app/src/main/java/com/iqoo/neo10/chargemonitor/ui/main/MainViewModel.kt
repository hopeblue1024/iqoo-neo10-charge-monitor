package com.iqoo.neo10.chargemonitor.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.iqoo.neo10.chargemonitor.App
import com.iqoo.neo10.chargemonitor.battery.BatteryReader
import com.iqoo.neo10.chargemonitor.battery.BatterySnapshot
import com.iqoo.neo10.chargemonitor.data.db.ChargingRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val repository = App.instance.repository

    private val _snapshot = MutableLiveData<BatterySnapshot>()
    val snapshot: LiveData<BatterySnapshot> = _snapshot

    val activeRecord: LiveData<ChargingRecord?> = repository.getActiveRecordFlow().asLiveData()

    private var tickJob: Job? = null

    fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                _snapshot.postValue(BatteryReader.readSnapshot())
                delay(1000)
            }
        }
    }

    fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    override fun onCleared() {
        stopTicking()
        super.onCleared()
    }
}
