package com.athletedata.openAthleteMetrics.ui.metric

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.repository.ActiveCalorieReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.BloodPressureReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.GlucoseReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.HrvReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.RespirationReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SkinTempReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.SpO2ReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.StepsReadingRepository
import com.athletedata.openAthleteMetrics.data.repository.DailySummaryRepository
import com.athletedata.openAthleteMetrics.data.repository.TotalCalorieReadingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@HiltViewModel(assistedFactory = MetricDetailViewModel.Factory::class)
class MetricDetailViewModel @AssistedInject constructor(
    @Assisted val metricType: MetricType,
    private val hrRepo: HrReadingRepository,
    private val hrvRepo: HrvReadingRepository,
    private val spo2Repo: SpO2ReadingRepository,
    private val respirationRepo: RespirationReadingRepository,
    private val skinTempRepo: SkinTempReadingRepository,
    private val stepsRepo: StepsReadingRepository,
    private val glucoseRepo: GlucoseReadingRepository,
    private val activeCalorieRepo: ActiveCalorieReadingRepository,
    private val totalCalorieRepo: TotalCalorieReadingRepository,
    private val bloodPressureRepo: BloodPressureReadingRepository,
    private val dailySummaryRepo: DailySummaryRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(metricType: MetricType): MetricDetailViewModel
    }

    private val _selectedRange = MutableStateFlow(TimeRange.DAYS_7)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<MetricDetailUiState> = _selectedRange
        .flatMapLatest { range -> loadData(range) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MetricDetailUiState.Loading,
        )

    fun setRange(range: TimeRange) {
        _selectedRange.value = range
    }

    private fun loadData(range: TimeRange) = flow<MetricDetailUiState> {
        emit(MetricDetailUiState.Loading)

        val emptyMessage = unsupportedMessage(metricType)
        if (emptyMessage != null) {
            emit(MetricDetailUiState.Empty(emptyMessage))
            return@flow
        }

        val today = LocalDate.now()
        val startMs = today.minusDays(range.days.toLong())
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMs = today.plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val dailyValues: Map<LocalDate, Double> = fetchAndAggregate(metricType, startMs, endMs)

        if (dailyValues.isEmpty()) {
            emit(MetricDetailUiState.Empty("No data available for the selected period."))
            return@flow
        }

        val sortedEntries = dailyValues.entries.sortedBy { it.key }
        val values = sortedEntries.map { it.value }

        val chartData = sortedEntries.map { (date, value) ->
            ChartPoint(
                timestampMs = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                value = value,
            )
        }
        val historyRows = sortedEntries.reversed().map { (date, value) -> HistoryRow(date, value) }
        val stats = MetricStats(
            min = values.minOrNull(),
            max = values.maxOrNull(),
            average = values.average(),
        )

        emit(
            MetricDetailUiState.Success(
                metricType = metricType,
                selectedRange = range,
                chartData = chartData,
                stats = stats,
                historyRows = historyRows,
            )
        )
    }

    private suspend fun fetchAndAggregate(
        type: MetricType,
        startMs: Long,
        endMs: Long,
    ): Map<LocalDate, Double> {
        fun Instant.localDate(): LocalDate = atZone(ZoneId.systemDefault()).toLocalDate()

        return when (type) {
            MetricType.HR -> hrRepo.getReadingsInRangeOnce(startMs, endMs)
                .groupBy { it.recordedAt.localDate() }
                .mapValues { (_, es) -> es.map { it.bpm.toDouble() }.average() }

            MetricType.HRV -> hrvRepo.getReadingsInRangeOnce(startMs, endMs)
                .groupBy { it.recordedAt.localDate() }
                .mapValues { (_, es) -> es.map { it.rmssdMs }.average() }

            MetricType.SPO2 -> spo2Repo.getReadingsInRangeOnce(startMs, endMs)
                .groupBy { it.recordedAt.localDate() }
                .mapValues { (_, es) -> es.map { it.percentage }.average() }

            MetricType.RESPIRATION -> respirationRepo.getReadingsInRangeOnce(startMs, endMs)
                .groupBy { it.recordedAt.localDate() }
                .mapValues { (_, es) -> es.map { it.breathsPerMinute }.average() }

            MetricType.SKIN_TEMP -> skinTempRepo.getReadingsInRangeOnce(startMs, endMs)
                .groupBy { it.recordedAt.localDate() }
                .mapValues { (_, es) -> es.map { it.celsius }.average() }

            MetricType.STEPS -> stepsRepo.getReadingsInRangeOnce(startMs, endMs)
                .groupBy { it.recordedAt.localDate() }
                .mapValues { (_, es) ->
                    // STEPS is a daily accumulator; the last reading of the day is the authoritative total
                    es.maxBy { it.recordedAt }.cumulativeSteps.toDouble()
                }

            MetricType.ACTIVE_CALORIES -> activeCalorieRepo.getReadingsInRangeOnce(startMs, endMs)
                .groupBy { it.recordedAt.localDate() }
                .mapValues { (_, es) -> es.map { it.calories }.average() }

            MetricType.TOTAL_CALORIES -> totalCalorieRepo.getReadingsInRangeOnce(startMs, endMs)
                .groupBy { it.recordedAt.localDate() }
                .mapValues { (_, es) -> es.map { it.calories }.average() }

            MetricType.GLUCOSE -> glucoseRepo.getReadingsInRangeOnce(startMs, endMs)
                .groupBy { it.recordedAt.localDate() }
                .mapValues { (_, es) -> es.map { it.value }.average() }

            MetricType.BLOOD_PRESSURE -> bloodPressureRepo.getReadingsInRangeOnce(startMs, endMs)
                .groupBy { it.recordedAt.localDate() }
                // TODO: render a dual-series (systolic + diastolic) chart as a future enhancement
                .mapValues { (_, es) -> es.map { it.systolic.toDouble() }.average() }

            MetricType.RHR -> {
                val from = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()
                val to = Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(1)
                dailySummaryRepo.getSummariesForRange(from, to)
                    .first()
                    .filter { it.restingHrBpm != null }
                    .associate { it.date to it.restingHrBpm!! }
            }

            else -> emptyMap()
        }
    }

    private fun unsupportedMessage(type: MetricType): String? = when (type) {
        MetricType.BATTERY ->
            // TODO: battery readings are ephemeral; persist to a battery_readings table if historical review is needed
            "Battery level data is not stored for historical review."
        MetricType.SLEEP_STAGE ->
            // TODO: sleep stage data is available via SleepStageRepository; render a stage-timeline chart
            "Sleep stage data is available on the full-day view."
        MetricType.HR, MetricType.HRV, MetricType.RHR, MetricType.SPO2, MetricType.RESPIRATION,
        MetricType.SKIN_TEMP, MetricType.STEPS, MetricType.ACTIVE_CALORIES,
        MetricType.TOTAL_CALORIES, MetricType.GLUCOSE, MetricType.BLOOD_PRESSURE -> null
        else ->
            // TODO: extend fetchAndAggregate and remove this branch when the remaining metric types gain dedicated tables
            "Data for this metric type is not available in this view."
    }
}

