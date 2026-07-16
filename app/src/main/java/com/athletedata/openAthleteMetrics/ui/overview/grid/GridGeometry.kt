package com.athletedata.openAthleteMetrics.ui.overview.grid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * Pixel geometry for the skyline grid, shared by [SkylineGridLayout] (measurement/placement)
 * and [DraggableGridState] (drag hit-testing) so the two formulas can never silently drift
 * apart from one another.
 */
data class GridGeometry(
    val columnGapPx: Int,
    val rowGapPx: Int,
    val rowUnitHeightPx: Int,
) {
    fun colWidthPx(containerWidthPx: Int): Int = ((containerWidthPx - columnGapPx) / 2).coerceAtLeast(0)

    fun heightPx(rowSpan: Int): Int = rowSpan * rowUnitHeightPx + (rowSpan - 1).coerceAtLeast(0) * rowGapPx

    fun totalHeightPx(totalRows: Int): Int =
        if (totalRows <= 0) 0 else totalRows * rowUnitHeightPx + (totalRows - 1) * rowGapPx

    fun pixelRect(rect: PackedRect, containerWidthPx: Int): Rect {
        val colWidth = colWidthPx(containerWidthPx)
        val x0 = rect.col * (colWidth + columnGapPx)
        val y0 = rect.row * (rowUnitHeightPx + rowGapPx)
        val width = if (rect.colSpan == 2) containerWidthPx else colWidth
        return Rect(
            offset = Offset(x0.toFloat(), y0.toFloat()),
            size = Size(width.toFloat(), heightPx(rect.rowSpan).toFloat()),
        )
    }
}
