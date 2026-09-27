package com.iqoo.neo10.chargemonitor.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChargingDao {

    @Insert
    suspend fun insertRecord(record: ChargingRecord): Long

    @Update
    suspend fun updateRecord(record: ChargingRecord)

    @Insert
    suspend fun insertSample(sample: BatterySample): Long

    @Query("SELECT * FROM charging_records WHERE endTime IS NULL ORDER BY id DESC LIMIT 1")
    fun getActiveRecordFlow(): Flow<ChargingRecord?>

    @Query("SELECT * FROM charging_records WHERE endTime IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun getActiveRecord(): ChargingRecord?

    @Query("SELECT * FROM battery_samples WHERE recordId = :recordId ORDER BY id ASC")
    fun getSamplesFlow(recordId: Long): Flow<List<BatterySample>>

    @Query("SELECT * FROM battery_samples WHERE recordId = :recordId ORDER BY id ASC")
    suspend fun getSamples(recordId: Long): List<BatterySample>

    @Query("SELECT * FROM battery_samples WHERE recordId = :recordId ORDER BY id DESC LIMIT 1")
    suspend fun getLastSample(recordId: Long): BatterySample?

    @Query("SELECT * FROM charging_records ORDER BY startTime DESC")
    fun getAllRecordsFlow(): Flow<List<ChargingRecord>>

    @Query("SELECT * FROM charging_records ORDER BY startTime DESC")
    suspend fun getAllRecordsOnce(): List<ChargingRecord>

    @Query("SELECT * FROM charging_records WHERE id = :id")
    suspend fun getRecordById(id: Long): ChargingRecord?

    @Query("DELETE FROM charging_records WHERE id = :id")
    suspend fun deleteRecord(id: Long)
}
