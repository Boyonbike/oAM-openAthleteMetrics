package com.athletedata.openAthleteMetrics.data.model

import java.time.LocalDate

data class QuestionResponse(
    val id: Long,
    val questionId: Long,
    val date: LocalDate,
    val value: String,
    val recordedAt: Long,
)
