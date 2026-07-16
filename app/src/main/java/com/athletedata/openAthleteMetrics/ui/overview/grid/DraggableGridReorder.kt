package com.athletedata.openAthleteMetrics.ui.overview.grid

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition

/**
 * Hand-rolled replacement for the `sh.calvin.reorderable` library, which is built around
 * simple linear/span-grid index math that doesn't map onto this fully custom skyline
 * layout. On drop this reduces to the same (from, to) list-index contract the library
 * gave - the packer recomputes placement from the resulting sequence.
 */
class DraggableGridState internal constructor(
    private val widgets: State<List<WidgetDefinition>>,
    private val packResult: State<PackResult>,
    private val containerWidthPx: State<Int>,
    private val geometry: GridGeometry,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDragEnd: () -> Unit,
) {
    var draggedWidgetId by mutableStateOf<Long?>(null)
        private set
    var dragOffset by mutableStateOf(Offset.Zero)
        private set

    val isDragging: Boolean get() = draggedWidgetId != null

    fun onDragStart(widgetId: Long) {
        draggedWidgetId = widgetId
        dragOffset = Offset.Zero
    }

    fun onDrag(dragAmount: Offset) {
        val draggedId = draggedWidgetId ?: return
        dragOffset += dragAmount

        val rects = packResult.value.rects
        val anchorRect = rects.firstOrNull { it.widgetId == draggedId } ?: return
        val currentCenter = geometry.pixelRect(anchorRect, containerWidthPx.value).center + dragOffset

        val targetRect = rects.firstOrNull {
            it.widgetId != draggedId && geometry.pixelRect(it, containerWidthPx.value).contains(currentCenter)
        } ?: return
        val list = widgets.value
        val fromIndex = list.indexOfFirst { it.id == draggedId }
        val toIndex = list.indexOfFirst { it.id == targetRect.widgetId }
        if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
            onMove(fromIndex, toIndex)
            // Re-base the accumulated offset from the target's (pre-repack) position so the
            // dragged item keeps tracking the finger smoothly across the swap, rather than
            // visually jumping once the packer places it at its new slot on next recompose.
            dragOffset = currentCenter - geometry.pixelRect(targetRect, containerWidthPx.value).center
        }
    }

    fun onDragStop() {
        draggedWidgetId = null
        dragOffset = Offset.Zero
        onDragEnd()
    }

    /**
     * Forces drag state to clear without waiting on the gesture's own onDragEnd/onDragCancel -
     * needed because toggling edit mode off mid-gesture removes this item's pointerInput
     * modifier from composition, and that removal isn't guaranteed to invoke the gesture
     * detector's own cancel callback. Without this, [isDragging] could get stuck true forever,
     * permanently blocking the dashboard's resync-from-database effect.
     */
    fun cancelIfDragging() {
        if (isDragging) onDragStop()
    }
}

@Composable
fun rememberDraggableGridState(
    widgets: List<WidgetDefinition>,
    packResult: PackResult,
    containerWidthPx: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onDragEnd: () -> Unit,
    columnGap: Dp = 12.dp,
    rowGap: Dp = 12.dp,
    rowUnitHeight: Dp = 96.dp,
): DraggableGridState {
    val density = LocalDensity.current
    val widgetsState = rememberUpdatedState(widgets)
    val packResultState = rememberUpdatedState(packResult)
    val containerWidthState = rememberUpdatedState(containerWidthPx)
    val onMoveState = rememberUpdatedState(onMove)
    val onDragEndState = rememberUpdatedState(onDragEnd)

    return remember {
        DraggableGridState(
            widgets = widgetsState,
            packResult = packResultState,
            containerWidthPx = containerWidthState,
            geometry = GridGeometry(
                columnGapPx = with(density) { columnGap.roundToPx() },
                rowGapPx = with(density) { rowGap.roundToPx() },
                rowUnitHeightPx = with(density) { rowUnitHeight.roundToPx() },
            ),
            onMove = { from, to -> onMoveState.value(from, to) },
            onDragEnd = { onDragEndState.value() },
        )
    }
}

/** Attaches long-press-drag reordering to a widget item; a no-op [Modifier] when [enabled] is false. */
fun Modifier.dashboardDragHandle(state: DraggableGridState, widgetId: Long, enabled: Boolean): Modifier {
    if (!enabled) return this
    return this
        .graphicsLayer {
            if (state.draggedWidgetId == widgetId) {
                translationX = state.dragOffset.x
                translationY = state.dragOffset.y
            }
        }
        .pointerInput(widgetId) {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.onDragStart(widgetId) },
                onDrag = { change, dragAmount ->
                    change.consume()
                    state.onDrag(dragAmount)
                },
                onDragEnd = { state.onDragStop() },
                onDragCancel = { state.onDragStop() },
            )
        }
}
