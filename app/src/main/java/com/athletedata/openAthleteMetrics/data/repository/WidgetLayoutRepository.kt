package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.WidgetLayout
import com.athletedata.openAthleteMetrics.data.model.WidgetSize
import com.athletedata.openAthleteMetrics.data.model.WidgetType
import kotlinx.coroutines.flow.Flow

interface WidgetLayoutRepository {
    fun getLayout(): Flow<List<WidgetLayout>>
    suspend fun addWidget(type: WidgetType, size: WidgetSize): Long
    suspend fun removeWidget(id: Long)
    suspend fun reorderWidgets(orderedIds: List<Long>)
}
