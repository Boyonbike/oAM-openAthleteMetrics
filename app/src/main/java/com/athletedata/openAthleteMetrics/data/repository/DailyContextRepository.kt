package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.DailyContext
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Contract for user-entered daily context (scores, habits, weight, illness).
 *
 * The Daily Questions screen and Weight sheet call [upsert]. ViewModels call
 * the query methods.
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

    /** One-shot read; used by the seeder to check for existing data before re-seeding. */
    suspend fun getForDateOnce(date: LocalDate): DailyContext?
}
