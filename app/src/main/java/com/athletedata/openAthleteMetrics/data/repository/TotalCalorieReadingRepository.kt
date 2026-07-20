package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.TotalCalorieReadingEntity

interface TotalCalorieReadingRepository : BaseReadingRepository<TotalCalorieReadingEntity> {
    // CALORIES-MODE: deletes DEVICE-sourced readings from the given driver within a calendar day window.
    suspend fun deleteDeviceReadingsForDay(driverId: String?, startMs: Long, endMs: Long) // CALORIES-MODE
}
