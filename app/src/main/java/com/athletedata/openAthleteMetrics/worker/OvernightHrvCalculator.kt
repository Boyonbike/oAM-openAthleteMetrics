package com.athletedata.openAthleteMetrics.worker

import com.athletedata.openAthleteMetrics.data.db.HrvReadingDao
import com.athletedata.openAthleteMetrics.data.db.SleepSessionDao
import java.time.LocalDate
import javax.inject.Inject

/**
 * Computes overnight HRV — the mean HRV during a driver's actual sleep
 * window, as opposed to [com.athletedata.openAthleteMetrics.data.model.DailySummary.avgHrvMs]
 * which averages across the full calendar day.
 */
class OvernightHrvCalculator @Inject constructor(
    private val hrvReadingDao: HrvReadingDao,
    private val sleepSessionDao: SleepSessionDao,
) {

    /**
     * Steps:
     *  a. Look up the sleep session for [date]. No session → no overnight
     *     window → null.
     *  b. Query HRV readings between sleepStartMs and sleepEndMs (inclusive)
     *     — the session's actual epoch-ms timestamps, NOT a recompute of
     *     calendar-day boundaries. The sleep window can start the evening
     *     before [date], so a naive date(recorded_at) = date query would
     *     wrongly exclude pre-midnight readings.
     *  c. Fewer than 3 readings → insufficient data → null.
     *  d. Drop readings outside the 10-250ms plausible range (device-noise
     *     outlier filter, applies across all supported hardware).
     *  e. Re-check the minimum-count threshold after filtering: outlier
     *     removal can itself drop the count below 3, and a mean of 1-2
     *     values post-filter is just as statistically unreliable as a raw
     *     undersized sample — both checks guard the same floor at different
     *     pipeline stages, so neither can be dropped in favour of the other.
     *  f. Return the arithmetic mean of what remains.
     */
    suspend fun calculate(date: LocalDate): Double? {
        val session = sleepSessionDao.getSessionForDateOnce(date) ?: return null

        // Use the session's own epoch-ms timestamps, not a date-derived
        // range — sleepStartMs is frequently the evening BEFORE `date`.
        val startMs = session.sleepStartMs.toEpochMilli()
        // getReadingsInRangeOnce's upper bound is exclusive; +1 makes the
        // sleep_end_ms boundary inclusive per spec without changing the
        // shared DAO query used by every other caller.
        val endMs = session.sleepEndMs.toEpochMilli() + 1

        val readings = hrvReadingDao.getReadingsInRangeOnce(startMs, endMs)
        if (readings.size < 3) return null

        val filtered = readings.filter { it.rmssdMs in 10.0..250.0 }
        if (filtered.size < 3) return null

        return filtered.map { it.rmssdMs }.average()
    }
}
