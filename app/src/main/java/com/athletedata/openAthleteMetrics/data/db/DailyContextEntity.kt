package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.DailyContext
import java.time.Instant
import java.time.LocalDate

/**
 * Room entity for the `daily_context` table.
 *
 * Stores user-entered subjective data: scores, illness state, habits, and
 * weight. One row per day; the primary key is the ISO date string so an
 * upsert replaces any existing entry for that date.
 *
 * There is no `source` column here — context is always user or seeder.
 * The seeder cleanup path calls [DailyContextDao.deleteAll] directly.
 */
@Entity(tableName = "daily_context")
data class DailyContextEntity(
    @PrimaryKey
    val date: LocalDate,
    val fatigue: Int? = null,
    val stress: Int? = null,
    val motivation: Int? = null,
    @ColumnInfo(name = "sleep_quality")
    val sleepQuality: Int? = null,
    @ColumnInfo(name = "performance_feel")
    val performanceFeel: Int? = null,
    @ColumnInfo(name = "is_ill")
    val isIll: Boolean = false,
    @ColumnInfo(name = "illness_notes")
    val illnessNotes: String? = null,
    @ColumnInfo(name = "habits_json")
    val habitsJson: String? = null,
    @ColumnInfo(name = "weight_kg")
    val weightKg: Double? = null,
    @ColumnInfo(name = "body_fat_pct")
    val bodyFatPct: Double? = null,
    val notes: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)

// ── Mappers ──────────────────────────────────────────────────────────────────

fun DailyContextEntity.toModel() = DailyContext(
    date = date,
    fatigue = fatigue,
    stress = stress,
    motivation = motivation,
    sleepQuality = sleepQuality,
    performanceFeel = performanceFeel,
    isIll = isIll,
    illnessNotes = illnessNotes,
    habitsJson = habitsJson,
    weightKg = weightKg,
    bodyFatPct = bodyFatPct,
    notes = notes,
    updatedAt = updatedAt,
)

fun DailyContext.toEntity() = DailyContextEntity(
    date = date,
    fatigue = fatigue,
    stress = stress,
    motivation = motivation,
    sleepQuality = sleepQuality,
    performanceFeel = performanceFeel,
    isIll = isIll,
    illnessNotes = illnessNotes,
    habitsJson = habitsJson,
    weightKg = weightKg,
    bodyFatPct = bodyFatPct,
    notes = notes,
    updatedAt = updatedAt,
)
