package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingDao
import com.athletedata.openAthleteMetrics.data.db.GlucoseReadingEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomGlucoseReadingRepository @Inject constructor(
    override val dao: GlucoseReadingDao,
) : BaseRoomReadingRepository<GlucoseReadingEntity, GlucoseReadingDao>(), GlucoseReadingRepository {
    override val readingLabel = "glucose"
}
