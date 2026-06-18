package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDefinitionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<QuestionDefinitionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QuestionDefinitionEntity): Long

    @Query("SELECT COUNT(*) FROM question_definitions")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM question_definitions WHERE category = 'LIFESTYLE'")
    suspend fun countLifestyle(): Int

    @Query("SELECT MAX(sort_order) FROM question_definitions WHERE category = 'CUSTOM'")
    suspend fun maxCustomSortOrder(): Int?

    @Query(
        """
        SELECT * FROM question_definitions
        WHERE category = 'LIFESTYLE'
        ORDER BY sort_order ASC
        """
    )
    fun getLifestyle(): Flow<List<QuestionDefinitionEntity>>

    @Query(
        """
        SELECT * FROM question_definitions
        WHERE category = 'CUSTOM'
        ORDER BY sort_order ASC
        """
    )
    fun getCustom(): Flow<List<QuestionDefinitionEntity>>

    @Query(
        """
        SELECT * FROM question_definitions
        WHERE category = 'LIFESTYLE' AND is_visible = 1 AND is_starred = 1
        ORDER BY sort_order ASC
        """
    )
    fun getStarredVisibleLifestyle(): Flow<List<QuestionDefinitionEntity>>

    @Query(
        """
        SELECT * FROM question_definitions
        WHERE category = 'CUSTOM' AND is_visible = 1 AND is_starred = 1
        ORDER BY sort_order ASC
        """
    )
    fun getStarredVisibleCustom(): Flow<List<QuestionDefinitionEntity>>

    @Query("UPDATE question_definitions SET is_visible = 1 WHERE id = :id")
    suspend fun show(id: Long)

    // Hiding a lifestyle question also clears its star so the dashboard bar updates immediately.
    @Query("UPDATE question_definitions SET is_visible = 0, is_starred = 0 WHERE id = :id")
    suspend fun hideAndUnstar(id: Long)

    @Query("UPDATE question_definitions SET is_starred = :starred WHERE id = :id")
    suspend fun setStar(id: Long, starred: Boolean)

    @Query("UPDATE question_definitions SET name = :name, type = :type WHERE id = :id")
    suspend fun updateNameAndType(id: Long, name: String, type: String)

    @Query("UPDATE question_definitions SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("DELETE FROM question_definitions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM question_definitions WHERE category = 'LIFESTYLE' ORDER BY sort_order ASC")
    suspend fun getLifestyleOnce(): List<QuestionDefinitionEntity>
}
