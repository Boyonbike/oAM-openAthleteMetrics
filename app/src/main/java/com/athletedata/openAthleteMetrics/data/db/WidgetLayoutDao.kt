package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetLayoutDao {

    @Query("SELECT * FROM widget_layout ORDER BY sort_order ASC")
    fun getAll(): Flow<List<WidgetLayoutEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WidgetLayoutEntity): Long

    @Query("DELETE FROM widget_layout WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE widget_layout SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WidgetLayoutEntity>)
}
