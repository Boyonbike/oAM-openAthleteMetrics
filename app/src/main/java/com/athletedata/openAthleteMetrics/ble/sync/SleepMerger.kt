package com.athletedata.openAthleteMetrics.ble.sync

import com.athletedata.openAthleteMetrics.data.model.SleepSession
import java.time.temporal.ChronoUnit

/**
 * Merges a list of [SleepSession]s that may span multiple syncs for the same
 * (driverId, date) pair into one canonical session per date.
 *
 * Stage data is stored in the `sleep_stages` table (not on the session) and
 * is merged separately by the sync processor after this call returns.
 *
 * Package-internal so both [DeviceSyncProcessor] and [DeviceReprocessor] can use it.
 */
internal fun mergeSleepSessions(sessions: List<SleepSession>): List<SleepSession> {
    if (sessions.isEmpty()) return sessions
    return sessions
        .groupBy { Pair(it.driverId, it.date) }
        .values
        .map { group ->
            val start = group.minOf { it.sleepStartMs }
            val end   = group.maxOf { it.sleepEndMs }
            group.first().copy(
                sleepStartMs    = start,
                sleepEndMs      = end,
                durationMinutes = ChronoUnit.MINUTES.between(start, end).toInt(),
            )
        }
}
