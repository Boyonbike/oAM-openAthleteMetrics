package com.athletedata.app.ui.questions

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyQuestionsScreen(
    modifier: Modifier = Modifier,
    onNavigateToSettings: () -> Unit = {},
    viewModel: DailyQuestionsViewModel = hiltViewModel(),
) {
    val questionsState by viewModel.questionsState.collectAsStateWithLifecycle()
    val weightState by viewModel.weightState.collectAsStateWithLifecycle()
    var showWeightSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = questionsState.date.format(
                            DateTimeFormatter.ofPattern("EEEE, d MMMM")
                        ),
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
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
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Text(
            text = "Habits",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
