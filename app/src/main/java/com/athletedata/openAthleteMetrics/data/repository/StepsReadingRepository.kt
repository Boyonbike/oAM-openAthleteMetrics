package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.StepsReadingEntity

interface StepsReadingRepository : BaseReadingRepository<StepsReadingEntity> {
    // STEPS-MODE: deletes DEVICE-sourced readings from the given driver within a calendar day window.
    suspend fun deleteDeviceReadingsForDay(driverId: String?, startMs: Long, endMs: Long) // STEPS-MODE
}
