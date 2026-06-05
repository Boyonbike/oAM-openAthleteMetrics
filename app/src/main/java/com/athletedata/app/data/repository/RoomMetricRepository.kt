package com.athletedata.app.data.repository

import com.athletedata.app.data.db.MetricReadingDao
import com.athletedata.app.data.db.toEntity
import com.athletedata.app.data.db.toModel
import com.athletedata.app.data.model.DataSource
import com.athletedata.app.data.model.MetricReading
import com.athletedata.app.data.model.MetricType
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
) : MetricRepository {

    /**
     * Inserts a single reading as-is. Source is the caller's responsibility.
     * Called by the device driver (source=DEVICE) and the seeder (source=SEEDER).
     * TODO: trigger DailySummaryWorker for reading.recordedAt.date after the worker is created.
     */
    override suspend fun insert(reading: MetricReading) {
        dao.insert(reading.toEntity())
    }

    /**
     * Batch insert — preferred for device sync or seeder writes to avoid N transactions.
     * TODO: trigger DailySummaryWorker for each distinct date in [readings] after the worker is created.
     */
    override suspend fun insertAll(readings: List<MetricReading>) {
        dao.insertAll(readings.map { it.toEntity() })
    }

    /**
     * Inserts a reading entered by the user, forcing source=MANUAL and createdAt=now.
     * Called by manual-entry screens that don't set these fields themselves.
     */
    override suspend fun insertManual(reading: MetricReading) {
        dao.insert(
            reading.copy(
                source = DataSource.MANUAL,
                createdAt = Instant.now(),
            ).toEntity()
        )
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
     */
    override suspend fun deleteBySource(source: DataSource) {
        dao.deleteBySource(source)
    }
}

/** Converts a [LocalDate] to the UTC epoch-ms value at the start of that day. */
private fun LocalDate.toUtcStartMs(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
