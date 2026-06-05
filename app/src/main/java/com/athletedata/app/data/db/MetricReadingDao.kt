package com.athletedata.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.app.data.model.DataSource
import com.athletedata.app.data.model.MetricType
import kotlinx.coroutines.flow.Flow

@Dao
interface MetricReadingDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MetricReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MetricReadingEntity>)

    @Delete
    suspend fun delete(entity: MetricReadingEntity)

    /** Deletes all readings from a specific source; used for seeder cleanup. */
    @Query("DELETE FROM metric_readings WHERE source = :source")
    suspend fun deleteBySource(source: DataSource)

    // ── Reads ─────────────────────────────────────────────────────────────────

    /**
     * Returns readings of [metricType] in the half-open interval
     * [[startMs], [endMs]).
     *
     * Used for both single-day and multi-day range queries. The repository
     * converts [LocalDate] boundaries to UTC epoch-ms values before calling
     * this method.
     */
    @Query(
        """
        SELECT * FROM metric_readings
        WHERE metric_type = :metricType
          AND recorded_at >= :startMs
          AND recorded_at < :endMs
        ORDER BY recorded_at ASC
        """
    )
    fun getReadingsInRange(
        metricType: MetricType,
        startMs: Long,
        endMs: Long,
    ): Flow<List<MetricReadingEntity>>

    /** Most recent reading of [metricType], or null if none exists. */
    @Query(
        """
        SELECT * FROM metric_readings
        WHERE metric_type = :metricType
        ORDER BY recorded_at DESC
        LIMIT 1
        """
    )
    fun getLatestReading(metricType: MetricType): Flow<MetricReadingEntity?>

    /**
     * One-shot (non-Flow) read of all readings for a date range; used by
     * DailySummaryWorker which runs on a background thread and does not need
     * continuous observation.
     */
    @Query(
        """
        SELECT * FROM metric_readings
        WHERE metric_type = :metricType
          AND recorded_at >= :startMs
          AND recorded_at < :endMs
        ORDER BY recorded_at ASC
        """
    )
    suspend fun getReadingsInRangeOnce(
        metricType: MetricType,
        startMs: Long,
        endMs: Long,
    ): List<MetricReadingEntity>
}
