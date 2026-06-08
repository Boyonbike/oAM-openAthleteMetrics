package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface ActivityRepository {

    suspend fun insert(activity: Activity)

    suspend fun insertAll(activities: List<Activity>)

    suspend fun deleteBySource(source: DataSource)

    fun getActivitiesForDate(date: LocalDate): Flow<List<Activity>>

    fun getActivitiesForRange(from: LocalDate, to: LocalDate): Flow<List<Activity>>

    suspend fun getActivitiesForDateOnce(date: LocalDate): List<Activity>
}
