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
import timber.log.Timber
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

    override suspend fun insert(reading: MetricReading) {
        try {
            dao.insert(reading.toEntity())
            enqueueSummaryWorker(reading.recordedAt.toLocalDate(), workManager)
        } catch (e: Exception) {
            Timber.e(e, "Failed to insert metric reading")
            throw e
        }
    }

    override suspend fun insertAll(readings: List<MetricReading>) {
        try {
            dao.insertAll(readings.map { it.toEntity() })
            readings
                .map { it.recordedAt.toLocalDate() }
                .distinct()
                .forEach { date -> enqueueSummaryWorker(date, workManager) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to batch-insert metric readings")
            throw e
        }
    }

    override suspend fun insertManual(reading: MetricReading) {
        try {
            val entity = reading.copy(
                source = DataSource.MANUAL,
                createdAt = Instant.now(),
            ).toEntity()
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
}

private fun LocalDate.toUtcStartMs(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Instant.toLocalDate(): LocalDate =
    atZone(ZoneOffset.UTC).toLocalDate()
