package com.athletedata.openAthleteMetrics.data.repository

import androidx.work.WorkManager
import com.athletedata.openAthleteMetrics.data.db.ActivityDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import com.athletedata.openAthleteMetrics.worker.enqueueSummaryWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomActivityRepository @Inject constructor(
    private val dao: ActivityDao,
    private val workManager: WorkManager,
) : ActivityRepository {

    override suspend fun insert(activity: Activity) {
        dao.insert(activity.toEntity())
        enqueueSummaryWorker(activity.startTime.atZone(ZoneOffset.UTC).toLocalDate(), workManager)
    }

    override suspend fun insertAll(activities: List<Activity>) {
        dao.insertAll(activities.map { it.toEntity() })
        activities
            .map { it.startTime.atZone(ZoneOffset.UTC).toLocalDate() }
            .distinct()
            .forEach { date -> enqueueSummaryWorker(date, workManager) }
    }

    override suspend fun insertAllFromDevice(activities: List<Activity>): Int {
        val rowIds = dao.insertAllOrIgnore(activities.map { it.copy(source = DataSource.DEVICE).toEntity() })
        return rowIds.count { it == -1L }
    }

    override suspend fun replaceAllFromDevice(activities: List<Activity>): Int {
        val entities = activities.map { it.copy(source = DataSource.DEVICE).toEntity() }
        dao.insertAll(entities)   // insertAll uses OnConflictStrategy.REPLACE
        return entities.size
    }

    override suspend fun deleteBySource(source: DataSource) = dao.deleteBySource(source)

    override suspend fun updateCategory(id: Long, category: UserCategory) =
        dao.updateCategory(id, category)

    override suspend fun updateCategoryAndNotes(id: Long, category: UserCategory?, notes: String?) =
        dao.updateCategoryAndNotes(id, category, notes)

    override fun getActivitiesForDate(date: LocalDate): Flow<List<Activity>> =
        dao.getActivitiesInRange(
            startMs = date.toUtcStartMs(),
            endMs = date.plusDays(1).toUtcStartMs(),
        ).map { entities -> entities.map { it.toModel() } }

    override fun getActivitiesForRange(from: LocalDate, to: LocalDate): Flow<List<Activity>> =
        dao.getActivitiesInRange(
            startMs = from.toUtcStartMs(),
            endMs = to.plusDays(1).toUtcStartMs(),
        ).map { entities -> entities.map { it.toModel() } }

    override suspend fun getActivitiesForDateOnce(date: LocalDate): List<Activity> =
        dao.getActivitiesInRangeOnce(
            startMs = date.toUtcStartMs(),
            endMs = date.plusDays(1).toUtcStartMs(),
        ).map { it.toModel() }
}

private fun LocalDate.toUtcStartMs(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
