package com.athletedata.openAthleteMetrics.data.repository

import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.SleepSession
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Contract for sleep session storage and retrieval.
 *
 * The device driver and seeder call [insert]. ViewModels and
 * DailySummaryWorker call the query methods.
 */
interface SleepRepository {

    /** Inserts or replaces the sleep session. Source must be set by the caller. */
    suspend fun insert(session: SleepSession)

    /** Inserts a device sleep session using insert-or-ignore. Returns 1 if already existed, 0 if newly inserted. */
    suspend fun insertFromDevice(session: SleepSession): Int

    /** Inserts or replaces a device sleep session; a correct morning session always wins over a prior partial. */
    suspend fun insertOrReplace(session: SleepSession)

    /** Live stream of the session for a given night (keyed by morning date), or null. */
    fun getSessionForDate(date: LocalDate): Flow<SleepSession?>

    /** Live stream of sessions in the inclusive date range [[from], [to]], oldest first. */
    fun getSessionsForRange(from: LocalDate, to: LocalDate): Flow<List<SleepSession>>

    /** One-shot read for a specific date; used by DailySummaryWorker. */
    suspend fun getSessionForDateOnce(date: LocalDate): SleepSession?

    /** Deletes all sessions whose source matches [source]. Used by the seeder cleanup flow. */
    suspend fun deleteBySource(source: DataSource)
}
