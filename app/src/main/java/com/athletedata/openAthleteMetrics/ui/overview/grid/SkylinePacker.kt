package com.athletedata.openAthleteMetrics.ui.overview.grid

import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition

/** A widget's placement rectangle in grid units (not pixels). */
data class PackedRect(
    val widgetId: Long,
    val row: Int,
    val col: Int,
    val colSpan: Int,
    val rowSpan: Int,
)

data class PackResult(
    val rects: List<PackedRect>,
    val totalRows: Int,
)

/**
 * Skyline bin-packer for the fixed-2-column dashboard grid. Processes widgets strictly
 * in [WidgetDefinition.sequenceOrder] — never backfills a gap out of order, even if a
 * later, smaller widget would fit into a hole first. Deterministic.
 *
 * colSpan == 2: placed at row = max(columnHeight[0], columnHeight[1]), spanning both
 * columns; both column heights become row + rowSpan.
 * colSpan == 1: placed in whichever column currently has the lower height (tie -> column 0);
 * that column's height becomes row + rowSpan.
 */
fun packSkyline(widgets: List<WidgetDefinition>): PackResult {
    val columnHeight = IntArray(2)
    val rects = mutableListOf<PackedRect>()

    for (widget in widgets.sortedBy { it.sequenceOrder }) {
        if (widget.colSpan == 2) {
            val row = maxOf(columnHeight[0], columnHeight[1])
            rects += PackedRect(widget.id, row, 0, 2, widget.rowSpan)
            columnHeight[0] = row + widget.rowSpan
            columnHeight[1] = row + widget.rowSpan
        } else {
            val col = if (columnHeight[0] <= columnHeight[1]) 0 else 1
            val row = columnHeight[col]
            rects += PackedRect(widget.id, row, col, 1, widget.rowSpan)
            columnHeight[col] = row + widget.rowSpan
        }
    }

    return PackResult(rects, maxOf(columnHeight[0], columnHeight[1]))
}
