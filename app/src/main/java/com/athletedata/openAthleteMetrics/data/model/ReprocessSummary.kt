package com.athletedata.openAthleteMetrics.data.model

import java.time.LocalDate

data class ReprocessSummary(
    val recordsReplaced: Int,
    val datesAffected: Set<LocalDate>,
    val error: String? = null,
)
