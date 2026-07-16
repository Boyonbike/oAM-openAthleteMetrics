package com.athletedata.openAthleteMetrics.ui.overview

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition
import com.athletedata.openAthleteMetrics.ui.components.DataPageDatePickerDialog
import com.athletedata.openAthleteMetrics.ui.components.DataPageTopBar
import com.athletedata.openAthleteMetrics.ui.components.horizontalDateSwipe
import com.athletedata.openAthleteMetrics.ui.dailydetail.DailyDetailSection
import com.athletedata.openAthleteMetrics.ui.nav.LocalBottomNavScrollBehavior
import com.athletedata.openAthleteMetrics.ui.nav.rememberBottomNavNestedScrollConnection
import com.athletedata.openAthleteMetrics.ui.overview.grid.SkylineGridLayout
import com.athletedata.openAthleteMetrics.ui.overview.grid.dashboardDragHandle
import com.athletedata.openAthleteMetrics.ui.overview.grid.packSkyline
import com.athletedata.openAthleteMetrics.ui.overview.grid.rememberDraggableGridState
import com.athletedata.openAthleteMetrics.ui.overview.widgets.GenericWidget
import com.athletedata.openAthleteMetrics.ui.overview.widgets.WidgetCatalogueSheet
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import kotlinx.coroutines.launch
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
        initialValue = -0.6f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 120, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wiggle_angle",
    )

    // Local snapshot list - mutated directly during drag, synced from uiState.widgets
    // otherwise so a live drag isn't clobbered mid-gesture by a Room re-emission.
    val localWidgets = remember { mutableStateListOf<WidgetDefinition>() }
    var containerWidthPx by remember { mutableStateOf(0) }

    // Shared with the drag-hit-testing math below - SkylineGridLayout and
    // DraggableGridState must agree on the exact same geometry, so these are passed
    // explicitly to both rather than relying on matching default parameter values.
    val gridColumnGap = 12.dp
    val gridRowGap = 12.dp
    val gridRowUnitHeight = 150.dp

    val packResult = packSkyline(localWidgets)
    val dragState = rememberDraggableGridState(
        widgets = localWidgets,
        packResult = packResult,
        containerWidthPx = containerWidthPx,
        columnGap = gridColumnGap,
        rowGap = gridRowGap,
        rowUnitHeight = gridRowUnitHeight,
        onMove = { from, to ->
            localWidgets.add(to, localWidgets.removeAt(from))
            // packSkyline sorts by sequenceOrder, not list position, so the moved
            // widgets must be renumbered in place or the grid won't visually reflect
            // the reorder until the persisted order round-trips back from Room.
            localWidgets.forEachIndexed { index, widget ->
                if (widget.sequenceOrder != index) localWidgets[index] = widget.copy(sequenceOrder = index)
            }
        },
        onDragEnd = { viewModel.onReorderPersist(localWidgets.toList()) },
    )

    LaunchedEffect(uiState.isEditMode) {
        if (!uiState.isEditMode) {
            dragState.cancelIfDragging()
        }
    }

    LaunchedEffect(uiState.widgets) {
        if (!dragState.isDragging) {
            localWidgets.clear()
            localWidgets.addAll(uiState.widgets)
        }
    }

    Scaffold(
        modifier = modifier.horizontalDateSwipe(
            enabled = !uiState.isEditMode,
            onDayForward = { viewModel.stepDate(true) },
            onDayBack = { viewModel.stepDate(false) },
        ),
        contentWindowInsets = WindowInsets.navigationBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            DataPageTopBar(
                date = uiState.date,
                onDateClick = { showDatePicker = true },
                onDateLongClick = { viewModel.setDate(LocalDate.now()) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .nestedScroll(nestedScrollConnection),
        ) {
            if (uiState.hasSeederData) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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

            SkylineGridLayout(
                widgets = localWidgets,
                packResult = packResult,
                columnGap = gridColumnGap,
                rowGap = gridRowGap,
                rowUnitHeight = gridRowUnitHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .onSizeChanged { containerWidthPx = it.width },
            ) { widget ->
                // Idle edit mode is static; once a widget is picked up for reorder, only
                // the OTHER widgets shimmer - the dragged one glides smoothly with the
                // finger via dashboardDragHandle's own translation, with no rotation added.
                val widgetWiggle = if (dragState.isDragging && widget.id != dragState.draggedWidgetId) {
                    wiggleAngle
                } else {
                    0f
                }
                GenericWidget(
                    widgetId = widget.id,
                    definition = widget,
                    date = uiState.date,
                    isEditMode = uiState.isEditMode,
                    wiggleAngle = widgetWiggle,
                    onTap = { viewModel.onWidgetTap(widget) },
                    onRemove = { viewModel.removeWidget(widget.id) },
                    onWeightEditTap = { showWeightSheet = true },
                    modifier = Modifier.dashboardDragHandle(dragState, widget.id, uiState.isEditMode),
                )
            }

            Spacer(Modifier.height(80.dp))
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
            onAddWidget = { templateId, colSpan, rowSpan ->
                viewModel.addWidget(templateId, colSpan, rowSpan)
                showCatalogue = false
            },
            onDismiss = { showCatalogue = false },
        )
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
        Column(
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
