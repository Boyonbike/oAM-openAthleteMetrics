package com.athletedata.openAthleteMetrics.ui.dailydetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.data.model.QuestionCategory
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import com.athletedata.openAthleteMetrics.ui.components.DataPageDatePickerDialog
import com.athletedata.openAthleteMetrics.ui.components.DataPageTopBar
import com.athletedata.openAthleteMetrics.ui.nav.LocalBottomNavScrollBehavior
import com.athletedata.openAthleteMetrics.ui.nav.rememberBottomNavNestedScrollConnection
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import org.json.JSONObject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyDetailScreen(
    initialDate: String? = null,
    onInitialDateConsumed: () -> Unit = {},
    initialSection: DailyDetailSection? = null,
    initialMetricKey: String? = null,
    onInitialSectionConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DailyDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val localDate by viewModel.localDate.collectAsStateWithLifecycle()
    val expandedTiles by viewModel.expandedTiles.collectAsStateWithLifecycle()
    val isEditMode by viewModel.isEditMode.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    var categorySheetActivity by remember { mutableStateOf<ActivityUiItem?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(initialDate) {
        if (initialDate != null) {
            viewModel.setDate(runCatching { LocalDate.parse(initialDate) }.getOrDefault(LocalDate.now()))
            onInitialDateConsumed()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            DataPageTopBar(
                date = localDate,
                onDateClick = { showDatePicker = true },
                centre = { Text("Daily Detail", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    IconButton(onClick = viewModel::toggleEditMode) {
                        Icon(
                            imageVector = if (isEditMode) Icons.Outlined.Check else Icons.Outlined.Edit,
                            contentDescription = if (isEditMode) "Done editing" else "Edit tiles",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            DailyDetailUiState.Loading -> LoadingContent(innerPadding)
            is DailyDetailUiState.Error -> ErrorContent(state.message, innerPadding)
            is DailyDetailUiState.Success -> SuccessContent(
                state = state,
                innerPadding = innerPadding,
                isEditMode = isEditMode,
                expandedTiles = expandedTiles,
                initialSection = initialSection,
                initialMetricKey = initialMetricKey,
                onInitialSectionConsumed = onInitialSectionConsumed,
                onTileToggle = viewModel::toggleTile,
                onTileReordered = viewModel::onTileReordered,
                onTileVisibilityToggle = viewModel::toggleTileVisibility,
                onActivityTap = { activity -> categorySheetActivity = activity },
            )
        }
    }

    categorySheetActivity?.let { activity ->
        CategorySheet(
            activity = activity,
            onSave = { cat, notes -> viewModel.updateActivityCategory(activity.id, cat, notes) },
            onDismiss = { categorySheetActivity = null },
        )
    }

    if (showDatePicker) {
        DataPageDatePickerDialog(
            currentDate = localDate,
            today = today,
            onConfirm = { date ->
                viewModel.setDate(date)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

// ── Loading / Error ───────────────────────────────────────────────────────────

@Composable
private fun LoadingContent(innerPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorContent(message: String, innerPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

// ── Success content ───────────────────────────────────────────────────────────

@Composable
private fun SuccessContent(
    state: DailyDetailUiState.Success,
    innerPadding: PaddingValues,
    isEditMode: Boolean,
    expandedTiles: Set<String>,
    initialSection: DailyDetailSection?,
    initialMetricKey: String?,
    onInitialSectionConsumed: () -> Unit,
    onTileToggle: (String) -> Unit,
    onTileReordered: (Int, Int) -> Unit,
    onTileVisibilityToggle: (String) -> Unit,
    onActivityTap: (ActivityUiItem) -> Unit,
) {
    val scrollBehavior = LocalBottomNavScrollBehavior.current
    val nestedScrollConnection = rememberBottomNavNestedScrollConnection(scrollBehavior)
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        onTileReordered(from.index, to.index)
    }

    val allTilesOrdered = state.tileOrder.sortedBy { it.sortOrder }
    val effectiveTiles = if (isEditMode) {
        allTilesOrdered
    } else {
        allTilesOrdered.filter { tile ->
            tile.isVisible && !(tile.id == "activities" && state.activities.isEmpty())
        }
    }

    LaunchedEffect(initialSection) {
        if (initialSection != null) {
            val tileId = initialSection.toTileId()
            if (tileId != null) {
                val index = effectiveTiles.indexOfFirst { it.id == tileId }
                if (index >= 0) listState.animateScrollToItem(index)
                onTileToggle(tileId)
            }
            onInitialSectionConsumed()
        }
    }

    var openReadingTables by remember { mutableStateOf<Set<String>>(emptySet()) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .nestedScroll(nestedScrollConnection)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(effectiveTiles, key = { it.id }) { tile ->
            ReorderableItem(reorderState, key = tile.id) { isDragging ->
                if (isEditMode) {
                    EditModeTile(
                        tile = tile,
                        isDragging = isDragging,
                        onToggleVisibility = { onTileVisibilityToggle(tile.id) },
                        dragHandleModifier = Modifier.draggableHandle(),
                    )
                } else {
                    CategoryTileFor(
                        tileId = tile.id,
                        state = state,
                        isExpanded = tile.id in expandedTiles,
                        openReadingTables = openReadingTables,
                        onToggle = { onTileToggle(tile.id) },
                        onToggleReadings = { key ->
                            openReadingTables = if (key in openReadingTables)
                                openReadingTables - key else openReadingTables + key
                        },
                        onActivityTap = onActivityTap,
                    )
                }
            }
        }
    }
}

private fun DailyDetailSection.toTileId(): String? = when (this) {
    DailyDetailSection.CARDIOVASCULAR -> "cardiovascular"
    DailyDetailSection.SLEEP          -> "sleep"
    DailyDetailSection.ACTIVITY       -> "activity"
    DailyDetailSection.BODY           -> "body"
    DailyDetailSection.QUESTIONS      -> "questions"
    DailyDetailSection.ACTIVITIES     -> "activities"
}

private fun String.toTileTitle(): String = when (this) {
    "cardiovascular" -> "Cardiovascular"
    "sleep"          -> "Sleep"
    "activity"       -> "Activity"
    "body"           -> "Body"
    "questions"      -> "Check-in"
    "activities"     -> "Activities"
    else             -> this.replaceFirstChar { it.uppercase() }
}

// ── Tile router ───────────────────────────────────────────────────────────────

@Composable
private fun CategoryTileFor(
    tileId: String,
    state: DailyDetailUiState.Success,
    isExpanded: Boolean,
    openReadingTables: Set<String>,
    onToggle: () -> Unit,
    onToggleReadings: (String) -> Unit,
    onActivityTap: (ActivityUiItem) -> Unit,
) {
    when (tileId) {
        "cardiovascular" -> CardiovascularTile(state, isExpanded, openReadingTables, onToggle, onToggleReadings)
        "sleep"          -> SleepTile(state, isExpanded, openReadingTables, onToggle, onToggleReadings)
        "activity"       -> ActivityTile(state, isExpanded, openReadingTables, onToggle, onToggleReadings)
        "body"           -> BodyTile(state, isExpanded, openReadingTables, onToggle, onToggleReadings)
        "questions"      -> QuestionsTile(state, isExpanded, onToggle)
        "activities"     -> ActivitiesTile(state, isExpanded, onToggle, onActivityTap)
    }
}

// ── Generic tile shell ────────────────────────────────────────────────────────

@Composable
private fun CategoryTile(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    collapsedSummary: @Composable () -> Unit,
    expandedContent: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            val chevronRotation by animateFloatAsState(
                targetValue = if (isExpanded) 180f else 0f,
                label = "chevron_$title",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = TypographyTitle)
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                )
            }
            AnimatedVisibility(visible = !isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 10.dp),
                ) {
                    collapsedSummary()
                }
            }
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    expandedContent()
                }
            }
        }
    }
}

// ── Edit mode tile ────────────────────────────────────────────────────────────

@Composable
private fun EditModeTile(
    tile: TileConfig,
    isDragging: Boolean,
    onToggleVisibility: () -> Unit,
    dragHandleModifier: Modifier,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 4.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.DragIndicator,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = dragHandleModifier,
            )
            Text(
                text = tile.id.toTileTitle(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = if (tile.isVisible) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    imageVector = if (tile.isVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = if (tile.isVisible) "Hide tile" else "Show tile",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Cardiovascular tile ───────────────────────────────────────────────────────

@Composable
private fun CardiovascularTile(
    state: DailyDetailUiState.Success,
    isExpanded: Boolean,
    openReadingTables: Set<String>,
    onToggle: () -> Unit,
    onToggleReadings: (String) -> Unit,
) {
    val data = state.cardiovascular
    CategoryTile(
        title = "Cardiovascular",
        isExpanded = isExpanded,
        onToggle = onToggle,
        collapsedSummary = {
            if (data == null) {
                EmptyStateText("No cardiovascular data")
            } else {
                val parts = buildList {
                    data.avgHrBpm?.let { add("HR %.0f bpm".format(it)) }
                    data.restingHrBpm?.let { add("RHR %.0f bpm".format(it)) }
                    data.morningHrvMs?.let { add("HRV %.0f ms".format(it)) }
                    data.avgSpo2Pct?.let { add("SpO₂ %.1f%%".format(it)) }
                }
                Text(
                    text = if (parts.isEmpty()) "No data" else parts.joinToString(" · "),
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        expandedContent = {
            if (data == null) {
                EmptyStateText("No cardiovascular data")
            } else {
                data.avgHrBpm?.let { avg ->
                    MetricSubsection(
                        title = "Heart Rate",
                        valueLine = "Avg %.0f bpm".format(avg),
                        extraLines = listOfNotNull(
                            data.restingHrBpm?.let { "Resting: %.0f bpm".format(it) },
                        ),
                        readings = state.rawReadings.hrReadings,
                        isReadingsExpanded = "HR" in openReadingTables,
                        onToggleReadings = { onToggleReadings("HR") },
                    )
                }
                if (data.avgHrBpm == null) {
                    data.restingHrBpm?.let { rhr ->
                        MetricSubsection(
                            title = "Resting Heart Rate",
                            valueLine = "%.0f bpm".format(rhr),
                            readings = state.rawReadings.hrReadings,
                            isReadingsExpanded = "RHR" in openReadingTables,
                            onToggleReadings = { onToggleReadings("RHR") },
                        )
                    }
                }
                data.morningHrvMs?.let { morning ->
                    MetricSubsection(
                        title = "HRV",
                        valueLine = "Morning %.0f ms".format(morning),
                        extraLines = listOfNotNull(
                            data.avgHrvMs?.let { "Avg: %.0f ms".format(it) },
                            data.hrvMinMs?.let { min ->
                                data.hrvMaxMs?.let { max -> "Range: %.0f – %.0f ms".format(min, max) }
                            },
                        ),
                        readings = state.rawReadings.hrvReadings,
                        isReadingsExpanded = "HRV" in openReadingTables,
                        onToggleReadings = { onToggleReadings("HRV") },
                    )
                }
                data.avgSpo2Pct?.let { avg ->
                    MetricSubsection(
                        title = "SpO₂",
                        valueLine = "Avg %.1f%%".format(avg),
                        extraLines = listOfNotNull(
                            data.spo2MinPct?.let { min ->
                                data.spo2MaxPct?.let { max -> "Range: %.1f – %.1f%%".format(min, max) }
                            },
                        ),
                        readings = state.rawReadings.spo2Readings,
                        isReadingsExpanded = "SPO2" in openReadingTables,
                        onToggleReadings = { onToggleReadings("SPO2") },
                    )
                }
            }
        },
    )
}

// ── Sleep tile ────────────────────────────────────────────────────────────────

@Composable
private fun SleepTile(
    state: DailyDetailUiState.Success,
    isExpanded: Boolean,
    openReadingTables: Set<String>,
    onToggle: () -> Unit,
    onToggleReadings: (String) -> Unit,
) {
    val data = state.sleep
    CategoryTile(
        title = "Sleep",
        isExpanded = isExpanded,
        onToggle = onToggle,
        collapsedSummary = {
            if (data == null) {
                EmptyStateText("No sleep data")
            } else {
                val stageParts = buildList {
                    data.deepMinutes?.let { add("D ${formatDurationShort(it)}") }
                    data.lightMinutes?.let { add("L ${formatDurationShort(it)}") }
                    data.remMinutes?.let { add("R ${formatDurationShort(it)}") }
                }
                Text(
                    text = if (stageParts.isEmpty()) data.formattedDuration
                           else "${data.formattedDuration} · ${stageParts.joinToString(" ")}",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        expandedContent = {
            if (data == null) {
                EmptyStateText("No sleep data")
            } else {
                MetricSubsection(
                    title = "Sleep Duration",
                    valueLine = data.formattedDuration,
                    readings = emptyList(),
                    isReadingsExpanded = false,
                    onToggleReadings = {},
                )
                val hasStages = data.deepMinutes != null || data.lightMinutes != null || data.remMinutes != null
                if (hasStages) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Sleep Stages", style = TypographyTitle)
                        val stageParts = buildList {
                            data.deepMinutes?.let { add("Deep: ${formatDurationShort(it)}") }
                            data.lightMinutes?.let { add("Light: ${formatDurationShort(it)}") }
                            data.remMinutes?.let { add("REM: ${formatDurationShort(it)}") }
                            data.awakeMinutes?.let { add("Awake: ${formatDurationShort(it)}") }
                        }
                        stageParts.forEach { part ->
                            Text(part, style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (data.hypnogramSegments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Hypnogram", style = TypographyTitle)
                        Hypnogram(
                            segments = data.hypnogramSegments,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                        )
                        HypnogramLegend()
                    }
                }
            }
        },
    )
}

// ── Activity tile ─────────────────────────────────────────────────────────────

@Composable
private fun ActivityTile(
    state: DailyDetailUiState.Success,
    isExpanded: Boolean,
    openReadingTables: Set<String>,
    onToggle: () -> Unit,
    onToggleReadings: (String) -> Unit,
) {
    val data = state.activity
    CategoryTile(
        title = "Activity",
        isExpanded = isExpanded,
        onToggle = onToggle,
        collapsedSummary = {
            if (data == null) {
                EmptyStateText("No activity data")
            } else {
                val parts = buildList {
                    data.steps?.let { add("${it} steps") }
                    data.activeCalories?.let { add("%.0f kcal active".format(it)) }
                    data.exerciseMinutes?.let { add("${it}m exercise") }
                }
                Text(
                    text = if (parts.isEmpty()) "No data" else parts.joinToString(" · "),
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        expandedContent = {
            if (data == null) {
                EmptyStateText("No activity data")
            } else {
                data.steps?.let { steps ->
                    MetricSubsection(
                        title = "Steps",
                        valueLine = steps.toString(),
                        extraLines = listOfNotNull(
                            data.exerciseMinutes?.let { "${it} min exercise" },
                        ),
                        readings = state.rawReadings.stepsReadings,
                        isReadingsExpanded = "STEPS" in openReadingTables,
                        onToggleReadings = { onToggleReadings("STEPS") },
                    )
                }
                data.activeCalories?.let { cal ->
                    MetricSubsection(
                        title = "Active Calories",
                        valueLine = "%.0f kcal".format(cal),
                        readings = state.rawReadings.activeCalorieReadings,
                        isReadingsExpanded = "ACTIVE_CAL" in openReadingTables,
                        onToggleReadings = { onToggleReadings("ACTIVE_CAL") },
                    )
                }
                if (data.steps == null && data.activeCalories == null && data.exerciseMinutes == null) {
                    EmptyStateText("No activity data")
                }
            }
        },
    )
}

// ── Body tile ─────────────────────────────────────────────────────────────────

@Composable
private fun BodyTile(
    state: DailyDetailUiState.Success,
    isExpanded: Boolean,
    openReadingTables: Set<String>,
    onToggle: () -> Unit,
    onToggleReadings: (String) -> Unit,
) {
    val data = state.body
    CategoryTile(
        title = "Body",
        isExpanded = isExpanded,
        onToggle = onToggle,
        collapsedSummary = {
            if (data == null) {
                EmptyStateText("No body data")
            } else {
                val parts = buildList {
                    data.weightKg?.let { add("%.1f kg".format(it)) }
                    data.bodyFatPct?.let { add("%.1f%% fat".format(it)) }
                    data.respirationAvg?.let { add("%.1f brpm".format(it)) }
                }
                Text(
                    text = if (parts.isEmpty()) "No data" else parts.joinToString(" · "),
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        expandedContent = {
            if (data == null) {
                EmptyStateText("No body data")
            } else {
                data.weightKg?.let { kg ->
                    MetricSubsection(
                        title = "Weight",
                        valueLine = "%.1f kg".format(kg),
                        extraLines = listOfNotNull(
                            data.bodyFatPct?.let { "Body fat: %.1f%%".format(it) },
                        ),
                        readings = emptyList(),
                        isReadingsExpanded = false,
                        onToggleReadings = {},
                    )
                }
                data.respirationAvg?.let { rate ->
                    MetricSubsection(
                        title = "Breathing Rate",
                        valueLine = "%.1f brpm".format(rate),
                        readings = state.rawReadings.respirationReadings,
                        isReadingsExpanded = "RESP" in openReadingTables,
                        onToggleReadings = { onToggleReadings("RESP") },
                    )
                }
                data.totalCalories?.let { cal ->
                    MetricSubsection(
                        title = "Total Calories",
                        valueLine = "%.0f kcal".format(cal),
                        readings = state.rawReadings.totalCalorieReadings,
                        isReadingsExpanded = "TOTAL_CAL" in openReadingTables,
                        onToggleReadings = { onToggleReadings("TOTAL_CAL") },
                    )
                }
                if (data.weightKg == null && data.respirationAvg == null && data.totalCalories == null) {
                    EmptyStateText("No body data")
                }
            }
        },
    )
}

// ── Questions tile ────────────────────────────────────────────────────────────

@Composable
private fun QuestionsTile(
    state: DailyDetailUiState.Success,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val hasData = state.isIll || state.questionGroups.isNotEmpty() ||
        state.contextScores != null || !state.habitsJson.isNullOrBlank()
    val habitsCount = parseHabitCounts(state.habitsJson)

    CategoryTile(
        title = "Check-in",
        isExpanded = isExpanded,
        onToggle = onToggle,
        collapsedSummary = {
            if (!hasData) {
                EmptyStateText("No check-in recorded")
            } else {
                val parts = buildList {
                    if (state.isIll) add("Illness noted")
                    state.contextScores?.stress?.let { add("Stress $it/5") }
                    state.contextScores?.motivation?.let { add("Motivation $it/5") }
                    habitsCount?.let { (done, total) -> add("Habits $done/$total") }
                }
                Text(
                    text = if (parts.isEmpty()) "Data recorded" else parts.joinToString(" · "),
                    style = TypographyMeta,
                    color = if (state.isIll) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        expandedContent = {
            if (!hasData) {
                EmptyStateText("No check-in recorded")
            } else {
                if (state.isIll) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Illness noted",
                            style = TypographyTitle,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (!state.illnessNotes.isNullOrBlank()) {
                            Text(
                                text = state.illnessNotes,
                                style = TypographyMeta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.contextScores?.let { scores ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Subjective Scores", style = TypographyTitle)
                        listOfNotNull(
                            scores.fatigue?.let { "Fatigue" to "$it/5" },
                            scores.stress?.let { "Stress" to "$it/5" },
                            scores.motivation?.let { "Motivation" to "$it/5" },
                            scores.sleepQuality?.let { "Sleep Quality" to "$it/5" },
                            scores.performanceFeel?.let { "Performance Feel" to "$it/5" },
                        ).forEach { (label, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(label, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                val habits = parseHabits(state.habitsJson)
                if (habits.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Habits", style = TypographyTitle)
                        habits.forEach { (name, done) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    name.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    if (done) "Yes" else "No",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                state.questionGroups.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val label = when (group.category) {
                            QuestionCategory.LIFESTYLE -> "Lifestyle"
                            QuestionCategory.CUSTOM    -> "Custom"
                        }
                        Text(label, style = TypographyTitle)
                        group.items.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(item.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(
                                    item.value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

// ── Activities tile ───────────────────────────────────────────────────────────

@Composable
private fun ActivitiesTile(
    state: DailyDetailUiState.Success,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onActivityTap: (ActivityUiItem) -> Unit,
) {
    CategoryTile(
        title = "Activities",
        isExpanded = isExpanded,
        onToggle = onToggle,
        collapsedSummary = {
            val count = state.activities.size
            Text(
                text = if (count == 1) "1 activity" else "$count activities",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        expandedContent = {
            if (state.activities.isEmpty()) {
                EmptyStateText("No activities")
            } else {
                state.activities.forEach { activity ->
                    ActivityCard(activity = activity, onTap = { onActivityTap(activity) })
                }
            }
        },
    )
}

// ── Metric sub-section ────────────────────────────────────────────────────────

@Composable
private fun MetricSubsection(
    title: String,
    valueLine: String,
    extraLines: List<String> = emptyList(),
    readings: List<TimestampedReading>,
    isReadingsExpanded: Boolean,
    onToggleReadings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = TypographyTitle)
        Text(valueLine, style = MaterialTheme.typography.bodyMedium)
        extraLines.forEach { line ->
            Text(line, style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (readings.size >= 2) {
            Spacer(Modifier.height(4.dp))
            ReadingsLineChart(
                readings = readings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )
        }
        if (readings.isNotEmpty()) {
            ReadingsTable(
                readings = readings,
                isExpanded = isReadingsExpanded,
                onToggle = onToggleReadings,
            )
        }
    }
}

// ── Vico line chart for intra-day readings ────────────────────────────────────

@Composable
private fun ReadingsLineChart(
    readings: List<TimestampedReading>,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    val pairs = remember(readings) {
        readings.mapIndexedNotNull { i, r -> r.value.toDoubleOrNull()?.let { i to it } }
    }

    LaunchedEffect(pairs) {
        if (pairs.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = pairs.map { it.first },
                    y = pairs.map { it.second },
                )
            }
        }
    }

    val axisLabelColor = MaterialTheme.colorScheme.onBackground
    val axisLabel = rememberTextComponent(color = axisLabelColor)
    val timeFormatter = remember(readings) {
        CartesianValueFormatter { _, value, _ ->
            readings.getOrNull(value.toLong().toInt())?.timeLabel ?: ""
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = VerticalAxis.rememberStart(label = axisLabel),
            bottomAxis = HorizontalAxis.rememberBottom(
                label = axisLabel,
                valueFormatter = timeFormatter,
            ),
        ),
        modelProducer = modelProducer,
        modifier = modifier,
    )
}

// ── Hypnogram ─────────────────────────────────────────────────────────────────

private val STAGE_COLORS = mapOf(
    SleepStage.DEEP  to Color(0xFF1A237E),
    SleepStage.LIGHT to Color(0xFF5C6BC0),
    SleepStage.REM   to Color(0xFF9C27B0),
    SleepStage.AWAKE to Color(0xFFB0BEC5),
)

@Composable
private fun Hypnogram(
    segments: List<HypnogramSegment>,
    modifier: Modifier = Modifier,
) {
    if (segments.isEmpty()) return
    val startMs = segments.minOf { it.startMs }
    val endMs = segments.maxOf { it.endMs }
    val totalMs = (endMs - startMs).toFloat()
    if (totalMs <= 0f) return

    Canvas(modifier = modifier) {
        segments.forEach { seg ->
            val startFrac = (seg.startMs - startMs) / totalMs
            val widthFrac = (seg.endMs - seg.startMs) / totalMs
            drawRect(
                color = STAGE_COLORS[seg.stage] ?: Color.Gray,
                topLeft = Offset(startFrac * size.width, 0f),
                size = Size(widthFrac * size.width, size.height),
            )
        }
    }
}

@Composable
private fun HypnogramLegend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        listOf(
            SleepStage.DEEP  to "Deep",
            SleepStage.LIGHT to "Light",
            SleepStage.REM   to "REM",
            SleepStage.AWAKE to "Awake",
        ).forEach { (stage, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(STAGE_COLORS[stage] ?: Color.Gray, shape = MaterialTheme.shapes.extraSmall),
                )
                Text(label, style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Collapsible readings table ────────────────────────────────────────────────

@Composable
private fun ReadingsTable(
    readings: List<TimestampedReading>,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${readings.size} reading${if (readings.size == 1) "" else "s"}",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val rotation by animateFloatAsState(if (isExpanded) 180f else 0f, label = "readings_chevron")
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(readings) { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = r.timeLabel,
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${r.value} ${r.unit}",
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

// ── Activity card ─────────────────────────────────────────────────────────────

@Composable
private fun ActivityCard(activity: ActivityUiItem, onTap: () -> Unit) {
    Card(
        onClick = if (activity.userCategory == null) onTap else ({}),
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = activity.deviceName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (activity.userCategory != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryChip(activity.userCategory)
                        IconButton(onClick = onTap) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Edit category",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Uncategorised",
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = activity.formattedDuration,
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            activity.avgHrBpm?.let {
                Text(
                    text = "Avg HR: %.0f bpm".format(it),
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            activity.notes?.let {
                Text(
                    text = it,
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(category: UserCategory) {
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = category.toDisplayLabel(),
            style = TypographyMeta,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun EmptyStateText(text: String) {
    Text(
        text = text,
        style = TypographyMeta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

private fun UserCategory.toDisplayLabel(): String = when (this) {
    UserCategory.TRAINING -> "Training"
    UserCategory.LIFE     -> "Life"
    UserCategory.RACE     -> "Race"
}

private fun formatDurationShort(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else   -> "${h}h${m}m"
    }
}

private fun parseHabitCounts(habitsJson: String?): Pair<Int, Int>? {
    if (habitsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = JSONObject(habitsJson)
        var total = 0
        var done = 0
        obj.keys().forEach { key ->
            total++
            if (obj.optBoolean(key, false)) done++
        }
        Pair(done, total)
    }.getOrNull()
}

private fun parseHabits(habitsJson: String?): List<Pair<String, Boolean>> {
    if (habitsJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val obj = JSONObject(habitsJson)
        obj.keys().asSequence().map { key ->
            Pair(key, obj.optBoolean(key, false))
        }.toList()
    }.getOrElse { emptyList() }
}

// Extension needed for ColumnScope reference in tile content lambdas
private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope
