package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface SleepSessionDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SleepSessionEntity)

    @Delete
    suspend fun delete(entity: SleepSessionEntity)

    /** Deletes all sessions from a specific source; used for seeder cleanup. */
    @Query("DELETE FROM sleep_sessions WHERE source = :source")
    suspend fun deleteBySource(source: DataSource)

    // ── Reads ─────────────────────────────────────────────────────────────────

    /** The session for a given night (keyed by morning date), or null. */
    @Query("SELECT * FROM sleep_sessions WHERE date = :date LIMIT 1")
    fun getSessionForDate(date: LocalDate): Flow<SleepSessionEntity?>

    /** All sessions in the inclusive date range [from, to], oldest first. */
    @Query(
        """
        SELECT * FROM sleep_sessions
        WHERE date >= :from AND date <= :to
        ORDER BY date ASC
        """
    )
    fun getSessionsForRange(from: LocalDate, to: LocalDate): Flow<List<SleepSessionEntity>>

    /** One-shot read for a specific date; used by DailySummaryWorker. */
    @Query("SELECT * FROM sleep_sessions WHERE date = :date LIMIT 1")
    suspend fun getSessionForDateOnce(date: LocalDate): SleepSessionEntity?
}
