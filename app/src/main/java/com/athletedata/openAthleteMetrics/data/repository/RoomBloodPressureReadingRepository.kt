package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingDao
import com.athletedata.openAthleteMetrics.data.db.BloodPressureReadingEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomBloodPressureReadingRepository @Inject constructor(
    override val dao: BloodPressureReadingDao,
) : BaseRoomReadingRepository<BloodPressureReadingEntity, BloodPressureReadingDao>(), BloodPressureReadingRepository {
    override val readingLabel = "blood pressure"
}
