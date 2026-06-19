package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.HrReadingEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface HrReadingRepository : BaseReadingRepository<HrReadingEntity> {
    fun hasSeederDataForDate(date: LocalDate): Flow<Boolean>
}
