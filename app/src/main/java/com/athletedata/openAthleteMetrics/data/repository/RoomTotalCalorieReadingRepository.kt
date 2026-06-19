package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingDao
import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomTotalCalorieReadingRepository @Inject constructor(
    override val dao: TotalCalorieReadingDao,
) : BaseRoomReadingRepository<TotalCalorieReadingEntity, TotalCalorieReadingDao>(), TotalCalorieReadingRepository {
    override val readingLabel = "total calorie"
}
