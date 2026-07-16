package com.athletedata.openAthleteMetrics.ui.overview.grid

import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition
import com.athletedata.openAthleteMetrics.data.model.WidgetLayoutHint
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import org.junit.Assert.assertEquals
import org.junit.Test

class SkylinePackerTest {

    private fun widget(id: Long, colSpan: Int, rowSpan: Int, sequenceOrder: Int) = WidgetDefinition(
        id = id,
        templateId = WidgetTemplateId.HR,
        layoutHint = WidgetLayoutHint.SINGLE_METRIC,
        colSpan = colSpan,
        rowSpan = rowSpan,
        sequenceOrder = sequenceOrder,
        bindings = emptyList(),
    )

    @Test
    fun `empty list packs to zero rows`() {
        val result = packSkyline(emptyList())
        assertEquals(emptyList<PackedRect>(), result.rects)
        assertEquals(0, result.totalRows)
    }

    @Test
    fun `two 1x1 widgets fill both columns on the same row`() {
        val result = packSkyline(listOf(widget(1, 1, 1, 0), widget(2, 1, 1, 1)))
        assertEquals(
            listOf(
                PackedRect(1, row = 0, col = 0, colSpan = 1, rowSpan = 1),
                PackedRect(2, row = 0, col = 1, colSpan = 1, rowSpan = 1),
            ),
            result.rects,
        )
        assertEquals(1, result.totalRows)
    }

    @Test
    fun `tie in column height goes to column 0`() {
        // Both columns start at height 0 - a 1x1 widget should land in column 0.
        val result = packSkyline(listOf(widget(1, 1, 1, 0)))
        assertEquals(0, result.rects.single().col)
    }

    @Test
    fun `narrow widget fills the lower column`() {
        // col 0 gets a rowSpan=3 widget, col 1 gets rowSpan=1 -> next 1x1 widget should go to col 1.
        val result = packSkyline(
            listOf(
                widget(1, 1, 3, 0), // col 0, rows 0-2
                widget(2, 1, 1, 1), // col 1, row 0
                widget(3, 1, 1, 2), // should land in col 1 (height 1) not col 0 (height 3)
            ),
        )
        val third = result.rects.single { it.widgetId == 3L }
        assertEquals(1, third.col)
        assertEquals(1, third.row)
    }

    @Test
    fun `wide widget spans both columns at the max of the two column heights and bumps both`() {
        val result = packSkyline(
            listOf(
                widget(1, 1, 3, 0), // col 0 -> height 3
                widget(2, 1, 1, 1), // col 1 -> height 1
                widget(3, 2, 2, 2), // wide widget: row = max(3,1) = 3
            ),
        )
        val wide = result.rects.single { it.widgetId == 3L }
        assertEquals(3, wide.row)
        assertEquals(0, wide.col)
        assertEquals(2, wide.colSpan)
        assertEquals(5, result.totalRows) // row 3 + rowSpan 2
    }

    @Test
    fun `placement is strictly sequence order and never backfills a gap`() {
        // col 0: rowSpan=3 (rows 0-2); col 1: rowSpan=1 (row 0), leaving a gap at col 1 rows 1-2.
        // A later 1x1 widget must NOT backfill that gap - the packer always looks at *current*
        // column heights, so with col1 height=1 < col0 height=3, it lands at col 1 row 1, which
        // happens to look like it filled the gap, but a subsequent widget must land at row 2+
        // strictly by height, not by hole-searching.
        val result = packSkyline(
            listOf(
                widget(1, 1, 3, 0), // col 0, rows 0-2, height -> 3
                widget(2, 1, 1, 1), // col 1, row 0, height -> 1
                widget(3, 1, 1, 2), // col 1 (lower), row 1, height -> 2
                widget(4, 1, 1, 3), // col 1 (still lower, 2 < 3), row 2, height -> 3
                widget(5, 1, 1, 4), // both columns now height 3, tie -> col 0, row 3
            ),
        )
        assertEquals(1, result.rects[3].col) // widget 4 -> col 1, row 2
        assertEquals(2, result.rects[3].row)
        assertEquals(0, result.rects[4].col) // widget 5 -> tie -> col 0
        assertEquals(3, result.rects[4].row)
    }

    @Test
    fun `sequenceOrder determines processing order regardless of list order`() {
        val result = packSkyline(
            listOf(
                widget(2, 1, 1, 1),
                widget(1, 1, 1, 0),
            ),
        )
        // widget 1 has the lower sequenceOrder, so it must be processed first and land in col 0.
        assertEquals(0, result.rects.single { it.widgetId == 1L }.col)
        assertEquals(1, result.rects.single { it.widgetId == 2L }.col)
    }
}
