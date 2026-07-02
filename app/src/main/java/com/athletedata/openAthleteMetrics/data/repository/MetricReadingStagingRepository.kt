package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Result of a guarded device batch insert. */
data class DeviceInsertResult(
    val newRecordsInserted: Int,
    val accumulatorUpdates: Int,
    val accumulatorNoChange: Int,
    val accumulatorGuarded: Int,
    val readingsSkipped: Int,
)

/**
 * Contract for staging metric reading operations.
 *
 * The BLE device driver and the seeder both call [insert] / [insertAll].
 * ViewModels call the query methods. Manual entry screens call [insertManual].
 */
interface MetricReadingStagingRepository {

    /** Inserts a single reading. Source field must be set by the caller. */
    suspend fun insert(reading: MetricReading)

    /** Batch insert — preferred for device sync or seeder writes. */
    suspend fun insertAll(readings: List<MetricReading>)

    // REMOVED: post-audit-cleanup — insertAllFromDevice and replaceAllFromDevice — see
    // RoomMetricReadingStagingRepository for details.

    /** Inserts a reading and forces source = MANUAL and createdAt = now. */
    suspend fun insertManual(reading: MetricReading)

    /** Live stream of all readings of [type] recorded on [date] (UTC boundaries). */
    fun getReadingsForDay(date: LocalDate, type: MetricType): Flow<List<MetricReading>>

    /** Live stream of all readings of [type] in the inclusive date range [[from], [to]]. */
    fun getReadingsForRange(from: LocalDate, to: LocalDate, type: MetricType): Flow<List<MetricReading>>

    /** Live stream of the single most recent reading of [type], or null. */
    fun getLatestReading(type: MetricType): Flow<MetricReading?>

    /** Deletes all readings whose source matches [source]. Used by the seeder cleanup flow. */
    suspend fun deleteBySource(source: DataSource)

    /** Live boolean — true if any [DataSource.SEEDER] readings exist for [date]. */
    fun hasSeederDataForDate(date: LocalDate): Flow<Boolean>

    /** One-shot version; used by the seeder to guard against re-seeding a date. */
    suspend fun hasSeederReadingsForDateOnce(date: LocalDate): Boolean

    /** Returns all SLEEP_STAGE rows for a given driver/source inserted during a sync window. */
    suspend fun getPendingSleepStages(
        source: DataSource,
        driverId: String,
        syncWindowStartMs: Long,
        syncWindowEndMs: Long,
    ): List<MetricReading>

    /** Deletes specific staging rows by id; used by SleepStagePromoter after promotion. */
    suspend fun deleteByIds(ids: List<Long>)
}
