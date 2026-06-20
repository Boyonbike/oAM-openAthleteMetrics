package com.athletedata.openAthleteMetrics.ui.overview.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import com.athletedata.openAthleteMetrics.data.model.WidgetSize
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
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

// ── ViewModel ────────────────────────────────────────────────────────────────

data class WeightWidgetUiState(
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val notes: String? = null,
    val sparkline: List<Float> = emptyList(),
    val trendLabel: String? = null,
)

@HiltViewModel
class WeightWidgetViewModel @Inject constructor(
    private val contextRepo: DailyContextRepository,
) : ViewModel() {

    private val _date = MutableStateFlow(LocalDate.now())

    fun setDate(date: LocalDate) { _date.value = date }

    val uiState: StateFlow<WeightWidgetUiState> = _date.flatMapLatest { date ->
        val sparklineFrom = date.minusDays(6)
        combine(
            contextRepo.getForDate(date),
            contextRepo.getForRange(sparklineFrom, date),
        ) { today, range ->
            val sorted = range.sortedBy { it.date }
            val sparkline = sorted.mapNotNull { it.weightKg?.toFloat() }
            val trendLabel = computeWeightTrend(sorted, date)
            WeightWidgetUiState(
                weightKg = today?.weightKg,
                bodyFatPct = today?.bodyFatPct,
                notes = today?.notes,
                sparkline = sparkline,
                trendLabel = trendLabel,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightWidgetUiState())

    private fun computeWeightTrend(contexts: List<DailyContext>, date: LocalDate): String? {
        val today = contexts.find { it.date == date }?.weightKg ?: return null
        val yesterday = contexts.find { it.date == date.minusDays(1) }?.weightKg ?: return null
        if (yesterday == 0.0) return null
        val pct = (today - yesterday) / yesterday * 100.0
        val arrow = when {
            pct > 0.1  -> "↑"
            pct < -0.1 -> "↓"
            else       -> "→"
        }
        return "$arrow ${"%.1f".format(kotlin.math.abs(pct))}%"
    }
}

// ── Composable ───────────────────────────────────────────────────────────────

@Composable
fun WeightWidget(
    widgetId: Long,
    date: LocalDate,
    size: WidgetSize,
    isEditMode: Boolean,
    wiggleAngle: Float,
    onTap: () -> Unit,
    onEditTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WeightWidgetViewModel = hiltViewModel(key = "weight_widget_$widgetId"),
) {
    LaunchedEffect(date) { viewModel.setDate(date) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WidgetShell(isEditMode = isEditMode, wiggleAngle = wiggleAngle, onRemove = onRemove, modifier = modifier) {
        WeightWidgetContent(
            uiState = uiState,
            onTap = onTap,
            onEditTap = onEditTap,
        )
    }
}

@Composable
private fun WeightWidgetContent(
    uiState: WeightWidgetUiState,
    onTap: () -> Unit,
    onEditTap: () -> Unit,
) {
    Card(
        onClick = onTap,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "Weight", style = TypographyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = uiState.weightKg?.let { "%.1f".format(it) } ?: "--",
                        style = TypographyValue,
                        modifier = Modifier.alignByBaseline(),
                    )
                    if (uiState.weightKg != null) {
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
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (uiState.bodyFatPct != null) {
                    Text(
                        text = "${"%.1f".format(uiState.bodyFatPct)}% body fat",
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!uiState.notes.isNullOrBlank()) {
                    Text(
                        text = uiState.notes,
                        style = TypographyMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (uiState.sparkline.size >= 2) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WidgetSparkline(
                        values = uiState.sparkline,
                        modifier = Modifier.width(72.dp).height(28.dp),
                    )
                    if (uiState.trendLabel != null) {
                        Text(
                            text = uiState.trendLabel,
                            style = TypographyMeta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (uiState.weightKg != null) {
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
