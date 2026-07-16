package com.athletedata.openAthleteMetrics.ui.overview.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.model.Activity
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.BaselineRange
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.QuestionType
import com.athletedata.openAthleteMetrics.data.model.SleepData
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import com.athletedata.openAthleteMetrics.data.model.WidgetDefinition
import com.athletedata.openAthleteMetrics.data.model.WidgetLayoutHint
import com.athletedata.openAthleteMetrics.data.model.WidgetTemplateId
import com.athletedata.openAthleteMetrics.data.model.toLabel
import com.athletedata.openAthleteMetrics.data.repository.ActivityRepository
import com.athletedata.openAthleteMetrics.data.repository.BaselineRepository
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.HrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.ResolvedValue
import com.athletedata.openAthleteMetrics.data.repository.SleepDetailProvider
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.data.repository.WidgetDataContext
import com.athletedata.openAthleteMetrics.data.repository.resolveDataSource
import com.athletedata.openAthleteMetrics.ui.dailydetail.Hypnogram
import com.athletedata.openAthleteMetrics.ui.dailydetail.HypnogramLegend
import com.athletedata.openAthleteMetrics.ui.dailydetail.STAGE_COLORS
import com.athletedata.openAthleteMetrics.ui.dailydetail.SleepDurationSection
import com.athletedata.openAthleteMetrics.ui.dailydetail.StageBox
import com.athletedata.openAthleteMetrics.ui.dailydetail.StageGrid
import com.athletedata.openAthleteMetrics.ui.dailydetail.formatDurationShort
import com.athletedata.openAthleteMetrics.ui.dailydetail.sleepAvgDurationLabel
import com.athletedata.openAthleteMetrics.ui.dailydetail.sleepAvgTimeRangeLabel
import com.athletedata.openAthleteMetrics.ui.dailydetail.sleepTimeRangeLabel
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import com.athletedata.openAthleteMetrics.ui.theme.TypographyValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

// ── State ─────────────────────────────────────────────────────────────────────

data class StarredLifestyleItem(val questionId: Long, val name: String, val value: String?)
data class CustomQuestionItem(val questionId: Long, val name: String, val type: QuestionType, val value: String?)

sealed class GenericWidgetUiState {
    object Loading : GenericWidgetUiState()

    data class SingleMetric(
        val label: String,
        val value: String?,
        val unit: String?,
        val trend: WidgetTrendInfo?,
        val sparkline: List<Float>,
    ) : GenericWidgetUiState()

    /**
     * Card-scoped mirror of DailyDetailUiState's HrvSectionState: same three-way
     * distinction (no sleep session / sleep session but no computed value / has data).
     * [GenericWidgetUiState.Loading] renders identically to [NoSleepSession] - it's only
     * the initial seed before setup() runs.
     */
    sealed class HrvMetric : GenericWidgetUiState() {
        data class HasData(val headlineMs: Double, val baseline: BaselineRange?, val readings: List<Float>) : HrvMetric()
        data class InsufficientData(val readings: List<Float>) : HrvMetric()
        object NoSleepSession : HrvMetric()
    }

    /**
     * Shared by all 6 sleep widgets — each just projects the parts of [SleepData] it needs.
     * Two-way (unlike [HrvMetric]'s three-way split) since [SleepDetailProvider.observeSleepData]
     * already collapses "no session and no summary" into a single null.
     */
    sealed class SleepMetric : GenericWidgetUiState() {
        data class HasSession(val data: SleepData) : SleepMetric()
        object NoSleepSession : SleepMetric()
    }

    data class Weight(
        val weightKg: Double?,
        val bodyFatPct: Double?,
        val notes: String?,
        val sparkline: List<Float>,
        val trendLabel: String?,
    ) : GenericWidgetUiState()

