package com.iqoo.neo10.chargemonitor.ui.chart

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.iqoo.neo10.chargemonitor.App
import com.iqoo.neo10.chargemonitor.data.db.BatterySample
import com.iqoo.neo10.chargemonitor.data.db.ChargingRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
class ChartViewModel : ViewModel() {

    private val repository = App.instance.repository

    private val _activeRecord = MutableLiveData<ChargingRecord?>()
    val activeRecord: LiveData<ChargingRecord?> = _activeRecord

    val samples: LiveData<List<BatterySample>> = repository.getActiveRecordFlow()
        .flatMapLatest { record ->
            _activeRecord.postValue(record)
            if (record != null) repository.getSamplesFlow(record.id)
            else flowOf(emptyList())
        }
        .asLiveData()
}
