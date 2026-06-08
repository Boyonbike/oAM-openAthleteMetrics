package com.athletedata.openAthleteMetrics.data.model

data class QuestionResponse(
    val id: Long,
    val questionId: Long,
    val date: String,
    val value: String,
    val recordedAt: Long,
)
