package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.WidgetLayoutDao
import com.athletedata.openAthleteMetrics.data.db.WidgetLayoutEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomWidgetLayoutRepository @Inject constructor(
    private val dao: WidgetLayoutDao,
) : WidgetLayoutRepository {

    override fun getLayout(): Flow<List<WidgetDefinition>> =
        dao.getAll().map { entities -> entities.mapNotNull { it.toModel() } }

    override suspend fun addWidget(templateId: WidgetTemplateId, colSpan: Int, rowSpan: Int): Long {
        val current = dao.getAll().first()
        val nextOrder = if (current.isEmpty()) 0 else current.maxOf { it.sequenceOrder } + 1
        return dao.insert(
            WidgetLayoutEntity(
                templateId = templateId.name,
                colSpan = colSpan,
                rowSpan = rowSpan,
                sequenceOrder = nextOrder,
            )
        )
    }

    override suspend fun removeWidget(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun reorderWidgets(orderedIds: List<Long>) {
        dao.updateSequenceOrders(orderedIds)
    }

    override suspend fun resizeWidget(id: Long, colSpan: Int, rowSpan: Int) {
        dao.updateSize(id, colSpan, rowSpan)
    }
}
