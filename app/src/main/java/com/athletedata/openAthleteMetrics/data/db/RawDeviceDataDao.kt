package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RawDeviceDataDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<RawDeviceDataEntity>)

    @Query("DELETE FROM raw_device_data WHERE sync_session_id = :syncSessionId")
    suspend fun deleteForSession(syncSessionId: Long)

    @Query("DELETE FROM raw_device_data WHERE sync_session_id IN (SELECT id FROM sync_sessions WHERE device_id = :deviceId)")
    suspend fun deleteForDevice(deviceId: Long)

    @Query("DELETE FROM raw_device_data WHERE received_at < :thresholdMs")
    suspend fun deleteOlderThan(thresholdMs: Long)

    @Query("DELETE FROM raw_device_data")
    suspend fun deleteAll()

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM raw_device_data WHERE sync_session_id = :syncSessionId")
    suspend fun getForSession(syncSessionId: Long): List<RawDeviceDataEntity>
}
