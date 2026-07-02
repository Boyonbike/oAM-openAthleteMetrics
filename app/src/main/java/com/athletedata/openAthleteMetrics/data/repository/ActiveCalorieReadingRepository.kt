package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.ActiveCalorieReadingEntity

interface ActiveCalorieReadingRepository : BaseReadingRepository<ActiveCalorieReadingEntity> {
    // CALORIES-MODE: deletes all DEVICE-sourced readings within a calendar day window.
    suspend fun deleteDeviceReadingsForDay(startMs: Long, endMs: Long) // CALORIES-MODE
}
