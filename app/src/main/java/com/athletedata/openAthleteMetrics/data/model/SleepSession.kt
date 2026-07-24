package com.athletedata.openAthleteMetrics.data.model

import java.time.Instant
import java.time.LocalDate

/**
 * One continuous sleep session, typically one per night.
 *
 * Stage data is now stored in dedicated [SleepStageEntity] rows in the
 * `sleep_stages` table, linked by session id.
 *
 * @property id Auto-generated primary key; 0 signals an unsaved instance.
 * @property date The calendar date of the morning the sleeper woke up (ISO YYYY-MM-DD).
 * @property sleepStartMs When the session began.
 * @property sleepEndMs When the session ended.
 * @property durationMinutes Derived: `(sleepEndMs - sleepStartMs) / 60_000`.
 * @property source Who produced this session record.
 * @property driverId Populated by the device driver; null for manual/seeder rows.
 * @property deviceId Physical device (numeric devices.id) this session came from; not the driver.
 */
data class SleepSession(
    val id: Long = 0,
    val date: LocalDate,
    val sleepStartMs: Instant,
    val sleepEndMs: Instant,
    val durationMinutes: Int,
    val source: DataSource,
    val driverId: String? = null,
    val deviceId: Long? = null,
)
