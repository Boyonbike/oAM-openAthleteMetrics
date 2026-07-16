package com.athletedata.openAthleteMetrics.ui.dailydetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.data.model.QuestionCategory
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import com.athletedata.openAthleteMetrics.ui.components.DataPageDatePickerDialog
import com.athletedata.openAthleteMetrics.ui.components.DataPageTopBar
import com.athletedata.openAthleteMetrics.ui.components.horizontalDateSwipe
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.LocalDate

/**
 * Live on-screen bounds (window coordinates) of currently-composed [ReadingsLineChart]s,
 * so the page-level swipe-to-change-date gesture can skip touches that start on a chart
 * and leave its own pan/zoom gesture handling uncontested.
 */
private val LocalChartExclusionZones =
    staticCompositionLocalOf<SnapshotStateMap<Any, Rect>?> { null }

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
    val chartExclusionZones = remember { mutableStateMapOf<Any, Rect>() }
    var swipeNodeCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    LaunchedEffect(initialDate) {
        if (initialDate != null) {
            viewModel.setDate(runCatching { LocalDate.parse(initialDate) }.getOrDefault(LocalDate.now()))
            onInitialDateConsumed()
        }
    }

    CompositionLocalProvider(LocalChartExclusionZones provides chartExclusionZones) {
        Scaffold(
            modifier = modifier
                .onGloballyPositioned { swipeNodeCoords = it }
                .horizontalDateSwipe(
                    exclusionZones = { chartExclusionZones.values },
                    toWindowPosition = { offset -> swipeNodeCoords?.localToWindow(offset) ?: offset },
                    onDayForward = { viewModel.stepDate(true) },
                    onDayBack    = { viewModel.stepDate(false) },
                ),
            topBar = {
                DataPageTopBar(
                    date = localDate,
                    onDateClick = { showDatePicker = true },
                    onDateLongClick = { viewModel.setDate(LocalDate.now()) },
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
                    onExpandTile = viewModel::expandTile,
                    onTileReordered = viewModel::onTileReordered,
                    onTileVisibilityToggle = viewModel::toggleTileVisibility,
                    onActivityTap = { activity -> categorySheetActivity = activity },
                )
            }
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
    onExpandTile: (String) -> Unit,
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
                onExpandTile(tileId)
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
internal fun CategoryTile(
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
                    data.overnightHrvMs?.let { add("HRV %.0f ms".format(it)) }
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
                HrvSection(
                    section = state.hrv,
                    isReadingsExpanded = "HRV" in openReadingTables,
                    onToggleReadings = { onToggleReadings("HRV") },
                )
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
    val hasData = state.isIll || state.questionGroups.isNotEmpty() || state.contextScores != null

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
                state.questionGroups.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val label = when (group.category) {
                            QuestionCategory.LIFESTYLE -> "Lifestyle"
                            QuestionCategory.CUSTOM    -> "Habits"
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

// ── HRV section ───────────────────────────────────────────────────────────────

@Composable
private fun HrvSection(
    section: HrvSectionState,
    isReadingsExpanded: Boolean,
    onToggleReadings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("HRV", style = TypographyTitle)
        when (section) {
            is HrvSectionState.NoSleepSession ->
                EmptyStateText("No sleep session recorded")

            is HrvSectionState.InsufficientData -> {
                EmptyStateText("Not enough overnight data")
                HrvReadingsBody(section.rawReadings, isReadingsExpanded, onToggleReadings)
            }

            is HrvSectionState.HasData -> {
                Text("Last Night HRV: %.0f ms".format(section.headlineMs), style = MaterialTheme.typography.bodyMedium)
                section.baseline?.let { baseline ->
                    val note = when {
                        section.headlineMs > baseline.upper -> " · Above baseline"
                        section.headlineMs < baseline.lower -> " · Below baseline"
                        else -> ""
                    }
                    Text(
                        text = "Baseline: %.0f – %.0f ms%s".format(baseline.lower, baseline.upper, note),
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HrvReadingsBody(section.rawReadings, isReadingsExpanded, onToggleReadings)
            }
        }
    }
}

@Composable
private fun HrvReadingsBody(
    readings: List<TimestampedReading>,
    isReadingsExpanded: Boolean,
    onToggleReadings: () -> Unit,
) {
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

// ── Vico line chart for intra-day readings ────────────────────────────────────

@Composable
internal fun ReadingsLineChart(
    readings: List<TimestampedReading>,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val exclusionZones = LocalChartExclusionZones.current
    val chartKey = remember { Any() }
    DisposableEffect(Unit) {
        onDispose { exclusionZones?.remove(chartKey) }
    }

    val pairs = remember(readings) {
        readings.mapIndexedNotNull { i, r -> r.value.toDoubleOrNull()?.let { i to it } }
    }

    // Readings actually reflected in modelProducer's current model. Only advances once
    // the transaction for `pairs` completes, so the axis formatter (index-based into this
    // list) never gets ahead of the async chart data update when `readings` changes.
    var plottedReadings by remember { mutableStateOf(readings) }

    LaunchedEffect(pairs) {
        modelProducer.runTransaction {
            if (pairs.isNotEmpty()) {
                lineSeries {
                    series(
                        x = pairs.map { it.first },
                        y = pairs.map { it.second },
                    )
                }
            }
        }
        plottedReadings = readings
    }

    val axisLabelColor = MaterialTheme.colorScheme.onBackground
    val axisLabel = rememberTextComponent(color = axisLabelColor)
    val timeFormatter = remember(plottedReadings) {
        CartesianValueFormatter { _, value, _ ->
            // Vico throws if a formatter ever returns "" (it uses ItemPlacer, not empty
            // strings, to decide which ticks to skip), so clamp to the nearest valid
            // reading instead of falling through to getOrNull returning null here.
            if (plottedReadings.isEmpty()) {
                ""
            } else {
                val index = value.toLong().toInt().coerceIn(0, plottedReadings.lastIndex)
                plottedReadings[index].timeLabel
            }
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
        modifier = modifier.onGloballyPositioned { coordinates ->
            exclusionZones?.set(chartKey, coordinates.boundsInWindow())
        },
    )
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
internal fun EmptyStateText(text: String) {
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

internal fun formatDurationShort(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else   -> "${h}h${m}m"
    }
}


// Extension needed for ColumnScope reference in tile content lambdas
internal typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope
