package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.WidgetLayout
import com.athletedata.openAthleteMetrics.data.model.WidgetSize
import com.athletedata.openAthleteMetrics.data.model.WidgetType

@Entity(tableName = "widget_layout")
data class WidgetLayoutEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "widget_type")
    val widgetType: String,
    val size: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    @ColumnInfo(name = "extra_id")
    val extraId: Long? = null,
)

fun WidgetLayoutEntity.toModel() = WidgetLayout(
    id = id,
    type = WidgetType.fromDiscriminator(widgetType, extraId),
    size = if (size == "WIDE") WidgetSize.WIDE else WidgetSize.SMALL,
    sortOrder = sortOrder,
)

fun WidgetLayout.toEntity() = WidgetLayoutEntity(
    id = id,
    widgetType = WidgetType.discriminator(type),
    size = if (size == WidgetSize.WIDE) "WIDE" else "SMALL",
    sortOrder = sortOrder,
    extraId = WidgetType.extraId(type),
)
