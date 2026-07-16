package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplates
import timber.log.Timber

@Entity(tableName = "widget_layout")
data class WidgetLayoutEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "template_id")
    val templateId: String,
    @ColumnInfo(name = "col_span")
    val colSpan: Int,
    @ColumnInfo(name = "row_span")
    val rowSpan: Int,
    @ColumnInfo(name = "sequence_order")
    val sequenceOrder: Int,
)

/**
 * Returns null (rather than throwing) for a `template_id` that doesn't match any current
 * [WidgetTemplateId] — e.g. after a rollback to a build with fewer templates. The template
 * set is closed and versioned, so there's no first-class "Unknown" model variant; an
 * unrecognized row is just dropped from the rendered layout.
 */
fun WidgetLayoutEntity.toModel(): WidgetDefinition? {
    val template = try {
        WidgetTemplateId.valueOf(templateId)
    } catch (e: IllegalArgumentException) {
        Timber.e(e, "Unrecognized widget template_id '%s' for widget_layout row %d", templateId, id)
        return null
    }
    return WidgetTemplates.definitionFor(
        id = id,
        templateId = template,
        colSpan = colSpan,
        rowSpan = rowSpan,
        sequenceOrder = sequenceOrder,
    )
}

fun WidgetDefinition.toEntity() = WidgetLayoutEntity(
    id = id,
    templateId = templateId.name,
    colSpan = colSpan,
    rowSpan = rowSpan,
    sequenceOrder = sequenceOrder,
)
