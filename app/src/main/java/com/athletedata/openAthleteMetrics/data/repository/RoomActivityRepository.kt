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
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

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
        val window = 30_000L
        var skipped = 0
        for (activity in activities) {
            val validated = fixDurationIfNeeded(activity)
            val entity = validated.copy(source = DataSource.DEVICE).toEntity()
            val driverId = entity.driverId
            val existing = if (driverId != null) {
                dao.findNear(
                    driverId = driverId,
                    windowStart = entity.startTime.toEpochMilli() - window,
                    windowEnd = entity.startTime.toEpochMilli() + window,
                )
            } else null

            if (existing != null) {
                val delta = entity.startTime.toEpochMilli() - existing.startTime.toEpochMilli()
                Timber.d(
                    "Activity drift-match: incoming startMs=%d matched existing startMs=%d delta=%dms",
                    entity.startTime.toEpochMilli(), existing.startTime.toEpochMilli(), delta,
                )
                if (entity.durationMinutes > existing.durationMinutes) {
                    dao.insert(entity.copy(id = existing.id))
                } else {
                    skipped++
                }
            } else {
                val rowId = dao.insertOrIgnore(entity)
                if (rowId == -1L) skipped++
            }
        }
        return skipped
    }

    private fun fixDurationIfNeeded(activity: Activity): Activity {
        val derivedMinutes = ((activity.endTime.toEpochMilli() - activity.startTime.toEpochMilli()) / 60_000L).toInt()
        return if (abs(derivedMinutes - activity.durationMinutes) > 2) {
            Timber.w(
                "Activity duration mismatch: stored=%d derived=%d — using derived value",
                activity.durationMinutes, derivedMinutes,
            )
            activity.copy(durationMinutes = derivedMinutes)
        } else {
            activity
        }
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
