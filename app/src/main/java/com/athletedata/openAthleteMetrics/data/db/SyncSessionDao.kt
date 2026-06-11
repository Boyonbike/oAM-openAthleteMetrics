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

    @Query("DELETE FROM sync_sessions WHERE device_id = :deviceId")
    suspend fun deleteForDevice(deviceId: Long)

    @Query("DELETE FROM sync_sessions WHERE started_at < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM sync_sessions")
    suspend fun deleteAll()

    @Query("UPDATE sync_sessions SET status = 'FAILED' WHERE status = 'PARTIAL' AND started_at < :cutoffMs")
    suspend fun markOldPartialsAsFailed(cutoffMs: Long)

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
