package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.RespirationReadingDao
import com.athletedata.openAthleteMetrics.data.db.RespirationReadingEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomRespirationReadingRepository @Inject constructor(
    override val dao: RespirationReadingDao,
) : BaseRoomReadingRepository<RespirationReadingEntity, RespirationReadingDao>(), RespirationReadingRepository {
    override val readingLabel = "respiration"
}
