package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.QuestionDefinition
import com.athletedata.openAthleteMetrics.data.model.QuestionResponse
import com.athletedata.openAthleteMetrics.data.model.QuestionType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface QuestionRepository {
    fun getLifestyleQuestions(): Flow<List<QuestionDefinition>>
    fun getStarredLifestyleQuestions(): Flow<List<QuestionDefinition>>
    fun getStarredHabitsQuestions(): Flow<List<QuestionDefinition>>
    fun getCustomQuestions(): Flow<List<QuestionDefinition>>
    fun getResponsesForDate(date: LocalDate): Flow<List<QuestionResponse>>
    suspend fun upsertResponse(questionId: Long, date: LocalDate, value: String)
    suspend fun toggleVisibility(questionId: Long, currentlyVisible: Boolean)
    suspend fun toggleStar(questionId: Long, currentlyStarred: Boolean)
    suspend fun reorderQuestions(updates: List<Pair<Long, Int>>)
    suspend fun deleteCustomQuestion(questionId: Long)
    suspend fun addCustomQuestion(name: String, type: QuestionType): Long
    suspend fun updateCustomQuestion(id: Long, name: String, type: QuestionType)
    suspend fun clearResponse(questionId: Long, date: LocalDate)
    fun getResponsesForRange(questionId: Long, from: LocalDate, to: LocalDate): Flow<List<QuestionResponse>>
}
