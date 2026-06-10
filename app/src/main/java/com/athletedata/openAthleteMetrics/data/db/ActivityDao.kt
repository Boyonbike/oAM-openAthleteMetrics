package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ActivityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ActivityEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllOrIgnore(entities: List<ActivityEntity>): List<Long>

    @Query("DELETE FROM activities WHERE source = :source")
    suspend fun deleteBySource(source: DataSource)

    @Query("DELETE FROM activities")
    suspend fun deleteAll()

    @Query("UPDATE activities SET user_category = :category WHERE id = :id")
    suspend fun updateCategory(id: Long, category: UserCategory)

    @Query("UPDATE activities SET user_category = :category, notes = :notes WHERE id = :id")
    suspend fun updateCategoryAndNotes(id: Long, category: UserCategory?, notes: String?)

    // ── Reads ─────────────────────────────────────────────────────────────────

    @Query(
        """
        SELECT * FROM activities
        WHERE start_time >= :startMs AND start_time < :endMs
        ORDER BY start_time ASC
        """
    )
    fun getActivitiesInRange(startMs: Long, endMs: Long): Flow<List<ActivityEntity>>

    @Query(
        """
        SELECT * FROM activities
        WHERE start_time >= :startMs AND start_time < :endMs
        ORDER BY start_time ASC
        """
    )
    suspend fun getActivitiesInRangeOnce(startMs: Long, endMs: Long): List<ActivityEntity>
}
