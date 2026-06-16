package com.athletedata.openAthleteMetrics.ui.metric

import com.athletedata.openAthleteMetrics.data.model.MetricType
import java.time.LocalDate

enum class TimeRange(val days: Int, val label: String) {
    DAYS_7(7, "7d"),
    DAYS_30(30, "30d"),
    DAYS_90(90, "90d"),
}

data class ChartPoint(val timestampMs: Long, val value: Double)

data class MetricStats(val min: Double?, val max: Double?, val average: Double?)

data class HistoryRow(val date: LocalDate, val value: Double)

sealed class MetricDetailUiState {
    object Loading : MetricDetailUiState()
    data class Empty(val message: String) : MetricDetailUiState()
    data class Success(
        val metricType: MetricType,
        val selectedRange: TimeRange,
        val chartData: List<ChartPoint>,
        val stats: MetricStats,
        val historyRows: List<HistoryRow>,
    ) : MetricDetailUiState()
}
