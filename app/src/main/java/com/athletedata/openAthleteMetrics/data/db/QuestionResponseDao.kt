package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionResponseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: QuestionResponseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<QuestionResponseEntity>)

    @Query("SELECT * FROM question_responses WHERE date = :date")
    fun getResponsesForDate(date: String): Flow<List<QuestionResponseEntity>>

    @Query(
        """
        SELECT * FROM question_responses
        WHERE question_id = :questionId AND date = :date
        LIMIT 1
        """
    )
    suspend fun getResponseOnce(questionId: Long, date: String): QuestionResponseEntity?

    @Query("DELETE FROM question_responses WHERE question_id = :questionId AND date = :date")
    suspend fun deleteResponse(questionId: Long, date: String)

    @Query("DELETE FROM question_responses")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM question_responses
        WHERE question_id = :questionId AND date BETWEEN :from AND :to
        ORDER BY date ASC
        """
    )
    fun getResponsesForRange(questionId: Long, from: String, to: String): Flow<List<QuestionResponseEntity>>
}
