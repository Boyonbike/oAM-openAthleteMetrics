package com.athletedata.app.data.repository

import androidx.work.WorkManager
import com.athletedata.app.data.db.MetricReadingDao
import com.athletedata.app.data.db.toEntity
import com.athletedata.app.data.db.toModel
import com.athletedata.app.data.model.DataSource
import com.athletedata.app.data.model.MetricReading
import com.athletedata.app.data.model.MetricType
import com.athletedata.app.worker.enqueueSummaryWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomMetricRepository @Inject constructor(
    private val dao: MetricReadingDao,
    private val workManager: WorkManager,
) : MetricRepository {

    /**
     * Inserts a single reading as-is. Source is the caller's responsibility.
     * Called by the device driver (source=DEVICE) and the seeder (source=SEEDER).
     * Triggers DailySummaryWorker for the reading's date.
     */
    override suspend fun insert(reading: MetricReading) {
        dao.insert(reading.toEntity())
        enqueueSummaryWorker(reading.recordedAt.toLocalDate(), workManager)
    }

    /**
     * Batch insert — preferred for device sync or seeder writes to avoid N transactions.
     * Triggers DailySummaryWorker for each distinct date present in [readings].
     */
    override suspend fun insertAll(readings: List<MetricReading>) {
        dao.insertAll(readings.map { it.toEntity() })
        readings
            .map { it.recordedAt.toLocalDate() }
            .distinct()
            .forEach { date -> enqueueSummaryWorker(date, workManager) }
    }

    /**
     * Inserts a reading entered by the user, forcing source=MANUAL and createdAt=now.
     * Called by manual-entry screens that don't set these fields themselves.
     * Triggers DailySummaryWorker for the reading's date.
     */
    override suspend fun insertManual(reading: MetricReading) {
        val entity = reading.copy(
            source = DataSource.MANUAL,
            createdAt = Instant.now(),
        ).toEntity()
        dao.insert(entity)
        enqueueSummaryWorker(reading.recordedAt.toLocalDate(), workManager)
    }

    /**
     * Live stream of all readings of [type] recorded on [date] (UTC day boundaries).
     * Observed by metric-detail screen ViewModels.
     */
    override fun getReadingsForDay(date: LocalDate, type: MetricType): Flow<List<MetricReading>> =
        dao.getReadingsInRange(
            metricType = type,
            startMs = date.toUtcStartMs(),
            endMs = date.plusDays(1).toUtcStartMs(),
        ).map { entities -> entities.map { it.toModel() } }

    /**
     * Live stream of all readings of [type] in [[from], [to]] inclusive.
     * Observed by metric-detail charts that support a range selector.
     */
    override fun getReadingsForRange(
        from: LocalDate,
        to: LocalDate,
        type: MetricType,
    ): Flow<List<MetricReading>> =
        dao.getReadingsInRange(
            metricType = type,
            startMs = from.toUtcStartMs(),
            endMs = to.plusDays(1).toUtcStartMs(),
        ).map { entities -> entities.map { it.toModel() } }

    /**
     * Live stream of the single most recent reading of [type].
     * Observed by dashboard metric cards that show the current value.
     */
    override fun getLatestReading(type: MetricType): Flow<MetricReading?> =
        dao.getLatestReading(type).map { it?.toModel() }

    /**
     * Deletes all readings with the given [source].
     * Called by the debug seeder cleanup with source=SEEDER.
     * Does not trigger the worker — seeder cleanup handles summary deletion separately.
     */
    override suspend fun deleteBySource(source: DataSource) {
        dao.deleteBySource(source)
    }
}

/** UTC epoch-ms at the start of this [LocalDate]. */
private fun LocalDate.toUtcStartMs(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Converts an [Instant] to the [LocalDate] it falls on in UTC. */
private fun Instant.toLocalDate(): LocalDate =
    atZone(ZoneOffset.UTC).toLocalDate()
