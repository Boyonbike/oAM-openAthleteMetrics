package com.athletedata.openAthleteMetrics.ui.dailydetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.QuestionCategory
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import com.athletedata.openAthleteMetrics.ui.components.SectionHeader
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.athletedata.openAthleteMetrics.ui.nav.LocalBottomNavScrollBehavior
import com.athletedata.openAthleteMetrics.ui.nav.rememberBottomNavNestedScrollConnection
import java.time.format.DateTimeFormatter

private val DATE_HEADER_FORMATTER = DateTimeFormatter.ofPattern("EEEE d MMMM")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DailyDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var categorySheetActivity by remember { mutableStateOf<ActivityUiItem?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            val title = (uiState as? DailyDetailUiState.Success)
                ?.date?.format(DATE_HEADER_FORMATTER) ?: ""
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            DailyDetailUiState.Loading -> LoadingContent(innerPadding)
            is DailyDetailUiState.Error -> ErrorContent(state.message, innerPadding)
            is DailyDetailUiState.Success -> SuccessContent(
                state = state,
                innerPadding = innerPadding,
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
    onActivityTap: (ActivityUiItem) -> Unit,
) {
    val scrollBehavior = LocalBottomNavScrollBehavior.current
    val nestedScrollConnection = rememberBottomNavNestedScrollConnection(scrollBehavior)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .nestedScroll(nestedScrollConnection)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        WearableSection(summary = state.summary, sleep = state.sleep)
        ActivitiesSection(activities = state.activities, onActivityTap = onActivityTap)
        QuestionsSection(
            groups = state.questionGroups,
            isIll = state.context?.isIll == true,
            illnessNotes = state.context?.illnessNotes,
        )
        val ctx = state.context
        if (ctx != null && ctx.weightKg != null) {
            WeightSection(context = ctx)
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ── Section 2: Wearable metrics ───────────────────────────────────────────────

@Composable
private fun WearableSection(summary: DailySummary?, sleep: SleepUiItem?) {
    val hasData = (summary != null && (
        summary.avgHrBpm != null || summary.restingHrBpm != null ||
        summary.morningHrvMs != null || summary.avgSpo2Pct != null ||
        summary.steps != null || summary.sleepMinutes != null
    )) || sleep != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Device data")
        Spacer(Modifier.height(0.dp))
        if (hasData) {
            summary?.avgHrBpm?.let { MetricCard("Heart Rate", "%.0f".format(it), "bpm") }
            summary?.restingHrBpm?.let { MetricCard("Resting HR", "%.0f".format(it), "bpm") }
            summary?.morningHrvMs?.let { MetricCard("HRV", "%.0f".format(it), "ms") }
            summary?.avgSpo2Pct?.let { MetricCard("SpO2", "%.1f".format(it), "%") }
            summary?.steps?.let { MetricCard("Steps", it.toString(), "steps") }
            sleep?.let { SleepCard(it) }
        } else {
            EmptyStateText("No device data")
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, unit: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(value, style = MaterialTheme.typography.bodyLarge)
                if (unit.isNotEmpty()) {
                    Text(
                        unit,
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepCard(sleep: SleepUiItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Sleep", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sleep.formattedDuration, style = MaterialTheme.typography.bodyLarge)
            }
            if (sleep.stages != null) {
                val s = sleep.stages
                Text(
                    text = "Deep ${s.deepMinutes}m · Light ${s.lightMinutes}m · REM ${s.remMinutes}m",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Section 3: Activities ─────────────────────────────────────────────────────

@Composable
private fun ActivitiesSection(
    activities: List<ActivityUiItem>,
    onActivityTap: (ActivityUiItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Activities")
        Spacer(Modifier.height(0.dp))
        if (activities.isEmpty()) {
            EmptyStateText("No activities")
        } else {
            activities.forEach { activity ->
                ActivityCard(activity = activity, onTap = { onActivityTap(activity) })
            }
        }
    }
}

@Composable
private fun ActivityCard(activity: ActivityUiItem, onTap: () -> Unit) {
    Card(
        onClick = if (activity.userCategory == null) onTap else ({ }),
        modifier = Modifier.fillMaxWidth(),
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

private fun UserCategory.toDisplayLabel(): String = when (this) {
    UserCategory.TRAINING -> "Training"
    UserCategory.LIFE     -> "Life"
    UserCategory.RACE     -> "Race"
}

// ── Section 4: Questions & habits ────────────────────────────────────────────

@Composable
private fun QuestionsSection(
    groups: List<QuestionGroup>,
    isIll: Boolean,
    illnessNotes: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Check-in")
        Spacer(Modifier.height(0.dp))

        if (!isIll && groups.isEmpty()) {
            EmptyStateText("No check-in recorded")
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    if (isIll) {
                        Text(
                            text = "Illness noted",
                            style = TypographyTitle,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        if (!illnessNotes.isNullOrBlank()) {
                            Text(
                                text = illnessNotes,
                                style = TypographyMeta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        if (groups.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }

                    groups.forEachIndexed { groupIndex, group ->
                        if (groupIndex > 0) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        }
                        val label = when (group.category) {
                            QuestionCategory.LIFESTYLE -> "Lifestyle"
                            QuestionCategory.CUSTOM    -> "Custom"
                        }
                        Text(
                            text = label,
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        group.items.forEachIndexed { itemIndex, item ->
                            if (itemIndex > 0) Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = item.value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Section 5: Weight ─────────────────────────────────────────────────────────

@Composable
private fun WeightSection(context: DailyContext) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Weight")
        Spacer(Modifier.height(0.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Weight: ${"%.1f".format(context.weightKg ?: 0.0)} kg",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (context.bodyFatPct != null) {
                            Text(
                                text = "· ${"%.1f".format(context.bodyFatPct)}% body fat",
                                style = TypographyMeta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
                }
                if (!context.notes.isNullOrBlank()) {
                    Text(
                        text = context.notes,
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun EmptyStateText(text: String) {
    Text(
        text = text,
        style = TypographyMeta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

