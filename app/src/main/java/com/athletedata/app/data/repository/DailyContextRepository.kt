package com.athletedata.app.data.repository

import com.athletedata.app.data.model.DailyContext
import com.athletedata.app.data.model.DataSource
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Contract for user-entered daily context (scores, habits, weight, illness).
 *
 * The Daily Questions screen and Weight sheet call [upsert]. ViewModels call
 * the query methods. The seeder calls [upsert] then [deleteBySource] for cleanup.
 */
interface DailyContextRepository {

    /**
     * Inserts or replaces the context for [context.date].
     * Calling a second time for the same date updates all fields in place.
     */
    suspend fun upsert(context: DailyContext)

    /** Live stream of the context for [date], or null if the user hasn't filled it in. */
    fun getForDate(date: LocalDate): Flow<DailyContext?>

    /** Live stream of contexts in the inclusive date range [[from], [to]], oldest first. */
    fun getForRange(from: LocalDate, to: LocalDate): Flow<List<DailyContext>>

    /**
     * Removes context rows for the given [source].
     *
     * Because `daily_context` has no source column, [DataSource.SEEDER] maps
     * to a delete-all; other values are no-ops in the current schema.
     */
    suspend fun deleteBySource(source: DataSource)
}
