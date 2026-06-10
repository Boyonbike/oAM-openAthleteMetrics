package com.athletedata.openAthleteMetrics.ui.history

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerDimensions
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.athletedata.openAthleteMetrics.ui.nav.LocalBottomNavScrollBehavior
import com.athletedata.openAthleteMetrics.ui.nav.rememberBottomNavNestedScrollConnection

private val DATE_FORMATTER_LONG  = DateTimeFormatter.ofPattern("d MMM yyyy")
private val DATE_FORMATTER_SHORT = DateTimeFormatter.ofPattern("d MMM")

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
    val seriesList   by viewModel.seriesList.collectAsStateWithLifecycle()
    val metricKeys   by viewModel.metricKeys.collectAsStateWithLifecycle()
    val availableOverlayGroups by viewModel.availableOverlayGroups.collectAsStateWithLifecycle()
    val metricTiles  by viewModel.metricTiles.collectAsStateWithLifecycle()

    val today = remember { LocalDate.now() }
    var showDatePicker    by remember { mutableStateOf(false) }
    var showOverlaySheet  by remember { mutableStateOf(false) }
    val scrollBehavior = LocalBottomNavScrollBehavior.current
    val nestedScrollConnection = rememberBottomNavNestedScrollConnection(scrollBehavior)

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            DateNavigationRow(
                date        = localDate,
                today       = today,
                onPrevDay   = { viewModel.moveCursor(localDate.minusDays(1)) },
                onNextDay   = { viewModel.moveCursor(localDate.plusDays(1)) },
                onDateClick = { showDatePicker = true },
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
                allSeries     = seriesList.map { it.entries },
                endDate       = today,
                rangeToggle   = rangeToggle,
                cursorDate    = localDate,
                scrollEnabled = rangeToggle == RangeToggle.ALL,
                modifier      = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )

            Spacer(Modifier.height(8.dp))

            RangeAndAddRow(
                rangeToggle   = rangeToggle,
                onRangeSelect = viewModel::setRange,
                onAddToChart  = { showOverlaySheet = true },
                canAddMore    = metricKeys.size < 5,
            )

            Spacer(Modifier.height(16.dp))

            MetricTilesColumn(
                tiles        = metricTiles,
                onRemoveTile = { key -> viewModel.removeMetric(key) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        HistoryDatePickerDialog(
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

// ── Date navigation row ───────────────────────────────────────────────────────

@Composable
private fun DateNavigationRow(
    date: LocalDate,
    today: LocalDate,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onDateClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrevDay) {
            Icon(Icons.Outlined.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        TextButton(onClick = onDateClick) {
            Text(
                text  = date.format(DATE_FORMATTER_LONG),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        IconButton(onClick = onNextDay, enabled = date < today) {
            Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

// ── Chart ─────────────────────────────────────────────────────────────────────

@Composable
private fun HistoryChart(
    allSeries: List<List<ChartEntry>>,
    endDate: LocalDate,
    rangeToggle: RangeToggle,
    cursorDate: LocalDate,
    scrollEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val hasData = remember(allSeries) { allSeries.any { it.isNotEmpty() } }

    val fromDate = remember(endDate, rangeToggle, allSeries) {
        if (rangeToggle == RangeToggle.ALL) {
            allSeries.flatten().minOfOrNull { it.date } ?: endDate.minusDays(29)
        } else {
            endDate.minusDays(rangeToggle.days - 1)
        }
    }

    val lastX = remember(endDate, fromDate) {
        ChronoUnit.DAYS.between(fromDate, endDate).toFloat()
    }

    val labelXs = remember(lastX, rangeToggle) {
        val lx = lastX.toDouble()
        if (rangeToggle == RangeToggle.ALL) {
            // One label per week, always including today as the last
            val weekly = generateSequence(0.0) { it + 7.0 }.takeWhile { it < lx }.toList()
            if (weekly.isEmpty() || weekly.last() < lx) weekly + lx else weekly
        } else {
            listOf(0.0, lx / 4.0, lx / 2.0, 3.0 * lx / 4.0, lx)
        }
    }

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(allSeries, fromDate, lastX) {
        val populated = allSeries.filter { it.isNotEmpty() }
        if (populated.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries {
                populated.forEach { entries ->
                    val dataMap = entries.associate {
                        ChronoUnit.DAYS.between(fromDate, it.date).toInt() to it.value
                    }.toMutableMap()
                    val lastXInt = lastX.roundToInt()
                    // Always anchor the range boundaries so Vico never clips the first/last labels
                    if (0 !in dataMap) {
                        val y = entries.minByOrNull {
                            ChronoUnit.DAYS.between(fromDate, it.date)
                        }?.value ?: entries.first().value
                        dataMap[0] = y
                    }
                    if (lastXInt !in dataMap) {
                        val y = entries.maxByOrNull {
                            ChronoUnit.DAYS.between(fromDate, it.date)
                        }?.value ?: entries.last().value
                        dataMap[lastXInt] = y
                    }
                    // Fill middle label positions from nearest real data
                    for (pos in labelXs.drop(1).dropLast(1)) {
                        val dayOffset = pos.roundToInt()
                        if (dayOffset !in dataMap) {
                            val y = entries.minByOrNull {
                                kotlin.math.abs(ChronoUnit.DAYS.between(fromDate, it.date) - dayOffset.toLong())
                            }?.value ?: continue
                            dataMap[dayOffset] = y
                        }
                    }
                    val sorted = dataMap.entries.sortedBy { it.key }
                    series(x = sorted.map { it.key.toFloat() }, y = sorted.map { it.value })
                }
            }
        }
    }

    // Custom placer: always show exactly the 5 label positions regardless of data density
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

    val axisFormatter = remember(fromDate) {
        CartesianValueFormatter { _, value, _ ->
            fromDate.plusDays(value.roundToLong()).format(DATE_FORMATTER_SHORT)
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

    // Cursor marker — vertical line + dot at each series intersection for the selected date
    val cursorX = remember(cursorDate, fromDate, lastX) {
        val offset = ChronoUnit.DAYS.between(fromDate, cursorDate).toDouble()
        if (offset < 0.0 || offset > lastX.toDouble()) null else offset
    }
    val markerGuideline = rememberLineComponent(
        fill      = fill(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        thickness = 1.dp,
    )
    val cursorMarker = rememberDefaultCartesianMarker(
        label         = rememberTextComponent(color = Color.Transparent),
        indicator     = { color -> ShapeComponent(fill = fill(color), shape = CorneredShape.Pill) },
        indicatorSize = 8.dp,
        guideline     = markerGuideline,
    )

    val scrollState = rememberVicoScrollState(
        scrollEnabled  = scrollEnabled,
        initialScroll  = if (scrollEnabled) Scroll.Absolute.End else Scroll.Absolute.Start,
    )
    val zoomState = rememberVicoZoomState(
        zoomEnabled  = scrollEnabled,
        initialZoom  = if (scrollEnabled) Zoom.max(Zoom.fixed(), Zoom.Content) else Zoom.Content,
    )

    if (hasData) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(lineProvider = lineProvider),
                startAxis  = VerticalAxis.rememberStart(label = axisLabel),
                bottomAxis = HorizontalAxis.rememberBottom(
                    label          = axisLabel,
                    valueFormatter = axisFormatter,
                    itemPlacer     = itemPlacer,
                ),
                persistentMarkers = cursorX?.let { cx -> { cursorMarker at cx } },
            ),
            modelProducer = modelProducer,
            scrollState   = scrollState,
            zoomState     = zoomState,
            modifier      = modifier,
        )
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

// ── Range chips + Add to chart row ────────────────────────────────────────────

@Composable
private fun RangeAndAddRow(
    rangeToggle: RangeToggle,
    onRangeSelect: (RangeToggle) -> Unit,
    onAddToChart: () -> Unit,
    canAddMore: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RangeToggle.entries.forEach { option ->
                FilterChip(
                    selected = rangeToggle == option,
                    onClick  = { onRangeSelect(option) },
                    label    = { Text(option.label, style = TypographyMeta) },
                )
            }
        }
        TextButton(
            onClick         = onAddToChart,
            enabled         = canAddMore,
            contentPadding  = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("＋ Add", style = TypographyMeta)
        }
    }
}

// ── Metric tiles — vertical, full-width ───────────────────────────────────────

@Composable
private fun MetricTilesColumn(
    tiles: List<MetricTile>,
    onRemoveTile: (String) -> Unit,
) {
    if (tiles.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { tile ->
            MetricTileCard(
                tile     = tile,
                onRemove = { onRemoveTile(tile.metricKey) },
            )
        }
    }
}

@Composable
private fun MetricTileCard(
    tile: MetricTile,
    onRemove: () -> Unit,
) {
    val formattedValue = formatMetricValue(tile.summary.value, tile.summary.unit, tile.questionType)
    val deltaStr = tile.summary.deltaVs30DayAvg?.let { delta ->
        val sign = if (delta >= 0f) "+" else "–"
        "$sign${"%.0f".format(kotlin.math.abs(delta))}% vs 30d"
    }

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = MaterialTheme.shapes.medium,
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(tile.seriesColor),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text     = tile.displayName,
                    style    = TypographyTitle,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text  = formattedValue,
                    style = TypographyValue,
                )
                if (deltaStr != null) {
                    Text(
                        text  = deltaStr,
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

// ── Date picker dialog ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDatePickerDialog(
    currentDate: LocalDate,
    today: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = currentDate
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val picked = Instant.ofEpochMilli(utcTimeMillis)
                    .atZone(ZoneOffset.UTC).toLocalDate()
                return !picked.isAfter(today)
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { ms ->
                    val picked = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                    onConfirm(picked)
                } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}
