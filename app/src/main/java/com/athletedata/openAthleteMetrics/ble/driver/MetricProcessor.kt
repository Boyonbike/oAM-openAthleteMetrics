package com.athletedata.openAthleteMetrics.ble.driver

import com.athletedata.openAthleteMetrics.data.model.MetricReading

/**
 * Part of the native Kotlin driver scaffolding. This interface is never instantiated
 * in the current codebase.
 *
 * When a native [DeviceDriver] implementation exists and returns a [MetricProcessor]
 * from [DeviceDriver.createProcessor], the BLE engine will call [onReading] for every
 * parsed reading during a sync session and [onSyncComplete] at quiescence.
 *
 * A new instance is created per sync session. Implementations must be stateful and
 * NOT shared across sessions.
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
