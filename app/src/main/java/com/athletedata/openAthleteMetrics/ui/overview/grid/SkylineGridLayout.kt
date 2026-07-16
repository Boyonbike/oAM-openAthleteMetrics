package com.athletedata.openAthleteMetrics.ui.overview.grid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition

/**
 * Custom 2D layout for the dashboard grid, consuming [packResult] (computed by the caller
 * via `remember(widgets) { packSkyline(widgets) }` so it can be reused for drag hit-testing
 * without recomputing). Every child's pixel rect is fully determined up front from its
 * [PackedRect] plus a fixed row-unit height (via [GridGeometry], shared with
 * [DraggableGridState]'s hit-testing), so a plain single-pass [Layout] suffices - no
 * SubcomposeLayout/content-driven sizing is needed.
 */
@Composable
fun SkylineGridLayout(
    widgets: List<WidgetDefinition>,
    packResult: PackResult,
    modifier: Modifier = Modifier,
    columnGap: Dp = 12.dp,
    rowGap: Dp = 12.dp,
    rowUnitHeight: Dp = 96.dp,
    content: @Composable (WidgetDefinition) -> Unit,
) {
    val density = LocalDensity.current
    val geometry = GridGeometry(
        columnGapPx = with(density) { columnGap.roundToPx() },
        rowGapPx = with(density) { rowGap.roundToPx() },
        rowUnitHeightPx = with(density) { rowUnitHeight.roundToPx() },
    )
    val rectsById = packResult.rects.associateBy { it.widgetId }

    Layout(
        content = { widgets.forEach { widget -> key(widget.id) { content(widget) } } },
        modifier = modifier,
    ) { measurables, constraints ->
        val colWidthPx = geometry.colWidthPx(constraints.maxWidth)

        val placeables = measurables.mapIndexed { index, measurable ->
            val rect = rectsById.getValue(widgets[index].id)
            val widthPx = if (rect.colSpan == 2) constraints.maxWidth else colWidthPx
            val heightPx = geometry.heightPx(rect.rowSpan)
            rect to measurable.measure(Constraints.fixed(widthPx, heightPx))
        }

        layout(constraints.maxWidth, geometry.totalHeightPx(packResult.totalRows)) {
            placeables.forEach { (rect, placeable) ->
                val pixelRect = geometry.pixelRect(rect, constraints.maxWidth)
                placeable.placeRelative(pixelRect.left.toInt(), pixelRect.top.toInt())
            }
        }
    }
}
