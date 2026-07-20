package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomActiveCalorieReadingRepository @Inject constructor(
    override val dao: ActiveCalorieReadingDao,
) : BaseRoomReadingRepository<ActiveCalorieReadingEntity, ActiveCalorieReadingDao>(), ActiveCalorieReadingRepository {
    override val readingLabel = "active calorie"

    override suspend fun deleteDeviceReadingsForDay(driverId: String?, startMs: Long, endMs: Long) = // CALORIES-MODE
        dao.deleteBySourceForDay(DataSource.DEVICE, driverId, startMs, endMs) // CALORIES-MODE
}