    data class StarredLifestyleBar(val items: List<StarredLifestyleItem>) : GenericWidgetUiState()
    data class CustomQuestionsBar(val items: List<CustomQuestionItem>) : GenericWidgetUiState()
    data class ActivitiesCard(val activities: List<Activity>) : GenericWidgetUiState()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class GenericWidgetViewModel @Inject constructor(
    private val summaryRepo: DailySummaryRepository,
    private val contextRepo: DailyContextRepository,
    private val activityRepo: ActivityRepository,
    private val questionRepo: QuestionRepository,
    private val sleepRepo: SleepRepository,
    private val sleepDetailProvider: SleepDetailProvider,
    private val baselineRepo: BaselineRepository,
    private val hrvRepo: HrvReadingRepository,
) : ViewModel() {

    private val _input = MutableStateFlow<Pair<LocalDate, WidgetDefinition>?>(null)

    fun setup(date: LocalDate, definition: WidgetDefinition) {
        _input.value = date to definition
    }

    val uiState: StateFlow<GenericWidgetUiState> = _input
        .flatMapLatest { input ->
            val (date, definition) = input ?: return@flatMapLatest flowOf<GenericWidgetUiState>(GenericWidgetUiState.Loading)
            when (definition.templateId) {
                WidgetTemplateId.HR, WidgetTemplateId.RHR,
                WidgetTemplateId.SPO2, WidgetTemplateId.STEPS -> singleMetricFlow(date, definition)
                WidgetTemplateId.HRV -> hrvFlow(date)
                WidgetTemplateId.SLEEP_DURATION, WidgetTemplateId.SLEEP_TIMINGS, WidgetTemplateId.SLEEP_STAGES,
                WidgetTemplateId.SLEEP_HYPNOGRAM, WidgetTemplateId.SLEEP_SUMMARY_SMALL,
                WidgetTemplateId.SLEEP_SUMMARY_LARGE -> sleepMetricFlow(date)
                WidgetTemplateId.WEIGHT -> weightFlow(date)
                WidgetTemplateId.STARRED_LIFESTYLE_BAR -> starredLifestyleFlow(date)
                WidgetTemplateId.CUSTOM_QUESTIONS_BAR -> customQuestionsFlow(date)
                WidgetTemplateId.ACTIVITIES -> activitiesFlow(date)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GenericWidgetUiState.Loading)

    private fun singleMetricFlow(date: LocalDate, definition: WidgetDefinition): Flow<GenericWidgetUiState> {
        val binding = definition.bindings.first()
        val sparklineFrom = date.minusDays(6)
        return combine(
            summaryRepo.getSummaryForDate(date),
            summaryRepo.getSummaryForDate(date.minusDays(1)),
            summaryRepo.getSummariesForRange(sparklineFrom, date),
        ) { today, yesterday, range ->
            val todayResolved = resolveDataSource(binding.key, summaryContext(date, today))
            val yesterdayResolved = resolveDataSource(binding.key, summaryContext(date.minusDays(1), yesterday))
            val sorted = range.sortedBy { it.date }
            GenericWidgetUiState.SingleMetric(
                label = binding.label,
                value = formatSingleMetricValue(binding.decimalPlaces, todayResolved),
                unit = binding.unitSuffix,
                trend = computeWidgetTrend(todayResolved.asDoubleOrNull(), yesterdayResolved.asDoubleOrNull()),
                sparkline = sorted.mapNotNull { s ->
                    resolveDataSource(binding.key, summaryContext(s.date, s)).asDoubleOrNull()?.toFloat()
                },
            )
        }
    }

    private fun summaryContext(date: LocalDate, summary: DailySummary?) = WidgetDataContext(
        date = date,
        summary = summary,
        context = null,
        primaryActivity = null,
        activityCountToday = 0,
    )

    private fun hrvFlow(date: LocalDate): Flow<GenericWidgetUiState> =
        combine(
            sleepRepo.getSessionForDate(date),
            summaryRepo.getSummaryForDate(date),
            baselineRepo.observeRange(BaselineMetric.HRV),
        ) { session, summary, baseline ->
            if (session == null) {
                GenericWidgetUiState.HrvMetric.NoSleepSession
            } else {
                val startMs = session.sleepStartMs.toEpochMilli()
                val endMs = session.sleepEndMs.toEpochMilli() + 1
                val readings = hrvRepo.getReadingsInRangeOnce(startMs, endMs).map { it.rmssdMs.toFloat() }
                val headline = summary?.overnightHrvMs
                if (headline == null) GenericWidgetUiState.HrvMetric.InsufficientData(readings)
                else GenericWidgetUiState.HrvMetric.HasData(headline, baseline, readings)
            }
        }

    private fun sleepMetricFlow(date: LocalDate): Flow<GenericWidgetUiState> =
        sleepDetailProvider.observeSleepData(date).map { data ->
            if (data == null) GenericWidgetUiState.SleepMetric.NoSleepSession
            else GenericWidgetUiState.SleepMetric.HasSession(data)
        }

    private fun weightFlow(date: LocalDate): Flow<GenericWidgetUiState> {
        val sparklineFrom = date.minusDays(6)
        return combine(
            contextRepo.getForDate(date),
            contextRepo.getForRange(sparklineFrom, date),
        ) { today, range ->
            val sorted = range.sortedBy { it.date }
            GenericWidgetUiState.Weight(
                weightKg = today?.weightKg,
                bodyFatPct = today?.bodyFatPct,
                notes = today?.notes,
                sparkline = sorted.mapNotNull { it.weightKg?.toFloat() },
                trendLabel = computeWeightTrend(sorted, date),
            )
        }
    }

    /** Own trend threshold (+-0.1%) - deliberately not the shared +-1.0% computeWidgetTrend. */
    private fun computeWeightTrend(contexts: List<DailyContext>, date: LocalDate): String? {
        val today = contexts.find { it.date == date }?.weightKg ?: return null
        val yesterday = contexts.find { it.date == date.minusDays(1) }?.weightKg ?: return null
        if (yesterday == 0.0) return null
        val pct = (today - yesterday) / yesterday * 100.0
        val arrow = when {
            pct > 0.1 -> "↑"
            pct < -0.1 -> "↓"
            else -> "→"
        }
        return "$arrow ${"%.1f".format(kotlin.math.abs(pct))}%"
    }

    private fun starredLifestyleFlow(date: LocalDate): Flow<GenericWidgetUiState> =
        combine(
            questionRepo.getStarredLifestyleQuestions(),
            questionRepo.getResponsesForDate(date),
        ) { questions, responses ->
            val responseMap = responses.associateBy { it.questionId }
            GenericWidgetUiState.StarredLifestyleBar(
                items = questions.map { q -> StarredLifestyleItem(q.id, q.name, responseMap[q.id]?.value) },
            )
        }

    /**
     * Deliberately queries ALL custom questions, not filtered by starred status - leftover
     * behavior from a previously removed feature, preserved as-is (see DataSourceKey's
     * STARRED_CUSTOM_QUESTIONS doc). Do not change to getStarredHabitsQuestions().
     */
    private fun customQuestionsFlow(date: LocalDate): Flow<GenericWidgetUiState> =
        combine(
            questionRepo.getCustomQuestions(),
            questionRepo.getResponsesForDate(date),
        ) { questions, responses ->
            val responseMap = responses.associateBy { it.questionId }
            GenericWidgetUiState.CustomQuestionsBar(
                items = questions.map { q -> CustomQuestionItem(q.id, q.name, q.type, responseMap[q.id]?.value) },
            )
        }

    private fun activitiesFlow(date: LocalDate): Flow<GenericWidgetUiState> =
        activityRepo.getActivitiesForDate(date).map { GenericWidgetUiState.ActivitiesCard(it) }
}

private fun ResolvedValue.asDoubleOrNull(): Double? = when (this) {
    is ResolvedValue.Numeric -> value
    is ResolvedValue.IntValue -> value.toDouble()
    else -> null
}

internal fun formatSingleMetricValue(decimalPlaces: Int, resolved: ResolvedValue): String? =
    when (resolved) {
        is ResolvedValue.Numeric -> "%.${decimalPlaces}f".format(resolved.value)
        is ResolvedValue.IntValue -> resolved.value.toString()
        is ResolvedValue.Text -> resolved.value
        else -> null
    }

private data class HrvCardParts(val valueText: String?, val baselineText: String?, val readings: List<Float>)

private fun GenericWidgetUiState?.toHrvCardParts(): HrvCardParts = when (this) {
    is GenericWidgetUiState.HrvMetric.HasData ->
        HrvCardParts("%.0f".format(headlineMs), formatHrvBaseline(headlineMs, baseline), readings)
    is GenericWidgetUiState.HrvMetric.InsufficientData ->
        HrvCardParts(null, "Not enough data", readings)
    else ->
        HrvCardParts(null, null, emptyList())
}

/** Compact card-width baseline text, e.g. "42-58 ms - Above baseline". */
private fun formatHrvBaseline(headlineMs: Double, baseline: BaselineRange?): String? {
    if (baseline == null) return null
    val note = when {
        headlineMs > baseline.upper -> " · Above baseline"
        headlineMs < baseline.lower -> " · Below baseline"
        else -> ""
    }
    return "%.0f–%.0f ms%s".format(baseline.lower, baseline.upper, note)
}

// ── Composable ────────────────────────────────────────────────────────────────

@Composable
fun GenericWidget(
    widgetId: Long,
    definition: WidgetDefinition,
    date: LocalDate,
    isEditMode: Boolean,
    wiggleAngle: Float,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    onWeightEditTap: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GenericWidgetViewModel = hiltViewModel(key = "generic_widget_$widgetId"),
) {
    LaunchedEffect(date, definition) { viewModel.setup(date, definition) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // While reordering, taps must not navigate or open sheets/dialogs - only the
    // drag handle (long-press) should be live.
    val guardedOnTap: () -> Unit = { if (!isEditMode) onTap() }
    val guardedOnWeightEditTap: () -> Unit = { if (!isEditMode) onWeightEditTap() }

    WidgetShell(isEditMode = isEditMode, wiggleAngle = wiggleAngle, onRemove = onRemove, modifier = modifier) {
        when (definition.layoutHint) {
            WidgetLayoutHint.SINGLE_METRIC -> SingleMetricBody(definition, uiState, guardedOnTap)
            WidgetLayoutHint.WEIGHT_CARD -> WeightCardBody(uiState, guardedOnTap, guardedOnWeightEditTap)
            WidgetLayoutHint.STARRED_BAR -> StarredLifestyleBarBody(uiState, guardedOnTap)
            WidgetLayoutHint.CUSTOM_QUESTIONS_BAR -> CustomQuestionsBarBody(uiState, guardedOnTap, isEditMode)
            WidgetLayoutHint.ACTIVITIES_CARD -> ActivitiesCardBody(uiState, guardedOnTap)
            WidgetLayoutHint.SLEEP_DURATION -> SleepDurationBody(uiState, guardedOnTap)
            WidgetLayoutHint.SLEEP_TIMINGS -> SleepTimingsBody(uiState, guardedOnTap)
            WidgetLayoutHint.SLEEP_STAGES -> SleepStagesBody(uiState, guardedOnTap)
            WidgetLayoutHint.SLEEP_HYPNOGRAM -> SleepHypnogramBody(uiState, guardedOnTap)
            WidgetLayoutHint.SLEEP_SUMMARY_SMALL -> SleepSummarySmallBody(uiState, guardedOnTap)
            WidgetLayoutHint.SLEEP_SUMMARY_LARGE -> SleepSummaryLargeBody(uiState, guardedOnTap)
        }
    }
}

// ── Body 1: single metric (Hr/Rhr/Sleep/Spo2/Steps/Hrv) ──────────────────────

@Composable
private fun SingleMetricBody(definition: WidgetDefinition, uiState: GenericWidgetUiState, onTap: () -> Unit) {
    if (definition.templateId == WidgetTemplateId.HRV) {
        when (definition.colSpan) {
            1 -> HrvMetricSmall(uiState, onTap)
            else -> HrvMetricWide(uiState, onTap)
        }
    } else {
        val metric = uiState as? GenericWidgetUiState.SingleMetric
        val label = metric?.label ?: definition.bindings.firstOrNull()?.label.orEmpty()
        when (definition.colSpan) {
            1 -> SingleMetricSmall(label, metric, onTap)
            else -> SingleMetricWide(label, metric, onTap)
        }
    }
}

@Composable
private fun SingleMetricSmall(label: String, state: GenericWidgetUiState.SingleMetric?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = label, style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = state?.value ?: "--", style = TypographyValue, modifier = Modifier.alignByBaseline())
                if (state?.unit != null && state.value != null) {
                    Text(
                        text = state.unit,
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            Text(
                text = state?.trend?.label ?: "",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
            )
            val sparkline = state?.sparkline.orEmpty()
            if (sparkline.size >= 2) {
                WidgetSparkline(values = sparkline, modifier = Modifier.fillMaxWidth().height(36.dp))
            } else {
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}

@Composable
private fun SingleMetricWide(label: String, state: GenericWidgetUiState.SingleMetric?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(text = label, style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = state?.value ?: "--", style = TypographyValue, modifier = Modifier.alignByBaseline())
                    if (state?.unit != null && state.value != null) {
                        Text(
                            text = state.unit,
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
                Text(
                    text = state?.trend?.label ?: "",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 1,
                )
            }
            val sparkline = state?.sparkline.orEmpty()
            if (sparkline.size >= 2) {
                WidgetSparkline(values = sparkline, modifier = Modifier.height(60.dp).fillMaxWidth(0.45f))
            }
        }
    }
}

// ── Body: HRV (bespoke, folded into the SINGLE_METRIC layout hint) ──────────

@Composable
private fun HrvMetricSmall(uiState: GenericWidgetUiState, onClick: () -> Unit) {
    val parts = uiState.toHrvCardParts()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "HRV", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = parts.valueText ?: "--", style = TypographyValue, modifier = Modifier.alignByBaseline())
                if (parts.valueText != null) {
                    Text(
                        text = "ms",
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            Text(
                text = parts.baselineText ?: "",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
            )
            if (parts.readings.size >= 2) {
                WidgetSparkline(values = parts.readings, modifier = Modifier.fillMaxWidth().height(36.dp))
            } else {
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}

@Composable
private fun HrvMetricWide(uiState: GenericWidgetUiState, onClick: () -> Unit) {
    val parts = uiState.toHrvCardParts()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(text = "HRV", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = parts.valueText ?: "--", style = TypographyValue, modifier = Modifier.alignByBaseline())
                    if (parts.valueText != null) {
                        Text(
                            text = "ms",
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
                Text(
                    text = parts.baselineText ?: "",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 1,
                )
            }
            if (parts.readings.size >= 2) {
                WidgetSparkline(values = parts.readings, modifier = Modifier.height(60.dp).fillMaxWidth(0.45f))
            }
        }
    }
}

// ── Body: Sleep (6 widgets, all sourced from SleepDetailProvider) ───────────

/** Value slightly bolder than the app's standard meta style, avg visibly smaller — per the
 *  chosen Sleep Summary Small design: value-primary, avg-secondary, not two equal-weight numbers. */
private val SleepSummaryValueStyle = TypographyMeta.copy(fontWeight = FontWeight.Bold)
private val SleepSummaryAvgStyle = TypographyMeta.copy(fontSize = 9.sp)

@Composable
private fun SleepStatCardBody(label: String, valueText: String?, avgText: String?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = label, style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = valueText ?: "--", style = TypographyValue)
            Text(
                text = avgText ?: "",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
            )
        }
    }
}

@Composable
private fun SleepDurationBody(uiState: GenericWidgetUiState, onClick: () -> Unit) {
    val data = (uiState as? GenericWidgetUiState.SleepMetric.HasSession)?.data
    SleepStatCardBody(
        label = "Sleep Duration",
        valueText = data?.formattedDuration,
        avgText = data?.let { sleepAvgDurationLabel(it.averages) },
        onClick = onClick,
    )
}

@Composable
private fun SleepTimingsBody(uiState: GenericWidgetUiState, onClick: () -> Unit) {
    val data = (uiState as? GenericWidgetUiState.SleepMetric.HasSession)?.data
    SleepStatCardBody(
        label = "Sleep Timings",
        valueText = data?.let { sleepTimeRangeLabel(it) },
        avgText = data?.let { sleepAvgTimeRangeLabel(it.averages) },
        onClick = onClick,
    )
}

@Composable
private fun SleepStagesBody(uiState: GenericWidgetUiState, onClick: () -> Unit) {
    val data = (uiState as? GenericWidgetUiState.SleepMetric.HasSession)?.data
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "Sleep Stages", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (data == null) {
                Text(text = "--", style = TypographyValue)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    StageBox(
                        color = STAGE_COLORS.getValue(SleepStage.DEEP), label = "Deep",
                        minutes = data.deepMinutes, pct = data.deepPct,
                        avgMinutes = data.averages.avgDeepMinutes, avgPct = data.averages.avgDeepPct,
                        modifier = Modifier.weight(1f),
                    )
                    StageBox(
                        color = STAGE_COLORS.getValue(SleepStage.LIGHT), label = "Light",
                        minutes = data.lightMinutes, pct = data.lightPct,
                        avgMinutes = data.averages.avgLightMinutes, avgPct = data.averages.avgLightPct,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    StageBox(
                        color = STAGE_COLORS.getValue(SleepStage.REM), label = "REM",
                        minutes = data.remMinutes, pct = data.remPct,
                        avgMinutes = data.averages.avgRemMinutes, avgPct = data.averages.avgRemPct,
                        modifier = Modifier.weight(1f),
                    )
                    StageBox(
                        color = STAGE_COLORS.getValue(SleepStage.AWAKE), label = "Awake",
                        minutes = data.awakeMinutes, pct = data.awakePct,
                        avgMinutes = data.averages.avgAwakeMinutes, avgPct = data.averages.avgAwakePct,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepHypnogramBody(uiState: GenericWidgetUiState, onClick: () -> Unit) {
    val data = (uiState as? GenericWidgetUiState.SleepMetric.HasSession)?.data
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "Hypnogram", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (data == null || data.hypnogramSegments.isEmpty()) {
                Text(text = "No sleep data", style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Hypnogram(
                    segments = data.hypnogramSegments,
                    onsetTimeLabel = data.onsetTimeLabel,
                    wakeTimeLabel = data.wakeTimeLabel,
                    modifier = Modifier.fillMaxWidth(),
                )
                HypnogramLegend()
            }
        }
    }
}

@Composable
private fun SleepSummaryCell(label: String, value: String?, avg: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = SleepSummaryAvgStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(text = value ?: "--", style = SleepSummaryValueStyle, maxLines = 1)
        Text(
            text = avg ?: "",
            style = SleepSummaryAvgStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Dense single-row layout (2x1 — same row height as a 1x1 cell): duration, timings, and all
 * 4 stages, each paired with its baseline average at a visibly smaller size than the headline
 * value. Chosen over dropping content to fit, per explicit sign-off on the tighter/denser option.
 */
@Composable
private fun SleepSummarySmallBody(uiState: GenericWidgetUiState, onClick: () -> Unit) {
    val data = (uiState as? GenericWidgetUiState.SleepMetric.HasSession)?.data
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        if (data == null) {
            Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.CenterStart) {
                Text(text = "No sleep data", style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SleepSummaryCell(
                    "Duration", data.formattedDuration, sleepAvgDurationLabel(data.averages),
                    Modifier.weight(1.2f),
                )
                SleepSummaryCell(
                    "Timings", sleepTimeRangeLabel(data), sleepAvgTimeRangeLabel(data.averages),
                    Modifier.weight(1.4f),
                )
                SleepSummaryCell(
                    "Deep", data.deepMinutes?.let { formatDurationShort(it) },
                    data.averages.avgDeepMinutes?.let { formatDurationShort(it) }, Modifier.weight(0.8f),
                )
                SleepSummaryCell(
                    "Light", data.lightMinutes?.let { formatDurationShort(it) },
                    data.averages.avgLightMinutes?.let { formatDurationShort(it) }, Modifier.weight(0.8f),
                )
                SleepSummaryCell(
                    "REM", data.remMinutes?.let { formatDurationShort(it) },
                    data.averages.avgRemMinutes?.let { formatDurationShort(it) }, Modifier.weight(0.8f),
                )
                SleepSummaryCell(
                    "Awake", data.awakeMinutes?.let { formatDurationShort(it) },
                    data.averages.avgAwakeMinutes?.let { formatDurationShort(it) }, Modifier.weight(0.8f),
                )
            }
        }
    }
}

/** 2x4 — Sleep Summary Small's content plus the Hypnogram, stacked (SleepTile's full expanded
 *  content minus the 6-night duration-history sparkline, which stays out of scope). */
@Composable
private fun SleepSummaryLargeBody(uiState: GenericWidgetUiState, onClick: () -> Unit) {
    val data = (uiState as? GenericWidgetUiState.SleepMetric.HasSession)?.data
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "Sleep Summary", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (data == null) {
                Text(text = "No sleep data", style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SleepDurationSection(data)
                val hasStages = data.deepMinutes != null || data.lightMinutes != null ||
                    data.remMinutes != null || data.awakeMinutes != null
                if (hasStages) {
                    StageGrid(data)
                }
                if (data.hypnogramSegments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Hypnogram", style = TypographyTitle)
                        Hypnogram(
                            segments = data.hypnogramSegments,
                            onsetTimeLabel = data.onsetTimeLabel,
                            wakeTimeLabel = data.wakeTimeLabel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HypnogramLegend()
                    }
                }
            }
        }
    }
}

// ── Body: Weight ──────────────────────────────────────────────────────────────

@Composable
private fun WeightCardBody(uiState: GenericWidgetUiState, onTap: () -> Unit, onEditTap: () -> Unit) {
    val state = uiState as? GenericWidgetUiState.Weight
    Card(
        onClick = onTap,
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "Weight", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state?.weightKg?.let { "%.1f".format(it) } ?: "--",
                        style = TypographyValue,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (state?.weightKg != null) {
                        Text(
                            text = "kg",
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (state?.bodyFatPct != null) {
                    Text(
                        text = "${"%.1f".format(state.bodyFatPct)}% body fat",
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!state?.notes.isNullOrBlank()) {
                    Text(
                        text = state?.notes.orEmpty(),
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val sparkline = state?.sparkline.orEmpty()
            if (sparkline.size >= 2) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WidgetSparkline(values = sparkline, modifier = Modifier.width(72.dp).height(28.dp))
                    if (state?.trendLabel != null) {
                        Text(
                            text = state.trendLabel,
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (state?.weightKg != null) {
                IconButton(onClick = onEditTap) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit weight",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Body: Starred Lifestyle bar ───────────────────────────────────────────────

@Composable
private fun StarredLifestyleBarBody(uiState: GenericWidgetUiState, onTap: () -> Unit) {
    val items = (uiState as? GenericWidgetUiState.StarredLifestyleBar)?.items.orEmpty()
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Lifestyle", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (items.isEmpty()) {
                Text(
                    text = "Star lifestyle questions to show them here",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onTap() },
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    items.forEach { item ->
                        val intValue = item.value?.toIntOrNull()
                        Column(
                            modifier = Modifier.weight(1f).clickable { onTap() }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (intValue != null) {
                                Row {
                                    Text(text = "$intValue", style = TypographyValue, modifier = Modifier.alignByBaseline())
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
        }
    }
}

// ── Body: Custom Questions bar (renamed from Starred Habits bar) ─────────────

@Composable
private fun CustomQuestionsBarBody(uiState: GenericWidgetUiState, onTap: () -> Unit, isEditMode: Boolean) {
    val items = (uiState as? GenericWidgetUiState.CustomQuestionsBar)?.items.orEmpty()
    var expandedTextItem by remember { mutableStateOf<CustomQuestionItem?>(null) }

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Habits", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (items.isEmpty()) {
                Text(
                    text = "No habits yet — add them in the Habits tab",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(enabled = !isEditMode) { onTap() },
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    items.forEach { item ->
                        val isAnswered = item.value != null
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !isEditMode) {
                                    when {
                                        item.type == QuestionType.TEXT && isAnswered -> expandedTextItem = item
                                        else -> onTap()
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            when (item.type) {
                                QuestionType.SCALE -> {
                                    val intValue = item.value?.toIntOrNull()
                                    if (intValue != null) {
                                        Row {
                                            Text(text = "$intValue", style = TypographyValue, modifier = Modifier.alignByBaseline())
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
                                }
                                QuestionType.BOOLEAN -> {
                                    Text(
                                        text = when (item.value) {
                                            "1" -> "Yes"
                                            "0" -> "No"
                                            else -> "--"
                                        },
                                        style = TypographyValue,
                                    )
                                }
                                QuestionType.TEXT -> {
                                    if (item.value != null) {
                                        Text(
                                            text = item.value,
                                            style = TypographyMeta,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } else {
                                        Text(text = "--", style = TypographyValue)
                                    }
                                }
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
        }
    }

    expandedTextItem?.let { item ->
        Dialog(onDismissRequest = { expandedTextItem = null }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(text = item.name, style = TypographyTitle, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.padding(6.dp))
                    Text(text = item.value ?: "", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.padding(8.dp))
                    TextButton(onClick = { expandedTextItem = null }, modifier = Modifier.align(Alignment.End)) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

// ── Body: Activities ──────────────────────────────────────────────────────────

@Composable
private fun ActivitiesCardBody(uiState: GenericWidgetUiState, onTap: () -> Unit) {
    val activities = (uiState as? GenericWidgetUiState.ActivitiesCard)?.activities.orEmpty()

    if (activities.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = "Activities", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = "No activities recorded", style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        val first = activities.first()
        val displayName = first.userCategory?.toLabel() ?: first.deviceName
        val duration = formatWidgetDuration(first.durationMinutes)
        Card(
            onClick = onTap,
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = "Activities", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(text = duration, style = TypographyMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (first.userCategory != null) {
                        ActivityCategoryChip(first.userCategory)
                    }
                }
                if (activities.size > 1) {
                    Text(
                        text = "+${activities.size - 1} more",
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityCategoryChip(category: UserCategory) {
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = category.toLabel(),
            style = TypographyMeta,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
