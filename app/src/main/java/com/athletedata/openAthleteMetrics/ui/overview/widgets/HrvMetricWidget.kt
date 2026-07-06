package com.athletedata.openAthleteMetrics.ui.overview.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.BaselineRange
import com.athletedata.openAthleteMetrics.data.model.WidgetSize
import com.athletedata.openAthleteMetrics.data.repository.BaselineRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.HrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SleepRepository
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import com.athletedata.openAthleteMetrics.ui.theme.TypographyValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

// ── State ────────────────────────────────────────────────────────────────────

/**
 * Card-scoped mirror of DailyDetailUiState's HrvSectionState: same three-way
 * distinction (no sleep session / sleep session but no computed value / has data),
 * but readings are plain floats since this card's chart carries no time axis.
 */
sealed class HrvCardState {
    data class HasData(
        val headlineMs: Double,
        val baseline: BaselineRange?,
        val readings: List<Float>,
    ) : HrvCardState()

    data class InsufficientData(
        val readings: List<Float>,
    ) : HrvCardState()

    object NoSleepSession : HrvCardState()

    /** Initial stateIn seed before setup() has run; renders identically to NoSleepSession. */
    object Loading : HrvCardState()
}

// ── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class HrvMetricWidgetViewModel @Inject constructor(
    private val summaryRepo: DailySummaryRepository,
    private val sleepRepo: SleepRepository,
    private val baselineRepo: BaselineRepository,
    private val hrvRepo: HrvReadingRepository,
) : ViewModel() {

    private val _date = MutableStateFlow<LocalDate?>(null)

    fun setup(date: LocalDate) {
        _date.value = date
    }

    val uiState: StateFlow<HrvCardState> = _date
        .flatMapLatest { date ->
            if (date == null) return@flatMapLatest kotlinx.coroutines.flow.flowOf<HrvCardState>(HrvCardState.Loading)
            combine(
                sleepRepo.getSessionForDate(date),
                summaryRepo.getSummaryForDate(date),
                baselineRepo.observeRange(BaselineMetric.HRV),
            ) { session, summary, baseline -> Triple(session, summary, baseline) }
                .flatMapLatest { (session, summary, baseline) ->
                    if (session == null) {
                        kotlinx.coroutines.flow.flowOf<HrvCardState>(HrvCardState.NoSleepSession)
                    } else {
                        flow {
                            val startMs = session.sleepStartMs.toEpochMilli()
                            val endMs = session.sleepEndMs.toEpochMilli() + 1
                            val readings = hrvRepo.getReadingsInRangeOnce(startMs, endMs)
                                .map { it.rmssdMs.toFloat() }
                            val headline = summary?.overnightHrvMs
                            emit(
                                if (headline == null) HrvCardState.InsufficientData(readings)
                                else HrvCardState.HasData(headline, baseline, readings)
                            )
                        }
                    }
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HrvCardState.Loading)
}

// ── Formatting ───────────────────────────────────────────────────────────────

private data class HrvCardParts(val valueText: String?, val baselineText: String?, val readings: List<Float>)

private fun HrvCardState.toCardParts(): HrvCardParts = when (this) {
    is HrvCardState.HasData ->
        HrvCardParts("%.0f".format(headlineMs), formatHrvBaseline(headlineMs, baseline), readings)
    is HrvCardState.InsufficientData ->
        HrvCardParts(null, "Not enough data", readings)
    HrvCardState.NoSleepSession, HrvCardState.Loading ->
        HrvCardParts(null, null, emptyList())
}

/** Compact card-width baseline text, e.g. "42–58 ms · Above baseline". Mirrors the Daily
 *  Detail HrvSection note wording, condensed onto one line for the trend-slot's fixed height. */
private fun formatHrvBaseline(headlineMs: Double, baseline: BaselineRange?): String? {
    if (baseline == null) return null
    val note = when {
        headlineMs > baseline.upper -> " · Above baseline"
        headlineMs < baseline.lower -> " · Below baseline"
        else -> ""
    }
    return "%.0f–%.0f ms%s".format(baseline.lower, baseline.upper, note)
}

// ── Composable ───────────────────────────────────────────────────────────────

@Composable
fun HrvMetricWidget(
    widgetId: Long,
    date: LocalDate,
    size: WidgetSize,
    isEditMode: Boolean,
    wiggleAngle: Float,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HrvMetricWidgetViewModel = hiltViewModel(key = "hrv_metric_widget_$widgetId"),
) {
    LaunchedEffect(date) { viewModel.setup(date) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WidgetShell(isEditMode = isEditMode, wiggleAngle = wiggleAngle, onRemove = onRemove, modifier = modifier) {
        when (size) {
            WidgetSize.SMALL -> HrvMetricWidgetSmall(state = uiState, onClick = onTap)
            WidgetSize.WIDE  -> HrvMetricWidgetWide(state = uiState, onClick = onTap)
        }
    }
}

@Composable
private fun HrvMetricWidgetSmall(state: HrvCardState, onClick: () -> Unit) {
    val parts = state.toCardParts()
    Card(
        onClick = onClick,
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
            Text(text = "HRV", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = parts.valueText ?: "--",
                    style = TypographyValue,
                    modifier = Modifier.alignByBaseline(),
                )
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
                WidgetSparkline(
                    values = parts.readings,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                )
            } else {
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}

@Composable
private fun HrvMetricWidgetWide(state: HrvCardState, onClick: () -> Unit) {
    val parts = state.toCardParts()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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
                WidgetSparkline(
                    values = parts.readings,
                    modifier = Modifier.height(60.dp).fillMaxWidth(0.45f),
                )
            }
        }
    }
}
