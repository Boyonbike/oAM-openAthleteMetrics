package com.athletedata.openAthleteMetrics.data.model

import java.time.Instant
import java.time.LocalDate

/**
 * One continuous sleep session, typically one per night.
 *
 * The full stage breakdown is stored as a JSON array in [stagesJson] rather
 * than individual rows, keeping dashboard queries simple. Each element has the
 * shape `{"stage":"DEEP"|"LIGHT"|"REM"|"AWAKE","startMs":<epoch>,"endMs":<epoch>}`.
 *
 * @property id Auto-generated primary key; 0 signals an unsaved instance.
 * @property date The calendar date of the morning the sleeper woke up (ISO YYYY-MM-DD).
 * @property sleepStartMs When the session began.
 * @property sleepEndMs When the session ended.
 * @property durationMinutes Derived: `(sleepEndMs - sleepStartMs) / 60_000`.
 * @property stagesJson JSON stage-breakdown array, or null if unavailable.
 * @property source Who produced this session record.
 * @property driverId Populated by the device driver; null for manual/seeder rows.
 */
data class SleepSession(
    val id: Long = 0,
    val date: LocalDate,
    val sleepStartMs: Instant,
    val sleepEndMs: Instant,
    val durationMinutes: Int,
    val stagesJson: String? = null,
    val source: DataSource,
    val driverId: String? = null,
)
