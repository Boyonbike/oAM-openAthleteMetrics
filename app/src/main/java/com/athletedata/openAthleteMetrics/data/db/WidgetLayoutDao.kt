package com.athletedata.openAthleteMetrics.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetLayoutDao {

    @Query("SELECT * FROM widget_layout ORDER BY sequence_order ASC")
    fun getAll(): Flow<List<WidgetLayoutEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WidgetLayoutEntity): Long

    @Query("DELETE FROM widget_layout WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE widget_layout SET sequence_order = :sequenceOrder WHERE id = :id")
    suspend fun updateSequenceOrder(id: Long, sequenceOrder: Int)

    /** Applies every (id -> new index) update as a single transaction, so a reorder is
     *  atomic - no intermediate, partially-reordered state is ever observable via [getAll]. */
    @Transaction
    suspend fun updateSequenceOrders(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> updateSequenceOrder(id, index) }
    }

    @Query("UPDATE widget_layout SET col_span = :colSpan, row_span = :rowSpan WHERE id = :id")
    suspend fun updateSize(id: Long, colSpan: Int, rowSpan: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WidgetLayoutEntity>)
}
