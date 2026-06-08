package com.athletedata.openAthleteMetrics.data.model

enum class QuestionType { SCALE, BOOLEAN, TEXT }
enum class QuestionCategory { LIFESTYLE, CUSTOM }

data class QuestionDefinition(
    val id: Long,
    val name: String,
    val type: QuestionType,
    val category: QuestionCategory,
    val isVisible: Boolean,
    val sortOrder: Int,
    val isStarred: Boolean = false,
)
