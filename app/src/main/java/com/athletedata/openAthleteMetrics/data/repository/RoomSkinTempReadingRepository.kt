package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.SkinTempReadingDao
import com.athletedata.openAthleteMetrics.data.db.SkinTempReadingEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSkinTempReadingRepository @Inject constructor(
    override val dao: SkinTempReadingDao,
) : BaseRoomReadingRepository<SkinTempReadingEntity, SkinTempReadingDao>(), SkinTempReadingRepository {
    override val readingLabel = "skin temp"
}
