package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface ActivityRepository {

    suspend fun insert(activity: Activity)

    suspend fun insertAll(activities: List<Activity>)

    /** Batch insert from BLE device using insert-or-ignore. Returns count of rows already existing (skipped). */
    suspend fun insertAllFromDevice(activities: List<Activity>): Int

    suspend fun deleteBySource(source: DataSource)

    suspend fun updateCategory(id: Long, category: UserCategory)

    suspend fun updateCategoryAndNotes(id: Long, category: UserCategory?, notes: String?)

    fun getActivitiesForDate(date: LocalDate): Flow<List<Activity>>

    fun getActivitiesForRange(from: LocalDate, to: LocalDate): Flow<List<Activity>>

    suspend fun getActivitiesForDateOnce(date: LocalDate): List<Activity>
}
