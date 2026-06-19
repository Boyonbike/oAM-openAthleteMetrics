package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomActiveCalorieReadingRepository @Inject constructor(
    override val dao: ActiveCalorieReadingDao,
) : BaseRoomReadingRepository<ActiveCalorieReadingEntity, ActiveCalorieReadingDao>(), ActiveCalorieReadingRepository {
    override val readingLabel = "active calorie"
}
