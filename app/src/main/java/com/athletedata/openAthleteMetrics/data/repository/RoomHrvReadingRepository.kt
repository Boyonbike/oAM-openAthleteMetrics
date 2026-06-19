package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.HrvReadingDao
import com.athletedata.openAthleteMetrics.data.db.HrvReadingEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHrvReadingRepository @Inject constructor(
    override val dao: HrvReadingDao,
) : BaseRoomReadingRepository<HrvReadingEntity, HrvReadingDao>(), HrvReadingRepository {
    override val readingLabel = "HRV"
}
