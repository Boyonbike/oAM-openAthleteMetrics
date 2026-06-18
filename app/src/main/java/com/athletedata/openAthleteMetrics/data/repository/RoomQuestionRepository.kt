package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.db.QuestionDefinitionDao
import com.athletedata.openAthleteMetrics.data.db.QuestionDefinitionEntity
import com.athletedata.openAthleteMetrics.data.db.QuestionResponseDao
import com.athletedata.openAthleteMetrics.data.db.QuestionResponseEntity
import com.athletedata.openAthleteMetrics.data.db.toModel
import com.athletedata.openAthleteMetrics.data.model.QuestionCategory
import com.athletedata.openAthleteMetrics.data.model.QuestionDefinition
import com.athletedata.openAthleteMetrics.data.model.QuestionResponse
import com.athletedata.openAthleteMetrics.data.model.QuestionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomQuestionRepository @Inject constructor(
    private val definitionDao: QuestionDefinitionDao,
    private val responseDao: QuestionResponseDao,
) : QuestionRepository {

    override fun getLifestyleQuestions(): Flow<List<QuestionDefinition>> =
        definitionDao.getLifestyle().map { list -> list.map { it.toModel() } }

    override fun getStarredLifestyleQuestions(): Flow<List<QuestionDefinition>> =
        definitionDao.getStarredVisibleLifestyle().map { list -> list.map { it.toModel() } }

    override fun getStarredHabitsQuestions(): Flow<List<QuestionDefinition>> =
        definitionDao.getStarredVisibleCustom().map { list -> list.map { it.toModel() } }

    override fun getCustomQuestions(): Flow<List<QuestionDefinition>> =
        definitionDao.getCustom().map { list -> list.map { it.toModel() } }

    override fun getResponsesForDate(date: LocalDate): Flow<List<QuestionResponse>> =
        responseDao.getResponsesForDate(date).map { list -> list.map { it.toModel() } }

    override suspend fun upsertResponse(questionId: Long, date: LocalDate, value: String) {
        try {
            responseDao.upsert(
                QuestionResponseEntity(
                    questionId = questionId,
                    date = date,
                    value = value,
                    recordedAt = System.currentTimeMillis(),
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to upsert question response")
            throw e
        }
    }

    override suspend fun toggleVisibility(questionId: Long, currentlyVisible: Boolean) {
        try {
            if (currentlyVisible) {
                definitionDao.hideAndUnstar(questionId)
            } else {
                definitionDao.show(questionId)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to toggle question visibility")
            throw e
        }
    }

    override suspend fun toggleStar(questionId: Long, currentlyStarred: Boolean) {
        try {
            definitionDao.setStar(questionId, !currentlyStarred)
        } catch (e: Exception) {
            Timber.e(e, "Failed to toggle question star")
            throw e
        }
    }

    override suspend fun reorderQuestions(updates: List<Pair<Long, Int>>) {
        try {
            updates.forEach { (id, order) -> definitionDao.updateSortOrder(id, order) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to reorder questions")
            throw e
        }
    }

    override suspend fun deleteCustomQuestion(questionId: Long) {
        try {
            definitionDao.deleteById(questionId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete custom question")
            throw e
        }
    }

    override suspend fun updateCustomQuestion(id: Long, name: String, type: QuestionType) {
        try {
            definitionDao.updateNameAndType(id, name, type.name)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update custom question")
            throw e
        }
    }

    override suspend fun clearResponse(questionId: Long, date: LocalDate) {
        try {
            responseDao.deleteResponse(questionId, date)
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear question response")
            throw e
        }
    }

    override fun getResponsesForRange(
        questionId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<QuestionResponse>> =
        responseDao.getResponsesForRange(questionId, from, to)
            .map { list -> list.map { it.toModel() } }

    override suspend fun addCustomQuestion(name: String, type: QuestionType): Long {
        return try {
            val nextOrder = (definitionDao.maxCustomSortOrder() ?: 0) + 1
            definitionDao.insert(
                QuestionDefinitionEntity(
                    name = name,
                    type = type.name,
                    category = QuestionCategory.CUSTOM.name,
                    isVisible = true,
                    sortOrder = nextOrder,
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to add custom question")
            throw e
        }
    }

    override suspend fun getLifestyleQuestionsOnce(): List<QuestionDefinition> =
        definitionDao.getLifestyleOnce().map { it.toModel() }

    override suspend fun upsertAllResponses(entities: List<QuestionResponseEntity>) {
        try {
            responseDao.upsertAll(entities)
        } catch (e: Exception) {
            Timber.e(e, "Failed to upsert question responses")
            throw e
        }
    }

    override suspend fun deleteAllResponses() {
        try {
            responseDao.deleteAll()
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete all question responses")
            throw e
        }
    }
}
