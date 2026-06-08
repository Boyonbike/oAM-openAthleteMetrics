package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncSessionDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SyncSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(entity: SyncSessionEntity)

    @Query("DELETE FROM sync_sessions")
    suspend fun deleteAll()

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Query(
        """
        SELECT * FROM sync_sessions
        WHERE device_id = :deviceId
        ORDER BY started_at DESC
        """
    )
    fun getSessionsForDevice(deviceId: Long): Flow<List<SyncSessionEntity>>

    @Query(
        """
        SELECT * FROM sync_sessions
        WHERE device_id = :deviceId
        ORDER BY started_at DESC
        LIMIT 1
        """
    )
    suspend fun getLatestSessionForDevice(deviceId: Long): SyncSessionEntity?
}
