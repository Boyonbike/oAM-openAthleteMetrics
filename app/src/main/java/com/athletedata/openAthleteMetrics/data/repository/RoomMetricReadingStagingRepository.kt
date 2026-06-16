package com.athletedata.openAthleteMetrics.data.repository

import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.data.db.MetricReadingStagingDao
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.db.toStagingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricReading
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.worker.enqueueSummaryWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
            enqueueSummaryWorker(reading.recordedAt.toLocalDate(), workManager)
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
                .forEach { date -> enqueueSummaryWorker(date, workManager) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to batch-insert metric readings")
            throw e
        }
    }

    override suspend fun insertAllFromDevice(readings: List<MetricReading>): DeviceInsertResult {
        try {
            val entities = readings.map { it.copy(source = DataSource.DEVICE).toStagingEntity() }
            val (accumulators, pointInTime) = entities.partition {
                MetricType.ACCUMULATOR_METRICS.contains(it.metricType)
            }
            var accumulatorInserted = 0
            var accumulatorUpdates = 0
            var accumulatorNoChange = 0
            var accumulatorGuarded = 0
            accumulators.forEach { entity ->
                when (dao.upsertAccumulator(entity)) {
                    MetricReadingStagingDao.AccumulatorWriteOutcome.INSERTED  -> accumulatorInserted++
                    MetricReadingStagingDao.AccumulatorWriteOutcome.UPDATED   -> accumulatorUpdates++
                    MetricReadingStagingDao.AccumulatorWriteOutcome.NO_CHANGE -> accumulatorNoChange++
                    MetricReadingStagingDao.AccumulatorWriteOutcome.GUARDED   -> accumulatorGuarded++
                }
            }
            val rowIds = dao.insertAllOrIgnore(pointInTime)
            val pointInTimeNew = rowIds.count { it != -1L }
            val readingsSkipped = rowIds.count { it == -1L }
            return DeviceInsertResult(
                newRecordsInserted = pointInTimeNew + accumulatorInserted,
                accumulatorUpdates = accumulatorUpdates,
                accumulatorNoChange = accumulatorNoChange,
                accumulatorGuarded = accumulatorGuarded,
                readingsSkipped = readingsSkipped,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to batch-insert device metric readings")
            throw e
        }
    }

    override suspend fun replaceAllFromDevice(readings: List<MetricReading>): Int {
        return try {
            val entities = readings.map { it.copy(source = DataSource.DEVICE).toStagingEntity() }
            dao.upsertAll(entities)
            entities.size
        } catch (e: Exception) {
            Timber.e(e, "Failed to force-replace device metric readings")
            throw e
        }
    }

    override suspend fun insertManual(reading: MetricReading) {
        try {
            val entity = reading.copy(
                source = DataSource.MANUAL,
                createdAt = Instant.now(),
            ).toStagingEntity()
            dao.insert(entity)
            enqueueSummaryWorker(reading.recordedAt.toLocalDate(), workManager)
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
        startMs: Long,
        endMs: Long,
    ): List<MetricReading> =
        dao.getPendingSleepStages(source, driverId, startMs, endMs).map { it.toModel() }

    override suspend fun deleteByIds(ids: List<Long>) = dao.deleteByIds(ids)
}

private fun LocalDate.toUtcStartMs(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Instant.toLocalDate(): LocalDate =
    atZone(ZoneOffset.UTC).toLocalDate()
