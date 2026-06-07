package com.athletedata.app.data.repository

import com.athletedata.app.data.db.QuestionDefinitionDao
import com.athletedata.app.data.db.QuestionDefinitionEntity
import com.athletedata.app.data.db.QuestionResponseDao
import com.athletedata.app.data.db.QuestionResponseEntity
import com.athletedata.app.data.db.toModel
import com.athletedata.app.data.model.QuestionCategory
import com.athletedata.app.data.model.QuestionDefinition
import com.athletedata.app.data.model.QuestionResponse
import com.athletedata.app.data.model.QuestionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    override fun getCustomQuestions(): Flow<List<QuestionDefinition>> =
        definitionDao.getCustom().map { list -> list.map { it.toModel() } }

    override fun getResponsesForDate(date: LocalDate): Flow<List<QuestionResponse>> =
        responseDao.getResponsesForDate(date.toString()).map { list -> list.map { it.toModel() } }

    override suspend fun upsertResponse(questionId: Long, date: LocalDate, value: String) {
        responseDao.upsert(
            QuestionResponseEntity(
                questionId = questionId,
                date = date.toString(),
                value = value,
                recordedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun toggleVisibility(questionId: Long, currentlyVisible: Boolean) {
        if (currentlyVisible) {
            definitionDao.hideAndUnstar(questionId)
        } else {
            definitionDao.show(questionId)
        }
    }

    override suspend fun toggleStar(questionId: Long, currentlyStarred: Boolean) {
        definitionDao.setStar(questionId, !currentlyStarred)
    }

    override suspend fun reorderQuestions(updates: List<Pair<Long, Int>>) {
        updates.forEach { (id, order) -> definitionDao.updateSortOrder(id, order) }
    }

    override suspend fun deleteCustomQuestion(questionId: Long) {
        definitionDao.deleteById(questionId)
    }

    override suspend fun updateCustomQuestion(id: Long, name: String, type: QuestionType) {
        definitionDao.updateNameAndType(id, name, type.name)
    }

    override suspend fun clearResponse(questionId: Long, date: LocalDate) {
        responseDao.deleteResponse(questionId, date.toString())
    }

    override suspend fun addCustomQuestion(name: String, type: QuestionType): Long {
        val nextOrder = (definitionDao.maxCustomSortOrder() ?: 0) + 1
        return definitionDao.insert(
            QuestionDefinitionEntity(
                name = name,
                type = type.name,
                category = QuestionCategory.CUSTOM.name,
                isVisible = true,
                sortOrder = nextOrder,
            )
        )
    }
}
