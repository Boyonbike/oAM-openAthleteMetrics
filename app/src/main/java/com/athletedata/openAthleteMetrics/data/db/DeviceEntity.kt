package com.athletedata.openAthleteMetrics.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athletedata.openAthleteMetrics.data.model.Device

@Entity(
    tableName = "devices",
    indices = [Index(value = ["ble_address"], unique = true)],
)
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "ble_address")
    val bleAddress: String,
    @ColumnInfo(name = "driver_id")
    val driverId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "last_seen_ms")
    val lastSeenMs: Long? = null,
    @ColumnInfo(name = "last_sync_ms")
    val lastSyncMs: Long? = null,
    @ColumnInfo(name = "last_battery_pct")
    val lastBatteryPct: Int? = null,
)

// ── Mappers ──────────────────────────────────────────────────────────────────

fun DeviceEntity.toModel() = Device(
    id = id,
    bleAddress = bleAddress,
    driverId = driverId,
    displayName = displayName,
    lastSeenMs = lastSeenMs,
    lastSyncMs = lastSyncMs,
    lastBatteryPct = lastBatteryPct,
)

fun Device.toEntity() = DeviceEntity(
    id = id,
    bleAddress = bleAddress,
    driverId = driverId,
    displayName = displayName,
    lastSeenMs = lastSeenMs,
    lastSyncMs = lastSyncMs,
    lastBatteryPct = lastBatteryPct,
)
