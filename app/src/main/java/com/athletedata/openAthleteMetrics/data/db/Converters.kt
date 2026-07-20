package com.athletedata.openAthleteMetrics.data.db

import androidx.room.TypeConverter
import com.athletedata.openAthleteMetrics.data.model.BaselineMetric
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.MetricType
import com.athletedata.openAthleteMetrics.data.model.SleepStage
import com.athletedata.openAthleteMetrics.data.model.SyncStatus
import com.athletedata.openAthleteMetrics.data.model.UserCategory
import java.time.Instant
import java.time.LocalDate

/**
 * Room TypeConverters for all non-primitive domain types.
 *
 * Registered at the [AppDatabase] level so they apply to every entity field,
 * every @Query parameter, and every query result column automatically.
 */
class Converters {

    // ── LocalDate ↔ String (ISO-8601: "YYYY-MM-DD") ──────────────────────────

    @TypeConverter
    fun fromLocalDate(date: LocalDate): String = date.toString()

    @TypeConverter
    fun toLocalDate(value: String): LocalDate = LocalDate.parse(value)

    // ── Instant ↔ Long (Unix epoch milliseconds) ─────────────────────────────

    @TypeConverter
    fun fromInstant(instant: Instant): Long = instant.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long): Instant = Instant.ofEpochMilli(value)

    // ── MetricType ↔ String ───────────────────────────────────────────────────

    @TypeConverter
    fun fromMetricType(type: MetricType): String = type.name

    @TypeConverter
    fun toMetricType(value: String): MetricType = when (value) {
        "RESPIRATORY_RATE" -> MetricType.RESPIRATION // legacy alias for pre-rename rows, see MIGRATION_10_11
        else -> MetricType.valueOf(value)
    }

    // ── DataSource ↔ String ───────────────────────────────────────────────────

    @TypeConverter
    fun fromDataSource(source: DataSource): String = source.name

    @TypeConverter
    fun toDataSource(value: String): DataSource = DataSource.valueOf(value)

    // ── UserCategory ↔ String (nullable) ─────────────────────────────────────

    @TypeConverter
    fun fromUserCategory(category: UserCategory?): String? = category?.name

    @TypeConverter
    fun toUserCategory(value: String?): UserCategory? = value?.let { UserCategory.valueOf(it) }

    // ── SyncStatus ↔ String ───────────────────────────────────────────────────

    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String = status.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    // ── SleepStage ↔ String ───────────────────────────────────────────────────

    @TypeConverter
    fun fromSleepStage(stage: SleepStage): String = stage.name

    @TypeConverter
    fun toSleepStage(value: String): SleepStage = SleepStage.valueOf(value)

    // ── BaselineMetric ↔ String ───────────────────────────────────────────────

    @TypeConverter
    fun fromBaselineMetric(metric: BaselineMetric): String = metric.name

    @TypeConverter
    fun toBaselineMetric(value: String): BaselineMetric = BaselineMetric.valueOf(value)
}
