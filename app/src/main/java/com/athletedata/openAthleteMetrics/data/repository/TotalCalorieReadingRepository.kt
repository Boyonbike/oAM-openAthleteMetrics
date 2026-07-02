package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingEntity

interface TotalCalorieReadingRepository : BaseReadingRepository<TotalCalorieReadingEntity> {
    // CALORIES-MODE: deletes all DEVICE-sourced readings within a calendar day window.
    suspend fun deleteDeviceReadingsForDay(startMs: Long, endMs: Long) // CALORIES-MODE
}
