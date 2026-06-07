package com.athletedata.app.data.model

data class QuestionResponse(
    val id: Long,
    val questionId: Long,
    val date: String,
    val value: String,
    val recordedAt: Long,
)
