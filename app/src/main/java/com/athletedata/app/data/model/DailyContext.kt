package com.athletedata.app.data.model

import java.time.Instant
import java.time.LocalDate

/**
 * User-entered context for a single day: subjective scores, illness state,
 * habits, and weight.
 *
 * This is always written by the user (or seeder) — there is no device source.
 * One row per day; upserted each time the user saves the Daily Questions screen
 * or the Weight sheet.
 *
 * All score fields use a 1–5 integer scale. [habitsJson] stores a flat JSON
 * object of habit keys to booleans, e.g. `{"alcohol":false,"meditation":true}`.
 *
 * @property date The calendar day this context belongs to (ISO YYYY-MM-DD).
 * @property fatigue Self-reported fatigue level (1 = very low … 5 = very high).
 * @property stress Self-reported stress level (1–5).
 * @property motivation Self-reported motivation level (1–5).
 * @property sleepQuality Subjective sleep quality rating (1–5).
 * @property performanceFeel How training/activity felt (1–5).
 * @property isIll Whether the user marked themselves as ill this day.
 * @property illnessNotes Free-text illness description; non-null only when [isIll] is true.
 * @property habitsJson JSON object of habit keys → boolean completed flags.
 * @property weightKg Body weight in kilograms.
 * @property bodyFatPct Optional body-fat percentage.
 * @property notes Free-text daily notes.
 * @property updatedAt When this row was last written.
 */
data class DailyContext(
    val date: LocalDate,
    val fatigue: Int? = null,
    val stress: Int? = null,
    val motivation: Int? = null,
    val sleepQuality: Int? = null,
    val performanceFeel: Int? = null,
    val isIll: Boolean = false,
    val illnessNotes: String? = null,
    val habitsJson: String? = null,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val notes: String? = null,
    val updatedAt: Instant,
)
