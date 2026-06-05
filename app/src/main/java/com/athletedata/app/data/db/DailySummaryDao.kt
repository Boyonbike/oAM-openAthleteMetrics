package com.athletedata.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface DailySummaryDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Inserts or replaces the summary for a given date.
     * Safe to call multiple times — REPLACE conflict strategy means the worker
     * can re-run without producing duplicate rows.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailySummaryEntity)

    @Delete
    suspend fun delete(entity: DailySummaryEntity)

    // ── Reads ─────────────────────────────────────────────────────────────────

    /** Live summary for [date]; emits null if no summary has been computed yet. */
    @Query("SELECT * FROM daily_summary WHERE date = :date LIMIT 1")
    fun getSummaryForDate(date: LocalDate): Flow<DailySummaryEntity?>

    /** Live summaries in the inclusive date range [from, to], oldest first. */
    @Query(
        """
        SELECT * FROM daily_summary
        WHERE date >= :from AND date <= :to
        ORDER BY date ASC
        """
    )
    fun getSummariesForRange(from: LocalDate, to: LocalDate): Flow<List<DailySummaryEntity>>

    /** One-shot read used by DailySummaryWorker before writing a new summary. */
    @Query("SELECT * FROM daily_summary WHERE date = :date LIMIT 1")
    suspend fun getSummaryForDateOnce(date: LocalDate): DailySummaryEntity?

    /** Deletes all rows; called after seeder data is cleared. */
    @Query("DELETE FROM daily_summary")
    suspend fun deleteAll()
}
