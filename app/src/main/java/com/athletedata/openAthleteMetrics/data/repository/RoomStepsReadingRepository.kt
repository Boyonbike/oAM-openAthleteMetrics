package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.StepsReadingDao
import com.athletedata.openAthleteMetrics.data.db.StepsReadingEntity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomStepsReadingRepository @Inject constructor(
    override val dao: StepsReadingDao,
) : BaseRoomReadingRepository<StepsReadingEntity, StepsReadingDao>(), StepsReadingRepository {
    override val readingLabel = "steps"

    override suspend fun deleteDeviceReadingsForDay(driverId: String?, startMs: Long, endMs: Long) = // STEPS-MODE
        dao.deleteBySourceForDay(DataSource.DEVICE, driverId, startMs, endMs) // STEPS-MODE
}
