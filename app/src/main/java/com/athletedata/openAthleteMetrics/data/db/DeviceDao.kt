package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DeviceEntity)

    @Delete
    suspend fun delete(entity: DeviceEntity)

    @Query("DELETE FROM devices")
    suspend fun deleteAll()

    @Query("UPDATE devices SET last_seen_ms = :timestampMs WHERE ble_address = :bleAddress")
    suspend fun updateLastSeen(bleAddress: String, timestampMs: Long)

    @Query("UPDATE devices SET last_sync_ms = :timestampMs WHERE ble_address = :bleAddress")
    suspend fun updateLastSync(bleAddress: String, timestampMs: Long)

    @Query("UPDATE devices SET last_battery_pct = :pct WHERE ble_address = :bleAddress")
    suspend fun updateLastBatteryPct(bleAddress: String, pct: Int)

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM devices ORDER BY display_name ASC")
    fun getAllDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE ble_address = :bleAddress LIMIT 1")
    suspend fun getDeviceByAddress(bleAddress: String): DeviceEntity?
}
