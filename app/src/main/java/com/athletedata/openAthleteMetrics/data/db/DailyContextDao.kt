package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface DailyContextDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Inserts or replaces the context for a given date.
     * Calling this a second time for the same date updates all fields in place.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyContextEntity)

    @Delete
    suspend fun delete(entity: DailyContextEntity)

    /**
     * Deletes every row in the table.
     *
     * Used by the seeder cleanup path. Because [DailyContextEntity] has no
     * `source` column (context is always user or seeder), clearing by date
     * range or blanket delete is the only practical option in the MVP.
     */
    @Query("DELETE FROM daily_context")
    suspend fun deleteAll()

    // ── Reads ─────────────────────────────────────────────────────────────────

    /** Live context for [date]; emits null if the user has not filled it in yet. */
    @Query("SELECT * FROM daily_context WHERE date = :date LIMIT 1")
    fun getContextForDate(date: LocalDate): Flow<DailyContextEntity?>

    /** One-shot read; used by the seeder to check for existing data before re-seeding. */
    @Query("SELECT * FROM daily_context WHERE date = :date LIMIT 1")
    suspend fun getContextForDateOnce(date: LocalDate): DailyContextEntity?

    /** Live contexts in the inclusive date range [from, to], oldest first. */
    @Query(
        """
        SELECT * FROM daily_context
        WHERE date >= :from AND date <= :to
        ORDER BY date ASC
        """
    )
    fun getContextsForRange(from: LocalDate, to: LocalDate): Flow<List<DailyContextEntity>>
}
