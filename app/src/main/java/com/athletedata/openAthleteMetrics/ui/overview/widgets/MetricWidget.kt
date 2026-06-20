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
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.WidgetSize
import com.athletedata.openAthleteMetrics.data.model.WidgetType
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.ui.theme.TypographyMeta
import com.athletedata.openAthleteMetrics.ui.theme.TypographyTitle
import com.athletedata.openAthleteMetrics.ui.theme.TypographyValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

// ── ViewModel ─���───────────────────────────────────────────────────────────────

data class MetricWidgetUiState(
    val label: String = "",
    val value: String? = null,
    val unit: String? = null,
    val trend: WidgetTrendInfo? = null,
    val sparkline: List<Float> = emptyList(),
)

@HiltViewModel
class MetricWidgetViewModel @Inject constructor(
    private val summaryRepo: DailySummaryRepository,
) : ViewModel() {

    private val _date = MutableStateFlow(LocalDate.now())
    private val _type = MutableStateFlow<WidgetType?>(null)

    fun setup(type: WidgetType, date: LocalDate) {
        _type.value = type
        _date.value = date
    }

    fun setDate(date: LocalDate) {
        _date.value = date
    }

    val uiState: StateFlow<MetricWidgetUiState> = combine(_date, _type) { d, t -> d to t }
        .flatMapLatest { (date, type) ->
            if (type == null) return@flatMapLatest kotlinx.coroutines.flow.flowOf(MetricWidgetUiState())
            val sparklineFrom = date.minusDays(6)
            combine(
                summaryRepo.getSummaryForDate(date),
                summaryRepo.getSummaryForDate(date.minusDays(1)),
                summaryRepo.getSummariesForRange(sparklineFrom, date),
            ) { today, yesterday, range ->
                val sorted = range.sortedBy { it.date }
                MetricWidgetUiState(
                    label = labelFor(type),
                    value = valueFor(type, today),
                    unit = unitFor(type),
                    trend = computeWidgetTrend(rawValueFor(type, today), rawValueFor(type, yesterday)),
                    sparkline = sorted.mapNotNull { rawValueFor(type, it)?.toFloat() },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetricWidgetUiState())

    private fun labelFor(type: WidgetType): String = when (type) {
        WidgetType.Hr    -> "Heart Rate"
        WidgetType.Hrv   -> "HRV"
        WidgetType.Rhr   -> "Resting HR"
        WidgetType.Sleep -> "Sleep"
        WidgetType.Spo2  -> "SpO₂"
        WidgetType.Steps -> "Steps"
        else             -> ""
    }

    private fun valueFor(type: WidgetType, summary: DailySummary?): String? = when (type) {
        WidgetType.Hr    -> summary?.avgHrBpm?.let { "%.0f".format(it) }
        WidgetType.Hrv   -> summary?.morningHrvMs?.let { "%.0f".format(it) }
        WidgetType.Rhr   -> summary?.restingHrBpm?.let { "%.0f".format(it) }
        WidgetType.Sleep -> summary?.sleepMinutes?.let { formatWidgetSleep(it) }
        WidgetType.Spo2  -> summary?.avgSpo2Pct?.let { "%.0f".format(it) }
        WidgetType.Steps -> summary?.steps?.toString()
        else             -> null
    }

    private fun unitFor(type: WidgetType): String? = when (type) {
        WidgetType.Hr    -> "bpm"
        WidgetType.Hrv   -> "ms"
        WidgetType.Rhr   -> "bpm"
        WidgetType.Sleep -> null
        WidgetType.Spo2  -> "%"
        WidgetType.Steps -> "steps"
        else             -> null
    }

    private fun rawValueFor(type: WidgetType, summary: DailySummary?): Double? = when (type) {
        WidgetType.Hr    -> summary?.avgHrBpm
        WidgetType.Hrv   -> summary?.morningHrvMs
        WidgetType.Rhr   -> summary?.restingHrBpm
        WidgetType.Sleep -> summary?.sleepMinutes?.toDouble()
        WidgetType.Spo2  -> summary?.avgSpo2Pct
        WidgetType.Steps -> summary?.steps?.toDouble()
        else             -> null
    }
}

// ── Composable ───────���────────────────────────────────���───────────────────────

@Composable
fun MetricWidget(
    widgetId: Long,
    widgetType: WidgetType,
    date: LocalDate,
    size: WidgetSize,
    isEditMode: Boolean,
    wiggleAngle: Float,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MetricWidgetViewModel = hiltViewModel(key = "metric_widget_$widgetId"),
) {
    LaunchedEffect(widgetType, date) { viewModel.setup(widgetType, date) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WidgetShell(isEditMode = isEditMode, wiggleAngle = wiggleAngle, onRemove = onRemove, modifier = modifier) {
        when (size) {
            WidgetSize.SMALL -> MetricWidgetSmall(uiState = uiState, onClick = onTap)
            WidgetSize.WIDE  -> MetricWidgetWide(uiState = uiState, onClick = onTap)
        }
    }
}

@Composable
private fun MetricWidgetSmall(uiState: MetricWidgetUiState, onClick: () -> Unit) {
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
            Text(text = uiState.label, style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = uiState.value ?: "--",
                    style = TypographyValue,
                    modifier = Modifier.alignByBaseline(),
                )
                if (uiState.unit != null && uiState.value != null) {
                    Text(
                        text = uiState.unit,
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            Text(
                text = uiState.trend?.label ?: "",
                style = TypographyMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
            )
            if (uiState.sparkline.size >= 2) {
                WidgetSparkline(
                    values = uiState.sparkline,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                )
            } else {
                Spacer(Modifier.height(36.dp))
            }
        }
    }
}

@Composable
private fun MetricWidgetWide(uiState: MetricWidgetUiState, onClick: () -> Unit) {
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
                Text(text = uiState.label, style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = uiState.value ?: "--", style = TypographyValue, modifier = Modifier.alignByBaseline())
                    if (uiState.unit != null && uiState.value != null) {
                        Text(
                            text = uiState.unit,
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
                Text(
                    text = uiState.trend?.label ?: "",
                    style = TypographyMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 1,
                )
            }
            if (uiState.sparkline.size >= 2) {
                WidgetSparkline(
                    values = uiState.sparkline,
                    modifier = Modifier.height(60.dp).fillMaxWidth(0.45f),
                )
            }
        }
    }
}
