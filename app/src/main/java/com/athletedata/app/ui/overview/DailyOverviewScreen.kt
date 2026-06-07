package com.athletedata.app.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.app.data.model.DailyContext
import com.athletedata.app.data.model.DailySummary
import com.athletedata.app.data.model.MetricType
import com.athletedata.app.ui.components.LoadingState
import com.athletedata.app.ui.components.MetricChip
import com.athletedata.app.ui.components.ScreenState
import com.athletedata.app.ui.components.SectionHeader
import com.athletedata.app.ui.components.StatCard
import com.athletedata.app.ui.nav.AppTopBar
import com.athletedata.app.ui.nav.Page
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val INITIAL_PAGE = 10_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyOverviewScreen(
    onNavigate: (Page) -> Unit,
    onSettingsClick: () -> Unit,
    onDevicesClick: () -> Unit,
    onNavigateToQuestions: (LocalDate) -> Unit,
    onNavigateToMetricDetail: (MetricType) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }

    val pagerState = rememberPagerState(initialPage = INITIAL_PAGE) { INITIAL_PAGE + 1 }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDatePicker by remember { mutableStateOf(false) }
    var showWeightSheet by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            viewModel.onDateChange(today.minusDays((INITIAL_PAGE - page).toLong()))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                currentPage = Page.DASHBOARD,
                subtitle = uiState.selectedDate.format(DateTimeFormatter.ofPattern("EEE, d MMM")),
                onSubtitleClick = { showDatePicker = true },
                onSettingsClick = onSettingsClick,
                onDevicesClick = onDevicesClick,
                onNavigate = onNavigate,
            )
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { page ->
            val pageDate = today.minusDays((INITIAL_PAGE - page).toLong())
            val isActive = pageDate == uiState.selectedDate

            OverviewPage(
                date = pageDate,
                summaryState = if (isActive) uiState.summaryState else ScreenState.Empty,
                contextState = if (isActive) uiState.contextState else ScreenState.Empty,
                habits = if (isActive) uiState.habits else OverviewViewModel.HABIT_KEYS.associateWith { false },
                hasSeederData = isActive && uiState.hasSeederData,
                onMetricCardTap = onNavigateToMetricDetail,
                onWeightCardTap = { showWeightSheet = true },
                onQuestionsRowTap = { onNavigateToQuestions(pageDate) },
                onHabitToggle = viewModel::onHabitToggle,
                onWeightButtonTap = { showWeightSheet = true },
            )
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = uiState.selectedDate
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
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { ms ->
                            val picked = Instant.ofEpochMilli(ms)
                                .atZone(ZoneOffset.UTC).toLocalDate()
                            val daysBack = ChronoUnit.DAYS.between(picked, today)
                            val targetPage = (INITIAL_PAGE - daysBack).toInt().coerceAtLeast(0)
                            scope.launch { pagerState.animateScrollToPage(targetPage) }
                        }
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showWeightSheet) {
        OverviewWeightSheet(
            existingContext = (uiState.contextState as? ScreenState.Success)?.data,
            onSave = { kg, fat, notes -> viewModel.saveWeight(kg, fat, notes) },
            onDismiss = { showWeightSheet = false },
        )
    }
}

// ── Page content ──────────────────────────────────────────────────────────────

