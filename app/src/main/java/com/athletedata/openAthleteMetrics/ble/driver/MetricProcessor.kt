package com.athletedata.openAthleteMetrics.ble.driver

import com.athletedata.openAthleteMetrics.data.model.MetricReading

/**
 * Session-scoped processor that observes each [MetricReading] as it arrives during a sync
 * and computes derived readings (HRV, sleep stages, etc.) once the sync is complete.
 *
 * A new instance is created per sync session via [DeviceDriver.createProcessor].
 * Implementations must be stateful and NOT shared across sessions.
 */
interface MetricProcessor {
    /** Called once for every [MetricReading] produced by the driver during a sync. */
    fun onReading(reading: MetricReading)

    /**
     * Called once when the end-of-sync marker is received.
     * Returns all derived [MetricReading]s computed from buffered data — HRV, processed
     * sleep stages, or any other driver-computed derived metrics.
     * Returns an empty list if there is nothing to compute.
     */
    fun onSyncComplete(): List<MetricReading>
}
