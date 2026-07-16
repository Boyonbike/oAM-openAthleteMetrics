package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import kotlinx.coroutines.flow.Flow

interface WidgetLayoutRepository {
    fun getLayout(): Flow<List<WidgetDefinition>>
    suspend fun addWidget(templateId: WidgetTemplateId, colSpan: Int, rowSpan: Int): Long
    suspend fun removeWidget(id: Long)
    suspend fun reorderWidgets(orderedIds: List<Long>)
    suspend fun resizeWidget(id: Long, colSpan: Int, rowSpan: Int)
}
