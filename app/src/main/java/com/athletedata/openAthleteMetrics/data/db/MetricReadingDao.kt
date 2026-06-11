package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricType
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import java.time.Instant

@Dao
abstract class MetricReadingDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(entity: MetricReadingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(entities: List<MetricReadingEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAllOrIgnore(entities: List<MetricReadingEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOrIgnore(entity: MetricReadingEntity): Long

    /** Upserts a single accumulator reading; latest daily total always wins. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: MetricReadingEntity)

    /** Batch upserts accumulator readings; latest daily total always wins for each row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entities: List<MetricReadingEntity>)

    enum class AccumulatorWriteOutcome { INSERTED, UPDATED, NO_CHANGE, GUARDED }

    /**
     * Guarded upsert for accumulator metrics.
     * - INSERTED: no prior row — first-time write.
     * - UPDATED: incoming value is strictly higher than stored — real progress.
     * - NO_CHANGE: incoming value equals stored — same data re-synced, no write needed.
     * - GUARDED: incoming value is lower than stored — kept existing, wrote nothing.
     */
    @Transaction
    open suspend fun upsertAccumulator(reading: MetricReadingEntity): AccumulatorWriteOutcome {
        val existing = getByDriverTypeAndDate(reading.driverId, reading.metricType, reading.recordedAt)
        return when {
            existing == null -> {
                insertOrIgnore(reading)
                AccumulatorWriteOutcome.INSERTED
            }
            reading.value > existing.value -> {
                upsert(reading)
                AccumulatorWriteOutcome.UPDATED
            }
            reading.value == existing.value -> {
                AccumulatorWriteOutcome.NO_CHANGE
            }
            else -> {
                Timber.w(
                    "Accumulator value guard: skipping %s incoming=%.2f stored=%.2f — " +
                    "incoming value is lower, keeping stored record",
                    reading.metricType,
                    reading.value,
                    existing.value,
                )
                AccumulatorWriteOutcome.GUARDED
            }
        }
    }

    @Delete
    abstract suspend fun delete(entity: MetricReadingEntity)

    /** Deletes all readings from a specific source; used for seeder cleanup. */
    @Query("DELETE FROM metric_readings WHERE source = :source")
    abstract suspend fun deleteBySource(source: DataSource)

    @Query("DELETE FROM metric_readings")
    abstract suspend fun deleteAll()

    // ── Reads ─────────────────────────────────────────────────────────────────

    /** Exact-key lookup by the accumulator dedup index; returns null if no row exists. */
    @Query(
        """
        SELECT * FROM metric_readings
        WHERE driver_id IS :driverId
          AND metric_type = :metricType
          AND recorded_at = :recordedAt
        LIMIT 1
        """
    )
    abstract suspend fun getByDriverTypeAndDate(
        driverId: String?,
        metricType: MetricType,
        recordedAt: Instant,
    ): MetricReadingEntity?

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
    abstract fun getReadingsInRange(
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
    abstract fun getLatestReading(metricType: MetricType): Flow<MetricReadingEntity?>

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
    abstract suspend fun getReadingsInRangeOnce(
        metricType: MetricType,
        startMs: Long,
        endMs: Long,
    ): List<MetricReadingEntity>

    /** Row count for a given [source] in a time window; used for the seeder banner. */
    @Query(
        "SELECT COUNT(*) FROM metric_readings " +
        "WHERE source = :source AND recorded_at >= :startMs AND recorded_at < :endMs"
    )
    abstract fun countSourceDataInRange(source: DataSource, startMs: Long, endMs: Long): Flow<Int>

    /** One-shot row count; used by the seeder to check for existing data before re-seeding. */
    @Query(
        "SELECT COUNT(*) FROM metric_readings " +
        "WHERE source = :source AND recorded_at >= :startMs AND recorded_at < :endMs"
    )
    abstract suspend fun countSourceDataInRangeOnce(source: DataSource, startMs: Long, endMs: Long): Int
}
