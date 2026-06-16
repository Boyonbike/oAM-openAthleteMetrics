package com.athletedata.openAthleteMetrics.ble.driver

/**
 * Extension point for native Kotlin drivers that need session-scoped processing.
 *
 * WASM-based drivers (the current default) do not implement this interface — they are
 * data-class manifests and receive no processor. Implement this interface on a Kotlin
 * driver class to supply a [MetricProcessor] that computes derived readings (e.g. HRV,
 * consolidated sleep stages) during a sync session.
 */
interface DeviceDriver {
    fun createProcessor(): MetricProcessor? = null
}
