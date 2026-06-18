package com.athletedata.openAthleteMetrics.ui.history

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.model.DailySummary
import com.athletedata.openAthleteMetrics.data.model.QuestionDefinition
import com.athletedata.openAthleteMetrics.data.model.QuestionType
import com.athletedata.openAthleteMetrics.data.repository.DailyContextRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.QuestionRepository
import com.athletedata.openAthleteMetrics.data.repository.SettingsRepository
import com.athletedata.openAthleteMetrics.ui.theme.SeriesColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

// ── Public data models ────────────────────────────────────────────────────────

enum class RangeToggle(val days: Long, val label: String) {
    DAYS_7(7L, "7d"),
    DAYS_30(30L, "30d"),
    DAYS_90(90L, "90d"),
    DAYS_180(180L, "180d"),
    DAYS_365(365L, "1y"),
    ALL(Long.MAX_VALUE, "All"),
}

data class ChartEntry(val date: LocalDate, val value: Float)

data class SeriesData(
    val metricKey: String,
    val displayName: String,
    val entries: List<ChartEntry>,
    val isBooleanType: Boolean = false,
)

data class SummaryData(
    val value: Float?,
    val unit: String,
    val deltaVs30DayAvg: Float?,
)

data class MetricTile(
    val metricKey: String,
    val displayName: String,
    val summary: SummaryData,
    val questionType: QuestionType?,
    val seriesColor: Color,
)

data class OverlayOption(
    val metricKey: String,
    val displayName: String,
    val isBooleanType: Boolean = false,
)

