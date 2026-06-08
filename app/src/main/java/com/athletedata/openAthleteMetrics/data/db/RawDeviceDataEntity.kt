package com.athletedata.openAthleteMetrics.data.db

import android.util.Base64
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.RawPayload
import java.time.Instant

@Entity(
    tableName = "raw_device_data",
    foreignKeys = [
        ForeignKey(
            entity = SyncSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sync_session_id"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("sync_session_id")],
)
data class RawDeviceDataEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "sync_session_id")
    val syncSessionId: Long,
    @ColumnInfo(name = "characteristic_uuid")
    val characteristicUuid: String,
    val payload: String,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
)

// ── Mappers ──────────────────────────────────────────────────────────────────

fun RawDeviceDataEntity.toModel() = RawPayload(
    characteristicUuid = characteristicUuid,
    payload = Base64.decode(payload, Base64.NO_WRAP),
    receivedAt = Instant.ofEpochMilli(receivedAt),
)

fun RawPayload.toEntity(syncSessionId: Long) = RawDeviceDataEntity(
    syncSessionId = syncSessionId,
    characteristicUuid = characteristicUuid,
    payload = Base64.encodeToString(payload, Base64.NO_WRAP),
    receivedAt = receivedAt.toEpochMilli(),
)
