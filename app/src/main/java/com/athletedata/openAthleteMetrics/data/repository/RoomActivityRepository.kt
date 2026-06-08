package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.ActivityDao
import com.athletedata.openAthleteMetrics.data.db.toEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomActivityRepository @Inject constructor(
    private val dao: ActivityDao,
) : ActivityRepository {

    override suspend fun insert(activity: Activity) = dao.insert(activity.toEntity())

    override suspend fun insertAll(activities: List<Activity>) =
        dao.insertAll(activities.map { it.toEntity() })

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
