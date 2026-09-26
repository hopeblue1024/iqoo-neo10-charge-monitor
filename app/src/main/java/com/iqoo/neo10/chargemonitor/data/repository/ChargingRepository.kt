package com.iqoo.neo10.chargemonitor.data.repository

import com.iqoo.neo10.chargemonitor.data.db.BatterySample
import com.iqoo.neo10.chargemonitor.data.db.ChargingDao
import com.iqoo.neo10.chargemonitor.data.db.ChargingRecord
import kotlinx.coroutines.flow.Flow

class ChargingRepository(private val dao: ChargingDao) {

    fun getActiveRecordFlow(): Flow<ChargingRecord?> = dao.getActiveRecordFlow()

    suspend fun getActiveRecord(): ChargingRecord? = dao.getActiveRecord()

    fun getSamplesFlow(recordId: Long): Flow<List<BatterySample>> = dao.getSamplesFlow(recordId)

    suspend fun getSamples(recordId: Long): List<BatterySample> = dao.getSamples(recordId)

    fun getAllRecordsFlow(): Flow<List<ChargingRecord>> = dao.getAllRecordsFlow()

    suspend fun getAllRecordsOnce(): List<ChargingRecord> = dao.getAllRecordsOnce()

    suspend fun getRecordById(id: Long): ChargingRecord? = dao.getRecordById(id)

    suspend fun insertRecord(record: ChargingRecord): Long = dao.insertRecord(record)

    suspend fun updateRecord(record: ChargingRecord) = dao.updateRecord(record)

    suspend fun insertSample(sample: BatterySample): Long = dao.insertSample(sample)

    suspend fun deleteRecord(id: Long) = dao.deleteRecord(id)
}
