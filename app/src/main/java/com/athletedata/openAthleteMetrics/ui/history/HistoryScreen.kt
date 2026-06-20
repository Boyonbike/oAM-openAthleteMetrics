package com.athletedata.openAthleteMetrics.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.athletedata.openAthleteMetrics.ui.components.DataPageDatePickerDialog
import com.athletedata.openAthleteMetrics.ui.components.DataPageTopBar
import com.athletedata.openAthleteMetrics.ui.components.horizontalDateSwipe
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import com.athletedata.openAthleteMetrics.ui.theme.SeriesColors
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import com.athletedata.openAthleteMetrics.ui.theme.TypographyValue
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerDimensions
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong
import com.athletedata.openAthleteMetrics.ui.nav.LocalBottomNavScrollBehavior
import com.athletedata.openAthleteMetrics.ui.nav.rememberBottomNavNestedScrollConnection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    initialMetricKey: String? = null,
    initialDateString: String? = null,
    onInitialTargetConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(initialMetricKey) {
        if (initialMetricKey != null) {
            viewModel.setTarget(initialMetricKey, initialDateString)
            onInitialTargetConsumed()
        }
    }

    val localDate    by viewModel.localDate.collectAsStateWithLifecycle()
    val rangeToggle  by viewModel.rangeToggle.collectAsStateWithLifecycle()
    val regularity   by viewModel.regularity.collectAsStateWithLifecycle()
    val metricKeys   by viewModel.metricKeys.collectAsStateWithLifecycle()
    val availableOverlayGroups by viewModel.availableOverlayGroups.collectAsStateWithLifecycle()
    val metricTiles  by viewModel.metricTiles.collectAsStateWithLifecycle()
    val selectedPoint by viewModel.selectedPointDate.collectAsStateWithLifecycle()

    val today = remember { LocalDate.now() }
    var showDatePicker    by remember { mutableStateOf(false) }
    var showOverlaySheet  by remember { mutableStateOf(false) }
    val scrollBehavior = LocalBottomNavScrollBehavior.current
    val nestedScrollConnection = rememberBottomNavNestedScrollConnection(scrollBehavior)

    Scaffold(
        modifier = modifier.horizontalDateSwipe(
            onDayForward = { viewModel.stepDate(true) },
            onDayBack    = { viewModel.stepDate(false) },
        ),
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            DataPageTopBar(
                date = localDate,
                onDateClick = { showDatePicker = true },
                centre = {
                    Text("History", style = MaterialTheme.typography.titleMedium)
                },
                actions = {
                    IconButton(
                        onClick = { showOverlaySheet = true },
                        enabled = metricKeys.size < 5,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "Add metric",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 80.dp),
        ) {
            HistoryChart(
                allSeries         = metricTiles.map { it.allPoints },
                endDate           = today,
                rangeToggle       = rangeToggle,
                regularity        = regularity,
                selectedPointDate = selectedPoint,
                onSnapToDate      = viewModel::moveCursor,
                scrollEnabled     = rangeToggle == RangeToggle.ALL,
                modifier          = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )

            Spacer(Modifier.height(8.dp))

            DurationRegularityRow(
                rangeToggle   = rangeToggle,
                regularity    = regularity,
                onRangeSelect = viewModel::setRange,
                onRegSelect   = viewModel::setRegularity,
            )

            Spacer(Modifier.height(16.dp))

            SelectedValueTilesColumn(
                tiles    = metricTiles,
                onRemove = viewModel::removeMetric,
                onStep   = viewModel::stepSelectedPoint,
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        DataPageDatePickerDialog(
            currentDate = localDate,
            today       = today,
            onConfirm   = { date ->
                viewModel.moveCursor(date)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showOverlaySheet) {
        OverlayBottomSheet(
            groups   = availableOverlayGroups,
            onSelect = { key ->
                viewModel.addMetric(key)
                showOverlaySheet = false
            },
            onDismiss = { showOverlaySheet = false },
        )
    }
}

// ── Chart ─────────────────────────────────────────────────────────────────────

@Composable
private fun HistoryChart(
    allSeries: List<List<AggregatedPoint>>,
    endDate: LocalDate,
    rangeToggle: RangeToggle,
    regularity: Regularity,
    selectedPointDate: LocalDate,
    onSnapToDate: (LocalDate) -> Unit,
    scrollEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val hasData = remember(allSeries) { allSeries.any { it.isNotEmpty() } }

    val fromDate = remember(endDate, rangeToggle, allSeries) {
        if (rangeToggle == RangeToggle.ALL) {
            allSeries.flatten().minOfOrNull { it.periodStart } ?: endDate.minusDays(29)
        } else {
            endDate.minusDays(rangeToggle.days - 1)
        }
    }

    val lastX = remember(endDate, fromDate) {
        ChronoUnit.DAYS.between(fromDate, endDate).toFloat()
    }

    val allPoints = remember(allSeries) {
        allSeries.flatten().sortedBy { it.periodStart }.distinctBy { it.periodStart }
    }

    // Index within allPoints for each period — used by the non-Daily model producer.
    val periodIndexMap = remember(allPoints) {
        allPoints.mapIndexed { i, pt -> pt.periodStart to i }.toMap()
    }

    val labelXs = remember(allPoints) {
        // Evenly spaced by period count for all regularities.
        val positions = allPoints.indices.map { it.toDouble() }
        val maxLabels = 5
        if (positions.size <= maxLabels) positions
        else (0 until maxLabels).map { i -> positions[i * (positions.size - 1) / (maxLabels - 1)] }.distinct()
    }

    val modelProducer = remember { CartesianChartModelProducer() }

    // Key is allSeries only — the data already reflects the active regularity, so we
    // never need to branch on regularity here. This also avoids the state-race where
    // `regularity` updates one frame before `allSeries` (metricTiles), which would
    // otherwise briefly plot daily data points in index space and produce a jagged line.
    LaunchedEffect(allSeries) {
        val populated = allSeries.filter { it.isNotEmpty() }
        if (populated.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries {
                // Index-based x for all regularities — one unit per aggregated period.
                // periodIndexMap aligns multiple overlaid series that may have different
                // date ranges to the same shared x-axis positions.
                populated.forEach { points ->
                    val xs = mutableListOf<Float>()
                    val ys = mutableListOf<Float>()
                    points.forEach { pt ->
                        val idx = periodIndexMap[pt.periodStart] ?: return@forEach
                        xs.add(idx.toFloat())
                        ys.add(pt.value)
                    }
                    if (xs.isNotEmpty()) series(x = xs, y = ys)
                }
            }
        }
    }

    val itemPlacer = remember(labelXs) {
        object : HorizontalAxis.ItemPlacer {
            private val positions = labelXs
            override fun getFirstLabelValue(context: CartesianMeasuringContext, maxLabelWidth: Float) = positions.first()
            override fun getLastLabelValue(context: CartesianMeasuringContext, maxLabelWidth: Float) = positions.last()
            override fun getLabelValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ) = positions
            override fun getWidthMeasurementLabelValues(
                context: CartesianMeasuringContext,
                layerDimensions: CartesianLayerDimensions,
                fullXRange: ClosedFloatingPointRange<Double>,
            ) = positions
            override fun getHeightMeasurementLabelValues(
                context: CartesianMeasuringContext,
                layerDimensions: CartesianLayerDimensions,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ) = positions
            override fun getStartLayerMargin(
                context: CartesianMeasuringContext,
                layerDimensions: CartesianLayerDimensions,
                tickThickness: Float,
                maxLabelWidth: Float,
            ) = maxLabelWidth / 2f
            override fun getEndLayerMargin(
                context: CartesianMeasuringContext,
                layerDimensions: CartesianLayerDimensions,
                tickThickness: Float,
                maxLabelWidth: Float,
            ) = maxLabelWidth / 2f
        }
    }

    val axisFormatter = remember(allPoints) {
        CartesianValueFormatter { _, value, _ ->
            allPoints.getOrNull(value.roundToLong().toInt())?.label ?: ""
        }
    }

    val axisLabelColor = MaterialTheme.colorScheme.onBackground
    val axisLabel = rememberTextComponent(color = axisLabelColor)

    val line0 = LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(SeriesColors[0])))
    val line1 = LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(SeriesColors[1])))
    val line2 = LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(SeriesColors[2])))
    val line3 = LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(SeriesColors[3])))
    val line4 = LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(SeriesColors[4])))
    val lineProvider = remember(line0, line1, line2, line3, line4) {
        LineCartesianLayer.LineProvider.series(listOf(line0, line1, line2, line3, line4))
    }

    val scrollState = rememberVicoScrollState(
        scrollEnabled  = scrollEnabled,
        initialScroll  = if (scrollEnabled) Scroll.Absolute.End else Scroll.Absolute.Start,
    )
    val zoomState = rememberVicoZoomState(
        zoomEnabled  = scrollEnabled,
        initialZoom  = if (scrollEnabled) Zoom.max(Zoom.fixed(), Zoom.Content) else Zoom.Content,
    )

    // Scrubber state — null when not actively dragging
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var chartWidthPx by remember { mutableIntStateOf(1) }

    val cursorFraction: Float? = remember(selectedPointDate, allPoints) {
        val idx = allPoints.indexOfLast { !it.periodStart.isAfter(selectedPointDate) }
        val last = allPoints.size - 1
        if (idx < 0 || last <= 0) null else idx.toFloat() / last
    }

    val scrubberColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)

    if (hasData) {
        Box(modifier = modifier) {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(lineProvider = lineProvider),
                    startAxis  = VerticalAxis.rememberStart(label = axisLabel),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        label          = axisLabel,
                        valueFormatter = axisFormatter,
                        itemPlacer     = itemPlacer,
                    ),
                ),
                modelProducer = modelProducer,
                scrollState   = scrollState,
                zoomState     = zoomState,
                modifier      = Modifier
                    .fillMaxSize()
                    .onSizeChanged { chartWidthPx = it.width },
            )

            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(allPoints, fromDate, lastX, regularity) {
                        detectDragGestures(
                            onDrag = { change: PointerInputChange, _: Offset ->
                                dragFraction = (change.position.x / chartWidthPx)
                                    .coerceIn(0f, 1f)
                                change.consume()
                            },
                            onDragEnd = {
                                dragFraction?.let { frac ->
                                    val nearest = if (regularity == Regularity.DAILY) {
                                        val dataX = frac * lastX
                                        allPoints.minByOrNull { pt ->
                                            abs(ChronoUnit.DAYS.between(fromDate, pt.periodStart) - dataX.toLong())
                                        }
                                    } else {
                                        val targetIdx = (frac * allPoints.lastIndex + 0.5f).toInt()
                                            .coerceIn(0, allPoints.lastIndex)
                                        allPoints.getOrNull(targetIdx)
                                    }
                                    nearest?.let { onSnapToDate(it.periodStart) }
                                }
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                        )
                    },
            ) {
                val frac = dragFraction ?: cursorFraction ?: return@Canvas
                val x = frac * size.width
                drawLine(
                    color       = scrubberColor,
                    start       = Offset(x, 0f),
                    end         = Offset(x, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text  = "No data for this range",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Duration + Regularity dropdowns ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DurationRegularityRow(
    rangeToggle: RangeToggle,
    regularity: Regularity,
    onRangeSelect: (RangeToggle) -> Unit,
    onRegSelect: (Regularity) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var durationExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = durationExpanded,
            onExpandedChange = { durationExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value         = rangeToggle.label,
                onValueChange = {},
                readOnly      = true,
                label         = { Text("Duration", style = TypographyMeta) },
                trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(durationExpanded) },
                textStyle     = TypographyMeta,
                modifier      = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = durationExpanded,
                onDismissRequest = { durationExpanded = false },
            ) {
                RangeToggle.entries.forEach { opt ->
                    DropdownMenuItem(
                        text    = { Text(opt.label, style = TypographyMeta) },
                        onClick = { onRangeSelect(opt); durationExpanded = false },
                    )
                }
            }
        }

        var regExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = regExpanded,
            onExpandedChange = { regExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value         = regularity.label,
                onValueChange = {},
                readOnly      = true,
                label         = { Text("Regularity", style = TypographyMeta) },
                trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(regExpanded) },
                textStyle     = TypographyMeta,
                modifier      = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = regExpanded,
                onDismissRequest = { regExpanded = false },
            ) {
                Regularity.entries.forEach { opt ->
                    DropdownMenuItem(
                        text    = { Text(opt.label, style = TypographyMeta) },
                        onClick = { onRegSelect(opt); regExpanded = false },
                        enabled = opt != Regularity.HOURLY,
                    )
                }
            }
        }
    }
}