@Composable
private fun OverviewPage(
    date: LocalDate,
    summaryState: ScreenState<DailySummary>,
    contextState: ScreenState<DailyContext>,
    habits: Map<String, Boolean>,
    hasSeederData: Boolean,
    onMetricCardTap: (MetricType) -> Unit,
    onWeightCardTap: () -> Unit,
    onQuestionsRowTap: () -> Unit,
    onHabitToggle: (String) -> Unit,
    onWeightButtonTap: () -> Unit,
) {
    val summary = (summaryState as? ScreenState.Success)?.data
    val context = (contextState as? ScreenState.Success)?.data

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (hasSeederData) {
            SeederBanner()
            Spacer(Modifier.height(8.dp))
        }

        when (summaryState) {
            ScreenState.Loading -> LoadingState(modifier = Modifier.height(300.dp))
            else -> MetricCardGrid(
                summary = summary,
                context = context,
                onMetricTap = onMetricCardTap,
                onWeightTap = onWeightCardTap,
            )
        }

        Spacer(Modifier.height(16.dp))

        QuestionsStrip(context = context, onClick = onQuestionsRowTap)

        Spacer(Modifier.height(12.dp))

        SectionHeader("Habits")
        Spacer(Modifier.height(8.dp))
        HabitsRow(habits = habits, onToggle = onHabitToggle)

        Spacer(Modifier.height(24.dp))

        val weightLabel = context?.weightKg?.let { "Weight · ${"%.1f".format(it)} kg" } ?: "Log weight"
        TextButton(
            onClick = onWeightButtonTap,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(weightLabel) }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Seeder banner ─────────────────────────────────────────────────────────────

@Composable
private fun SeederBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "SEEDER DATA",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

// ── Metric card grid ──────────────────────────────────────────────────────────

@Composable
private fun MetricCardGrid(
    summary: DailySummary?,
    context: DailyContext?,
    onMetricTap: (MetricType) -> Unit,
    onWeightTap: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                label = "Heart Rate",
                value = summary?.restingHrBpm?.let { "%.0f".format(it) },
                unit = "bpm",
                onClick = { onMetricTap(MetricType.HR) },
                modifier = Modifier.weight(1f).height(100.dp),
            )
            StatCard(
                label = "HRV",
                value = summary?.morningHrvMs?.let { "%.0f".format(it) },
                unit = "ms",
                onClick = { onMetricTap(MetricType.HRV) },
                modifier = Modifier.weight(1f).height(100.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                label = "SpO₂",
                value = summary?.avgSpo2Pct?.let { "%.0f".format(it) },
                unit = "%",
                onClick = { onMetricTap(MetricType.SPO2) },
                modifier = Modifier.weight(1f).height(100.dp),
            )
            StepsCard(
                steps = summary?.steps,
                onClick = { onMetricTap(MetricType.STEPS) },
                modifier = Modifier.weight(1f).height(100.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                label = "Sleep",
                value = summary?.sleepMinutes?.let { formatSleep(it) },
                unit = null,
                onClick = { onMetricTap(MetricType.SLEEP_STAGE) },
                modifier = Modifier.weight(1f).height(100.dp),
            )
            StatCard(
                label = "Weight",
                value = context?.weightKg?.let { "%.1f".format(it) },
                unit = "kg",
                onClick = onWeightTap,
                modifier = Modifier.weight(1f).height(100.dp),
            )
        }
    }
}

@Composable
private fun StepsCard(
    steps: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Steps",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = steps?.toString() ?: "--",
                style = MaterialTheme.typography.headlineSmall,
            )
            LinearProgressIndicator(
                progress = { (steps ?: 0).toFloat() / 10_000f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
    }
}

private fun formatSleep(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}

// ── Questions strip ───────────────────────────────────────────────────────────

@Composable
private fun QuestionsStrip(context: DailyContext?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            MetricChip("Fatigue", context?.fatigue?.toString())
            MetricChip("Stress", context?.stress?.toString())
            MetricChip("Motivation", context?.motivation?.toString())
            MetricChip("Sleep Q", context?.sleepQuality?.toString())
            MetricChip("Perf", context?.performanceFeel?.toString())
        }
    }
}

// ── Habits row ────────────────────────────────────────────────────────────────

@Composable
private fun HabitsRow(habits: Map<String, Boolean>, onToggle: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        OverviewViewModel.HABIT_KEYS.forEach { key ->
            FilterChip(
                selected = habits[key] == true,
                onClick = { onToggle(key) },
                label = { Text(OverviewViewModel.HABIT_LABELS[key] ?: key) },
            )
        }
    }
}

// ── Weight sheet ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverviewWeightSheet(
    existingContext: DailyContext?,
    onSave: (weightKg: Double?, bodyFatPct: Double?, notes: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var weightKg by remember { mutableStateOf(existingContext?.weightKg?.let { "%.1f".format(it) } ?: "") }
    var bodyFatPct by remember { mutableStateOf(existingContext?.bodyFatPct?.let { "%.1f".format(it) } ?: "") }
    var notes by remember { mutableStateOf(existingContext?.notes ?: "") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
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
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                },
                enabled = weightKg.toDoubleOrNull() != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
