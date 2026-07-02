package com.athletedata.openAthleteMetrics.data.repository

import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.data.db.MetricReadingStagingDao
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.db.toStagingEntity
import com.athletedata.openAthleteMetrics.data.db.toUtcStartMs
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.worker.enqueueSummaryWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomMetricReadingStagingRepository @Inject constructor(
    private val dao: MetricReadingStagingDao,
    private val workManager: WorkManager,
) : MetricReadingStagingRepository {

    override suspend fun insert(reading: MetricReading) {
        try {
            dao.insert(reading.toStagingEntity())
            val date = reading.recordedAt.toLocalDate()
            Timber.tag("data-pathway-tracker").d("[STAGE-6 DB-WRITE] enqueueing DailySummaryWorker for date=%s (local tz=%s)", date, ZoneId.systemDefault()) // TZ-FIX
            enqueueSummaryWorker(date, workManager)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert metric reading")
            throw e
        }
    }

    override suspend fun insertAll(readings: List<MetricReading>) {
        try {
            dao.insertAll(readings.map { it.toStagingEntity() })
            readings
                .map { it.recordedAt.toLocalDate() }
                .distinct()
                .forEach { date ->
                    Timber.tag("data-pathway-tracker").d("[STAGE-6 DB-WRITE] enqueueing DailySummaryWorker for date=%s (local tz=%s)", date, ZoneId.systemDefault()) // TZ-FIX
                    enqueueSummaryWorker(date, workManager)
                }
        } catch (e: Exception) {
            Timber.e(e, "Failed to batch-insert metric readings")
            throw e
        }
    }

    // REMOVED: post-audit-cleanup — insertAllFromDevice and replaceAllFromDevice were called by
    // DeviceSyncProcessor before MetricRouter took full ownership of per-notification writes
    // (Prompt B). No callers remain.

    override suspend fun insertManual(reading: MetricReading) {
        try {
            val entity = reading.copy(
                source = DataSource.MANUAL,
                createdAt = Instant.now(),
            ).toStagingEntity()
            dao.insert(entity)
            val date = reading.recordedAt.toLocalDate()
            Timber.tag("data-pathway-tracker").d("[STAGE-6 DB-WRITE] enqueueing DailySummaryWorker for date=%s (local tz=%s)", date, ZoneId.systemDefault()) // TZ-FIX
            enqueueSummaryWorker(date, workManager)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert manual metric reading")
            throw e
        }
    }

    override fun getReadingsForDay(date: LocalDate, type: MetricType): Flow<List<MetricReading>> =
        dao.getReadingsInRange(
            metricType = type,
            startMs = date.toUtcStartMs(),
            endMs = date.plusDays(1).toUtcStartMs(),
        ).map { entities -> entities.map { it.toModel() } }

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

    override fun getLatestReading(type: MetricType): Flow<MetricReading?> =
        dao.getLatestReading(type).map { it?.toModel() }

    override suspend fun deleteBySource(source: DataSource) {
        try {
            dao.deleteBySource(source)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete metric readings by source")
            throw e
        }
    }

    override fun hasSeederDataForDate(date: LocalDate): Flow<Boolean> =
        dao.countSourceDataInRange(
            source = DataSource.SEEDER,
            startMs = date.toUtcStartMs(),
            endMs = date.plusDays(1).toUtcStartMs(),
        ).map { it > 0 }

    override suspend fun hasSeederReadingsForDateOnce(date: LocalDate): Boolean =
        dao.countSourceDataInRangeOnce(
            source = DataSource.SEEDER,
            startMs = date.toUtcStartMs(),
            endMs = date.plusDays(1).toUtcStartMs(),
        ) > 0

    override suspend fun getPendingSleepStages(
        source: DataSource,
        driverId: String,
        syncWindowStartMs: Long,
        syncWindowEndMs: Long,
    ): List<MetricReading> =
        dao.getPendingSleepStages(source, driverId, syncWindowStartMs, syncWindowEndMs).map { it.toModel() }

    override suspend fun deleteByIds(ids: List<Long>) = dao.deleteByIds(ids)
}

private fun Instant.toLocalDate(): LocalDate =
    atZone(ZoneId.systemDefault()).toLocalDate() // TZ-FIX
