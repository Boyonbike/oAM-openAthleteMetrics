package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SpO2ReadingDao
import com.athletedata.openAthleteMetrics.data.db.SpO2ReadingEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSpO2ReadingRepository @Inject constructor(
    override val dao: SpO2ReadingDao,
) : BaseRoomReadingRepository<SpO2ReadingEntity, SpO2ReadingDao>(), SpO2ReadingRepository {
    override val readingLabel = "SpO2"
}
