package com.athletedata.openAthleteMetrics.ui.metric

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.ui.components.EmptyState
import com.athletedata.openAthleteMetrics.ui.components.LoadingState
import com.athletedata.openAthleteMetrics.ui.components.PillSelector
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import com.athletedata.openAthleteMetrics.ui.theme.TypographyValue
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerDimensions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

private val DATE_FORMATTER_SHORT = DateTimeFormatter.ofPattern("d MMM")
private val DATE_FORMATTER_ROW = DateTimeFormatter.ofPattern("d MMM")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricDetailScreen(
    metricType: MetricType,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: MetricDetailViewModel = hiltViewModel<MetricDetailViewModel, MetricDetailViewModel.Factory>(
        creationCallback = { factory -> factory.create(metricType) },
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is MetricDetailUiState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingState()
            }
        }

        is MetricDetailUiState.Empty -> {
            Scaffold(
                modifier = modifier,
                contentWindowInsets = WindowInsets.navigationBars,
                topBar = {
                    TopAppBar(
                        title = { Text(metricType.displayName) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                    )
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(message = state.message)
                }
            }
        }

        is MetricDetailUiState.Success -> {
            SuccessContent(
                state = state,
                onRangeSelected = { viewModel.setRange(it) },
                onBack = onBack,
                modifier = modifier,
            )
        }
    }
}

// ── Success layout ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuccessContent(
    state: MetricDetailUiState.Success,
    onRangeSelected: (TimeRange) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unit = state.metricType.displayUnit
    val rangeLabels = TimeRange.entries.map { it.label }
    val selectedRangeIndex = TimeRange.entries.indexOf(state.selectedRange)

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = { Text(state.metricType.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 80.dp),
        ) {
            // Current value (most recent day)
            val latestRow = state.historyRows.firstOrNull()
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = latestRow?.let { formatValue(it.value, state.metricType) } ?: "--",
                    style = TypographyValue,
                )
                if (latestRow != null && unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            if (latestRow != null) {
                Text(
                    text = latestRow.date.format(DATE_FORMATTER_SHORT),
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            // Time-range selector
            PillSelector(
                tabs = rangeLabels,
                selectedIndex = selectedRangeIndex,
                onSelect = { onRangeSelected(TimeRange.entries[it]) },
            )

            Spacer(Modifier.height(16.dp))

            // Vico line chart
            MetricChart(
                chartData = state.chartData,
                rangeDays = state.selectedRange.days,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            )

            Spacer(Modifier.height(16.dp))

            // Stats row: Min / Avg / Max
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatColumn("Min", state.stats.min?.let { formatValue(it, state.metricType) }, unit)
                StatColumn("Avg", state.stats.average?.let { formatValue(it, state.metricType) }, unit)
                StatColumn("Max", state.stats.max?.let { formatValue(it, state.metricType) }, unit)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()

            // History list — one row per day, most recent first
            state.historyRows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.date.format(DATE_FORMATTER_ROW),
                        style = TypographyTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${formatValue(row.value, state.metricType)}${if (unit.isNotEmpty()) " $unit" else ""}",
                        style = TypographyTitle,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

// ── Stat column ────────────────────────────────────────────────────────────────

@Composable
private fun StatColumn(label: String, value: String?, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(text = value ?: "--", style = TypographyValue)
        if (unit.isNotEmpty()) {
            Text(text = unit, style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Vico chart ─────────────────────────────────────────────────────────────────

@Composable
private fun MetricChart(
    chartData: List<ChartPoint>,
    rangeDays: Int,
    modifier: Modifier = Modifier,
) {
    if (chartData.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No data for this range",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val today = remember { LocalDate.now() }
    val fromDate = remember(today, rangeDays) { today.minusDays(rangeDays.toLong()) }
    val lastX = remember(rangeDays) { rangeDays.toFloat() }

    val labelXs = remember(lastX) {
        val lx = lastX.toDouble()
        listOf(0.0, lx / 4.0, lx / 2.0, 3.0 * lx / 4.0, lx)
    }

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(chartData, fromDate) {
        if (chartData.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries {
                val dataMap = chartData.associate { point ->
                    val date = Instant.ofEpochMilli(point.timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()
                    ChronoUnit.DAYS.between(fromDate, date).toInt() to point.value
                }.toMutableMap()

                val lastXInt = lastX.toInt()
                if (0 !in dataMap) dataMap[0] = dataMap.values.first()
                if (lastXInt !in dataMap) dataMap[lastXInt] = dataMap.values.last()

                val sorted = dataMap.entries.sortedBy { it.key }
                series(x = sorted.map { it.key.toFloat() }, y = sorted.map { it.value })
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

    val axisFormatter = remember(fromDate) {
        CartesianValueFormatter { _, value, _ ->
            fromDate.plusDays(value.roundToLong()).format(DATE_FORMATTER_SHORT)
        }
    }

    val axisLabelColor = MaterialTheme.colorScheme.onBackground
    val axisLabel = rememberTextComponent(color = axisLabelColor)

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = VerticalAxis.rememberStart(label = axisLabel),
            bottomAxis = HorizontalAxis.rememberBottom(
                label = axisLabel,
                valueFormatter = axisFormatter,
                itemPlacer = itemPlacer,
            ),
        ),
        modelProducer = modelProducer,
        modifier = modifier,
    )
}

// ── Formatting ─────────────────────────────────────────────────────────────────

private fun formatValue(value: Double, metricType: MetricType): String = when (metricType) {
    MetricType.STEPS -> "%.0f".format(value)
    MetricType.HR, MetricType.RHR -> "%.0f".format(value)
    MetricType.ACTIVE_CALORIES, MetricType.TOTAL_CALORIES -> "%.0f".format(value)
    else -> "%.1f".format(value)
}
