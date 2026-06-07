package com.athletedata.app.ui.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.app.data.model.DailyContext
import com.athletedata.app.data.model.DailySummary
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.athletedata.app.ui.components.SectionHeader
import com.athletedata.app.ui.nav.AppTopBar
import com.athletedata.app.ui.nav.Page
import com.athletedata.app.ui.theme.TypographyMeta
import com.athletedata.app.ui.theme.TypographyTitle
import com.athletedata.app.ui.theme.TypographyValue
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onSettingsClick: () -> Unit,
    onDevicesClick: () -> Unit,
    onNavigateToQuestions: (LocalDate) -> Unit,
    onNavigateToHistory: (metricKey: String, dateStr: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showCheckinBanner by viewModel.showCheckinBanner.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val today = remember { LocalDate.now() }
    var showDatePicker by remember { mutableStateOf(false) }
    var showWeightSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                AppTopBar(
                    currentPage = Page.DASHBOARD,
                    onNavigate = { page ->
                        when (page) {
                            Page.HISTORY -> onNavigateToHistory("all", uiState.date.toString())
                            Page.QUESTIONS -> onNavigateToQuestions(uiState.date)
                            else -> {}
                        }
                    },
                    onSettingsClick = onSettingsClick,
                    onDevicesClick = onDevicesClick,
                )
                DateNavigationRow(
                    date = uiState.date,
                    today = today,
                    onPrevDay = { viewModel.setDate(uiState.date.minusDays(1)) },
                    onNextDay = { viewModel.setDate(uiState.date.plusDays(1)) },
                    onDateClick = { showDatePicker = true },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                MetricCardGrid(
                    summary = uiState.summaryForDate,
                    yesterdaySummary = uiState.yesterdaySummary,
                    sparklineData = uiState.sparklineData,
                    date = uiState.date,
                    onNavigateToHistory = onNavigateToHistory,
                )

                Spacer(Modifier.height(20.dp))

                SectionHeader("Lifestyle")
                Spacer(Modifier.height(8.dp))
                StarredLifestyleBar(
                    items = uiState.starredLifestyleItems,
                    date = uiState.date,
                    onNavigateToQuestions = onNavigateToQuestions,
                    onNavigateToHistory = onNavigateToHistory,
                )

                Spacer(Modifier.height(20.dp))

                SectionHeader("Habits")
                Spacer(Modifier.height(8.dp))
                HabitsRow(habits = uiState.habits, onToggle = viewModel::toggleHabit)

                Spacer(Modifier.height(20.dp))

                WeightRow(
                    weightKg = uiState.contextForDate?.weightKg,
                    onClick = { showWeightSheet = true },
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "No device connected",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))
            }

            AnimatedVisibility(
                visible = showCheckinBanner,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                CheckinReminderBanner(
                    onOpen = { onNavigateToQuestions(uiState.date) },
                    onDismiss = viewModel::dismissCheckinBanner,
                )
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = uiState.date
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
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { ms ->
                        val picked = Instant.ofEpochMilli(ms)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        viewModel.setDate(picked)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showWeightSheet) {
        DashboardWeightSheet(
            existingContext = uiState.contextForDate,
            onSave = { kg, fat, notes -> viewModel.saveWeight(kg, fat, notes) },
            onDismiss = { showWeightSheet = false },
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrevDay) {
            Icon(Icons.Outlined.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        TextButton(onClick = onDateClick) {
            Text(
                text = date.format(DATE_FORMATTER),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        IconButton(onClick = onNextDay, enabled = date < today) {
            Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

// ── Metric card grid (3 rows × 2 columns — non-lazy to allow outer scroll) ───

@Composable
private fun MetricCardGrid(
    summary: DailySummary?,
    yesterdaySummary: DailySummary?,
    sparklineData: Map<String, List<Float>>,
    date: LocalDate,
    onNavigateToHistory: (metricKey: String, dateStr: String) -> Unit,
) {
    val dateStr = date.toString()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                label = "Heart Rate",
                value = summary?.restingHrBpm?.let { "%.0f".format(it) },
                unit = "bpm",
                trend = computeTrend(summary?.restingHrBpm, yesterdaySummary?.restingHrBpm),
                sparkline = sparklineData["HR"] ?: emptyList(),
                onClick = { onNavigateToHistory("HR", dateStr) },
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "HRV",
                value = summary?.morningHrvMs?.let { "%.0f".format(it) },
                unit = "ms",
                trend = computeTrend(summary?.morningHrvMs, yesterdaySummary?.morningHrvMs),
                sparkline = sparklineData["HRV"] ?: emptyList(),
                onClick = { onNavigateToHistory("HRV", dateStr) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                label = "Resting HR",
                value = summary?.restingHrBpm?.let { "%.0f".format(it) },
                unit = "bpm",
                trend = computeTrend(summary?.restingHrBpm, yesterdaySummary?.restingHrBpm),
                sparkline = sparklineData["RHR"] ?: emptyList(),
                onClick = { onNavigateToHistory("RHR", dateStr) },
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "Sleep",
                value = summary?.sleepMinutes?.let { formatSleepMinutes(it) },
                unit = null,
                trend = computeTrend(
                    summary?.sleepMinutes?.toDouble(),
                    yesterdaySummary?.sleepMinutes?.toDouble(),
                ),
                sparkline = sparklineData["SLEEP_STAGE"] ?: emptyList(),
                onClick = { onNavigateToHistory("SLEEP_STAGE", dateStr) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                label = "SpO₂",
                value = summary?.avgSpo2Pct?.let { "%.0f".format(it) },
                unit = "%",
                trend = computeTrend(summary?.avgSpo2Pct, yesterdaySummary?.avgSpo2Pct),
                sparkline = sparklineData["SPO2"] ?: emptyList(),
                onClick = { onNavigateToHistory("SPO2", dateStr) },
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "Steps",
                value = summary?.steps?.toString(),
                unit = "steps",
                trend = computeTrend(
                    summary?.steps?.toDouble(),
                    yesterdaySummary?.steps?.toDouble(),
                ),
                sparkline = sparklineData["STEPS"] ?: emptyList(),
                onClick = { onNavigateToHistory("STEPS", dateStr) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Individual metric card ────────────────────────────────────────────────────

private data class TrendInfo(val label: String)

private fun computeTrend(today: Double?, yesterday: Double?): TrendInfo? {
    if (today == null || yesterday == null || yesterday == 0.0) return null
    val pct = (today - yesterday) / yesterday * 100.0
    val arrow = when {
        pct > 1.0 -> "↑"
        pct < -1.0 -> "↓"
        else -> "→"
    }
    return TrendInfo("$arrow ${"%.1f".format(kotlin.math.abs(pct))}%")
}

@Composable
private fun MetricCard(
    label: String,
    value: String?,
    unit: String?,
    trend: TrendInfo?,
    sparkline: List<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = TypographyTitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = value ?: "--",
                    style = TypographyValue,
                    modifier = Modifier.alignByBaseline(),
                )
                if (unit != null && value != null) {
                    Text(
                        text = unit,
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            // Always reserve one line for the trend to keep card heights consistent
            Text(
                text = trend?.label ?: "",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
            )
            if (sparkline.size >= 2) {
                Sparkline(
                    values = sparkline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                )
            } else {
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}

// ── Vico sparkline ────────────────────────────────────────────────────────────

@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier = Modifier) {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        modelProducer.runTransaction {
            lineSeries { series(values) }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(rememberLineCartesianLayer()),
        modelProducer = modelProducer,
        modifier = modifier,
    )
}

// ── Starred lifestyle bar ─────────────────────────────────────────────────────

@Composable
private fun StarredLifestyleBar(
    items: List<StarredLifestyleItem>,
    date: LocalDate,
    onNavigateToQuestions: (LocalDate) -> Unit,
    onNavigateToHistory: (metricKey: String, dateStr: String) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            text = "Star lifestyle questions to show them here",
            style = TypographyMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val dateStr = date.toString()
    Row(modifier = Modifier.fillMaxWidth()) {
        items.forEach { item ->
            val intValue = item.value?.toIntOrNull()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        if (intValue != null) {
                            onNavigateToHistory("Q_${item.questionId}", dateStr)
                        } else {
                            onNavigateToQuestions(date)
                        }
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (intValue != null) {
                    Row {
                        Text(
                            text = "$intValue",
                            style = TypographyValue,
                            modifier = Modifier.alignByBaseline(),
                        )
                        Text(
                            text = "/5",
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                } else {
                    Text(text = "--", style = TypographyValue)
                }
                Text(
                    text = item.name,
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

// ── Weight row ────────────────────────────────────────────────────────────────

@Composable
private fun WeightRow(weightKg: Double?, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClick) {
            Text(
                text = if (weightKg != null) "Weight · ${"%.1f".format(weightKg)} kg" else "Log weight",
                style = TypographyMeta,
                color = if (weightKg != null) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Weight bottom sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardWeightSheet(
    existingContext: DailyContext?,
    onSave: (weightKg: Double?, bodyFatPct: Double?, notes: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var weightKg by remember { mutableStateOf(existingContext?.weightKg?.let { "%.1f".format(it) } ?: "") }
    var bodyFatPct by remember { mutableStateOf(existingContext?.bodyFatPct?.let { "%.1f".format(it) } ?: "") }
    var notes by remember { mutableStateOf(existingContext?.notes ?: "") }
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

// ── Check-in reminder banner ──────────────────────────────────────────────────

@Composable
private fun CheckinReminderBanner(
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Daily check-in not completed",
                    style = TypographyTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Lifestyle · Habits",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpen) {
                Text(
                    text = "Open →",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss check-in reminder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatSleepMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}
