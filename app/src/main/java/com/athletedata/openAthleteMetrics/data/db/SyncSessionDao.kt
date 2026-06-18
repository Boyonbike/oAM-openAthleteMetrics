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
    suspend fun upsert(entity: SyncSessionEntity)

    @Query("DELETE FROM sync_sessions WHERE device_id = :deviceId")
    suspend fun deleteForDevice(deviceId: Long)

    @Query("DELETE FROM sync_sessions WHERE started_at < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    @Query("DELETE FROM sync_sessions")
    suspend fun deleteAll()

    @Query("""
        UPDATE sync_sessions
        SET status = 'FAILED'
        WHERE (status = 'IN_PROGRESS' OR (status = 'PARTIAL' AND ended_at IS NULL))
          AND started_at < :cutoffMs
    """)
    suspend fun markOldInProgressAsFailed(cutoffMs: Long)

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM sync_sessions WHERE id = :id")
    suspend fun getById(id: Long): SyncSessionEntity?

    @Query(
        """
        SELECT * FROM sync_sessions
        WHERE status = 'IN_PROGRESS'
          AND ended_at IS NULL
          AND started_at >= :cutoffMs
        ORDER BY started_at DESC
        """
    )
    suspend fun getRecentInProgress(cutoffMs: Long): List<SyncSessionEntity>

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
