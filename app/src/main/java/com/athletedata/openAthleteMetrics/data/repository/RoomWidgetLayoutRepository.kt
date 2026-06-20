package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.WidgetLayoutDao
import com.athletedata.openAthleteMetrics.data.db.WidgetLayoutEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.WidgetLayout
import com.athletedata.openAthleteMetrics.data.model.WidgetSize
import com.athletedata.openAthleteMetrics.data.model.WidgetType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomWidgetLayoutRepository @Inject constructor(
    private val dao: WidgetLayoutDao,
) : WidgetLayoutRepository {

    override fun getLayout(): Flow<List<WidgetLayout>> =
        dao.getAll().map { entities -> entities.map { it.toModel() } }

    override suspend fun addWidget(type: WidgetType, size: WidgetSize): Long {
        val current = dao.getAll().first()
        val nextOrder = if (current.isEmpty()) 0 else current.maxOf { it.sortOrder } + 1
        return dao.insert(
            WidgetLayoutEntity(
                widgetType = WidgetType.discriminator(type),
                size = if (size == WidgetSize.WIDE) "WIDE" else "SMALL",
                sortOrder = nextOrder,
                extraId = WidgetType.extraId(type),
            )
        )
    }

    override suspend fun removeWidget(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun reorderWidgets(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            dao.updateSortOrder(id, index)
        }
    }
}
