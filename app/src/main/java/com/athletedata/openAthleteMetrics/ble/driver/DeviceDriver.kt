package com.athletedata.openAthleteMetrics.ble.driver

/**
 * Scaffolding for a future native Kotlin driver contribution path.
 *
 * **Current status:** No class in this codebase implements this interface.
 * [WasmDriverManifest] is the active driver type and does not implement [DeviceDriver].
 * The cast `(activeManifest as? DeviceDriver)` in `BleEngine.connect()` is intentionally
 * a no-op until a native driver is contributed.
 *
 * **How to contribute a native driver:** Create a Kotlin class that implements this
 * interface and returns a [MetricProcessor] from [createProcessor]. The BLE engine will
 * call [MetricProcessor.onReading] for every parsed reading and
 * [MetricProcessor.onSyncComplete] at quiescence.
 *
 * The WASM manifest path ([WasmDriverManifest]) is the only supported external driver
 * format at this time.
 */
interface DeviceDriver {
    fun createProcessor(): MetricProcessor? = null
}
