package com.athletedata.openAthleteMetrics.data.model

import java.time.Instant

/**
 * The single object every BLE device driver returns after completing a sync.
 *
 * The BLE engine passes this to the validation layer, which is responsible
 * for deduplication and writing to the database via the existing repositories.
 * Drivers never write to the database directly.
 *
 * @property deviceId        BLE MAC address or other unique device identifier.
 * @property driverId        Stable identifier for the driver implementation (e.g. "hume_band_v1").
 * @property syncStartedAt   When the BLE engine initiated the sync connection.
 * @property syncEndedAt     When the driver finished parsing and returned this result.
 * @property metricReadings  Point-in-time sensor readings collected during the sync.
 * @property sleepSessions   Sleep sessions found on the device since the last sync.
 * @property activities      Timestamped activity events found on the device since the last sync.
 * @property rawPayloads     Unprocessed characteristic payloads for logging and reparse.
 */
data class DriverSyncResult(
    val deviceId: String,
    val driverId: String,
    val syncStartedAt: Instant,
    val syncEndedAt: Instant,
    val metricReadings: List<MetricReading>,
    val sleepSessions: List<SleepSession>,
    val activities: List<Activity>,
    val rawPayloads: List<RawPayload>,
    val packetsReceived: Int = 0,
    // REMOVED: early-sync-warning
)
