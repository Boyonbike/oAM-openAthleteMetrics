package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("UPDATE devices SET is_primary = 0, auto_sync_enabled = 0 WHERE is_primary = 1 AND id != :deviceId")
    suspend fun clearOtherPrimaries(deviceId: Long)

    @Query("UPDATE devices SET is_primary = 1, auto_sync_enabled = 1 WHERE id = :deviceId")
    suspend fun setPrimaryFlag(deviceId: Long)

    /**
     * Stars [deviceId]: makes it the sole primary device with auto-sync on, and turns the
     * previous primary's auto-sync off. Idempotent for the current primary -
     * [clearOtherPrimaries] excludes it via `id != :deviceId`, so re-starring never flips its
     * own auto-sync off. [deviceId] must be a real device id - passing a non-existent id
     * still clears the real current primary via [clearOtherPrimaries] while [setPrimaryFlag]
     * updates zero rows, leaving no device primary.
     */
    @Transaction
    suspend fun setPrimary(deviceId: Long) {
        clearOtherPrimaries(deviceId)
        setPrimaryFlag(deviceId)
    }

    @Query("UPDATE devices SET auto_sync_enabled = :enabled WHERE id = :deviceId")
    suspend fun setAutoSync(deviceId: Long, enabled: Boolean)

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM devices ORDER BY display_name ASC")
    fun getAllDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DeviceEntity?

    @Query("SELECT * FROM devices WHERE ble_address = :bleAddress LIMIT 1")
    suspend fun getDeviceByAddress(bleAddress: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE is_primary = 1 LIMIT 1")
    fun observePrimary(): Flow<DeviceEntity?>

    @Query("SELECT * FROM devices WHERE is_primary = 1 LIMIT 1")
    suspend fun getPrimary(): DeviceEntity?

    // Primary first (when it's also auto-sync-enabled), then the rest of the
    // auto-sync-enabled devices alphabetically. A primary device can have auto-sync
    // individually disabled via setAutoSync(), which this correctly excludes.
    @Query("SELECT * FROM devices WHERE auto_sync_enabled = 1 ORDER BY is_primary DESC, display_name ASC")
    suspend fun getAutoSyncEnabledOrdered(): List<DeviceEntity>
}