// ── Display helpers ────────────────────────────────────────────────────────────

val MetricType.displayName: String
    get() = when (this) {
        MetricType.HR -> "Heart Rate"
        MetricType.HRV -> "HRV"
        MetricType.RHR -> "Resting HR"
        MetricType.SPO2 -> "SpO₂"
        MetricType.STEPS -> "Steps"
        MetricType.SLEEP_STAGE -> "Sleep"
        MetricType.BATTERY -> "Battery"
        MetricType.SKIN_TEMP -> "Skin Temp"
        MetricType.RESPIRATION -> "Respiration"
        MetricType.ACTIVE_CALORIES -> "Active Calories"
        MetricType.TOTAL_CALORIES -> "Total Calories"
        MetricType.BLOOD_PRESSURE -> "Blood Pressure"
        MetricType.GLUCOSE -> "Glucose"
        else -> name
    }

val MetricType.displayUnit: String
    get() = when (this) {
        MetricType.HR -> "bpm"
        MetricType.HRV -> "ms"
        MetricType.RHR -> "bpm"
        MetricType.SPO2 -> "%"
        MetricType.STEPS -> "steps"
        MetricType.SLEEP_STAGE -> "h"
        MetricType.BATTERY -> "%"
        MetricType.SKIN_TEMP -> "°C"
        MetricType.RESPIRATION -> "brpm"
        MetricType.ACTIVE_CALORIES -> "kcal"
        MetricType.TOTAL_CALORIES -> "kcal"
        MetricType.BLOOD_PRESSURE -> "mmHg"
        MetricType.GLUCOSE -> "mmol"
        else -> ""
    }