// ── Selected value tiles ──────────────────────────────────────────────────────

@Composable
private fun SelectedValueTilesColumn(
    tiles: List<MetricTile>,
    onRemove: (String) -> Unit,
    onStep: (Boolean) -> Unit,
) {
    if (tiles.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { tile ->
            SelectedValueTile(
                tile     = tile,
                onRemove = { onRemove(tile.metricKey) },
                onStep   = onStep,
            )
        }
    }
}

@Composable
private fun SelectedValueTile(
    tile: MetricTile,
    onRemove: () -> Unit,
    onStep: (Boolean) -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val displayValue = formatMetricValue(tile.selectedValue, tile.unit, tile.questionType)

    Card(
        shape     = MaterialTheme.shapes.medium,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier  = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                val swipeThreshold = touchSlop * 3f
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var dx = 0f
                    var dy = 0f
                    var isHorizontal: Boolean? = null

                    // Phase 1: determine direction without consuming events
                    outer@ while (isHorizontal == null) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (change.id != down.id) continue
                            if (!change.pressed) break@outer
                            dx += change.positionChange().x
                            dy += change.positionChange().y
                            if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                                isHorizontal = abs(dx) > abs(dy)
                            }
                        }
                    }

                    // Phase 2: only consume if the gesture is horizontal
                    if (isHorizontal == true) {
                        drag(down.id) { change ->
                            dx += change.positionChange().x
                            change.consume()
                        }
                        when {
                            dx < -swipeThreshold -> onStep(true)
                            dx > swipeThreshold  -> onStep(false)
                        }
                    }
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp)) {
            // Header: colour dot + name + expand chevron + remove button
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(tile.seriesColor),
                )
                Text(
                    text     = tile.displayName,
                    style    = TypographyTitle,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector        = Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier           = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = if (isExpanded) 180f else 0f },
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector        = Icons.Default.Close,
                        contentDescription = "Remove",
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier           = Modifier.size(18.dp),
                    )
                }
            }

            // Large selected value
            Text(
                text     = displayValue,
                style    = TypographyValue,
                modifier = Modifier.padding(top = 4.dp),
            )

            // Expanded data table
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    HorizontalDivider()
                    tile.allPoints.forEachIndexed { idx, point ->
                        val isSelected = idx == tile.selectedIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text  = point.label,
                                style = TypographyMeta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text  = formatMetricValue(point.value, tile.unit, tile.questionType),
                                style = TypographyMeta,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Add overlay bottom sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverlayBottomSheet(
    groups: List<OverlayOptionGroup>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            Text(
                text     = "Add to chart",
                style    = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            HorizontalDivider()
            if (groups.isEmpty()) {
                Text(
                    text     = "No more metrics to add",
                    style    = TypographyMeta,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            groups.forEach { group ->
                Text(
                    text     = group.section.uppercase(),
                    style    = TypographyTitle,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                )
                group.items.forEach { option ->
                    TextButton(
                        onClick        = { onSelect(option.metricKey) },
                        modifier       = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text     = option.displayName,
                            style    = MaterialTheme.typography.bodyLarge,
                            color    = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
