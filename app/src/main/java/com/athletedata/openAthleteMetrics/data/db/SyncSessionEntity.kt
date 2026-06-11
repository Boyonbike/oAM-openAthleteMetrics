package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.SyncSession
import com.athletedata.openAthleteMetrics.data.model.SyncStatus
import java.time.Instant

@Entity(
    tableName = "sync_sessions",
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["device_id"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["device_id", "started_at"],
            orders = [Index.Order.ASC, Index.Order.DESC],
        ),
    ],
)
data class SyncSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "device_id")
    val deviceId: Long,
    @ColumnInfo(name = "driver_id")
    val driverId: String,
    @ColumnInfo(name = "started_at")
    val startedAt: Instant,
    @ColumnInfo(name = "ended_at")
    val endedAt: Instant? = null,
    val status: SyncStatus,
    @ColumnInfo(name = "records_imported")
    val recordsImported: Int = 0,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "packets_received")
    val packetsReceived: Int = 0,
    @ColumnInfo(name = "synced_before_quiescence")
    val syncedBeforeQuiescence: Boolean = false,
)

// ── Mappers ──────────────────────────────────────────────────────────────────

fun SyncSessionEntity.toModel() = SyncSession(
    id = id,
    deviceId = deviceId,
    driverId = driverId,
    startedAt = startedAt,
    endedAt = endedAt,
    status = status,
    recordsImported = recordsImported,
    errorMessage = errorMessage,
    packetsReceived = packetsReceived,
    syncedBeforeQuiescence = syncedBeforeQuiescence,
)

fun SyncSession.toEntity() = SyncSessionEntity(
    id = id,
    deviceId = deviceId,
    driverId = driverId,
    startedAt = startedAt,
    endedAt = endedAt,
    status = status,
    recordsImported = recordsImported,
    errorMessage = errorMessage,
    packetsReceived = packetsReceived,
    syncedBeforeQuiescence = syncedBeforeQuiescence,
)
