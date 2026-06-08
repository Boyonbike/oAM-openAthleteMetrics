package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.DailySummary
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Contract for the pre-computed daily summary table.
 *
 * DailySummaryWorker is the only writer. ViewModels are the primary readers.
 */
interface DailySummaryRepository {

    /** Inserts or replaces the summary for [summary.date]. Called by DailySummaryWorker. */
    suspend fun upsert(summary: DailySummary)

    /** Live stream of the summary for [date], or null if the worker hasn't run yet. */
    fun getSummaryForDate(date: LocalDate): Flow<DailySummary?>

    /** Live stream of summaries in the inclusive date range [[from], [to]], oldest first. */
    fun getSummariesForRange(from: LocalDate, to: LocalDate): Flow<List<DailySummary>>

    /** One-shot read for a specific date; used by DailySummaryWorker before writing. */
    suspend fun getSummaryForDateOnce(date: LocalDate): DailySummary?

    /** Deletes all summary rows; called after seeder data is cleared so stale summaries don't linger. */
    suspend fun deleteAll()
}
