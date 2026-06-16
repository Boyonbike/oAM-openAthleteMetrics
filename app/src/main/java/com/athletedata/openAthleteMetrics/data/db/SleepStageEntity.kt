package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.DataSource
import com.athletedata.openAthleteMetrics.data.model.SleepStage

/**
 * Room entity for the `sleep_stages` table.
 *
 * One row per discrete sleep stage block within a session. Linked to
 * [SleepSessionEntity] via [sessionId]; cascade-deleted when the parent
 * session is removed.
 *
 * This entity intentionally omits the standard time-series columns
 * (recorded_at, created_at, confidence, meta_json) because stage data
 * is always computed from the session rather than recorded independently.
 */
@Entity(
    tableName = "sleep_stages",
    foreignKeys = [
        ForeignKey(
            entity = SleepSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["session_id", "start_ms"], unique = true)],
)
data class SleepStageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    val stage: SleepStage,
    @ColumnInfo(name = "start_ms")
    val startMs: Long,
    @ColumnInfo(name = "end_ms")
    val endMs: Long,
    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int,
    val source: DataSource,
    @ColumnInfo(name = "driver_id")
    val driverId: String? = null,
    @ColumnInfo(name = "computed_by_version")
    val computedByVersion: Int,
)
