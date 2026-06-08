package com.athletedata.openAthleteMetrics.ui.questions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athletedata.openAthleteMetrics.ui.components.LoadingState
import com.athletedata.openAthleteMetrics.ui.components.SectionHeader
import com.athletedata.openAthleteMetrics.ui.nav.AppTopBar
import com.athletedata.openAthleteMetrics.ui.nav.Page
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyQuestionsScreen(
    onNavigate: (Page) -> Unit,
    onSettingsClick: () -> Unit,
    onDevicesClick: () -> Unit,
    onNavigateToDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DailyQuestionsViewModel = hiltViewModel(),
) {
    val questionsState by viewModel.questionsState.collectAsStateWithLifecycle()
    val weightState by viewModel.weightState.collectAsStateWithLifecycle()
    var showWeightSheet by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val today = remember { LocalDate.now() }

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
                currentPage = Page.QUESTIONS,
                continuousIndex = Page.QUESTIONS.ordinal.toFloat(),
                subtitle = questionsState.date.format(DateTimeFormatter.ofPattern("EEE, d MMM")),
                onSubtitleClick = { showDatePicker = true },
                onSettingsClick = onSettingsClick,
                onDevicesClick = onDevicesClick,
                onNavigate = onNavigate,
            )
        },
    ) { innerPadding ->
        if (questionsState.isLoading) {
            LoadingState(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
            )
        } else Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            QuestionRow(
                label = "Fatigue",
                value = questionsState.fatigue,
                onSelect = viewModel::onFatigueChange,
            )
            QuestionRow(
                label = "Stress",
                value = questionsState.stress,
                onSelect = viewModel::onStressChange,
            )
            QuestionRow(
                label = "Motivation",
                value = questionsState.motivation,
                onSelect = viewModel::onMotivationChange,
            )
            QuestionRow(
                label = "Sleep Quality",
                value = questionsState.sleepQuality,
                onSelect = viewModel::onSleepQualityChange,
            )
            QuestionRow(
                label = "Performance Feel",
                value = questionsState.performanceFeel,
                onSelect = viewModel::onPerformanceFeelChange,
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            IllnessSection(
                isIll = questionsState.isIll,
                illnessNotes = questionsState.illnessNotes,
                onToggle = viewModel::onIllToggle,
                onNotesChange = viewModel::onIllnessNotesChange,
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            HabitsSection(
                habits = questionsState.habits,
                onHabitToggle = viewModel::onHabitToggle,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = viewModel::saveQuestions,
                enabled = !questionsState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (questionsState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Save")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val weightLabel = weightState.weightKg
                .toDoubleOrNull()
                ?.let { "Weight · ${"%.1f".format(it)} kg" }
                ?: "Log Weight"

            TextButton(
                onClick = { showWeightSheet = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(weightLabel)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = questionsState.date
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
                            onNavigateToDate(picked)
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
        WeightEntrySheet(
            viewModel = viewModel,
            onDismiss = { showWeightSheet = false },
        )
    }
}

@Composable
private fun QuestionRow(
    label: String,
    value: Int?,
    onSelect: (Int?) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        SectionHeader(label)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in 1..5) {
                val selected = value == n
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(if (selected) null else n) },
                    label = { Text(n.toString()) },
                )
            }
        }
    }
}

@Composable
private fun IllnessSection(
    isIll: Boolean,
    illnessNotes: String,
    onToggle: (Boolean) -> Unit,
    onNotesChange: (String) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "I am ill today",
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = isIll, onCheckedChange = onToggle)
        }
        AnimatedVisibility(visible = isIll) {
            OutlinedTextField(
                value = illnessNotes,
                onValueChange = onNotesChange,
                label = { Text("Illness notes") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                minLines = 2,
            )
        }
    }
}

@Composable
private fun HabitsSection(
    habits: Map<String, Boolean>,
    onHabitToggle: (String) -> Unit,
) {
    Column {
        SectionHeader("Habits")
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            for (key in DailyQuestionsViewModel.HABIT_KEYS) {
                val label = DailyQuestionsViewModel.HABIT_LABELS[key] ?: key
                val selected = habits[key] == true
                FilterChip(
                    selected = selected,
                    onClick = { onHabitToggle(key) },
                    label = { Text(label) },
                )
            }
        }
    }
}