data class OverlayOptionGroup(
    val section: String,
    val items: List<OverlayOption>,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val sessionState: HistorySessionState,
    private val summaryRepo: DailySummaryRepository,
    private val contextRepo: DailyContextRepository,
    private val questionRepo: QuestionRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _metricKeys = MutableStateFlow<List<String>>(emptyList())
    val metricKeys: StateFlow<List<String>> = _metricKeys.asStateFlow()

    private val _localDate = MutableStateFlow(sessionState.date)
    val localDate: StateFlow<LocalDate> = _localDate.asStateFlow()

    private val _rangeToggle = MutableStateFlow(RangeToggle.DAYS_30)
    val rangeToggle: StateFlow<RangeToggle> = _rangeToggle.asStateFlow()

    private val allQuestions: Flow<List<QuestionDefinition>> = combine(
        questionRepo.getLifestyleQuestions(),
        questionRepo.getCustomQuestions(),
    ) { ls, cs -> ls + cs }

    val titleName: StateFlow<String?> = combine(_metricKeys, allQuestions) { keys, questions ->
        keys.firstOrNull()?.let { key ->
            when {
                key.startsWith("q:") -> {
                    val qId = key.removePrefix("q:").toLongOrNull() ?: -1L
                    questions.find { it.id == qId }?.name ?: key
                }
                else -> metricDisplayName(key)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val seriesList: StateFlow<List<SeriesData>> = combine(
        _metricKeys, _rangeToggle, allQuestions,
    ) { keys, range, questions ->
        SeriesFetchParams(keys, LocalDate.now(), range, questions)
    }.flatMapLatest { params ->
        if (params.keys.isEmpty()) return@flatMapLatest flowOf(emptyList())
        val flows = params.keys.map { key ->
            val isBoolean = params.questions.find { "q:${it.id}" == key }?.type == QuestionType.BOOLEAN
            fetchSeriesFlow(key, params.date, params.range.days).map { entries ->
                SeriesData(
                    metricKey     = key,
                    displayName   = metricDisplayName(key, params.questions),
                    entries       = entries,
                    isBooleanType = isBoolean,
                )
            }
        }
        combine(flows) { it.toList() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val metricTiles: StateFlow<List<MetricTile>> = combine(
        seriesList, _localDate, allQuestions,
    ) { series, date, questions ->
        series.mapIndexed { idx, s ->
            MetricTile(
                metricKey    = s.metricKey,
                displayName  = s.displayName,
                summary      = computeSummary(s.metricKey, date, s.entries),
                questionType = questionTypeForKey(s.metricKey, questions),
                seriesColor  = SeriesColors[idx.coerceAtMost(SeriesColors.lastIndex)],
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val availableOverlayGroups: StateFlow<List<OverlayOptionGroup>> = combine(
        questionRepo.getLifestyleQuestions(),
        questionRepo.getCustomQuestions(),
        _metricKeys,
    ) { lifestyle, custom, activeKeys ->
        buildOverlayGroups(lifestyle, custom, activeKeys.toSet())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Mutations ─────────────────────────────────────────────────────────────

    fun moveCursor(date: LocalDate) {
        _localDate.value = date
        sessionState.date = date
    }

    fun setRange(range: RangeToggle) { _rangeToggle.value = range }

    fun addMetric(key: String) {
        val current = _metricKeys.value
        if (current.size < 5 && key !in current) _metricKeys.value = current + key
    }

    fun removeMetric(key: String) {
        _metricKeys.value = _metricKeys.value.filter { it != key }
        if (_metricKeys.value.isEmpty()) {
            sessionState.metricKey = null
            viewModelScope.launch { settingsRepository.setHistoryMetricKey(null) }
        }
    }

    fun setTarget(metricKey: String, dateString: String?) {
        _metricKeys.value = listOf(metricKey)
        sessionState.metricKey = metricKey
        val date = runCatching { LocalDate.parse(dateString ?: "") }.getOrDefault(LocalDate.now())
        _localDate.value = date
        sessionState.date = date
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun fetchSeriesFlow(key: String, endDate: LocalDate, days: Long): Flow<List<ChartEntry>> {
        val from = if (days == Long.MAX_VALUE) LocalDate.of(2000, 1, 1) else endDate.minusDays(days - 1)
        return when {
            key == "WEIGHT" -> contextRepo.getForRange(from, endDate).map { contexts ->
                contexts.mapNotNull { ctx ->
                    val w = ctx.weightKg?.toFloat() ?: return@mapNotNull null
                    ChartEntry(ctx.date, w)
                }
            }
            key.startsWith("q:") -> {
                val questionId = key.removePrefix("q:").toLongOrNull()
                    ?: return flowOf(emptyList())
                questionRepo.getResponsesForRange(questionId, from, endDate).map { responses ->
                    responses.mapNotNull { resp ->
                        val date = resp.date
                        val value = resp.value.toFloatOrNull()
                            ?: when {
                                resp.value.equals("true", ignoreCase = true) -> 1f
                                resp.value.equals("false", ignoreCase = true) -> 0f
                                else -> return@mapNotNull null
                            }
                        ChartEntry(date, value)
                    }
                }
            }
            else -> summaryRepo.getSummariesForRange(from, endDate).map { summaries ->
                summaries.mapNotNull { s ->
                    val v = extractWearableValue(key, s) ?: return@mapNotNull null
                    ChartEntry(s.date, v)
                }.sortedBy { it.date }
            }
        }
    }

    private fun computeSummary(key: String, date: LocalDate, entries: List<ChartEntry>): SummaryData {
        val cardValue = entries.find { it.date == date }?.value
            ?: entries.filter { !it.date.isAfter(date) }.maxByOrNull { it.date }?.value
        val window30 = entries.filter {
            !it.date.isAfter(date) && !it.date.isBefore(date.minusDays(29))
        }
        val avg30 = if (window30.isNotEmpty()) window30.map { it.value }.average().toFloat() else null
        val delta = if (cardValue != null && avg30 != null && avg30 != 0f) {
            (cardValue - avg30) / avg30 * 100f
        } else null
        return SummaryData(cardValue, metricUnit(key), delta)
    }

    private fun questionTypeForKey(key: String, questions: List<QuestionDefinition>): QuestionType? {
        if (!key.startsWith("q:")) return null
        val qId = key.removePrefix("q:").toLongOrNull() ?: return null
        return questions.find { it.id == qId }?.type
    }

    private fun buildOverlayGroups(
        lifestyle: List<QuestionDefinition>,
        custom: List<QuestionDefinition>,
        excluded: Set<String>,
    ): List<OverlayOptionGroup> {
        val wearables = WEARABLE_KEYS.filter { it !in excluded }
            .map { OverlayOption(it, metricDisplayName(it)) }
        val lifestyleOpts = lifestyle.filter { it.isVisible && "q:${it.id}" !in excluded }
            .map { OverlayOption("q:${it.id}", it.name, it.type == QuestionType.BOOLEAN) }
        val customOpts = custom.filter { it.isVisible && "q:${it.id}" !in excluded }
            .map { OverlayOption("q:${it.id}", it.name, it.type == QuestionType.BOOLEAN) }
        return buildList {
            if (wearables.isNotEmpty()) add(OverlayOptionGroup("Metrics", wearables))
            if (lifestyleOpts.isNotEmpty()) add(OverlayOptionGroup("Lifestyle", lifestyleOpts))
            if (customOpts.isNotEmpty()) add(OverlayOptionGroup("Custom", customOpts))
        }
    }

    private fun extractWearableValue(key: String, s: DailySummary): Float? = when (key) {
        "HR"    -> s.avgHrBpm?.toFloat()
        "HRV"   -> s.morningHrvMs?.toFloat() ?: s.avgHrvMs?.toFloat()
        "RHR"   -> s.restingHrBpm?.toFloat()
        "SPO2"  -> s.avgSpo2Pct?.toFloat()
        "STEPS" -> s.steps?.toFloat()
        "SLEEP" -> s.sleepMinutes?.let { it / 60f }
        else    -> null
    }

    companion object {
        val WEARABLE_KEYS = listOf("HR", "HRV", "RHR", "SPO2", "STEPS", "SLEEP", "WEIGHT")
    }
}

// ── Package-level helpers (shared with Screen) ────────────────────────────────

private data class SeriesFetchParams(
    val keys: List<String>,
    val date: LocalDate,
    val range: RangeToggle,
    val questions: List<QuestionDefinition>,
)

fun metricDisplayName(key: String, questions: List<QuestionDefinition> = emptyList()): String = when {
    key == "HR"     -> "Heart Rate"
    key == "HRV"    -> "HRV"
    key == "RHR"    -> "Resting HR"
    key == "SPO2"   -> "SpO₂"
    key == "STEPS"  -> "Steps"
    key == "SLEEP"  -> "Sleep"
    key == "WEIGHT" -> "Weight"
    key.startsWith("q:") -> {
        val id = key.removePrefix("q:").toLongOrNull()
        questions.find { it.id == id }?.name ?: key
    }
    else -> key
}

fun metricUnit(key: String): String = when (key) {
    "HR", "RHR" -> "bpm"
    "HRV"       -> "ms"
    "SPO2"      -> "%"
    "STEPS"     -> "steps"
    "SLEEP"     -> "hm"
    "WEIGHT"    -> "kg"
    else        -> ""
}

fun formatMetricValue(value: Float?, unit: String, questionType: QuestionType?): String {
    if (value == null) return "--"
    return when (questionType) {
        QuestionType.BOOLEAN -> if (value >= 0.5f) "Yes" else "No"
        QuestionType.SCALE   -> "${"%.0f".format(value)}/5"
        QuestionType.TEXT    -> "%.1f".format(value)
        null -> when (unit) {
            "hm"    -> {
                val totalMin = (value * 60).toInt()
                val h = totalMin / 60
                val m = totalMin % 60
                if (m == 0) "${h}hr" else "${h}hr ${m}min"
            }
            "steps" -> "%,.0f steps".format(value)
            else    -> "${"%.0f".format(value)}${if (unit.isNotEmpty()) " $unit" else ""}"
        }
    }
}
