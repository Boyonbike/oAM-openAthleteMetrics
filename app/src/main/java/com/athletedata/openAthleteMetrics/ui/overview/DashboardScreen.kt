package com.athletedata.openAthleteMetrics.ui.overview

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.data.model.WidgetLayout
import com.athletedata.openAthleteMetrics.data.model.WidgetSize
import com.athletedata.openAthleteMetrics.data.model.WidgetType
import com.athletedata.openAthleteMetrics.ui.components.DataPageDatePickerDialog
import com.athletedata.openAthleteMetrics.ui.components.DataPageTopBar
import com.athletedata.openAthleteMetrics.ui.components.horizontalDateSwipe
import com.athletedata.openAthleteMetrics.ui.dailydetail.DailyDetailSection
import com.athletedata.openAthleteMetrics.ui.nav.LocalBottomNavScrollBehavior
import com.athletedata.openAthleteMetrics.ui.nav.rememberBottomNavNestedScrollConnection
import com.athletedata.openAthleteMetrics.ui.overview.widgets.ActivitiesWidget
import com.athletedata.openAthleteMetrics.ui.overview.widgets.MetricWidget
import com.athletedata.openAthleteMetrics.ui.overview.widgets.StarredHabitsBarWidget
import com.athletedata.openAthleteMetrics.ui.overview.widgets.StarredLifestyleBarWidget
import com.athletedata.openAthleteMetrics.ui.overview.widgets.WeightWidget
import com.athletedata.openAthleteMetrics.ui.overview.widgets.WidgetCatalogueSheet
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToQuestions: (LocalDate) -> Unit,
    onNavigateToHabitsTab: (LocalDate) -> Unit,
    onOpenDailyDetailAtSection: (date: LocalDate, section: DailyDetailSection, metricKey: String?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = LocalBottomNavScrollBehavior.current
    val nestedScrollConnection = rememberBottomNavNestedScrollConnection(scrollBehavior)
    val snackbarHostState = remember { SnackbarHostState() }
    val today = remember { LocalDate.now() }
    var showDatePicker by remember { mutableStateOf(false) }
    var showWeightSheet by remember { mutableStateOf(false) }
    var showCatalogue by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is DashboardNavigationEvent.OpenDailyDetail ->
                    onOpenDailyDetailAtSection(event.date, event.section, event.metricKey)
                is DashboardNavigationEvent.OpenQuestions ->
                    onNavigateToQuestions(event.date)
                is DashboardNavigationEvent.OpenHabitsTab ->
                    onNavigateToHabitsTab(event.date)
                DashboardNavigationEvent.OpenWeightSheet ->
                    showWeightSheet = true
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "wiggle")
    val wiggleAngle by infiniteTransition.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 120, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wiggle_angle",
    )
    val effectiveWiggle = if (uiState.isEditMode) wiggleAngle else 0f

    // Local snapshot list — Calvin library mutates this directly during drag.
    // Syncing from uiState is blocked while a drag is active to avoid
    // mid-drag recompositions that confuse the library's item tracking.
    val localWidgets = remember { mutableStateListOf<WidgetLayout>() }
    val isDraggingActive = remember { mutableStateOf(false) }
    val currentHasSeederData = rememberUpdatedState(uiState.hasSeederData)

    LaunchedEffect(uiState.widgets) {
        if (!isDraggingActive.value) {
            localWidgets.clear()
            localWidgets.addAll(uiState.widgets)
        }
    }

    val lazyGridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
        // Subtract the offset of non-widget DSL items that precede the items() block.
        val offset = if (currentHasSeederData.value) 1 else 0
        val fromIdx = from.index - offset
        val toIdx = to.index - offset
        if (fromIdx in localWidgets.indices && toIdx in localWidgets.indices) {
            localWidgets.add(toIdx, localWidgets.removeAt(fromIdx))
        }
    }

    Scaffold(
        modifier = modifier.horizontalDateSwipe(
            enabled = !uiState.isEditMode,
            onDayForward = { viewModel.stepDate(true) },
            onDayBack    = { viewModel.stepDate(false) },
        ),
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            DataPageTopBar(
                date = uiState.date,
                onDateClick = { showDatePicker = true },
                centre = { Text("Dashboard", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    if (uiState.isEditMode) {
                        IconButton(onClick = { showCatalogue = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "Add widget",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = viewModel::toggleEditMode) {
                        Icon(
                            imageVector = if (uiState.isEditMode) Icons.Outlined.Check else Icons.Outlined.Edit,
                            contentDescription = if (uiState.isEditMode) "Done" else "Edit layout",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            state = lazyGridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp, ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.hasSeederData) {
                item(key = "seeder_banner", span = { GridItemSpan(2) }) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Text(
                            text = "Demo data — clear via Settings › Developer › Clear seeder data",
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            items(
                items = localWidgets,
                key = { it.id },
                span = { widget -> GridItemSpan(if (widget.size == WidgetSize.WIDE) 2 else 1) },
            ) { widget ->
                ReorderableItem(state = reorderState, key = widget.id, enabled = uiState.isEditMode) { isDraggingItem ->
                    WidgetHost(
                        widget = widget,
                        date = uiState.date,
                        isEditMode = uiState.isEditMode,
                        isDragging = isDraggingItem,
                        wiggleAngle = effectiveWiggle,
                        onTap = { viewModel.onWidgetTap(widget) },
                        onRemove = { viewModel.removeWidget(widget.id) },
                        onWeightEditTap = { showWeightSheet = true },
                        draggableHandleModifier = Modifier.draggableHandle(
                            onDragStarted = { isDraggingActive.value = true },
                            onDragStopped = {
                                isDraggingActive.value = false
                                viewModel.onReorderPersist(localWidgets.toList())
                            },
                        ),
                    )
                }
            }

            item(key = "bottom_spacer", span = { GridItemSpan(2) }) {
                Spacer(Modifier.height(80.dp))
            }
        }
    }

    if (showDatePicker) {
        DataPageDatePickerDialog(
            currentDate = uiState.date,
            today = today,
            onConfirm = { date ->
                viewModel.setDate(date)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }

    if (showWeightSheet) {
        DashboardWeightSheet(
            date = uiState.date,
            onSave = { kg, fat, notes ->
                viewModel.saveWeight(kg, fat, notes)
                showWeightSheet = false
            },
            onDismiss = { showWeightSheet = false },
            viewModel = viewModel,
        )
    }

    if (showCatalogue) {
        WidgetCatalogueSheet(
            placedWidgets = uiState.widgets,
            onAddWidget = { type, size ->
                viewModel.addWidget(type, size)
                showCatalogue = false
            },
            onDismiss = { showCatalogue = false },
        )
    }
}

// ── Widget host — dispatches to the right widget composable ──────────────────

@Composable
private fun WidgetHost(
    widget: com.athletedata.openAthleteMetrics.data.model.WidgetLayout,
    date: LocalDate,
    isEditMode: Boolean,
    isDragging: Boolean,
    wiggleAngle: Float,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    onWeightEditTap: () -> Unit,
    draggableHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val combinedModifier = modifier.then(draggableHandleModifier)
    when (widget.type) {
        is WidgetType.Hr, WidgetType.Hrv, WidgetType.Rhr,
        WidgetType.Sleep, WidgetType.Spo2, WidgetType.Steps ->
            MetricWidget(
                widgetId = widget.id,
                widgetType = widget.type,
                date = date,
                size = widget.size,
                isEditMode = isEditMode,
                wiggleAngle = wiggleAngle,
                onTap = onTap,
                onRemove = onRemove,
                modifier = combinedModifier,
            )
        is WidgetType.Weight ->
            WeightWidget(
                widgetId = widget.id,
                date = date,
                size = widget.size,
                isEditMode = isEditMode,
                wiggleAngle = wiggleAngle,
                onTap = onTap,
                onEditTap = onWeightEditTap,
                onRemove = onRemove,
                modifier = combinedModifier,
            )
        is WidgetType.StarredLifestyleBar ->
            StarredLifestyleBarWidget(
                widgetId = widget.id,
                date = date,
                size = widget.size,
                isEditMode = isEditMode,
                wiggleAngle = wiggleAngle,
                onTap = onTap,
                onRemove = onRemove,
                modifier = combinedModifier,
            )
        is WidgetType.StarredHabitsBar ->
            StarredHabitsBarWidget(
                widgetId = widget.id,
                date = date,
                size = widget.size,
                isEditMode = isEditMode,
                wiggleAngle = wiggleAngle,
                onTap = onTap,
                onRemove = onRemove,
                modifier = combinedModifier,
            )
        is WidgetType.Activities ->
            ActivitiesWidget(
                widgetId = widget.id,
                date = date,
                size = widget.size,
                isEditMode = isEditMode,
                wiggleAngle = wiggleAngle,
                onTap = onTap,
                onRemove = onRemove,
                modifier = combinedModifier,
            )
        else -> Unit
    }
}

// ── Weight bottom sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardWeightSheet(
    date: LocalDate,
    onSave: (weightKg: Double?, bodyFatPct: Double?, notes: String?) -> Unit,
    onDismiss: () -> Unit,
    viewModel: DashboardViewModel,
) {
    // Read the existing context to pre-fill the form
    val contextFlow = remember(date, viewModel) {
        viewModel.contextForDate(date)
    }
    val existingContext by contextFlow.collectAsStateWithLifecycle(initialValue = null)
    var weightKg by remember(existingContext) { mutableStateOf(existingContext?.weightKg?.let { "%.1f".format(it) } ?: "") }
    var bodyFatPct by remember(existingContext) { mutableStateOf(existingContext?.bodyFatPct?.let { "%.1f".format(it) } ?: "") }
    var notes by remember(existingContext) { mutableStateOf(existingContext?.notes ?: "") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
        ) {
            Text(
                text = "Log Weight",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 20.dp),
            )
            OutlinedTextField(
                value = weightKg,
                onValueChange = { weightKg = it },
                label = { Text("Weight (kg)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = bodyFatPct,
                onValueChange = { bodyFatPct = it },
                label = { Text("Body fat % (optional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    onSave(
                        weightKg.toDoubleOrNull(),
                        bodyFatPct.toDoubleOrNull(),
                        notes.takeIf { it.isNotBlank() },
                    )
                    scope.launch { sheetState.hide(); onDismiss() }
                },
                enabled = weightKg.toDoubleOrNull() != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
