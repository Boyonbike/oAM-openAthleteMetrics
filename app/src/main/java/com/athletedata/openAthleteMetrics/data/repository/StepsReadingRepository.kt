package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.StepsReadingEntity

interface StepsReadingRepository : BaseReadingRepository<StepsReadingEntity> {
    // STEPS-MODE: deletes all DEVICE-sourced readings within a calendar day window.
    suspend fun deleteDeviceReadingsForDay(startMs: Long, endMs: Long) // STEPS-MODE
}
